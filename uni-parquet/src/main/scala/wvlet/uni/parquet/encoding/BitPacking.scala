package wvlet.uni.parquet.encoding

import wvlet.uni.parquet.ParquetException

private[parquet] object BitPacking:

  /**
    * Unpacks `n` values of `bitWidth` bits (at most 32), packed least-significant bit first, from
    * `src` starting at byte `offset`. Missing trailing bytes read as zero, since the last group of
    * a page may be cut short.
    */
  def unpackInts(
      src: Array[Byte],
      offset: Int,
      limit: Int,
      bitWidth: Int,
      dst: Array[Int],
      dstOffset: Int,
      n: Int
  ): Unit =
    if bitWidth == 0 then
      java.util.Arrays.fill(dst, dstOffset, dstOffset + n, 0)
    else
      val mask =
        if bitWidth == 32 then
          -1L & 0xffffffffL
        else
          (1L << bitWidth) - 1
      var acc  = 0L
      var bits = 0
      var ip   = offset
      var i    = 0
      while i < n do
        while bits < bitWidth do
          val b =
            if ip < limit then
              src(ip) & 0xffL
            else
              0L
          acc |= b << bits
          bits += 8
          ip += 1
        dst(dstOffset + i) = (acc & mask).toInt
        acc >>>= bitWidth
        bits -= bitWidth
        i += 1

  /** Unpacks values of up to 64 bits, used by DELTA_BINARY_PACKED */
  def unpackLongs(
      src: Array[Byte],
      offset: Int,
      limit: Int,
      bitWidth: Int,
      dst: Array[Long],
      dstOffset: Int,
      n: Int
  ): Unit =
    if bitWidth == 0 then
      java.util.Arrays.fill(dst, dstOffset, dstOffset + n, 0L)
    else if bitWidth > 64 then
      throw ParquetException.corrupt(s"bit width ${bitWidth} is larger than 64")
    else
      var bitPos = offset.toLong * 8
      var i      = 0
      while i < n do
        var v   = 0L
        var got = 0
        while got < bitWidth do
          val byteIdx = (bitPos >>> 3).toInt
          val inByte  = (bitPos & 7).toInt
          val take    = math.min(8 - inByte, bitWidth - got)
          val b       =
            if byteIdx < limit then
              src(byteIdx) & 0xff
            else
              0
          val bitsVal = (b >>> inByte) & ((1 << take) - 1)
          v |= bitsVal.toLong << got
          got += take
          bitPos += take
        dst(dstOffset + i) = v
        i += 1

  /** The deprecated BIT_PACKED level encoding packs from the most significant bit */
  def unpackIntsMsbFirst(
      src: Array[Byte],
      offset: Int,
      limit: Int,
      bitWidth: Int,
      dst: Array[Int],
      n: Int
  ): Unit =
    var bitPos = offset.toLong * 8
    var i      = 0
    while i < n do
      var v = 0
      var j = 0
      while j < bitWidth do
        val byteIdx = (bitPos >>> 3).toInt
        val b       =
          if byteIdx < limit then
            src(byteIdx) & 0xff
          else
            0
        val bit = (b >>> (7 - (bitPos & 7).toInt)) & 1
        v = (v << 1) | bit
        bitPos += 1
        j += 1
      dst(i) = v
      i += 1

  def bitWidthOf(maxValue: Int): Int = 32 - Integer.numberOfLeadingZeros(maxValue)

end BitPacking

/**
  * Decoder for the RLE / bit-packing hybrid encoding used for definition and repetition levels,
  * dictionary indices, and booleans.
  */
private[parquet] final class RleBitPackedDecoder(
    buf: Array[Byte],
    start: Int,
    end: Int,
    bitWidth: Int
):
  if bitWidth < 0 || bitWidth > 32 then
    throw ParquetException.corrupt(s"invalid RLE bit width ${bitWidth}")

  private var pos             = start
  private var rleRemaining    = 0
  private var rleValue        = 0
  private var packed          = new Array[Int](0)
  private var packedPos       = 0
  private var packedRemaining = 0
  private val valueBytes      = (bitWidth + 7) / 8

  private def readVarInt(): Int =
    var result = 0
    var shift  = 0
    var b      = 0
    while
      if pos >= end then
        throw ParquetException.corrupt("RLE run header is truncated")
      if shift > 28 then
        throw ParquetException.corrupt("RLE run header is too long")
      b = buf(pos) & 0xff
      pos += 1
      result |= (b & 0x7f) << shift
      shift += 7
      (b & 0x80) != 0
    do
      (
    )
    result

  private def nextRun(): Unit =
    val header = readVarInt()
    if (header & 1) == 0 then
      rleRemaining = header >>> 1
      var v = 0
      var i = 0
      while i < valueBytes do
        if pos >= end then
          throw ParquetException.corrupt("RLE run value is truncated")
        v |= (buf(pos) & 0xff) << (8 * i)
        pos += 1
        i += 1
      rleValue = v
    else
      val groups = header >>> 1
      if groups <= 0 || groups > (1 << 24) then
        throw ParquetException.corrupt(s"bit-packed run has ${groups} groups")
      val count = groups * 8
      // A run's bytes may be cut short at the end of the data; missing bits read as zero
      val bytes = groups.toLong * bitWidth
      if packed.length < count then
        packed = new Array[Int](count)
      BitPacking.unpackInts(buf, pos, end, bitWidth, packed, 0, count)
      pos = math.min(end.toLong, pos + bytes).toInt
      packedPos = 0
      packedRemaining = count

  /** Reads `n` values into `dst`. Throws when the data ends first. */
  def read(dst: Array[Int], offset: Int, n: Int): Unit =
    var i = 0
    while i < n do
      if rleRemaining > 0 then
        val k = math.min(rleRemaining, n - i)
        java.util.Arrays.fill(dst, offset + i, offset + i + k, rleValue)
        rleRemaining -= k
        i += k
      else if packedRemaining > 0 then
        val k = math.min(packedRemaining, n - i)
        System.arraycopy(packed, packedPos, dst, offset + i, k)
        packedPos += k
        packedRemaining -= k
        i += k
      else if pos >= end then
        throw ParquetException.corrupt(s"RLE data ends after ${i} of ${n} values")
      else
        nextRun()

end RleBitPackedDecoder
