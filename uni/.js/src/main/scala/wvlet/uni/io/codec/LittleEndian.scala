package wvlet.uni.io.codec

/** Bulk reads of little-endian values from byte arrays, for decoding PLAIN Parquet pages */
private[uni] object LittleEndian:
  def ints(src: Array[Byte], off: Int, dst: Array[Int], dstOff: Int, n: Int): Unit =
    var i = 0
    while i < n do
      dst(dstOff + i) = Zstd.le32(src, off + i * 4)
      i += 1

  def intsAsLongs(src: Array[Byte], off: Int, dst: Array[Long], dstOff: Int, n: Int): Unit =
    var i = 0
    while i < n do
      dst(dstOff + i) = Zstd.le32(src, off + i * 4).toLong
      i += 1

  def longs(src: Array[Byte], off: Int, dst: Array[Long], dstOff: Int, n: Int): Unit =
    var i = 0
    while i < n do
      dst(dstOff + i) = Word8.le64(src, off + i * 8)
      i += 1

  def floats(src: Array[Byte], off: Int, dst: Array[Float], dstOff: Int, n: Int): Unit =
    var i = 0
    while i < n do
      dst(dstOff + i) = java.lang.Float.intBitsToFloat(Zstd.le32(src, off + i * 4))
      i += 1

  def doubles(src: Array[Byte], off: Int, dst: Array[Double], dstOff: Int, n: Int): Unit =
    var i = 0
    while i < n do
      dst(dstOff + i) = java.lang.Double.longBitsToDouble(Word8.le64(src, off + i * 8))
      i += 1

end LittleEndian
