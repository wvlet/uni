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
  * A page with no selected row is skipped without being decompressed. In the others, values between
  * selected rows are skipped, and runs of selected rows are decoded in bulk: PLAIN and dictionary
  * pages decode only the selected values; pages of other encodings are decoded once, and the
  * selected values copied out. The dictionary page is decoded when a page first needs it.
  *
  * [[select]] hands out values and definition levels of the selected rows only, as a [[ColumnData]]
  * whose entry k is the k-th selected row.
  */
private[parquet] final class SelectiveCursor(
    val field: ParquetField.Primitive,
    meta: ColumnChunkMeta,
    input: ChunkInput,
    int32AsLong: Boolean = false
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
  private var indices: Array[Int]             = null

  // The page being read: its definition levels, the entry of the next row, the entries left, and
  // the entry up to which its values are consumed (read or skipped)
  private var pageDefs: Array[Byte] = null
  private var entry                 = 0
  private var pageLeft              = 0
  private var valueEntry            = 0
  private var values: PageValues    = null
  // The buffer compressed pages decode into, from PageBuffers, and the offset of the last page in
  // the array pageBytes returned
  private var pageBuffer: Array[Byte] = null
  private var pageOffset              = 0

  // The row of the batch being read
  private var row = 0

  private def newValues(capacity: Int): ValueBuffer = ValueDecoder.newBuffer(
    field.physicalType,
    capacity,
    int32AsLong
  )

  /**
    * Reads the next `rows` rows, and returns the values of those at the first `n` of `selected`
    * (row numbers within these rows, in increasing order). With `n` = 0 the rows are skipped.
    */
  def select(rows: Int, selected: Array[Int], n: Int): ColumnData =
    val out  = newValues(math.max(n, 1))
    val defs =
      if maxDef > 0 then
        LevelBuffers.bytes.borrow(math.max(n, 1))
      else
        null
    var o = 0
    var j = 0
    row = 0
    while row < rows do
      if pageLeft == 0 then
        nextPage(rows, selected, j, n)
      if row < rows then
        // The page's entries [entry, entry + m) are the batch's rows [row, row + m)
        val m      = math.min(pageLeft, rows - row)
        val segEnd = row + m
        while j < n && selected(j) < segEnd do
          val s = selected(j)
          pass(s - row)
          // A run of consecutive selected rows
          var k = 1
          while j + k < n && selected(j + k) == s + k && s + k < segEnd do
            k += 1
          val nonNull =
            if defs == null then
              k
            else
              System.arraycopy(pageDefs, entry, defs, o, k)
              countNonNull(entry, entry + k)
          // A run of nulls only reads nothing, and leaves the skip to the next run with values
          if nonNull > 0 then
            if valueEntry < entry then
              values.skip(countNonNull(valueEntry, entry))
            values.read(nonNull, out)
            valueEntry = entry + k
          pass(k)
          o += k
          j += k
        pass(segEnd - row)
        if pageLeft == 0 then
          values = null
    end while
    if j != n then
      throw IllegalArgumentException(s"${n - j} selected rows are beyond the batch's ${rows} rows")
    ColumnData(field, out, defs, null, n)

  end select

  /** Moves past `k` entries of the page */
  private def pass(k: Int): Unit =
    entry += k
    pageLeft -= k
    row += k

  private def countNonNull(from: Int, until: Int): Int =
    if pageDefs == null then
      until - from
    else
      var c = 0
      var i = from
      while i < until do
        if pageDefs(i) == maxDef then
          c += 1
        i += 1
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
          val chunk = input.bytes(pos, size)
          val start = input.offset
          dictPage = java.util.Arrays.copyOfRange(chunk, start, start + size)
          dictSize = header.uncompressedSize
          dictValues = d.numValues
          dictionary = None
          pos += size
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
    valueEntry = 0
    pageLeft = k

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
        if indices == null then
          indices = new Array[Int](ValueDecoder.DictionaryBlock)
        DictionaryValues(RleBitPackedDecoder(buf, start + 1, end, buf(start) & 0xff), dict, indices)
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
    if uncompressedSize < 0 then
      throw ParquetException.corrupt(s"negative uncompressed page size ${uncompressedSize}")
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

end SelectiveCursor

private[parquet] object SelectiveCursor:

  /** The non-null values of a page, read in order: some skipped, some appended to a buffer */
  private abstract class PageValues:
    def skip(n: Int): Unit
    def read(n: Int, out: ValueBuffer): Unit

  private object NoValues extends PageValues:
    def skip(n: Int): Unit =
      if n > 0 then
        fail()

    def read(n: Int, out: ValueBuffer): Unit =
      if n > 0 then
        fail()

    private def fail(): Nothing =
      throw ParquetException.corrupt("a page has fewer values than rows")

  private def outOfData(): Nothing =
    throw ParquetException.corrupt("a page has fewer values than its definition levels require")

  /** PLAIN values of a fixed size */
  private final class PlainFixed(
      field: ParquetField.Primitive,
      buf: Array[Byte],
      start: Int,
      end: Int
  ) extends PageValues:
    private val width = ValueDecoder.fixedWidth(field.physicalType, field.typeLength)
    private var p     = start

    def skip(n: Int): Unit =
      val next = p.toLong + n.toLong * width
      if next > end then
        outOfData()
      p = next.toInt

    def read(n: Int, out: ValueBuffer): Unit =
      ValueDecoder.decodePlain(field.physicalType, field.typeLength, buf, p, end, n, out)
      p += n * width

  /** PLAIN booleans, one bit each */
  private final class PlainBooleans(buf: Array[Byte], start: Int, end: Int) extends PageValues:
    private var bit = 0L

    def skip(n: Int): Unit = bit += n

    def read(n: Int, out: ValueBuffer): Unit =
      if start + ((bit + n + 7) >>> 3) > end then
        outOfData()
      val b = out.asInstanceOf[BooleanBuffer]
      b.ensure(n)
      var i = 0
      while i < n do
        val k = bit + i
        b.values(b.size + i) = ((buf(start + (k >>> 3).toInt) >>> (k & 7).toInt) & 1) != 0
        i += 1
      b.size += n
      bit += n

  /** PLAIN byte arrays, each after its 4-byte length */
  private final class PlainBinary(buf: Array[Byte], start: Int, end: Int) extends PageValues:
    private var p = start

    private def lengthAt(): Int =
      if p + 4 > end then
        outOfData()
      val len = Bytes.intLE(buf, p)
      if len < 0 || len > end - p - 4 then
        throw ParquetException.corrupt(s"a byte array of ${len} bytes overruns its page")
      len

    def skip(n: Int): Unit =
      var i = 0
      while i < n do
        p += 4 + lengthAt()
        i += 1

    def read(n: Int, out: ValueBuffer): Unit =
      val b = out.asInstanceOf[BinaryBuffer]
      b.ensureValues(n)
      var i = 0
      while i < n do
        val len = lengthAt()
        b.add(buf, p + 4, len)
        p += 4 + len
        i += 1

  /** Dictionary indices, looked up for the values read */
  private final class DictionaryValues(
      decoder: RleBitPackedDecoder,
      dict: ValueBuffer,
      indices: Array[Int]
  ) extends PageValues:
    def skip(n: Int): Unit                   = decoder.skip(n)
    def read(n: Int, out: ValueBuffer): Unit = decoder.readDictionary(out, dict, n, indices)

  /** Values decoded beforehand, for encodings that can't skip */
  private final class DecodedValues(decoded: ValueBuffer) extends PageValues:
    private var next            = 0
    private var idx: Array[Int] = null
    def skip(n: Int): Unit      = next += n

    def read(n: Int, out: ValueBuffer): Unit =
      if next + n > decoded.size then
        outOfData()
      decoded match
        case d: BinaryBuffer =>
          // Copied as plain values: a page of these has no dictionary entries
          val b = out.asInstanceOf[BinaryBuffer]
          b.ensureValues(n)
          var i = 0
          while i < n do
            b.add(d.data, d.offsets(next + i), d.length(next + i))
            i += 1
        case _ =>
          lookUp(n, out)
      next += n

    private def lookUp(n: Int, out: ValueBuffer): Unit =
      if idx == null || idx.length < n then
        idx = new Array[Int](n)
      var i = 0
      while i < n do
        idx(i) = next + i
        i += 1
      out.appendFromDictionary(decoded, idx, n)

end SelectiveCursor
