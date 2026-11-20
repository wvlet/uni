package wvlet.uni.parquet.column

import wvlet.uni.parquet.ParquetException
import wvlet.uni.parquet.ParquetField
import wvlet.uni.parquet.codec.Codecs
import wvlet.uni.parquet.encoding.BitPacking
import wvlet.uni.parquet.encoding.RleBitPackedDecoder
import wvlet.uni.parquet.encoding.ValueDecoder
import wvlet.uni.parquet.format.*
import wvlet.uni.parquet.io.ByteReader
import wvlet.uni.parquet.thrift.CompactProtocol

/**
  * The decoded levels and non-null values of a range of rows of one column. Levels are null when
  * the column's maximum level is 0. The level arrays may be longer than `numLevels`.
  */
private[parquet] final class ColumnData(
    val field: ParquetField.Primitive,
    val values: ValueBuffer,
    val definitionLevels: Array[Int],
    val repetitionLevels: Array[Int],
    val numLevels: Int
)

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
    hardLimit: Long
):
  private val total: Long = meta.numValues
  if total < 0 then
    throw ParquetException.corrupt(s"column ${field.pathString} has ${total} values")

  private val maxDef = field.maxDefinitionLevel
  private val maxRep = field.maxRepetitionLevel

  // Position in the chunk, and the level entries read from its pages so far (held page included)
  private var pos        = 0L
  private var levelsRead = 0L

  private var dictionary: Option[ValueBuffer] = None

  // Levels of committed pages are [0, committed); those of the held page are [committed, levelEnd).
  // They grow page by page, so a corrupt value count in the metadata cannot force a huge allocation.
  private var defLevels: Array[Int] = newLevels(1024)
  private var repLevels: Array[Int] =
    if maxRep > 0 then
      new Array[Int](initialLevels(1024))
    else
      null

  private var committed = 0
  private var levelEnd  = 0
  // Entries with repetition level 0 in [0, committed): the rows that start there
  private var rowStarts      = 0
  private var values         = ValueDecoder.newBuffer(field.physicalType, initialLevels(1024))
  private var held: HeldPage = null

  /** A data page whose levels are decoded and whose values are not yet */
  private final class HeldPage(
      val numValues: Int,
      val nonNull: Int,
      val encoding: Encoding,
      val buf: Array[Byte],
      val start: Int,
      val end: Int
  ):
    /** An upper bound of the bytes its values add */
    val valueBytes: Long =
      values match
        case _: BinaryBuffer =>
          encoding match
            case Encoding.RLE_DICTIONARY | Encoding.PLAIN_DICTIONARY =>
              dictionary match
                case Some(d: BinaryBuffer) =>
                  nonNull.toLong * (d.maxLength + 4)
                case _ =>
                  0L
            case _ =>
              (end - start).toLong + 4L * nonNull
        case _: LongBuffer | _: DoubleBuffer =>
          8L * nonNull
        case _: BooleanBuffer =>
          nonNull.toLong
        case _ =>
          4L * nonNull

  private def initialLevels(n: Int): Int = math.min(total, n.toLong).toInt

  private def newLevels(n: Int): Array[Int] =
    if maxDef > 0 then
      new Array[Int](initialLevels(n))
    else
      null

  private def levelArrays: Int =
    (
      if defLevels != null then
        1
      else
        0
    ) + (
      if repLevels != null then
        1
      else
        0
    )

  private def pendingBytes: Long = values.byteSize + 4L * levelEnd * levelArrays

  private def exhausted: Boolean = levelsRead >= total || pos >= input.length

  /** Complete rows in [0, committed). A row is complete once the next row has started. */
  private def completeRows: Int =
    if maxRep == 0 then
      committed
    else if rowStarts == 0 then
      0
    else if held == null || repLevels(committed) == 0 then
      // No held page means the chunk has ended
      rowStarts
    else
      rowStarts - 1

  /**
    * Decodes pages until `target` complete rows are ready, or fewer when the values pass the soft
    * limit or the chunk ends. Returns the number of complete rows ready, at most `target`.
    */
  def fill(target: Int): Int =
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
      else if complete > 0 &&
        (pendingBytes >= softLimit || pendingBytes + held.valueBytes > hardLimit)
      then
        result = complete
      else
        commit()
    result

  /** Hands out the first `rows` complete rows and keeps the rest */
  def take(rows: Int): ColumnData =
    // The entry where row `rows` starts
    val e =
      if maxRep == 0 then
        rows
      else if rows == rowStarts then
        committed
      else
        var i      = 0
        var starts = 0
        while starts <= rows do
          if repLevels(i) == 0 then
            starts += 1
          i += 1
        i - 1
    val v =
      if maxDef == 0 then
        e
      else
        var c = 0
        var i = 0
        while i < e do
          if defLevels(i) == maxDef then
            c += 1
          i += 1
        c
    val tail = levelEnd - e
    val out  =
      if v == values.size then
        // Everything committed goes out: hand over the arrays, and start new ones for the rest
        val data = ColumnData(field, values, defLevels, repLevels, e)
        values = ValueDecoder.newBuffer(field.physicalType, initialLevels(1024))
        defLevels = moveLevels(defLevels, e, tail)
        repLevels = moveLevels(repLevels, e, tail)
        data
      else
        val data = ColumnData(field, values.take(v), prefix(defLevels, e), prefix(repLevels, e), e)
        values.dropFirst(v)
        shiftLevels(defLevels, e, tail)
        shiftLevels(repLevels, e, tail)
        data
    committed -= e
    levelEnd = tail
    rowStarts -= rows
    out

  end take

  /** Fails when the chunk has values beyond the row group's rows */
  def checkDone(): Unit =
    if held == null then
      loadNextPage()
    if levelEnd > 0 then
      throw ParquetException.corrupt(
        s"column ${field.pathString} has more values than the row group has rows"
      )

  private def prefix(levels: Array[Int], n: Int): Array[Int] =
    if levels == null then
      null
    else
      java.util.Arrays.copyOf(levels, n)

  private def moveLevels(levels: Array[Int], from: Int, n: Int): Array[Int] =
    if levels == null then
      null
    else
      val a = new Array[Int](math.max(n, initialLevels(1024)))
      System.arraycopy(levels, from, a, 0, n)
      a

  private def shiftLevels(levels: Array[Int], from: Int, n: Int): Unit =
    if levels != null then
      System.arraycopy(levels, from, levels, 0, n)

  private def ensureLevels(n: Int): Unit =
    val needed = levelEnd.toLong + n
    if needed > ValueBuffer.MaxArrayLength then
      throw ParquetException.unsupported(
        s"a batch with more than ${ValueBuffer.MaxArrayLength} values in column ${field.pathString}"
      )
    if defLevels != null && defLevels.length < needed then
      defLevels = java
        .util
        .Arrays
        .copyOf(defLevels, ValueBuffer.grow(defLevels.length, needed.toInt))
    if repLevels != null && repLevels.length < needed then
      repLevels = java
        .util
        .Arrays
        .copyOf(repLevels, ValueBuffer.grow(repLevels.length, needed.toInt))

  /** Decodes the held page's values */
  private def commit(): Unit =
    val h = held
    ValueDecoder.decode(
      h.encoding,
      field.physicalType,
      field.typeLength,
      h.buf,
      h.start,
      h.end,
      h.nonNull,
      values,
      dictionary
    )
    if maxRep > 0 then
      var i = committed
      while i < levelEnd do
        if repLevels(i) == 0 then
          rowStarts += 1
        i += 1
    committed = levelEnd
    held = null

  /** Reads a page header, reading more bytes when the header is longer than the first guess */
  private def readHeader(): PageHeader =
    val left               = input.length - pos
    var n                  = math.min(left, 64L * 1024).toInt
    var header: PageHeader = null
    while header == null do
      val (buf, off) = input.bytes(pos, n)
      val r          = ByteReader(buf, off, off + n)
      try
        header = MetadataDecoder.pageHeader(CompactProtocol.readStruct(r))
        pos += r.pos - off
      catch
        case e: ParquetException if n < left =>
          n = math.min(left, n.toLong * 4).toInt
    header

  /** Reads pages until one with values is held, or the chunk ends */
  private def loadNextPage(): Unit =
    while held == null && !exhausted do
      val header = readHeader()
      val size   = header.compressedSize
      if size < 0 then
        throw ParquetException.corrupt(s"negative page size ${size}")
      if size > input.length - pos then
        throw ParquetException.corrupt(
          s"a page of column ${field.pathString} needs ${size} bytes, but only ${input.length -
              pos} remain"
        )
      val (chunk, pageStart) = input.bytes(pos, size)
      pos += size
      header.pageType match
        case PageType.DICTIONARY_PAGE =>
          val d = header
            .dictionaryPage
            .getOrElse(throw ParquetException.corrupt("dictionary page without its header"))
          if d.numValues < 0 then
            throw ParquetException.corrupt(s"dictionary has ${d.numValues} values")
          val page = Codecs.decompress(meta.codec, chunk, pageStart, size, header.uncompressedSize)
          val dict = ValueDecoder.newBuffer(field.physicalType, math.min(d.numValues, 1 << 16))
          ValueDecoder.decodePlain(
            field.physicalType,
            field.typeLength,
            page,
            0,
            page.length,
            d.numValues,
            dict
          )
          dictionary = Some(dict)
        case PageType.DATA_PAGE =>
          val h = header
            .dataPage
            .getOrElse(throw ParquetException.corrupt("data page without its header"))
          checkPageValues(h.numValues)
          ensureLevels(h.numValues)
          val page = Codecs.decompress(meta.codec, chunk, pageStart, size, header.uncompressedSize)
          val pr   = ByteReader(page)
          if maxRep > 0 then
            readLevelsV1(pr, h.repetitionLevelEncoding, maxRep, repLevels, levelEnd, h.numValues)
          if maxDef > 0 then
            readLevelsV1(pr, h.definitionLevelEncoding, maxDef, defLevels, levelEnd, h.numValues)
          hold(h.numValues, h.encoding, page, pr.pos, page.length)
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
              .read(repLevels, levelEnd, h.numValues)
            checkLevels(repLevels, levelEnd, h.numValues, maxRep)
          if maxDef > 0 then
            RleBitPackedDecoder(
              chunk,
              pageStart + repLen,
              pageStart + repLen + defLen,
              BitPacking.bitWidthOf(maxDef)
            ).read(defLevels, levelEnd, h.numValues)
            checkLevels(defLevels, levelEnd, h.numValues, maxDef)
          val valuesStart       = pageStart + repLen + defLen
          val valuesLen         = size - repLen - defLen
          val rawLen            = header.uncompressedSize - repLen - defLen
          val (buf, start, end) =
            if rawLen == 0 then
              // An empty page: nothing to decompress
              (Array.emptyByteArray, 0, 0)
            else if h.isCompressed && meta.codec != CompressionCodec.UNCOMPRESSED then
              val out = Codecs.decompress(meta.codec, chunk, valuesStart, valuesLen, rawLen)
              (out, 0, out.length)
            else
              // The values stay in the chunk's bytes, which ChunkInput keeps until the next read
              (chunk, valuesStart, valuesStart + valuesLen)
          hold(h.numValues, h.encoding, buf, start, end)
        case PageType.INDEX_PAGE =>
        // Not used by readers
      end match
    end while

  end loadNextPage

  private def hold(n: Int, encoding: Encoding, buf: Array[Byte], start: Int, end: Int): Unit =
    levelsRead += n
    if n > 0 then
      val nonNull = countNonNull(defLevels, levelEnd, n, maxDef)
      levelEnd += n
      held = HeldPage(n, nonNull, encoding, buf, start, end)
      if maxRep > 0 && committed == 0 && rowStarts == 0 && repLevels(0) != 0 then
        throw ParquetException.corrupt(
          s"column ${field.pathString} starts in the middle of a row (repetition level ${repLevels(
              0
            )})"
        )

  private def checkPageValues(n: Int): Unit =
    if n < 0 || n > total - levelsRead then
      throw ParquetException.corrupt(
        s"a page of column ${field
            .pathString} has ${n} values, more than the chunk's remaining ${total - levelsRead}"
      )

  private def countNonNull(defLevels: Array[Int], offset: Int, n: Int, maxDef: Int): Int =
    if maxDef == 0 then
      n
    else
      var c = 0
      var i = offset
      val e = offset + n
      while i < e do
        if defLevels(i) == maxDef then
          c += 1
        i += 1
      c

  private def readLevelsV1(
      r: ByteReader,
      encoding: Encoding,
      maxLevel: Int,
      dst: Array[Int],
      offset: Int,
      n: Int
  ): Unit =
    val bitWidth = BitPacking.bitWidthOf(maxLevel)
    encoding match
      case Encoding.RLE =>
        val len = r.readIntLE()
        r.ensure(len)
        RleBitPackedDecoder(r.buf, r.pos, r.pos + len, bitWidth).read(dst, offset, n)
        r.pos += len
      case Encoding.BIT_PACKED =>
        val len = ((n.toLong * bitWidth + 7) / 8).toInt
        r.ensure(len)
        val tmp = new Array[Int](n)
        BitPacking.unpackIntsMsbFirst(r.buf, r.pos, r.pos + len, bitWidth, tmp, n)
        System.arraycopy(tmp, 0, dst, offset, n)
        r.pos += len
      case other =>
        throw ParquetException.unsupported(s"${other} encoding for levels")
    checkLevels(dst, offset, n, maxLevel)

  private def checkLevels(levels: Array[Int], offset: Int, n: Int, maxLevel: Int): Unit =
    var i = offset
    while i < offset + n do
      if levels(i) > maxLevel then
        throw ParquetException.corrupt(s"level ${levels(i)} exceeds the maximum ${maxLevel}")
      i += 1

end ColumnChunkCursor
