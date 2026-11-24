package wvlet.uni.cloud.parquet.encoding

import wvlet.uni.cloud.parquet.ParquetException
import wvlet.uni.cloud.parquet.column.FixedValueBuffer
import wvlet.uni.cloud.parquet.column.ValueBuffer
import wvlet.uni.io.codec.Bytes

private[parquet] object BitPacking:

  /**
    * Unpacks `n` values of `bitWidth` bits (at most 32), packed least-significant bit first, from
    * `src` starting `firstBit` bits after byte `offset`. Missing trailing bytes read as zero, since
    * the last group of a page may be cut short.
    *
    * Values whose 8 bytes from their first byte lie before `limit` are read with one 64-bit load
    * each (a value starts at most 7 bits into its first byte, so it fits in the load); the last few
    * values go bit by bit.
    */
  def unpackInts(
      src: Array[Byte],
      offset: Int,
      limit: Int,
      bitWidth: Int,
      dst: Array[Int],
      dstOffset: Int,
      n: Int,
      firstBit: Long = 0L
  ): Unit =
    if bitWidth == 0 then
      java.util.Arrays.fill(dst, dstOffset, dstOffset + n, 0)
    else
      val mask =
        if bitWidth == 32 then
          0xffffffffL
        else
          (1L << bitWidth) - 1
      // Values [0, fast) have their 8 bytes in range: byte offset + (bit >>> 3) + 8 <= limit
      val lastByte = limit.toLong - 8 - offset
      val fast     =
        if lastByte < 0 || firstBit > lastByte * 8 + 7 then
          0
        else
          math.min(n.toLong, (lastByte * 8 + 7 - firstBit) / bitWidth + 1).toInt
      var bit = firstBit
      var i   = 0
      while i < fast do
        val w = Bytes.wordLE(src, offset + (bit >>> 3).toInt)
        dst(dstOffset + i) = ((w >>> (bit & 7).toInt) & mask).toInt
        bit += bitWidth
        i += 1
      while i < n do
        var v   = 0L
        var got = 0
        while got < bitWidth do
          val byteIdx = offset + (bit >>> 3)
          val inByte  = (bit & 7).toInt
          val take    = math.min(8 - inByte, bitWidth - got)
          val b       =
            if byteIdx < limit then
              src(byteIdx.toInt) & 0xff
            else
              0
          v |= ((b >>> inByte) & ((1 << take) - 1)).toLong << got
          got += take
          bit += take
        dst(dstOffset + i) = v.toInt
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

  private var pos          = start
  private var rleRemaining = 0
  private var rleValue     = 0
  // The bit-packed run being read, left in `buf` and unpacked as it is read: its first byte, the
  // index of its next value, and the values left
  private var bitStart     = 0
  private var bitIndex     = 0
  private var bitRemaining = 0
  // Unpacked levels of a run wider than 1 bit, before they are checked and copied as bytes
  private var scratch: Array[Int] = null
  private val valueBytes          = (bitWidth + 7) / 8

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
      else
        bitStart = pos
        bitIndex = 0
        bitRemaining = count
        pos = math.min(end.toLong, pos + bytes).toInt
    end if

  end nextRun

  /** Unpacks the next `k` values of the bit-packed run into `dst` */
  private def unpack(dst: Array[Int], offset: Int, k: Int): Unit =
    BitPacking.unpackInts(
      buf,
      bitStart,
      end,
      bitWidth,
      dst,
      offset,
      k,
      firstBit = bitIndex.toLong * bitWidth
    )
    bitIndex += k
    bitRemaining -= k

  /** Reads `n` values into `dst`. Throws when the data ends first. */
  def read(dst: Array[Int], offset: Int, n: Int): Unit =
    var i = 0
    while i < n do
      if rleRemaining > 0 then
        val k = math.min(rleRemaining, n - i)
        java.util.Arrays.fill(dst, offset + i, offset + i + k, rleValue)
        rleRemaining -= k
        i += k
      else if bitRemaining > 0 then
        val k = math.min(bitRemaining, n - i)
        unpack(dst, offset + i, k)
        i += k
      else if pos >= end then
        throw ParquetException.corrupt(s"RLE data ends after ${i} of ${n} values")
      else
        nextRun()

  /**
    * Appends `dict(index)` to `out` for the next `n` dictionary indices. An RLE run is looked up
    * once for the whole run; bit-packed indices are unpacked into `indices`, a block at a time.
    * Throws when the data ends first or an index is out of the dictionary.
    */
  def readDictionary(out: ValueBuffer, dict: ValueBuffer, n: Int, indices: Array[Int]): Unit =
    var i = 0
    while i < n do
      if rleRemaining > 0 then
        val k = math.min(rleRemaining, n - i)
        out.appendRepeated(dict, rleValue, k, indices)
        rleRemaining -= k
        i += k
      else if bitRemaining > 0 then
        val k = math.min(math.min(bitRemaining, n - i), indices.length)
        unpack(indices, 0, k)
        out.appendFromDictionary(dict, indices, k)
        i += k
      else if pos >= end then
        throw ParquetException.corrupt(s"RLE data ends after ${i} of ${n} values")
      else
        nextRun()

  /**
    * Puts the values of the next `n` dictionary indices in the rows [out.size, to) of `out` that
    * `nulls` does not flag, and the default value in the flagged rows. `n` must be the number of
    * rows not flagged. As in [[readDictionary]], an RLE run is looked up once and its value put in
    * its rows; bit-packed indices are unpacked a block at a time into `indices` and looked up
    * straight into their rows.
    */
  def readDictionaryInto(
      out: FixedValueBuffer,
      dict: ValueBuffer,
      n: Int,
      nulls: Array[Boolean],
      to: Int,
      indices: Array[Int]
  ): Unit =
    out.ensure(to - out.size)
    var row = out.size
    var i   = 0
    while i < n do
      if rleRemaining > 0 then
        // One value for the whole run, put straight into its rows
        val k = math.min(rleRemaining, n - i)
        row = out.placeRepeated(dict, rleValue, k, nulls, row, to)
        rleRemaining -= k
        i += k
      else if bitRemaining > 0 then
        val k = math.min(math.min(bitRemaining, n - i), indices.length)
        unpack(indices, 0, k)
        row = out.placeFromDictionary(dict, indices, k, nulls, row, to)
        i += k
      else if pos >= end then
        throw ParquetException.corrupt(s"RLE data ends after ${i} of ${n} values")
      else
        nextRun()
    // The rows after the last value are null
    out.size = row
    out.appendNulls(to - row)

  end readDictionaryInto

  /**
    * Reads `n` 1-bit definition levels as null flags into `dst`: level 0 is null, level 1 is not.
    * Returns how many are not null. Throws when a level exceeds 1 or the data ends first.
    */
  def readNullFlags(dst: Array[Boolean], offset: Int, n: Int): Int =
    if bitWidth != 1 then
      throw ParquetException.corrupt(s"null flags need 1-bit levels, not ${bitWidth}-bit")
    var count = 0
    var i     = 0
    while i < n do
      if bitRemaining > 0 then
        val k = math.min(bitRemaining, n - i)
        count += expandNullFlags(dst, offset + i, k)
        bitIndex += k
        bitRemaining -= k
        i += k
      else if rleRemaining > 0 then
        val k = math.min(rleRemaining, n - i)
        if rleValue < 0 || rleValue > 1 then
          RleBitPackedDecoder.levelOutOfRange(rleValue, 1)
        java.util.Arrays.fill(dst, offset + i, offset + i + k, rleValue == 0)
        if rleValue == 1 then
          count += k
        rleRemaining -= k
        i += k
      else if pos >= end then
        throw ParquetException.corrupt(s"RLE data ends after ${i} of ${n} values")
      else
        nextRun()
    count

  /** As [[expandBits]], writing a null flag (bit 0) per bit */
  private def expandNullFlags(dst: Array[Boolean], offset: Int, k: Int): Int =
    var count = 0
    var t     = 0
    while t < k do
      val bi = bitIndex + t
      val p  = bitStart + (bi >>> 3)
      if (bi & 7) == 0 && k - t >= 8 && p < end then
        val b = buf(p) & 0xff
        val o = offset + t
        dst(o) = (b & 1) == 0
        dst(o + 1) = (b & 2) == 0
        dst(o + 2) = (b & 4) == 0
        dst(o + 3) = (b & 8) == 0
        dst(o + 4) = (b & 16) == 0
        dst(o + 5) = (b & 32) == 0
        dst(o + 6) = (b & 64) == 0
        dst(o + 7) = (b & 128) == 0
        count += Integer.bitCount(b)
        t += 8
      else
        val v =
          if p < end then
            (buf(p) >>> (bi & 7)) & 1
          else
            0
        dst(offset + t) = v == 0
        count += v
        t += 1
    count

  end expandNullFlags

  /**
    * Reads `n` levels into `dst` as bytes, and returns how many equal `maxLevel`. Throws when a
    * level exceeds `maxLevel` (at most 127) or the data ends first. An RLE run is checked and
    * counted once for the whole run, so levels need no second pass.
    */
  def readLevels(dst: Array[Byte], offset: Int, n: Int, maxLevel: Int): Int =
    var count = 0
    var i     = 0
    while i < n do
      if bitRemaining > 0 then
        val k =
          if bitWidth == 1 then
            // 1-bit levels (flat optional columns): 0 or 1, never out of range
            val k = math.min(bitRemaining, n - i)
            count += expandBits(dst, offset + i, k)
            bitIndex += k
            bitRemaining -= k
            k
          else
            if scratch == null then
              scratch = new Array[Int](ValueDecoder.DictionaryBlock)
            val k = math.min(math.min(bitRemaining, n - i), scratch.length)
            unpack(scratch, 0, k)
            count += RleBitPackedDecoder.copyLevels(scratch, 0, dst, offset + i, k, maxLevel)
            k
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
      else if pos >= end then
        throw ParquetException.corrupt(s"RLE data ends after ${i} of ${n} values")
      else
        nextRun()
    end while
    count

  end readLevels

  /**
    * Writes the next `k` bits of the 1-bit run to `dst` as bytes, and returns how many are 1. Whole
    * bytes go 8 bits at a time; missing bytes at the end of the data read as 0.
    */
  private def expandBits(dst: Array[Byte], offset: Int, k: Int): Int =
    var count = 0
    var t     = 0
    while t < k do
      val bi = bitIndex + t
      val p  = bitStart + (bi >>> 3)
      if (bi & 7) == 0 && k - t >= 8 && p < end then
        val b = buf(p) & 0xff
        val o = offset + t
        dst(o) = (b & 1).toByte
        dst(o + 1) = ((b >>> 1) & 1).toByte
        dst(o + 2) = ((b >>> 2) & 1).toByte
        dst(o + 3) = ((b >>> 3) & 1).toByte
        dst(o + 4) = ((b >>> 4) & 1).toByte
        dst(o + 5) = ((b >>> 5) & 1).toByte
        dst(o + 6) = ((b >>> 6) & 1).toByte
        dst(o + 7) = (b >>> 7).toByte
        count += Integer.bitCount(b)
        t += 8
      else
        val v =
          if p < end then
            (buf(p) >>> (bi & 7)) & 1
          else
            0
        dst(offset + t) = v.toByte
        count += v
        t += 1
    count

  end expandBits

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
