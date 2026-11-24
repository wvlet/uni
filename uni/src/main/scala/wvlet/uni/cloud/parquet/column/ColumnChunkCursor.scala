package wvlet.uni.cloud.parquet.column

import wvlet.uni.cloud.parquet.ParquetException
import wvlet.uni.cloud.parquet.ParquetField
import wvlet.uni.cloud.parquet.codec.Codecs
import wvlet.uni.cloud.parquet.encoding.BitPacking
import wvlet.uni.cloud.parquet.encoding.NullMask
import wvlet.uni.cloud.parquet.encoding.RleBitPackedDecoder
import wvlet.uni.cloud.parquet.encoding.ValueDecoder
import wvlet.uni.cloud.parquet.format.*
import wvlet.uni.cloud.parquet.io.ByteReader
import wvlet.uni.cloud.parquet.thrift.CompactProtocol

/**
  * The decoded levels and non-null values of a range of rows of one column. Levels are null when
  * the column's maximum level is 0. The level arrays may be longer than `numLevels`.
  *
  * With `rowSlots` (a flat column with one definition level), `values` has a slot for every row,
  * null rows included, and `nulls` flags the null rows in place of definition levels; `nulls` is
  * null when no row is null. `nulls` may be longer than `numLevels`.
  */
private[parquet] final class ColumnData(
    val field: ParquetField.Primitive,
    val values: ValueBuffer,
    val definitionLevels: Array[Byte],
    val repetitionLevels: Array[Byte],
    val numLevels: Int,
    val nulls: Array[Boolean] = null,
    val rowSlots: Boolean = false
):
  /** Returns the level arrays to [[LevelBuffers]], once the batch's vectors are built */
  def releaseLevels(): Unit =
    LevelBuffers.bytes.release(definitionLevels)
    LevelBuffers.bytes.release(repetitionLevels)

/**
  * Reads the pages of a column chunk in batches of rows.
  *
  * [[fill]] decodes pages until the cursor holds the asked number of complete rows, or until its
  * decoded values pass `softLimit` bytes; [[take]] then hands out a number of rows that every
  * column of the batch can give, and keeps the rest for the next batch. A page's levels are decoded
  * before its values (the page is "held"), so the cursor knows whether its last row is complete,
  * and can stop before a page that would push its values past `hardLimit`.
  */
private[parquet] final class ColumnChunkCursor(
    val field: ParquetField.Primitive,
    meta: ColumnChunkMeta,
    input: ChunkInput,
    softLimit: Long,
    hardLimit: Long,
    int32AsLong: Boolean = false
):
  private val total: Long = meta.numValues
  if total < 0 then
    throw ParquetException.corrupt(s"column ${field.pathString} has ${total} values")

  private val maxDef = field.maxDefinitionLevel
  private val maxRep = field.maxRepetitionLevel
  // A flat column with one definition level, the level of every top-level OPTIONAL column. Its
  // levels say only whether each row is null, so they decode straight into a null mask, and each
  // page's values move to their row slots as the page is decoded (see ColumnData)
  private val rowSlots = maxRep == 0 && maxDef == 1
  // Levels are kept one byte per entry: a quarter of the memory of Int levels
  if maxDef > Byte.MaxValue || maxRep > Byte.MaxValue then
    throw ParquetException.unsupported(
      s"column ${field.pathString} nested more than ${Byte.MaxValue} levels deep"
    )

  // Position in the chunk, and the level entries read from its pages so far (held page included)
  private var pos        = 0L
  private var levelsRead = 0L
  // Bytes of dictionary pages, which hold no level entries
  private var dictionaryBytes = 0L
  // A block of dictionary indices of the page being decoded, kept across pages
  private var indices: Array[Int] = null

  private var dictionary: Option[ValueBuffer] = None

  // The buffer that compressed pages decode into, from PageBuffers, and returned to it when the
  // chunk ends. Every decoder copies values out of a page, so a page's buffer is free once its
  // values are committed. pageOffset is where the last page starts in the array pageBytes returned.
  private var pageBuffer: Array[Byte] = null
  private var pageOffset              = 0

  // Level entries [0, base) are handed out, [base, committed) are committed (values decoded), and
  // [committed, levelEnd) belong to the held page; values [0, valueBase) are handed out. Handed-out
  // entries are dropped when the next page loads, not on every take, so small batches stay linear.
  // The arrays grow page by page, so a corrupt value count cannot force a huge allocation.
  private var defLevels: Array[Byte] =
    if rowSlots then
      null
    else
      newLevels(1024)

  // With rowSlots: the null mask, true for null entries, allocated when the first null shows up.
  // It holds levelCap entries, and is false past levelEnd, so pages only flag their nulls.
  // nullMask also keeps the held page's null positions, for moving its values to their rows.
  private var nulls: Array[Boolean] = null
  private var levelCap              = initialLevels(1024)
  // With rowSlots: the null entries in [base, committed)
  private var committedNulls = 0
  private val nullMask       =
    new NullMask:
      def array: Array[Boolean] =
        if nulls == null then
          nulls = new Array[Boolean](levelCap)
        nulls

  private var repLevels: Array[Byte] =
    if maxRep > 0 then
      LevelBuffers.bytes.borrow(initialLevels(1024))
    else
      null

  private var base      = 0
  private var valueBase = 0
  private var committed = 0
  private var levelEnd  = 0
  // Entries with repetition level 0 in [base, committed): the rows that start there
  private var rowStarts      = 0
  private var values         = newValues(initialLevels(1024))
  private var held: HeldPage = null
  // Whether the batch being filled takes every row left in the chunk
  private var toChunkEnd = false

  /** A data page whose levels are decoded and whose values are not yet */
  private final class HeldPage(
      val numValues: Int,
      val nonNull: Int,
      val encoding: Encoding,
      val buf: Array[Byte],
      val start: Int,
      val end: Int
  ):
    /** An upper bound of the bytes its values add to a binary column's data array, else 0 */
    val dataBytes: Long =
      values match
        case _: BinaryBuffer =>
          encoding match
            case Encoding.RLE_DICTIONARY | Encoding.PLAIN_DICTIONARY =>
              dictionary match
                case Some(d: BinaryBuffer) =>
                  nonNull.toLong * d.maxLength
                case _ =>
                  0L
            case _ =>
              (end - start).toLong
        case _ =>
          0L

  /**
    * The values a page adds to the buffer: a slot per entry with rowSlots, else its non-null ones
    */
  private def slotsOf(h: HeldPage): Int =
    if rowSlots then
      h.numValues
    else
      h.nonNull

  /** Whether decoding the held page would push an array past the hard limit (elements or bytes) */
  private def overflows(h: HeldPage): Boolean =
    values.size.toLong + slotsOf(h) > hardLimit || (
      values match
        case b: BinaryBuffer =>
          b.dataSize.toLong + h.dataBytes > hardLimit
        case _ =>
          false
    )

  private def newValues(capacity: Int): ValueBuffer = ValueDecoder.newBuffer(
    field.physicalType,
    capacity,
    int32AsLong
  )

  private def initialLevels(n: Int): Int = math.min(total, n.toLong).toInt

  private def newLevels(n: Int): Array[Byte] =
    if maxDef > 0 then
      LevelBuffers.bytes.borrow(initialLevels(n))
    else
      null

  private def levelArrays: Int =
    (
      if defLevels != null || nulls != null then
        1
      else
        0
    ) + (
      if repLevels != null then
        1
      else
        0
    )

  private def pendingBytes: Long = values.byteSize + levelEnd.toLong * levelArrays

  private def exhausted: Boolean = levelsRead >= total || pos >= input.length

  /** Complete rows in [base, committed). A row is complete once the next row has started. */
  private def completeRows: Int =
    if maxRep == 0 then
      committed - base
    else if rowStarts == 0 then
      0
    else if held == null || repLevels(committed) == 0 then
      // No held page means the chunk has ended
      rowStarts
    else
      rowStarts - 1

  /**
    * Decodes pages until `target` complete rows are ready, or fewer when the values pass the soft
    * limit or the chunk ends. Returns the number of complete rows ready, at most `target`. With
    * `toEnd`, `target` is every row left in the chunk, so the buffers are sized for the whole rest
    * of it instead of growing page by page.
    */
  def fill(target: Int, toEnd: Boolean = false): Int =
    toChunkEnd = toEnd
    var result = -1
    while result < 0 do
      if held == null then
        loadNextPage()
      val complete = completeRows
      if complete >= target then
        result = target
      else if held == null then
        if levelsRead < total then
          throw ParquetException.corrupt(
            s"column ${field.pathString} ends after ${levelsRead} of ${total} values"
          )
        result = complete
      else if complete > 0 && (pendingBytes >= softLimit || overflows(held)) then
        result = complete
      else
        commit()
    result

  /** Hands out the first `rows` complete rows and keeps the rest */
  def take(rows: Int): ColumnData =
    // The entry where row `rows` starts
    val e =
      if maxRep == 0 then
        base + rows
      else if rows == rowStarts then
        committed
      else
        var i      = base
        var starts = 0
        while starts <= rows do
          if repLevels(i) == 0 then
            starts += 1
          i += 1
        i - 1
    val v =
      if maxDef == 0 || rowSlots then
        e - base
      else if e == committed then
        // Every committed entry goes out, so does every decoded value: no need to count them
        values.size - valueBase
      else
        var c = 0
        var i = base
        while i < e do
          if defLevels(i) == maxDef then
            c += 1
          i += 1
        c
    val out =
      if base == 0 && v == values.size then
        // Everything committed goes out: hand over the arrays, and start new ones for the rest,
        // sized like this batch so the next one does not grow from scratch
        val tail = levelEnd - e
        val left = total - levelsRead + tail
        val data = ColumnData(
          field,
          values,
          defLevels,
          repLevels,
          e,
          if committedNulls == 0 then
            null
          else
            nulls
          ,
          rowSlots
        )
        committedNulls = 0
        values = newValues(math.min(math.max(v, 1024).toLong, left).toInt)
        val levels = math.max(tail.toLong, math.min(math.max(e, 1024).toLong, left)).toInt
        defLevels = moveLevels(defLevels, e, tail, levels)
        repLevels = moveLevels(repLevels, e, tail, levels)
        nulls = moveNulls(e, tail, levels)
        levelCap = levels
        committed -= e
        levelEnd = tail
        valueBase = 0
        data
      else
        val data = ColumnData(
          field,
          values.slice(valueBase, valueBase + v),
          slice(defLevels, base, e),
          slice(repLevels, base, e),
          e - base,
          sliceNulls(base, e),
          rowSlots
        )
        base = e
        valueBase += v
        data
    rowStarts -= rows
    out

  end take

  /** Drops the handed-out entries and values */
  private def compact(): Unit =
    if base > 0 || valueBase > 0 then
      val n = levelEnd - base
      shiftLevels(defLevels, base, n)
      shiftLevels(repLevels, base, n)
      if nulls != null then
        System.arraycopy(nulls, base, nulls, 0, n)
        // Keep the mask false past the entries, so later pages only flag their nulls
        java.util.Arrays.fill(nulls, n, levelEnd, false)
      values.dropFirst(valueBase)
      committed -= base
      levelEnd = n
      base = 0
      valueBase = 0

  /** Fails when the chunk has values beyond the row group's rows */
  def checkDone(): Unit =
    if held == null then
      loadNextPage()
    if levelEnd > base then
      throw ParquetException.corrupt(
        s"column ${field.pathString} has more values than the row group has rows"
      )
    // Every row is handed out: the cursor's own level arrays go back to the pool
    LevelBuffers.bytes.release(defLevels)
    LevelBuffers.bytes.release(repLevels)
    defLevels = null
    repLevels = null

  // Level arrays come from LevelBuffers, and are released by ColumnData.releaseLevels once handed
  // out, or when the chunk ends
  private def slice(levels: Array[Byte], from: Int, until: Int): Array[Byte] = moveLevels(
    levels,
    from,
    until - from,
    until - from
  )

  private def moveLevels(levels: Array[Byte], from: Int, n: Int, capacity: Int): Array[Byte] =
    if levels == null then
      null
    else
      val a = LevelBuffers.bytes.borrow(math.max(n, capacity))
      System.arraycopy(levels, from, a, 0, n)
      a

  /** A level array of `size` entries with the first `n` of `levels`, which goes back to the pool */
  private def growLevels(levels: Array[Byte], n: Int, size: Int): Array[Byte] =
    val a = moveLevels(levels, 0, n, size)
    LevelBuffers.bytes.release(levels)
    a

  /** The mask of entries [from, until), or null when none of them is null */
  private def sliceNulls(from: Int, until: Int): Array[Boolean] =
    if committedNulls == 0 then
      null
    else
      var c = 0
      var i = from
      while i < until do
        if nulls(i) then
          c += 1
        i += 1
      committedNulls -= c
      if c == 0 then
        null
      else
        java.util.Arrays.copyOfRange(nulls, from, until)

  /**
    * The mask for the `n` entries from `from`, those of the held page, at `capacity`, or null when
    * none of them is null. The held page's null positions move with its entries.
    */
  private def moveNulls(from: Int, n: Int, capacity: Int): Array[Boolean] =
    if nulls == null || nullMask.count == 0 then
      null
    else
      val a = new Array[Boolean](math.max(n, capacity))
      System.arraycopy(nulls, from, a, 0, n)
      var i = 0
      while i < nullMask.count do
        nullMask.positions(i) -= from
        i += 1
      a

  private def shiftLevels(levels: Array[Byte], from: Int, n: Int): Unit =
    if levels != null then
      System.arraycopy(levels, from, levels, 0, n)

  /** Level entries the chunk still has after the read pages and `pending` more */
  private def entriesLeft(pending: Int): Long = math.max(0L, total - levelsRead - pending)

  /**
    * Of the [[entriesLeft]], those the rest of the chunk is projected to hold from the entries per
    * byte of the data pages read so far. Projecting from the pages read keeps a corrupt value count
    * alone from forcing a huge allocation.
    */
  private def entriesAhead(pending: Int): Long =
    val left      = entriesLeft(pending)
    val dataBytes = pos - dictionaryBytes
    if left == 0 || dataBytes <= 0 then
      0L
    else
      val projected = ((levelsRead + pending).toDouble / dataBytes * (input.length - pos)).toLong +
        1
      // A batch stops near the soft limit, so sizing past it only wastes memory
      math.min(math.min(left, projected), softLimit / 4)

  /**
    * The size to grow an array of `current` entries to, to hold `needed`. When the batch takes the
    * rest of the chunk, it leaves room for the `ahead` projected entries and never grows past what
    * the chunk can still add (`left`), so the last page fills it exactly and the array can become
    * the vector without a copy. Otherwise it doubles.
    */
  private def grownSize(current: Int, needed: Long, ahead: Long, left: Long): Int =
    val doubled = ValueBuffer.grow(current, needed.toInt)
    if !toChunkEnd then
      doubled
    else
      math
        .min(
          math.max(needed + ahead, math.min(doubled.toLong, needed + left)),
          ValueBuffer.MaxArrayLength.toLong
        )
        .toInt

  private def ensureLevels(n: Int): Unit =
    val needed = levelEnd.toLong + n
    if needed > ValueBuffer.MaxArrayLength then
      throw ParquetException.unsupported(
        s"a batch with more than ${ValueBuffer.MaxArrayLength} values in column ${field.pathString}"
      )
    if (defLevels != null && defLevels.length < needed) ||
      (repLevels != null && repLevels.length < needed) ||
      (rowSlots && levelCap < needed)
    then
      val ahead = entriesAhead(n)
      val left  = entriesLeft(n)
      if rowSlots && levelCap < needed then
        levelCap = grownSize(levelCap, needed, ahead, left)
        if nulls != null then
          nulls = java.util.Arrays.copyOf(nulls, levelCap)
      if defLevels != null && defLevels.length < needed then
        defLevels = growLevels(
          defLevels,
          levelEnd,
          grownSize(defLevels.length, needed, ahead, left)
        )
      if repLevels != null && repLevels.length < needed then
        repLevels = growLevels(
          repLevels,
          levelEnd,
          grownSize(repLevels.length, needed, ahead, left)
        )

  /**
    * When the batch takes the rest of the chunk, sizes the value buffer for it before the held
    * page's values go in, as [[grownSize]] does for levels. A flat nullable column of fixed-size
    * values gets a slot for every level entry, so Assembly can spread the values out to their rows
    * in place.
    */
  private def reserveValues(h: HeldPage): Unit =
    val needed = values.size.toLong + slotsOf(h)
    // Deeper flat columns of fixed-size values get a slot per entry too, which Assembly fills
    val slotted  = rowSlots || (maxRep == 0 && maxDef > 0 && !values.isInstanceOf[BinaryBuffer])
    val capacity =
      if slotted && !rowSlots then
        math.max(needed, valueBase.toLong + levelEnd - base)
      else
        needed
    // Non-null values per level entry so far, projected over the entries not handed out yet
    def projectedAhead(): Long =
      val nonNull = values.size - valueBase + h.nonNull
      val ahead   = entriesAhead(0)
      if ahead == 0 then
        0L
      else if slotted then
        ahead
      else
        (ahead.toDouble * nonNull / (levelEnd - base)).toLong + 1
    if toChunkEnd && capacity > values.capacity && capacity <= ValueBuffer.MaxArrayLength then
      values.reserve(grownSize(values.capacity, capacity, projectedAhead(), entriesLeft(0)))
    else if rowSlots && capacity > values.capacity then
      // Room for the slots now, so neither the decoder nor the spread grows the buffer
      values.reserve(ValueBuffer.grow(values.capacity, capacity.toInt))
    values match
      case b: BinaryBuffer if toChunkEnd && b.size > valueBase =>
        // Size the data for the rest of the chunk from the bytes per value so far, once this page
        // may not fit, instead of doubling it page by page: each doubling zero-fills a new array
        // and copies the old one
        val perValue = (b.dataSize - b.offsets(valueBase)).toDouble / (b.size - valueBase)
        if b.dataSize + perValue * h.nonNull > b.data.length then
          val more = projectedAhead()
          // A little headroom, so values slightly longer than the average don't grow it again
          val bytes = (b.dataSize + perValue * (h.nonNull + more)) * 1.0625
          b.reserveData(math.min(bytes, ValueBuffer.MaxArrayLength.toDouble).toInt)
      case _ =>

  end reserveValues

  /** Decodes the held page's values */
  private def commit(): Unit =
    val h = held
    reserveValues(h)
    if (h.encoding == Encoding.RLE_DICTIONARY || h.encoding == Encoding.PLAIN_DICTIONARY) &&
      indices == null
    then
      // Indices are decoded one block at a time, so a block-sized array serves every page
      indices = new Array[Int](math.min(total, ValueDecoder.DictionaryBlock.toLong).toInt)
    val m = nullMask.count
    if rowSlots && m != h.numValues - h.nonNull then
      throw ParquetException.corrupt(
        s"column ${field.pathString} has ${m} nulls in a page of ${h.numValues} values with ${h
            .nonNull} non-null"
      )
    // Dictionary pages of fixed-size values decode straight into their row slots. Other pages
    // decode their values packed, which then move out to their rows: PLAIN pages decode in bulk,
    // which costs more per call than moving the values afterwards
    val inSlots =
      rowSlots && m > 0 && values.writesSlots &&
        (h.encoding == Encoding.RLE_DICTIONARY || h.encoding == Encoding.PLAIN_DICTIONARY)
    if inSlots then
      values.beginSlots(nullMask.positions, m)
    ValueDecoder.decode(
      h.encoding,
      field.physicalType,
      field.typeLength,
      h.buf,
      h.start,
      h.end,
      h.nonNull,
      values,
      dictionary,
      indices
    )
    if rowSlots then
      val written =
        if inSlots then
          values.endSlots(committed + h.numValues)
        else
          values.size - committed
      if written != h.nonNull then
        throw ParquetException.corrupt(
          s"a page of column ${field.pathString} has ${written} values for ${h
              .nonNull} non-null rows"
        )
      if !inSlots then
        values.spreadToRows(committed, h.numValues, nullMask.positions, m)
      nullMask.count = 0
      committedNulls += m
    if maxRep > 0 then
      var i = committed
      while i < levelEnd do
        if repLevels(i) == 0 then
          rowStarts += 1
        i += 1
    committed = levelEnd
    held = null

  end commit

  /** Reads a page header, reading more bytes when the header is longer than the first guess */
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

  /** Reads pages until one with values is held, or the chunk ends */
  private def loadNextPage(): Unit =
    compact()
    while held == null && !exhausted do
      val headerStart = pos
      val header      = readHeader()
      val size        = header.compressedSize
      if size < 0 then
        throw ParquetException.corrupt(s"negative page size ${size}")
      if size > input.length - pos then
        throw ParquetException.corrupt(
          s"a page of column ${field.pathString} needs ${size} bytes, but only ${input.length -
              pos} remain"
        )
      val chunk     = input.bytes(pos, size)
      val pageStart = input.offset
      pos += size
      header.pageType match
        case PageType.DICTIONARY_PAGE =>
          val d = header
            .dictionaryPage
            .getOrElse(throw ParquetException.corrupt("dictionary page without its header"))
          if d.numValues < 0 then
            throw ParquetException.corrupt(s"dictionary has ${d.numValues} values")
          val page = pageBytes(chunk, pageStart, size, header.uncompressedSize)
          val dict = newValues(math.min(d.numValues, 1 << 16))
          ValueDecoder.decodePlain(
            field.physicalType,
            field.typeLength,
            page,
            pageOffset,
            pageOffset + header.uncompressedSize,
            d.numValues,
            dict
          )
          dictionary = Some(dict)
          dictionaryBytes += pos - headerStart
        case PageType.DATA_PAGE =>
          val h = header
            .dataPage
            .getOrElse(throw ParquetException.corrupt("data page without its header"))
          checkPageValues(h.numValues)
          ensureLevels(h.numValues)
          val page    = pageBytes(chunk, pageStart, size, header.uncompressedSize)
          val pageEnd = pageOffset + header.uncompressedSize
          val pr      = ByteReader(page, pageOffset, pageEnd)
          if maxRep > 0 then
            readLevelsV1(pr, h.repetitionLevelEncoding, maxRep, repLevels, levelEnd, h.numValues)
          val nonNull =
            if rowSlots then
              readNullsV1(pr, h.definitionLevelEncoding, h.numValues)
            else if maxDef > 0 then
              readLevelsV1(pr, h.definitionLevelEncoding, maxDef, defLevels, levelEnd, h.numValues)
            else
              h.numValues
          hold(h.numValues, nonNull, h.encoding, page, pr.pos, pageEnd)
        case PageType.DATA_PAGE_V2 =>
          val h = header
            .dataPageV2
            .getOrElse(throw ParquetException.corrupt("data page v2 without its header"))
          checkPageValues(h.numValues)
          ensureLevels(h.numValues)
          val repLen = h.repetitionLevelsByteLength
          val defLen = h.definitionLevelsByteLength
          if repLen < 0 || defLen < 0 || repLen.toLong + defLen > size then
            throw ParquetException.corrupt("data page v2 level lengths are out of range")
          if maxRep > 0 then
            RleBitPackedDecoder(chunk, pageStart, pageStart + repLen, BitPacking.bitWidthOf(maxRep))
              .readLevels(repLevels, levelEnd, h.numValues, maxRep)
          val nonNull =
            if rowSlots then
              RleBitPackedDecoder(chunk, pageStart + repLen, pageStart + repLen + defLen, 1)
                .readNulls(nullMask, levelEnd, h.numValues)
            else if maxDef > 0 then
              RleBitPackedDecoder(
                chunk,
                pageStart + repLen,
                pageStart + repLen + defLen,
                BitPacking.bitWidthOf(maxDef)
              ).readLevels(defLevels, levelEnd, h.numValues, maxDef)
            else
              h.numValues
          val valuesStart       = pageStart + repLen + defLen
          val valuesLen         = size - repLen - defLen
          val rawLen            = header.uncompressedSize - repLen - defLen
          val (buf, start, end) =
            if rawLen == 0 then
              // An empty page: nothing to decompress
              (Array.emptyByteArray, 0, 0)
            else if h.isCompressed && meta.codec != CompressionCodec.UNCOMPRESSED then
              val out = pageBytes(chunk, valuesStart, valuesLen, rawLen)
              (out, pageOffset, pageOffset + rawLen)
            else
              // The values stay in the chunk's bytes, which ChunkInput keeps until the next read
              (chunk, valuesStart, valuesStart + valuesLen)
          hold(h.numValues, nonNull, h.encoding, buf, start, end)
        case PageType.INDEX_PAGE =>
        // Not used by readers
      end match
    end while
    if held == null && pageBuffer != null then
      // The chunk has ended, and its last page's values are committed
      PageBuffers.release(pageBuffer)
      pageBuffer = null
    if held == null then
      nullMask.release()

  end loadNextPage

  /**
    * The decompressed bytes of a page, at [pageOffset, pageOffset + uncompressedSize) of the
    * returned array. Uncompressed pages stay in the chunk's bytes, which ChunkInput keeps until the
    * next read; codecs that decode into a buffer use [[pageBuffer]]; others get an array of their
    * own. Called only when no page is held, so the buffer's last page is committed.
    */
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

  /** Holds a page of `n` level entries, `nonNull` of which have a value */
  private def hold(
      n: Int,
      nonNull: Int,
      encoding: Encoding,
      buf: Array[Byte],
      start: Int,
      end: Int
  ): Unit =
    levelsRead += n
    if n > 0 then
      levelEnd += n
      held = HeldPage(n, nonNull, encoding, buf, start, end)
      // With no committed entries left, the page must start a row: batches end at row starts
      if maxRep > 0 && committed == base && repLevels(committed) != 0 then
        val level = repLevels(committed)
        throw ParquetException.corrupt(
          s"column ${field.pathString} starts in the middle of a row (repetition level ${level})"
        )

  private def checkPageValues(n: Int): Unit =
    if n < 0 || n > total - levelsRead then
      throw ParquetException.corrupt(
        s"a page of column ${field
            .pathString} has ${n} values, more than the chunk's remaining ${total - levelsRead}"
      )

  /** Reads a page's 1-bit definition levels into the null mask; returns the non-null count */
  private def readNullsV1(r: ByteReader, encoding: Encoding, n: Int): Int =
    encoding match
      case Encoding.RLE =>
        val len = r.readIntLE()
        r.ensure(len)
        val c = RleBitPackedDecoder(r.buf, r.pos, r.pos + len, 1).readNulls(nullMask, levelEnd, n)
        r.pos += len
        c
      case Encoding.BIT_PACKED =>
        val len = (n + 7) / 8
        r.ensure(len)
        val tmp = new Array[Int](n)
        BitPacking.unpackIntsMsbFirst(r.buf, r.pos, r.pos + len, 1, tmp, n)
        r.pos += len
        RleBitPackedDecoder.copyNulls(tmp, nullMask, levelEnd, n)
      case other =>
        throw ParquetException.unsupported(s"${other} encoding for levels")

  private def readLevelsV1(
      r: ByteReader,
      encoding: Encoding,
      maxLevel: Int,
      dst: Array[Byte],
      offset: Int,
      n: Int
  ): Int =
    val bitWidth = BitPacking.bitWidthOf(maxLevel)
    encoding match
      case Encoding.RLE =>
        val len = r.readIntLE()
        r.ensure(len)
        val c = RleBitPackedDecoder(r.buf, r.pos, r.pos + len, bitWidth).readLevels(
          dst,
          offset,
          n,
          maxLevel
        )
        r.pos += len
        c
      case Encoding.BIT_PACKED =>
        val len = ((n.toLong * bitWidth + 7) / 8).toInt
        r.ensure(len)
        val tmp = new Array[Int](n)
        BitPacking.unpackIntsMsbFirst(r.buf, r.pos, r.pos + len, bitWidth, tmp, n)
        r.pos += len
        RleBitPackedDecoder.copyLevels(tmp, 0, dst, offset, n, maxLevel)
      case other =>
        throw ParquetException.unsupported(s"${other} encoding for levels")

  end readLevelsV1

end ColumnChunkCursor
