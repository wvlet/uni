package wvlet.uni.io.codec

/**
  * Fixed-width integer reads from a byte array, shared by the Parquet reader and the Arrow writer
  */
private[uni] object Bytes:
  inline def intLE(b: Array[Byte], i: Int): Int =
    (b(i) & 0xff) | ((b(i + 1) & 0xff) << 8) | ((b(i + 2) & 0xff) << 16) | ((b(i + 3) & 0xff) << 24)

  inline def longLE(b: Array[Byte], i: Int): Long =
    (intLE(b, i).toLong & 0xffffffffL) | (intLE(b, i + 4).toLong << 32)

  inline def longBE(b: Array[Byte], i: Int): Long =
    var v = 0L
    var k = 0
    while k < 8 do
      v = (v << 8) | (b(i + k) & 0xffL)
      k += 1
    v

  // Bulk reads of n little-endian values at src(off) into dst(dstOff), through the platform's
  // fastest copy. Plain defs, not inline: an inline forwarder to the JVM's Java class crashes
  // scalac.

  def intsLE(src: Array[Byte], off: Int, dst: Array[Int], dstOff: Int, n: Int): Unit =
    checkRange(src, off, 4, dst.length, dstOff, n)
    LittleEndian.ints(src, off, dst, dstOff, n)

  /** INT32 values widened to Long */
  def intsAsLongsLE(src: Array[Byte], off: Int, dst: Array[Long], dstOff: Int, n: Int): Unit =
    checkRange(src, off, 4, dst.length, dstOff, n)
    LittleEndian.intsAsLongs(src, off, dst, dstOff, n)

  def longsLE(src: Array[Byte], off: Int, dst: Array[Long], dstOff: Int, n: Int): Unit =
    checkRange(src, off, 8, dst.length, dstOff, n)
    LittleEndian.longs(src, off, dst, dstOff, n)

  def floatsLE(src: Array[Byte], off: Int, dst: Array[Float], dstOff: Int, n: Int): Unit =
    checkRange(src, off, 4, dst.length, dstOff, n)
    LittleEndian.floats(src, off, dst, dstOff, n)

  def doublesLE(src: Array[Byte], off: Int, dst: Array[Double], dstOff: Int, n: Int): Unit =
    checkRange(src, off, 8, dst.length, dstOff, n)
    LittleEndian.doubles(src, off, dst, dstOff, n)

  /** Scala Native's copies don't check bounds, so every platform checks them here */
  private def checkRange(
      src: Array[Byte],
      off: Int,
      width: Int,
      dstLength: Int,
      dstOff: Int,
      n: Int
  ): Unit =
    if n < 0 || off < 0 || dstOff < 0 || off + n.toLong * width > src.length ||
      dstOff.toLong + n > dstLength
    then
      throw IndexOutOfBoundsException(
        s"reading ${n} values of ${width} bytes at ${off} of ${src
            .length} bytes into ${dstOff} of ${dstLength}"
      )

end Bytes
