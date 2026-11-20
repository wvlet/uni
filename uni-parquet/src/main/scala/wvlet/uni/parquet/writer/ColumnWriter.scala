package wvlet.uni.parquet.writer

import wvlet.uni.parquet.ParquetField
import wvlet.uni.parquet.ParquetWriteOptions
import wvlet.uni.parquet.codec.Compressors
import wvlet.uni.parquet.encoding.BitPacking
import wvlet.uni.parquet.encoding.RleEncoder
import wvlet.uni.parquet.format.*
import wvlet.uni.parquet.io.ByteBuf
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
  * the rest of the row group.
  */
private[parquet] final class ColumnWriter(
    val field: ParquetField.Primitive,
    options: ParquetWriteOptions
):
  private val codec       = options.compression
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

  // Dictionary state for the current row group
  private var useDict    = canUseDict
  private val dictionary = mutable.HashMap.empty[Any, Int]
  private val dictBytes  = ByteBuf()

  // Closed pages of the current row group, each a page header followed by the compressed body
  private val pages            = mutable.ArrayBuffer.empty[Array[Byte]]
  private var pagesSize        = 0L
  private var uncompressedSize = 0L
  private var pageValues       = 0
  private var chunkValues      = 0L
  private var usedPlainPage    = false
  private var usedDictPage     = false

  /** Adds one value with its levels; `value` is null when `defLevel < maxDefinitionLevel` */
  def add(repLevel: Int, defLevel: Int, value: Any): Unit =
    if maxRep > 0 then
      repLevels.add(repLevel)
    if maxDef > 0 then
      defLevels.add(defLevel)
    pageValues += 1
    if defLevel == maxDef then
      stats.add(value)
      if useDict then
        val key = dictKey(value)
        val idx =
          dictionary.get(key) match
            case Some(i) =>
              i
            case None =>
              val i = dictionary.size
              dictionary.put(key, i)
              PlainEncoder.write(physical, value, dictBytes)
              i
        dictIndices.add(idx)
      else if physical == PhysicalType.BOOLEAN then
        boolValues.add(
          if value.asInstanceOf[Boolean] then
            1
          else
            0
        )
      else
        PlainEncoder.write(physical, value, plainValues)
    else
      stats.addNull()

  end add

  /** Called after each row: closes the page when it is full */
  def endRow(): Unit =
    if useDict && dictBytes.size > options.dictionaryPageSize then
      // Keep the pages written so far, and write the rest of the row group as PLAIN
      flushPage()
      useDict = false
    else if currentPageSize >= options.pageSize then
      flushPage()

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
        if useDict && dictionary.nonEmpty then
          usedDictPage = true
          val bw = math.max(1, BitPacking.bitWidthOf(dictionary.size - 1))
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
      val compressed = Compressors.compress(codec, body.array, body.size)
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
  def writeChunk(out: CountingOutput): ColumnChunkMeta =
    flushPage()
    val start = out.position
    // The dictionary page goes first, and its size counts toward the chunk's sizes
    val dictOffset =
      if usedDictPage then
        val compressed = Compressors.compress(codec, dictBytes.array, dictBytes.size)
        val header     = MetadataEncoder.pageHeader(
          PageHeader(
            pageType = PageType.DICTIONARY_PAGE,
            uncompressedSize = dictBytes.size,
            compressedSize = compressed.length,
            crc = None,
            dataPage = None,
            dataPageV2 = None,
            dictionaryPage = Some(DictionaryPageHeader(dictionary.size, Encoding.PLAIN))
          )
        )
        out.write(header)
        out.write(compressed)
        uncompressedSize += header.length + dictBytes.size
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
    dictionary.clear()
    dictBytes.reset()
    stats.reset()

  // By physical type, not runtime class: on Scala.js every number matches `Float`. Floating-point
  // values are compared by their bits, so NaN and -0.0 get their own entries.
  private def dictKey(v: Any): Any =
    physical match
      case PhysicalType.BYTE_ARRAY | PhysicalType.FIXED_LEN_BYTE_ARRAY =>
        BytesKey(v.asInstanceOf[Array[Byte]])
      case PhysicalType.FLOAT =>
        java.lang.Float.floatToRawIntBits(v.asInstanceOf[Float])
      case PhysicalType.DOUBLE =>
        java.lang.Double.doubleToRawLongBits(v.asInstanceOf[Double])
      case _ =>
        v

end ColumnWriter

/** A byte array as a hash key */
private final class BytesKey(val bytes: Array[Byte]):
  override val hashCode: Int           = java.util.Arrays.hashCode(bytes)
  override def equals(o: Any): Boolean =
    o match
      case k: BytesKey =>
        java.util.Arrays.equals(bytes, k.bytes)
      case _ =>
        false

/** PLAIN encoding of physical values */
private[parquet] object PlainEncoder:
  def write(tpe: PhysicalType, v: Any, out: ByteBuf): Unit =
    tpe match
      case PhysicalType.INT32 =>
        out.writeIntLE(v.asInstanceOf[Int])
      case PhysicalType.INT64 =>
        out.writeLongLE(v.asInstanceOf[Long])
      case PhysicalType.FLOAT =>
        out.writeIntLE(java.lang.Float.floatToRawIntBits(v.asInstanceOf[Float]))
      case PhysicalType.DOUBLE =>
        out.writeLongLE(java.lang.Double.doubleToRawLongBits(v.asInstanceOf[Double]))
      case PhysicalType.BYTE_ARRAY =>
        val b = v.asInstanceOf[Array[Byte]]
        out.writeIntLE(b.length)
        out.write(b)
      case PhysicalType.FIXED_LEN_BYTE_ARRAY =>
        out.write(v.asInstanceOf[Array[Byte]])
      case PhysicalType.BOOLEAN =>
        // Only dictionary pages write booleans one at a time, and booleans are never
        // dictionary-encoded
        out.writeByte(
          if v.asInstanceOf[Boolean] then
            1
          else
            0
        )
      case PhysicalType.INT96 =>
        throw wvlet.uni.parquet.ParquetException.unsupported("writing INT96 columns")

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
