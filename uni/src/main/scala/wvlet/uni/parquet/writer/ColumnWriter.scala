package wvlet.uni.parquet.writer

import wvlet.uni.parquet.ParquetField
import wvlet.uni.parquet.ParquetWriteOptions
import wvlet.uni.parquet.codec.Compressors
import wvlet.uni.parquet.column.BinaryBuffer
import wvlet.uni.parquet.encoding.BitPacking
import wvlet.uni.parquet.encoding.RleEncoder
import wvlet.uni.parquet.format.*
import wvlet.uni.io.codec.ByteBuf
import wvlet.uni.parquet.io.IntBuf

import scala.collection.mutable

/** Counts the bytes written to an output stream, for the offsets in the footer */
private[parquet] final class CountingOutput(out: java.io.OutputStream):
  private var pos = 0L

  def position: Long = pos

  def write(b: Array[Byte]): Unit = write(b, 0, b.length)

  def write(b: Array[Byte], off: Int, len: Int): Unit =
    out.write(b, off, len)
    pos += len

  def flush(): Unit = out.flush()
  def close(): Unit = out.close()

  /** Discards the output after a failure: an object upload is aborted, other streams closed */
  def abort(): Unit =
    out match
      case o: wvlet.uni.storage.ObjectOutputStream =>
        o.abort()
      case _ =>
        out.close()

/**
  * Buffers the pages of one leaf column for the current row group. Values arrive with their
  * repetition and definition levels; a page is closed at a row boundary once it reaches the page
  * size. Values are dictionary-encoded until the dictionary grows past its limit, then PLAIN for
  * the rest of the row group. After the first [[ColumnWriter.DictCheckValues]] values of a row
  * group, a dictionary that is no smaller than PLAIN would be (dictionary page plus bit-packed
  * indices against the raw values) is dropped too, as parquet-java does at its first page: such a
  * column has too many distinct values for the dictionary to pay off, and looking each value up
  * costs time. A dictionary dropped before any dictionary-encoded page is closed leaves no trace:
  * the buffered values are rewritten as PLAIN, so the chunk has no dictionary page.
  */
private[parquet] final class ColumnWriter(
    val field: ParquetField.Primitive,
    options: ParquetWriteOptions
):
  private val codec       = options.compression
  private val level       = options.compressionLevel
  private val maxRep      = field.maxRepetitionLevel
  private val maxDef      = field.maxDefinitionLevel
  private val physical    = field.physicalType
  private val canUseDict  = options.dictionaryEnabled && physical != PhysicalType.BOOLEAN
  private val stats       = ColumnStats(field)
  private val repLevels   = IntBuf()
  private val defLevels   = IntBuf()
  private val dictIndices = IntBuf()
  private val plainValues = ByteBuf()
  private val boolValues  = IntBuf()

  // Dictionary state for the current row group. `dictBytes` is the PLAIN-encoded dictionary page.
  private var useDict   = canUseDict
  private val dictBytes = ByteBuf()
  private val longDict  =
    physical match
      case PhysicalType.BYTE_ARRAY | PhysicalType.FIXED_LEN_BYTE_ARRAY =>
        null
      case _ =>
        LongDictionary()

  private val bytesDict =
    physical match
      case PhysicalType.BYTE_ARRAY =>
        BytesDictionary(dictBytes, lengthPrefix = true)
      case PhysicalType.FIXED_LEN_BYTE_ARRAY =>
        BytesDictionary(dictBytes, lengthPrefix = false)
      case _ =>
        null

  // The writer's dictionary index of each entry of `remapFor`, a reader's dictionary, or -1 until a
  // row uses the entry. Valid for the current row group only, while `useDict` holds.
  private var remapFor: BinaryBuffer = null
  private var remap: Array[Int]      = null

  // Values added through the dictionary in this row group, and their PLAIN size, for the check
  private var dictValues  = 0
  private var dictRaw     = 0L
  private var dictChecked = false

  private def dictionarySize: Int =
    if longDict != null then
      longDict.size
    else
      bytesDict.size

  // Closed pages of the current row group, each a page header followed by the compressed body
  private val pages            = mutable.ArrayBuffer.empty[Array[Byte]]
  private var pagesSize        = 0L
  private var uncompressedSize = 0L
  private var pageValues       = 0
  private var chunkValues      = 0L
  private var usedPlainPage    = false
  private var usedDictPage     = false

  private inline def levels(repLevel: Int, defLevel: Int): Unit =
    if maxRep > 0 then
      repLevels.add(repLevel)
    if maxDef > 0 then
      defLevels.add(defLevel)
    pageValues += 1

  // The typed appends below take a non-null value, at the column's maximum definition level. The
  // caller calls the one of the column's physical type.

  /** Adds a null (or an empty list) at a definition level below the maximum */
  def addNull(repLevel: Int, defLevel: Int): Unit =
    levels(repLevel, defLevel)
    stats.addNull()

  def addInt(repLevel: Int, v: Int): Unit =
    levels(repLevel, maxDef)
    stats.addInt(v)
    if useDict then
      val idx = longDict.indexOf(v.toLong)
      if longDict.added then
        dictBytes.writeIntLE(v)
      dictIndices.add(idx)
      dictValues += 1
      dictRaw += 4
    else
      plainValues.writeIntLE(v)

  def addLong(repLevel: Int, v: Long): Unit =
    levels(repLevel, maxDef)
    stats.addLong(v)
    if useDict then
      val idx = longDict.indexOf(v)
      if longDict.added then
        dictBytes.writeLongLE(v)
      dictIndices.add(idx)
      dictValues += 1
      dictRaw += 8
    else
      plainValues.writeLongLE(v)

  // Floating-point values are keyed by their bits, so NaN and -0.0 get their own entries
  def addFloat(repLevel: Int, v: Float): Unit =
    levels(repLevel, maxDef)
    stats.addDouble(v.toDouble)
    val bits = java.lang.Float.floatToRawIntBits(v)
    if useDict then
      val idx = longDict.indexOf(bits.toLong)
      if longDict.added then
        dictBytes.writeIntLE(bits)
      dictIndices.add(idx)
      dictValues += 1
      dictRaw += 4
    else
      plainValues.writeIntLE(bits)

  def addDouble(repLevel: Int, v: Double): Unit =
    levels(repLevel, maxDef)
    stats.addDouble(v)
    val bits = java.lang.Double.doubleToRawLongBits(v)
    if useDict then
      val idx = longDict.indexOf(bits)
      if longDict.added then
        dictBytes.writeLongLE(bits)
      dictIndices.add(idx)
      dictValues += 1
      dictRaw += 8
    else
      plainValues.writeLongLE(bits)

  /** Booleans are never dictionary-encoded */
  def addBoolean(repLevel: Int, v: Boolean): Unit =
    levels(repLevel, maxDef)
    stats.addBoolean(v)
    boolValues.add(
      if v then
        1
      else
        0
    )

  /** BYTE_ARRAY and FIXED_LEN_BYTE_ARRAY values: `src[off, off + len)` */
  def addBytes(repLevel: Int, src: Array[Byte], off: Int, len: Int): Unit =
    levels(repLevel, maxDef)
    if useDict then
      dictIndices.add(bytesDict.indexOf(src, off, len))
      // A value already in the dictionary cannot change min or max
      if bytesDict.added then
        stats.addBytes(src, off, len)
      countDictBytes(len)
    else
      stats.addBytes(src, off, len)
      if physical == PhysicalType.BYTE_ARRAY then
        plainValues.writeIntLE(len)
      plainValues.write(src, off, len)

  /**
    * A BYTE_ARRAY or FIXED_LEN_BYTE_ARRAY value that is entry `entry` of `dict`, the dictionary a
    * reader decoded it from. Each entry is looked up in the writer's dictionary once per row group;
    * later rows with the same entry skip the hash and the probe. Their bytes are still compared
    * with the writer's entry, since a caller may have edited a read vector's data before writing
    * it.
    */
  def addDictBytes(
      repLevel: Int,
      src: Array[Byte],
      off: Int,
      len: Int,
      dict: BinaryBuffer,
      entry: Int
  ): Unit =
    if !useDict then
      addBytes(repLevel, src, off, len)
    else
      levels(repLevel, maxDef)
      if remapFor ne dict then
        useRemap(dict)
      val known = remap(entry)
      countDictBytes(len)
      if known >= 0 && bytesDict.same(known, src, off, len) then
        dictIndices.add(known)
      else
        val idx = bytesDict.indexOf(src, off, len)
        // A value already in the dictionary cannot change min or max
        if bytesDict.added then
          stats.addBytes(src, off, len)
        if known < 0 then
          remap(entry) = idx
        dictIndices.add(idx)

  private inline def countDictBytes(len: Int): Unit =
    dictValues += 1
    dictRaw += (
      if physical == PhysicalType.BYTE_ARRAY then
        len + 4
      else
        len
    )

  private def useRemap(dict: BinaryBuffer): Unit =
    if remap == null || remap.length < dict.size then
      remap = new Array[Int](dict.size)
    java.util.Arrays.fill(remap, 0, dict.size, -1)
    remapFor = dict

  /** Called after each row: closes the page when it is full */
  def endRow(): Unit =
    if useDict && (
        dictBytes.size > options.dictionaryPageSize ||
          (!dictChecked && dictValues >= ColumnWriter.DictCheckValues && !dictionaryPays)
      )
    then
      dropDictionary()
    else if currentPageSize >= options.pageSize then
      flushPage()

  /** Writes the rest of the row group as PLAIN */
  private def dropDictionary(): Unit =
    if !usedDictPage && physical != PhysicalType.INT96 then
      // No page went out dictionary-encoded: rewrite the open page's values as PLAIN
      rewriteAsPlain()
      useDict = false
      remapFor = null
      if longDict != null then
        longDict.clear()
      else
        bytesDict.clear()
      dictBytes.reset()
    else
      // Keep the pages written so far
      flushPage()
      useDict = false

  private def rewriteAsPlain(): Unit =
    val d = dictBytes.array
    var i = 0
    physical match
      case PhysicalType.BYTE_ARRAY | PhysicalType.FIXED_LEN_BYTE_ARRAY =>
        val prefix = physical == PhysicalType.BYTE_ARRAY
        while i < dictIndices.size do
          val e   = dictIndices(i)
          val len = bytesDict.lengthOf(e)
          if prefix then
            plainValues.writeIntLE(len)
          plainValues.write(d, bytesDict.offsetOf(e), len)
          i += 1
      case _ =>
        // Fixed-width entries, in index order
        val width =
          physical match
            case PhysicalType.INT64 | PhysicalType.DOUBLE =>
              8
            case _ =>
              4
        while i < dictIndices.size do
          plainValues.write(d, dictIndices(i) * width, width)
          i += 1
    dictIndices.reset()

  /**
    * Whether the dictionary is smaller than PLAIN for the values so far; checked once a row group
    */
  private def dictionaryPays: Boolean =
    dictChecked = true
    val bitWidth = math.max(1, BitPacking.bitWidthOf(dictionarySize - 1))
    dictBytes.size + (dictValues.toLong * bitWidth + 7) / 8 < dictRaw

  private def currentPageSize: Long =
    val values: Long =
      if useDict then
        dictIndices.size.toLong * 4
      else
        plainValues.size + boolValues.size / 8
    values + (repLevels.size + defLevels.size).toLong / 2

  /** Bytes buffered for the current row group */
  def bufferedSize: Long = pagesSize + currentPageSize + dictBytes.size

  private def flushPage(): Unit =
    if pageValues > 0 then
      val body = ByteBuf(1024)
      if maxRep > 0 then
        writeLevels(repLevels, maxRep, body)
      if maxDef > 0 then
        writeLevels(defLevels, maxDef, body)
      val encoding =
        if useDict && dictionarySize > 0 then
          usedDictPage = true
          val bw = math.max(1, BitPacking.bitWidthOf(dictionarySize - 1))
          body.writeByte(bw)
          RleEncoder.encode(dictIndices, bw, body)
          Encoding.RLE_DICTIONARY
        else
          usedPlainPage = true
          if physical == PhysicalType.BOOLEAN then
            PlainEncoder.writeBooleans(boolValues, body)
          else
            body.write(plainValues)
          Encoding.PLAIN
      val compressed = Compressors.compress(codec, body.array, body.size, level)
      val header     = MetadataEncoder.pageHeader(
        PageHeader(
          pageType = PageType.DATA_PAGE,
          uncompressedSize = body.size,
          compressedSize = compressed.length,
          crc = None,
          dataPage = Some(DataPageHeader(pageValues, encoding, Encoding.RLE, Encoding.RLE)),
          dataPageV2 = None,
          dictionaryPage = None
        )
      )
      addPage(header, compressed, body.size)
      chunkValues += pageValues
      pageValues = 0
      repLevels.reset()
      defLevels.reset()
      dictIndices.reset()
      plainValues.reset()
      boolValues.reset()

  private def addPage(header: Array[Byte], body: Array[Byte], rawSize: Int): Unit =
    val page = new Array[Byte](header.length + body.length)
    System.arraycopy(header, 0, page, 0, header.length)
    System.arraycopy(body, 0, page, header.length, body.length)
    pages += page
    pagesSize += page.length
    uncompressedSize += header.length + rawSize

  private def writeLevels(levels: IntBuf, max: Int, body: ByteBuf): Unit =
    val lenPos = body.size
    body.writeIntLE(0)
    RleEncoder.encode(levels, BitPacking.bitWidthOf(max), body)
    body.setIntLE(lenPos, body.size - lenPos - 4)

  /** Writes the column chunk of the current row group and resets for the next one */
  // The compressed dictionary page with its header, set by finishPages
  private var dictPage: Array[Byte] = null
  private var finished              = false

  /**
    * Closes the last page and compresses the dictionary page, the CPU work of [[writeChunk]].
    * Column writers don't share state, so the writers of a row group can finish their pages in
    * parallel.
    */
  def finishPages(): Unit =
    if !finished then
      flushPage()
      if usedDictPage then
        val compressed = Compressors.compress(codec, dictBytes.array, dictBytes.size, level)
        val header     = MetadataEncoder.pageHeader(
          PageHeader(
            pageType = PageType.DICTIONARY_PAGE,
            uncompressedSize = dictBytes.size,
            compressedSize = compressed.length,
            crc = None,
            dataPage = None,
            dataPageV2 = None,
            dictionaryPage = Some(DictionaryPageHeader(dictionarySize, Encoding.PLAIN))
          )
        )
        dictPage = new Array[Byte](header.length + compressed.length)
        System.arraycopy(header, 0, dictPage, 0, header.length)
        System.arraycopy(compressed, 0, dictPage, header.length, compressed.length)
        uncompressedSize += header.length + dictBytes.size
      finished = true

  def writeChunk(out: CountingOutput): ColumnChunkMeta =
    finishPages()
    val start = out.position
    // The dictionary page goes first, and its size counts toward the chunk's sizes
    val dictOffset =
      if dictPage != null then
        out.write(dictPage)
        Some(start)
      else
        None
    val dataOffset = out.position
    pages.foreach(out.write)
    val encodings = Seq.newBuilder[Encoding]
    if usedDictPage || usedPlainPage then
      encodings += Encoding.PLAIN
    if maxRep > 0 || maxDef > 0 then
      encodings += Encoding.RLE
    if usedDictPage then
      encodings += Encoding.RLE_DICTIONARY
    val meta = ColumnChunkMeta(
      path = field.path,
      physicalType = physical,
      encodings = encodings.result(),
      codec = codec,
      numValues = chunkValues,
      totalUncompressedSize = uncompressedSize,
      totalCompressedSize = out.position - start,
      dataPageOffset = dataOffset,
      dictionaryPageOffset = dictOffset,
      statistics = Some(stats.result()),
      offsetIndexOffset = None,
      offsetIndexLength = None,
      columnIndexOffset = None,
      columnIndexLength = None,
      bloomFilterOffset = None,
      bloomFilterLength = None,
      filePath = None,
      isEncrypted = false
    )
    reset()
    meta

  end writeChunk

  private def reset(): Unit =
    pages.clear()
    pagesSize = 0
    uncompressedSize = 0
    chunkValues = 0
    usedPlainPage = false
    usedDictPage = false
    useDict = canUseDict
    dictPage = null
    finished = false
    dictValues = 0
    dictRaw = 0
    dictChecked = false
    // The writer's dictionary is cleared below, so the indices of the reader's entries are stale
    remapFor = null
    if longDict != null then
      longDict.clear()
    else
      bytesDict.clear()
    dictBytes.reset()
    stats.reset()

end ColumnWriter

private[parquet] object ColumnWriter:
  /** The values of a row group after which the writer checks that its dictionary pays off */
  val DictCheckValues = 20000

/** PLAIN encoding of booleans */
private[parquet] object PlainEncoder:
  /** Booleans are bit-packed, least-significant bit first */
  def writeBooleans(values: IntBuf, out: ByteBuf): Unit =
    var acc  = 0
    var bits = 0
    var i    = 0
    while i < values.size do
      acc |= (values(i) & 1) << bits
      bits += 1
      if bits == 8 then
        out.writeByte(acc)
        acc = 0
        bits = 0
      i += 1
    if bits > 0 then
      out.writeByte(acc)

end PlainEncoder
