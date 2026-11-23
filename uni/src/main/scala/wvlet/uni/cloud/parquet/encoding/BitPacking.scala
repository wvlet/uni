package wvlet.uni.cloud.parquet.encoding

import wvlet.uni.cloud.parquet.ParquetException

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
  // A bit-packed run of 1-bit levels read straight from `buf`: its first byte, next bit, and bits left
  private var bitStart     = 0
  private var bitIndex     = 0
  private var bitRemaining = 0
  private val valueBytes   = (bitWidth + 7) / 8

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

  /** With `lazyBits`, a bit-packed run of 1-bit values is left in `buf` for [[readLevels]] */
  private def nextRun(lazyBits: Boolean = false): Unit =
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
      // A run's last group may be cut short at the end of the data (missing bits read as zero), but
      // never by more than one group
      val bytes = groups.toLong * bitWidth
      if groups <= 0 || bytes > (end - pos).toLong + bitWidth || groups > (1 << 27) then
        throw ParquetException.corrupt(s"bit-packed run has ${groups} groups")
      val count = groups * 8
      if bitWidth == 0 then
        // Zero-width values are all 0: no need to unpack them
        rleRemaining = count
        rleValue = 0
        return
      if lazyBits && bitWidth == 1 then
        bitStart = pos
        bitIndex = 0
        bitRemaining = count
        pos = math.min(end.toLong, pos + bytes).toInt
        return
      if packed.length < count then
        packed = new Array[Int](count)
      BitPacking.unpackInts(buf, pos, end, bitWidth, packed, 0, count)
      pos = math.min(end.toLong, pos + bytes).toInt
      packedPos = 0
      packedRemaining = count
    end if

  end nextRun

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

  /**
    * Reads `n` levels into `dst` as bytes, and returns how many equal `maxLevel`. Throws when a
    * level exceeds `maxLevel` (at most 127) or the data ends first. An RLE run is checked and
    * counted once for the whole run, so levels need no second pass.
    */
  def readLevels(dst: Array[Byte], offset: Int, n: Int, maxLevel: Int): Int =
    // 1-bit levels (flat optional columns) are expanded from the packed bits as they are counted
    val lazyBits = bitWidth == 1 && maxLevel == 1
    var count    = 0
    var i        = 0
    while i < n do
      if bitRemaining > 0 then
        val k = math.min(bitRemaining, n - i)
        var t = 0
        while t < k do
          val bi = bitIndex + t
          val p  = bitStart + (bi >>> 3)
          // The run's last group may be cut short at the end of the data: missing bits read as 0
          val v =
            if p < end then
              (buf(p) >>> (bi & 7)) & 1
            else
              0
          dst(offset + i + t) = v.toByte
          count += v
          t += 1
        bitIndex += k
        bitRemaining -= k
        i += k
      else if rleRemaining > 0 then
        val k = math.min(rleRemaining, n - i)
        if rleValue < 0 || rleValue > maxLevel then
          RleBitPackedDecoder.levelOutOfRange(rleValue, maxLevel)
        java.util.Arrays.fill(dst, offset + i, offset + i + k, rleValue.toByte)
        if rleValue == maxLevel then
          count += k
        rleRemaining -= k
        i += k
      else if packedRemaining > 0 then
        val k = math.min(packedRemaining, n - i)
        count += RleBitPackedDecoder.copyLevels(packed, packedPos, dst, offset + i, k, maxLevel)
        packedPos += k
        packedRemaining -= k
        i += k
      else if pos >= end then
        throw ParquetException.corrupt(s"RLE data ends after ${i} of ${n} values")
      else
        nextRun(lazyBits)
    end while
    count

  end readLevels

end RleBitPackedDecoder

private[parquet] object RleBitPackedDecoder:

  /**
    * Copies `n` levels from `src` to `dst` as bytes, and returns how many equal `maxLevel`. Throws
    * when a level exceeds `maxLevel`.
    */
  def copyLevels(
      src: Array[Int],
      from: Int,
      dst: Array[Byte],
      offset: Int,
      n: Int,
      maxLevel: Int
  ): Int =
    var count = 0
    var bad   = 0
    var j     = 0
    while j < n do
      val v = src(from + j)
      dst(offset + j) = v.toByte
      // Branch-free, so the loop stays simple: unsigned compare catches negatives too
      bad |= (
        if Integer.compareUnsigned(v, maxLevel) > 0 then
          1
        else
          0
      )
      count += (
        if v == maxLevel then
          1
        else
          0
      )
      j += 1
    if bad != 0 then
      var k = 0
      while k < n do
        levelOutOfRangeIf(src(from + k), maxLevel)
        k += 1
    count

  end copyLevels

  private def levelOutOfRangeIf(v: Int, maxLevel: Int): Unit =
    if Integer.compareUnsigned(v, maxLevel) > 0 then
      levelOutOfRange(v, maxLevel)

  def levelOutOfRange(v: Int, maxLevel: Int): Nothing =
    throw ParquetException.corrupt(s"level ${v} exceeds the maximum ${maxLevel}")

end RleBitPackedDecoder
