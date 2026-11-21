package wvlet.uni.parquet.codec

/** 8-byte reads from byte arrays; the JVM and Scala Native versions also copy 8-byte words */
private[parquet] object Word8:

  /** The little-endian long at `b(i)` */
  inline def le64(b: Array[Byte], i: Int): Long =
    (Zstd.le32(b, i) & 0xffffffffL) | (Zstd.le32(b, i + 4).toLong << 32)

  /** The little-endian int at `b(i)`, where `b(i)` to `b(i + 3)` must be inside the array */
  inline def le32(b: Array[Byte], i: Int): Int = Zstd.le32(b, i)

  /**
    * The number of equal bytes at `src(a)` and `src(b)`, counting up to `limit` (exclusive) for
    * `b`, where `a` is below `b`. Compares 4 bytes at a time, since a `Long` is emulated on
    * Scala.js: the first differing byte is the lowest set byte of the XOR.
    */
  def matchLength(src: Array[Byte], a: Int, b: Int, limit: Int): Int =
    var n = 0
    while b + n <= limit - 4 do
      val x = Zstd.le32(src, a + n) ^ Zstd.le32(src, b + n)
      if x != 0 then
        return n + (Integer.numberOfTrailingZeros(x) >>> 3)
      n += 4
    while b + n < limit && src(a + n) == src(b + n) do
      n += 1
    n
