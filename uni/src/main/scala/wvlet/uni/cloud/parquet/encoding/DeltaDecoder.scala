package wvlet.uni.cloud.parquet.encoding

import wvlet.uni.cloud.parquet.ParquetException
import wvlet.uni.cloud.parquet.column.*
import wvlet.uni.cloud.parquet.format.PhysicalType
import wvlet.uni.cloud.parquet.io.ByteReader

/** DELTA_BINARY_PACKED, DELTA_LENGTH_BYTE_ARRAY, and DELTA_BYTE_ARRAY */
private[parquet] object DeltaDecoder:

  /**
    * Decodes DELTA_BINARY_PACKED values. Returns the decoded values and the position right after
    * them, which DELTA_LENGTH_BYTE_ARRAY needs to find the byte data that follows.
    */
  def readBinaryPacked(buf: Array[Byte], start: Int, end: Int, maxValues: Int): (Array[Long], Int) =
    val r                  = ByteReader(buf, start, end)
    val blockSize          = r.readUnsignedVarInt()
    val miniBlocksPerBlock = r.readUnsignedVarInt()
    val totalCount         = r.readUnsignedVarInt()
    var last               = r.readZigZagLong()
    if blockSize <= 0 || blockSize % 128 != 0 || miniBlocksPerBlock <= 0 ||
      blockSize                    % miniBlocksPerBlock != 0
    then
      throw ParquetException.corrupt(
        s"invalid DELTA_BINARY_PACKED header: block size ${blockSize}, ${miniBlocksPerBlock} miniblocks"
      )
    // Writers use blocks of 128 values; a bound keeps a corrupt header from forcing a huge allocation
    if blockSize > (1 << 20) || miniBlocksPerBlock > 1024 then
      throw ParquetException.corrupt(s"DELTA_BINARY_PACKED block size  is too large")
    val perMiniBlock = blockSize / miniBlocksPerBlock
    if perMiniBlock % 32 != 0 then
      throw ParquetException.corrupt(s"miniblock size ${perMiniBlock} is not a multiple of 32")
    if totalCount < 0 || totalCount > maxValues then
      throw ParquetException.corrupt(
        s"DELTA_BINARY_PACKED holds ${totalCount} values, more than the page's ${maxValues}"
      )
    val out = new Array[Long](totalCount)
    if totalCount > 0 then
      out(0) = last
    var n         = 1
    val bitWidths = new Array[Int](miniBlocksPerBlock)
    val mini      = new Array[Long](perMiniBlock)
    while n < totalCount do
      val minDelta = r.readZigZagLong()
      var i        = 0
      while i < miniBlocksPerBlock do
        bitWidths(i) = r.readUnsignedByte()
        i += 1
      var m = 0
      while m < miniBlocksPerBlock && n < totalCount do
        val w = bitWidths(m)
        if w > 64 then
          throw ParquetException.corrupt(s"miniblock bit width ${w} is larger than 64")
        val bytes = perMiniBlock / 8 * w
        // The last miniblock may be cut short when it is not full
        BitPacking.unpackLongs(buf, r.pos, end, w, mini, 0, perMiniBlock)
        r.pos = math.min(end, r.pos + bytes)
        var k = 0
        while k < perMiniBlock && n < totalCount do
          last = last + minDelta + mini(k)
          out(n) = last
          n += 1
          k += 1
        m += 1
    end while
    (out, r.pos)

  end readBinaryPacked

  def decodeBinaryPacked(
      tpe: PhysicalType,
      buf: Array[Byte],
      start: Int,
      end: Int,
      n: Int,
      out: ValueBuffer
  ): Unit =
    val (values, _) = readBinaryPacked(buf, start, end, n)
    if values.length < n then
      throw ParquetException.corrupt(s"DELTA_BINARY_PACKED has ${values.length} values, need ${n}")
    tpe match
      case PhysicalType.INT32 =>
        out match
          case b: LongBuffer =>
            // INT32 values read as Long: truncate to 32 bits as an Int column would, then widen
            b.ensure(n)
            var i = 0
            while i < n do
              b.values(b.size + i) = values(i).toInt.toLong
              i += 1
            b.size += n
          case _ =>
            val b = out.asInstanceOf[IntBuffer]
            b.ensure(n)
            var i = 0
            while i < n do
              b.values(b.size + i) = values(i).toInt
              i += 1
            b.size += n
      case PhysicalType.INT64 =>
        val b = out.asInstanceOf[LongBuffer]
        b.ensure(n)
        System.arraycopy(values, 0, b.values, b.size, n)
        b.size += n
      case other =>
        throw ParquetException.unsupported(s"DELTA_BINARY_PACKED encoding for ${other} values")

  end decodeBinaryPacked

  def decodeLengthByteArray(
      buf: Array[Byte],
      start: Int,
      end: Int,
      n: Int,
      out: BinaryBuffer
  ): Unit =
    val (lengths, dataStart) = readBinaryPacked(buf, start, end, n)
    if lengths.length < n then
      throw ParquetException.corrupt(
        s"DELTA_LENGTH_BYTE_ARRAY has ${lengths.length} values, need ${n}"
      )
    var pos = dataStart
    out.ensureValues(n)
    var i = 0
    while i < n do
      val len = lengths(i)
      if len < 0 || len > end - pos then
        throw ParquetException.corrupt(s"byte array length ${len} is out of range")
      out.add(buf, pos, len.toInt)
      pos += len.toInt
      i += 1

  def decodeByteArray(buf: Array[Byte], start: Int, end: Int, n: Int, out: BinaryBuffer): Unit =
    val (prefixes, suffixStart) = readBinaryPacked(buf, start, end, n)
    val (suffixLens, dataStart) = readBinaryPacked(buf, suffixStart, end, n)
    if prefixes.length < n || suffixLens.length < n then
      throw ParquetException.corrupt("DELTA_BYTE_ARRAY has fewer values than the page")
    var pos = dataStart
    // Each value starts with a prefix of the previous one, copied within the output buffer
    var prevStart = 0
    var prevLen   = 0
    out.ensureValues(n)
    var i = 0
    while i < n do
      val p = prefixes(i)
      val s = suffixLens(i)
      if p < 0 || p > prevLen || s < 0 || s > end - pos then
        throw ParquetException.corrupt("DELTA_BYTE_ARRAY prefix or suffix is out of range")
      val len = (p + s).toInt
      out.ensureData(len)
      val start = out.dataSize
      System.arraycopy(out.data, prevStart, out.data, start, p.toInt)
      System.arraycopy(buf, pos, out.data, start + p.toInt, s.toInt)
      pos += s.toInt
      out.offsets(out.size + 1) = start + len
      out.size += 1
      prevStart = start
      prevLen = len
      i += 1

end DeltaDecoder
