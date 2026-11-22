package wvlet.uni.parquet.encoding

import wvlet.uni.io.codec.ByteBuf
import wvlet.uni.parquet.io.IntBuf

/**
  * Encodes the RLE/bit-packing hybrid that Parquet uses for repetition and definition levels,
  * dictionary indices, and booleans. Runs of 8 or more equal values become RLE runs; the rest are
  * bit-packed in groups of 8.
  */
private[parquet] object RleEncoder:

  def encode(values: IntBuf, bitWidth: Int, out: ByteBuf): Unit = encode(
    values.array,
    values.size,
    bitWidth,
    out
  )

  def encode(values: Array[Int], n: Int, bitWidth: Int, out: ByteBuf): Unit =
    val valueBytes = (bitWidth + 7) / 8
    var i          = 0
    while i < n do
      val run = runLength(values, i, n)
      if run >= 8 then
        out.writeUnsignedVarInt(run << 1)
        val v = values(i)
        var b = 0
        while b < valueBytes do
          out.writeByte(v >>> (8 * b))
          b += 1
        i += run
      else
        // Bit-pack groups of 8 until a long run starts at a group boundary. A header holds at most
        // 63 groups.
        val start  = i
        var groups = 0
        var done   = false
        while !done do
          groups += 1
          i += 8
          if i >= n || groups == 63 || runLength(values, i, n) >= 8 then
            done = true
        out.writeUnsignedVarInt((groups << 1) | 1)
        packGroups(values, start, math.min(i, n), groups * 8, bitWidth, out)
        i = math.min(i, n)
    end while

  end encode

  private def runLength(values: Array[Int], from: Int, n: Int): Int =
    val v = values(from)
    var j = from + 1
    while j < n && values(j) == v do
      j += 1
    j - from

  /** Packs `count` values (padding past `to` with zeros), least-significant bit first */
  private def packGroups(
      values: Array[Int],
      from: Int,
      to: Int,
      count: Int,
      bitWidth: Int,
      out: ByteBuf
  ): Unit =
    var acc  = 0L
    var bits = 0
    var k    = 0
    while k < count do
      val idx = from + k
      val v   =
        if idx < to then
          values(idx).toLong & 0xffffffffL
        else
          0L
      acc |= v << bits
      bits += bitWidth
      while bits >= 8 do
        out.writeByte((acc & 0xff).toInt)
        acc >>>= 8
        bits -= 8
      k += 1
    if bits > 0 then
      out.writeByte((acc & 0xff).toInt)

end RleEncoder
