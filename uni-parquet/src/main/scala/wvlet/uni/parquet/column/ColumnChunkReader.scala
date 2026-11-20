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
  * The decoded levels and non-null values of one column chunk. Levels are null when the column's
  * maximum level is 0.
  */
private[parquet] final class ColumnData(
    val field: ParquetField.Primitive,
    val values: ValueBuffer,
    val definitionLevels: Array[Int],
    val repetitionLevels: Array[Int],
    val numLevels: Int
)

/** Reads the pages of a column chunk */
private[parquet] object ColumnChunkReader:

  /**
    * Decodes a column chunk. `chunk` holds the bytes from the chunk's first page through its end.
    */
  def read(
      field: ParquetField.Primitive,
      meta: ColumnChunkMeta,
      chunk: Array[Byte],
      chunkOffset: Int,
      chunkLength: Int
  ): ColumnData =
    val numValues = meta.numValues
    if numValues < 0 || numValues > Int.MaxValue then
      throw ParquetException.corrupt(s"column ${field.pathString} has ${numValues} values")
    val total      = numValues.toInt
    var levelCount = 0
    val maxDef     = field.maxDefinitionLevel
    val maxRep     = field.maxRepetitionLevel
    // Level arrays grow page by page, so a corrupt value count in the metadata alone cannot force
    // a huge allocation
    var defLevels: Array[Int] =
      if maxDef > 0 then
        new Array[Int](math.min(total, 1024))
      else
        null
    var repLevels: Array[Int] =
      if maxRep > 0 then
        new Array[Int](math.min(total, 1024))
      else
        null
    def ensureLevels(n: Int): Unit =
      val needed = levelCount + n
      if defLevels != null && defLevels.length < needed then
        defLevels = java.util.Arrays.copyOf(defLevels, ValueBuffer.grow(defLevels.length, needed))
      if repLevels != null && repLevels.length < needed then
        repLevels = java.util.Arrays.copyOf(repLevels, ValueBuffer.grow(repLevels.length, needed))
    val values = ValueDecoder.newBuffer(field.physicalType, math.min(total, 1024))
    var dictionary: Option[ValueBuffer] = None
    val r                               = ByteReader(chunk, chunkOffset, chunkOffset + chunkLength)

    while levelCount < total && r.remaining > 0 do
      val header = MetadataDecoder.pageHeader(CompactProtocol.readStruct(r))
      if header.compressedSize < 0 then
        throw ParquetException.corrupt(s"negative page size ${header.compressedSize}")
      r.ensure(header.compressedSize)
      val pageStart = r.pos
      r.pos += header.compressedSize
      header.pageType match
        case PageType.DICTIONARY_PAGE =>
          val d = header
            .dictionaryPage
            .getOrElse(throw ParquetException.corrupt("dictionary page without its header"))
          if d.numValues < 0 then
            throw ParquetException.corrupt(s"dictionary has ${d.numValues} values")
          val page = Codecs.decompress(
            meta.codec,
            chunk,
            pageStart,
            header.compressedSize,
            header.uncompressedSize
          )
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
          checkPageValues(h.numValues, levelCount, total, field)
          ensureLevels(h.numValues)
          val page = Codecs.decompress(
            meta.codec,
            chunk,
            pageStart,
            header.compressedSize,
            header.uncompressedSize
          )
          val pr = ByteReader(page)
          if maxRep > 0 then
            readLevelsV1(pr, h.repetitionLevelEncoding, maxRep, repLevels, levelCount, h.numValues)
          if maxDef > 0 then
            readLevelsV1(pr, h.definitionLevelEncoding, maxDef, defLevels, levelCount, h.numValues)
          val nonNull = countNonNull(defLevels, levelCount, h.numValues, maxDef)
          ValueDecoder.decode(
            h.encoding,
            field.physicalType,
            field.typeLength,
            page,
            pr.pos,
            page.length,
            nonNull,
            values,
            dictionary
          )
          levelCount += h.numValues
        case PageType.DATA_PAGE_V2 =>
          val h = header
            .dataPageV2
            .getOrElse(throw ParquetException.corrupt("data page v2 without its header"))
          checkPageValues(h.numValues, levelCount, total, field)
          ensureLevels(h.numValues)
          val repLen = h.repetitionLevelsByteLength
          val defLen = h.definitionLevelsByteLength
          if repLen < 0 || defLen < 0 || repLen.toLong + defLen > header.compressedSize then
            throw ParquetException.corrupt("data page v2 level lengths are out of range")
          if maxRep > 0 then
            RleBitPackedDecoder(chunk, pageStart, pageStart + repLen, BitPacking.bitWidthOf(maxRep))
              .read(repLevels, levelCount, h.numValues)
            checkLevels(repLevels, levelCount, h.numValues, maxRep)
          if maxDef > 0 then
            RleBitPackedDecoder(
              chunk,
              pageStart + repLen,
              pageStart + repLen + defLen,
              BitPacking.bitWidthOf(maxDef)
            ).read(defLevels, levelCount, h.numValues)
            checkLevels(defLevels, levelCount, h.numValues, maxDef)
          val valuesStart       = pageStart + repLen + defLen
          val valuesLen         = header.compressedSize - repLen - defLen
          val rawLen            = header.uncompressedSize - repLen - defLen
          val (buf, start, end) =
            if h.isCompressed && meta.codec != CompressionCodec.UNCOMPRESSED then
              val out = Codecs.decompress(meta.codec, chunk, valuesStart, valuesLen, rawLen)
              (out, 0, out.length)
            else
              (chunk, valuesStart, valuesStart + valuesLen)
          val nonNull = countNonNull(defLevels, levelCount, h.numValues, maxDef)
          ValueDecoder.decode(
            h.encoding,
            field.physicalType,
            field.typeLength,
            buf,
            start,
            end,
            nonNull,
            values,
            dictionary
          )
          levelCount += h.numValues
        case PageType.INDEX_PAGE =>
        // Not used by readers
      end match
    end while

    if levelCount < total then
      throw ParquetException.corrupt(
        s"column ${field.pathString} ends after ${levelCount} of ${total} values"
      )
    ColumnData(field, values, defLevels, repLevels, levelCount)

  end read

  private def checkPageValues(n: Int, current: Int, total: Int, field: ParquetField): Unit =
    if n < 0 || n > total - current then
      throw ParquetException.corrupt(
        s"a page of column ${field
            .pathString} has ${n} values, more than the chunk's remaining ${total - current}"
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

end ColumnChunkReader
