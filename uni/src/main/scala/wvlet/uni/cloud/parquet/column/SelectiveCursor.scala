package wvlet.uni.cloud.parquet.column

import wvlet.uni.io.codec.Bytes
import wvlet.uni.cloud.parquet.ParquetException
import wvlet.uni.cloud.parquet.ParquetField
import wvlet.uni.cloud.parquet.codec.Codecs
import wvlet.uni.cloud.parquet.encoding.BitPacking
import wvlet.uni.cloud.parquet.encoding.RleBitPackedDecoder
import wvlet.uni.cloud.parquet.encoding.ValueDecoder
import wvlet.uni.cloud.parquet.format.*
import wvlet.uni.cloud.parquet.io.ByteReader
import wvlet.uni.cloud.parquet.thrift.CompactProtocol

/**
  * Reads a flat column chunk (one without repetition levels) for the selected rows of each batch
  * only, the columns a row filter does not need (late materialization).
  *
  * Each level entry of a flat column is a row, so a page header tells how many rows the page holds.
  * A page with no selected row is skipped without being decompressed. In the others, the values of
  * a batch's selected rows are gathered in one call per page: PLAIN pages read them in place (in
  * bulk when they are consecutive), dictionary pages expand indices up to the last selected value
  * and look up only the selected ones, and pages of other encodings are decoded once and the
  * selected values copied out. The dictionary page is decoded when a page first needs it.
  *
  * [[select]] hands out values and definition levels of the selected rows only, as a [[ColumnData]]
  * whose entry k is the k-th selected row.
  */
private[parquet] final class SelectiveCursor(
    val field: ParquetField.Primitive,
    meta: ColumnChunkMeta,
    input: ChunkInput,
    int32AsLong: Boolean = false,
    dictionaryCodesOnly: Boolean = false
):
  import SelectiveCursor.*

  if field.maxRepetitionLevel != 0 then
    throw IllegalArgumentException(s"column ${field.pathString} is not flat")

  private val maxDef = field.maxDefinitionLevel
  if maxDef > Byte.MaxValue then
    throw ParquetException.unsupported(
      s"column ${field.pathString} nested more than ${Byte.MaxValue} levels deep"
    )

  private val total: Long = meta.numValues
  if total < 0 then
    throw ParquetException.corrupt(s"column ${field.pathString} has ${total} values")

  // Position in the chunk, and the entries of the pages read or skipped so far
  private var pos        = 0L
  private var levelsRead = 0L

  // The dictionary page, kept compressed until a page needs its values
  private var dictPage: Array[Byte]           = null
  private var dictSize                        = 0
  private var dictValues                      = 0
  private var dictionary: Option[ValueBuffer] = None
  // A dictionary page's expanded indices, from LevelBuffers.ints like positions
  private var pageIndices: Array[Int] = null

  // The page being read: its definition levels, whether every entry has a value, the entry of the
  // next row, and the entries left. ordinal counts the values before entry ordEntry.
  private var pageDefs: Array[Byte] = null
  private var allValid              = true
  private var entry                 = 0
  private var pageLeft              = 0
  private var ordEntry              = 0
  private var ordinal               = 0
  private var values: PageValues    = null
  // The value ordinals of the selected rows of one page, gathered in one call. Scratch arrays come
  // from LevelBuffers, as a new cursor reads each row group, and go back when the chunk ends.
  private var positions: Array[Int] = null

  /** `a` when it holds `n`, else a pooled array that does, `a` going back to the pool */
  private def borrowInts(a: Array[Int], n: Int): Array[Int] =
    if a != null && a.length >= n then
      a
    else
      if a != null then
        LevelBuffers.ints.release(a)
      LevelBuffers.ints.borrow(math.max(n, 64))

  // The buffer compressed pages decode into, from PageBuffers, and the offset of the last page in
  // the array pageBytes returned
  private var pageBuffer: Array[Byte] = null
  private var pageOffset              = 0

  // The row of the batch being read
  private var row = 0

  private def newValues(capacity: Int): ValueBuffer =
    val b = ValueDecoder.newBuffer(field.physicalType, capacity, int32AsLong)
    if dictionaryCodesOnly then
      b match
        case x: BinaryBuffer =>
          x.codesOnly = true
        case _ =>
    b

  /**
    * Reads the next `rows` rows, and returns the values of those at the first `n` of `selected`
    * (row numbers within these rows, in increasing order). With `n` = 0 the rows are skipped.
    */
  def select(rows: Int, selected: Array[Int], n: Int): ColumnData =
    val out  = newValues(math.max(n, 1))
    var defs =
      if maxDef > 0 then
        LevelBuffers.bytes.borrow(math.max(n, 1))
      else
        null
    var anyNull = false
    var o       = 0
    var j       = 0
    row = 0
    while row < rows do
      if pageLeft == 0 then
        nextPage(rows, selected, j, n)
      if row < rows then
        // The page's entries [entry, entry + m) are the batch's rows [row, row + m)
        val m     = math.min(pageLeft, rows - row)
        val shift = entry - row
        val most  = math.min(m, n - j)
        if positions == null || positions.length < most then
          positions = borrowInts(positions, most)
        var c = 0
        while j < n && selected(j) < row + m do
          val e = selected(j) + shift
          if allValid then
            positions(c) = e
            c += 1
            if defs != null then
              defs(o) = maxDef.toByte
          else
            val d = pageDefs(e)
            defs(o) = d
            if d == maxDef then
              positions(c) = ordinalAt(e)
              c += 1
            else
              anyNull = true
          o += 1
          j += 1
        if c > 0 then
          values.gather(positions, c, out)
        pass(m)
        if pageLeft == 0 then
          values = null
      end if
    end while
    if j != n then
      throw IllegalArgumentException(s"${n - j} selected rows are beyond the batch's ${rows} rows")
    if defs != null && !anyNull then
      // Every selected row has a value: without levels, the vector takes the values as they are
      LevelBuffers.bytes.release(defs)
      defs = null
    ColumnData(field, out, defs, null, n)

  end select

  /** Moves past `k` entries of the page */
  private def pass(k: Int): Unit =
    entry += k
    pageLeft -= k
    row += k

  /** The number of values before entry `e` of the page, for entries in increasing order */
  private def ordinalAt(e: Int): Int =
    var c = ordinal
    var i = ordEntry
    if maxDef == 1 then
      // Levels of a flat optional column are bytes 0 or 1: count eight at a time, as bits set.
      // Selected rows can be far apart, so this pass covers most of the page's entries.
      while i + 8 <= e do
        c += java.lang.Long.bitCount(Bytes.wordLE(pageDefs, i))
        i += 8
    while i < e do
      if pageDefs(i) == maxDef then
        c += 1
      i += 1
    ordinal = c
    ordEntry = e
    c

  /** Fails when the chunk has values beyond the row group's rows; frees the page buffer */
  def checkDone(): Unit =
    if pageLeft > 0 then
      throw ParquetException.corrupt(
        s"column ${field.pathString} has more values than the row group has rows"
      )
    while pos < input.length && levelsRead < total do
      val header = readHeader()
      if header.pageType == PageType.DATA_PAGE || header.pageType == PageType.DATA_PAGE_V2 then
        if pageValues(header) > 0 then
          throw ParquetException.corrupt(
            s"column ${field.pathString} has more values than the row group has rows"
          )
      pos += header.compressedSize
    release()

  private def release(): Unit =
    if positions != null then
      LevelBuffers.ints.release(positions)
      positions = null
    if pageIndices != null then
      LevelBuffers.ints.release(pageIndices)
      pageIndices = null
    if pageBuffer != null then
      PageBuffers.release(pageBuffer)
      pageBuffer = null
    if pageDefs != null then
      LevelBuffers.bytes.release(pageDefs)
      pageDefs = null

  private def pageValues(h: PageHeader): Int =
    h.pageType match
      case PageType.DATA_PAGE =>
        h.dataPage
          .getOrElse(throw ParquetException.corrupt("data page without its header"))
          .numValues
      case _ =>
        h.dataPageV2
          .getOrElse(throw ParquetException.corrupt("data page v2 without its header"))
          .numValues

  /**
    * Reads page headers until a data page with a selected row, or one that ends past the batch, is
    * loaded. Pages that end in the batch and hold no selected row are skipped, as their rows are.
    * `j` is the next selected row's index in `selected`.
    */
  private def nextPage(rows: Int, selected: Array[Int], j: Int, n: Int): Unit =
    while pageLeft == 0 && row < rows do
      if pos >= input.length || levelsRead >= total then
        throw ParquetException.corrupt(
          s"column ${field.pathString} ends after ${levelsRead} of ${total} values"
        )
      val header = readHeader()
      val size   = header.compressedSize
      if size < 0 then
        throw ParquetException.corrupt(s"negative page size ${size}")
      if size > input.length - pos then
        throw ParquetException.corrupt(
          s"a page of column ${field.pathString} needs ${size} bytes, but only ${input.length -
              pos} remain"
        )
      header.pageType match
        case PageType.DICTIONARY_PAGE =>
          val d = header
            .dictionaryPage
            .getOrElse(throw ParquetException.corrupt("dictionary page without its header"))
          if d.numValues < 0 then
            throw ParquetException.corrupt(s"dictionary has ${d.numValues} values")
          keepDictionary(header, d.numValues)
        case PageType.DATA_PAGE | PageType.DATA_PAGE_V2 =>
          val k = pageValues(header)
          if k < 0 || k > total - levelsRead then
            throw ParquetException.corrupt(
              s"a page of column ${field
                  .pathString} has ${k} values, more than the chunk's remaining ${total -
                  levelsRead}"
            )
          levelsRead += k
          if k == 0 then
            pos += size
          else if row + k <= rows && (j >= n || selected(j) >= row + k) then
            // No selected row in this page
            pos += size
            row += k
          else
            load(header, k)
        case _ =>
          // Index pages are not used by readers
          pos += size
      end match
    end while

  end nextPage

  /** Keeps the dictionary page at `pos` compressed, until a page needs its values */
  private def keepDictionary(header: PageHeader, numValues: Int): Unit =
    val size  = header.compressedSize
    val chunk = input.bytes(pos, size)
    val start = input.offset
    dictPage = java.util.Arrays.copyOfRange(chunk, start, start + size)
    dictSize = header.uncompressedSize
    dictValues = numValues
    dictionary = None
    pos += size

  /**
    * An upper bound of the bytes the chunk's values take once decoded, like the one
    * [[ColumnChunkCursor]] checks page by page against its limits. Binary values from a dictionary
    * are bounded by its longest entry, so this decodes the dictionary page, which comes first.
    * DELTA_BYTE_ARRAY values share prefixes, so theirs is unbounded.
    */
  def decodedBound: Long =
    val width = ValueDecoder.fixedWidth(field.physicalType, field.typeLength)
    field.physicalType match
      case PhysicalType.BYTE_ARRAY | PhysicalType.FIXED_LEN_BYTE_ARRAY | PhysicalType.INT96 =>
        if meta.encodings.contains(Encoding.DELTA_BYTE_ARRAY) then
          Long.MaxValue
        else
          // PLAIN values take no more than their pages, plus an offset each
          val plain = meta.totalUncompressedSize + 4L * total
          if meta
              .encodings
              .exists(e => e == Encoding.PLAIN_DICTIONARY || e == Encoding.RLE_DICTIONARY)
          then
            if pos == 0 && dictPage == null && dictionary.isEmpty && input.length > 0 then
              val header = readHeader()
              header.dictionaryPage match
                case Some(d) if header.pageType == PageType.DICTIONARY_PAGE =>
                  if d.numValues < 0 then
                    throw ParquetException.corrupt(s"dictionary has ${d.numValues} values")
                  if header.compressedSize < 0 || header.compressedSize > input.length - pos then
                    throw ParquetException.corrupt(
                      s"a page of column ${field.pathString} overruns its chunk"
                    )
                  keepDictionary(header, d.numValues)
                case _ =>
                  pos = 0
            if dictPage == null && dictionary.isEmpty then
              plain
            else
              dictionaryValues() match
                case d: BinaryBuffer =>
                  plain + total * d.maxLength
                case _ =>
                  plain
          else
            plain
      case _ =>
        // Fixed-size values, or bits for booleans
        total * math.max(width, 1)
    end match

  end decodedBound

  /** Decodes the levels of a data page of `k` entries, and prepares its values */
  private def load(header: PageHeader, k: Int): Unit =
    val size      = header.compressedSize
    val chunk     = input.bytes(pos, size)
    val pageStart = input.offset
    pos += size
    if maxDef > 0 && (pageDefs == null || pageDefs.length < k) then
      if pageDefs != null then
        LevelBuffers.bytes.release(pageDefs)
      pageDefs = LevelBuffers.bytes.borrow(k)
    val levels =
      if maxDef > 0 then
        pageDefs
      else
        null
    var nonNull = k
    header.pageType match
      case PageType.DATA_PAGE =>
        val h       = header.dataPage.get
        val page    = pageBytes(chunk, pageStart, size, header.uncompressedSize)
        val pageEnd = pageOffset + header.uncompressedSize
        val pr      = ByteReader(page, pageOffset, pageEnd)
        if maxDef > 0 then
          nonNull = ColumnChunkCursor.readLevelsV1(
            pr,
            h.definitionLevelEncoding,
            maxDef,
            levels,
            0,
            k
          )
        values = pageValuesOf(h.encoding, page, pr.pos, pageEnd, nonNull)
      case _ =>
        val h      = header.dataPageV2.get
        val repLen = h.repetitionLevelsByteLength
        val defLen = h.definitionLevelsByteLength
        if repLen != 0 || defLen < 0 || defLen.toLong > size then
          throw ParquetException.corrupt("data page v2 level lengths are out of range")
        if maxDef > 0 then
          nonNull = RleBitPackedDecoder(
            chunk,
            pageStart,
            pageStart + defLen,
            BitPacking.bitWidthOf(maxDef)
          ).readLevels(levels, 0, k, maxDef)
        val valuesStart = pageStart + defLen
        val valuesLen   = size - defLen
        val rawLen      = header.uncompressedSize - defLen
        if rawLen == 0 then
          values = pageValuesOf(h.encoding, Array.emptyByteArray, 0, 0, nonNull)
        else if h.isCompressed && meta.codec != CompressionCodec.UNCOMPRESSED then
          val out = pageBytes(chunk, valuesStart, valuesLen, rawLen)
          values = pageValuesOf(h.encoding, out, pageOffset, pageOffset + rawLen, nonNull)
        else
          values = pageValuesOf(h.encoding, chunk, valuesStart, valuesStart + valuesLen, nonNull)
    end match
    entry = 0
    pageLeft = k
    allValid = nonNull == k
    ordEntry = 0
    ordinal = 0

  end load

  private def dictionaryValues(): ValueBuffer =
    dictionary match
      case Some(d) =>
        d
      case None =>
        if dictPage == null then
          throw ParquetException.corrupt("dictionary-encoded page without a dictionary page")
        // Decoded into an array of its own: the page buffer holds the data page
        val page =
          if meta.codec == CompressionCodec.UNCOMPRESSED then
            if dictPage.length != dictSize then
              throw ParquetException.corrupt(
                s"uncompressed page has ${dictPage.length} bytes, but its header says ${dictSize}"
              )
            dictPage
          else
            Codecs.decompress(meta.codec, dictPage, 0, dictPage.length, dictSize)
        val d = newValues(math.min(dictValues, 1 << 16))
        ValueDecoder.decodePlain(
          field.physicalType,
          field.typeLength,
          page,
          0,
          dictSize,
          dictValues,
          d
        )
        dictionary = Some(d)
        dictPage = null
        d

  /** The values of a page, `n` of them non-null, in [start, end) of `buf` */
  private def pageValuesOf(
      encoding: Encoding,
      buf: Array[Byte],
      start: Int,
      end: Int,
      n: Int
  ): PageValues =
    val tpe = field.physicalType
    encoding match
      case _ if n == 0 =>
        NoValues
      case Encoding.PLAIN if tpe == PhysicalType.BOOLEAN =>
        PlainBooleans(buf, start, end)
      case Encoding.PLAIN if tpe == PhysicalType.BYTE_ARRAY =>
        PlainBinary(buf, start, end)
      case Encoding.PLAIN =>
        PlainFixed(field, buf, start, end)
      case Encoding.PLAIN_DICTIONARY | Encoding.RLE_DICTIONARY =>
        val dict = dictionaryValues()
        if start >= end then
          throw ParquetException.corrupt("dictionary index data is empty")
        if pageIndices == null || pageIndices.length < n then
          pageIndices = borrowInts(pageIndices, n)
        DictionaryValues(RleBitPackedDecoder(buf, start + 1, end, buf(start) & 0xff), dict, n)
      case _ =>
        val decoded = newValues(n)
        ValueDecoder.decode(
          encoding,
          tpe,
          field.typeLength,
          buf,
          start,
          end,
          n,
          decoded,
          dictionary
        )
        if decoded.size != n then
          throw ParquetException.corrupt(
            s"a page of column ${field.pathString} has ${decoded
                .size} values for ${n} non-null rows"
          )
        DecodedValues(decoded)
    end match

  end pageValuesOf

  private def readHeader(): PageHeader =
    // Headers hold statistics, which can be long, but not this long
    val limit              = math.min(input.length - pos, 64L * 1024 * 1024).toInt
    var n                  = math.min(limit, 64 * 1024)
    var header: PageHeader = null
    while header == null do
      val buf = input.bytes(pos, n)
      val off = input.offset
      val r   = ByteReader(buf, off, off + n)
      try
        header = MetadataDecoder.pageHeader(CompactProtocol.readStruct(r))
        pos += r.pos - off
      catch
        case e: ParquetException if n < limit =>
          n = math.min(limit.toLong, n.toLong * 4).toInt
    header

  /** The decompressed bytes of a page, at [pageOffset, pageOffset + uncompressedSize) */
  private def pageBytes(
      chunk: Array[Byte],
      start: Int,
      length: Int,
      uncompressedSize: Int
  ): Array[Byte] =
    // Before any buffer is sized by the header
    Codecs.checkUncompressedSize(meta.codec, length, uncompressedSize)
    if meta.codec == CompressionCodec.UNCOMPRESSED then
      if length != uncompressedSize then
        throw ParquetException.corrupt(
          s"uncompressed page has ${length} bytes, but its header says ${uncompressedSize}"
        )
      pageOffset = start
      chunk
    else if Codecs.decodesInto(meta.codec) then
      if pageBuffer == null || pageBuffer.length < uncompressedSize then
        PageBuffers.release(pageBuffer)
        pageBuffer = PageBuffers.borrow(uncompressedSize)
      Codecs.decompressInto(meta.codec, chunk, start, length, pageBuffer, uncompressedSize)
      pageOffset = 0
      pageBuffer
    else
      pageOffset = 0
      Codecs.decompress(meta.codec, chunk, start, length, uncompressedSize)

  /** Dictionary indices, expanded up to the last value gathered so far */
  private final class DictionaryValues(decoder: RleBitPackedDecoder, dict: ValueBuffer, n: Int)
      extends PageValues:
    private var expanded = 0

    def gather(pos: Array[Int], c: Int, out: ValueBuffer): Unit =
      val last = pos(c - 1)
      if last >= n then
        outOfData()
      // The ordinals become dictionary indices in place: the caller is done with them
      if (last + 1 - expanded).toLong > SparseRatio.toLong * c then
        // Few values selected: read only theirs, skipping the indices between them
        var i = 0
        while i < c do
          pos(i) -= expanded
          i += 1
        decoder.readAt(pos, c)
      else
        decoder.read(pageIndices, expanded, last + 1 - expanded)
        var i = 0
        while i < c do
          pos(i) = pageIndices(pos(i))
          i += 1
      expanded = last + 1
      out.appendFromDictionary(dict, pos, c)

end SelectiveCursor

private[parquet] object SelectiveCursor:

  /**
    * The non-null values of a page. [[gather]] appends the values at the given ordinals, which
    * increase within a page, also across calls.
    */
  private[column] abstract class PageValues:
    def gather(pos: Array[Int], c: Int, out: ValueBuffer): Unit

  private object NoValues extends PageValues:
    def gather(pos: Array[Int], c: Int, out: ValueBuffer): Unit = outOfData()

  private def outOfData(): Nothing =
    throw ParquetException.corrupt("a page has fewer values than its definition levels require")

  /**
    * A dictionary page reads only the selected indices when it would otherwise expand more than
    * this many indices per selected value
    */
  private final val SparseRatio = 8

  /** Consecutive values from this many on are copied in bulk */
  private final val BulkRun = 16

  /** PLAIN values of a fixed size, read in place */
  private final class PlainFixed(
      field: ParquetField.Primitive,
      buf: Array[Byte],
      start: Int,
      end: Int
  ) extends PageValues:
    private val tpe   = field.physicalType
    private val width = ValueDecoder.fixedWidth(tpe, field.typeLength)

    def gather(pos: Array[Int], c: Int, out: ValueBuffer): Unit =
      if start.toLong + (pos(c - 1).toLong + 1) * width > end then
        outOfData()
      // Runs of consecutive values copy in bulk; a bulk call costs more than a few scalar reads
      var i = 0
      while i < c do
        var j = i + 1
        while j < c && pos(j) == pos(j - 1) + 1 do
          j += 1
        if j - i >= BulkRun then
          ValueDecoder.decodePlain(
            tpe,
            field.typeLength,
            buf,
            start + pos(i) * width,
            end,
            j - i,
            out
          )
        else
          scalar(pos, i, j, out)
        i = j

    /** Appends the values at pos(from until until), one at a time */
    private def scalar(pos: Array[Int], from: Int, until: Int, out: ValueBuffer): Unit =
      val c                                                 = until - from
      inline def each(inline set: (Int, Int) => Unit): Unit =
        var i = 0
        while i < c do
          set(i, start + pos(from + i) * width)
          i += 1
      out match
        case b: LongBuffer if tpe == PhysicalType.INT32 =>
          b.ensure(c)
          val v = b.values
          val s = b.size
          each((i, p) => v(s + i) = Bytes.intLE(buf, p).toLong)
          b.size += c
        case b: LongBuffer =>
          b.ensure(c)
          val v = b.values
          val s = b.size
          each((i, p) => v(s + i) = Bytes.wordLE(buf, p))
          b.size += c
        case b: IntBuffer =>
          b.ensure(c)
          val v = b.values
          val s = b.size
          each((i, p) => v(s + i) = Bytes.intLE(buf, p))
          b.size += c
        case b: FloatBuffer =>
          b.ensure(c)
          val v = b.values
          val s = b.size
          each((i, p) => v(s + i) = java.lang.Float.intBitsToFloat(Bytes.intLE(buf, p)))
          b.size += c
        case b: DoubleBuffer =>
          b.ensure(c)
          val v = b.values
          val s = b.size
          each((i, p) => v(s + i) = java.lang.Double.longBitsToDouble(Bytes.wordLE(buf, p)))
          b.size += c
        case b: BinaryBuffer =>
          b.ensureValues(c)
          b.ensureData(c * width)
          each((_, p) => b.add(buf, p, width))
        case other =>
          throw IllegalStateException(s"${tpe} values in ${other.getClass.getSimpleName}")
      end match

    end scalar

  end PlainFixed

  /** PLAIN booleans, one bit each */
  private final class PlainBooleans(buf: Array[Byte], start: Int, end: Int) extends PageValues:
    def gather(pos: Array[Int], c: Int, out: ValueBuffer): Unit =
      if start + (pos(c - 1).toLong >>> 3) >= end then
        outOfData()
      val b = out.asInstanceOf[BooleanBuffer]
      b.ensure(c)
      var i = 0
      while i < c do
        val k = pos(i)
        b.values(b.size + i) = ((buf(start + (k >>> 3)) >>> (k & 7)) & 1) != 0
        i += 1
      b.size += c

  /** PLAIN byte arrays, each after its 4-byte length, scanned in order */
  private final class PlainBinary(buf: Array[Byte], start: Int, end: Int) extends PageValues:
    // The position of the value with ordinal `next`
    private var p    = start
    private var next = 0

    private def lengthAt(): Int =
      if p + 4 > end then
        outOfData()
      val len = Bytes.intLE(buf, p)
      if len < 0 || len > end - p - 4 then
        throw ParquetException.corrupt(s"a byte array of ${len} bytes overruns its page")
      len

    def gather(pos: Array[Int], c: Int, out: ValueBuffer): Unit =
      val b = out.asInstanceOf[BinaryBuffer]
      b.ensureValues(c)
      var i = 0
      while i < c do
        val target = pos(i)
        while next < target do
          p += 4 + lengthAt()
          next += 1
        val len = lengthAt()
        b.add(buf, p + 4, len)
        p += 4 + len
        next += 1
        i += 1

  /** Values decoded beforehand, for encodings that can't be read in place */
  private final class DecodedValues(decoded: ValueBuffer) extends PageValues:
    def gather(pos: Array[Int], c: Int, out: ValueBuffer): Unit =
      if pos(c - 1) >= decoded.size then
        outOfData()
      decoded match
        case d: BinaryBuffer =>
          // Copied as plain values: a page of these has no dictionary entries
          val b = out.asInstanceOf[BinaryBuffer]
          b.ensureValues(c)
          var i = 0
          while i < c do
            b.add(d.data, d.offsets(pos(i)), d.length(pos(i)))
            i += 1
        case _ =>
          out.appendFromDictionary(decoded, pos, c)

end SelectiveCursor
