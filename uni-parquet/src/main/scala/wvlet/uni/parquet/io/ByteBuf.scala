package wvlet.uni.parquet.io

/** A growable byte buffer that writes little-endian numbers and varints */
private[parquet] final class ByteBuf(initialCapacity: Int = 256):
  private var buf: Array[Byte] = new Array[Byte](math.max(16, initialCapacity))
  private var len: Int         = 0

  def size: Int        = len
  def isEmpty: Boolean = len == 0
  def reset(): Unit    = len = 0

  /** The backing array; only the first `size` bytes are valid */
  def array: Array[Byte] = buf

  def toByteArray: Array[Byte] = java.util.Arrays.copyOf(buf, len)

  private def ensure(extra: Int): Unit =
    val need = len + extra
    if need < 0 then
      throw wvlet.uni.parquet.ParquetException("a buffer grew beyond 2 GiB")
    if need > buf.length then
      var cap = buf.length
      while cap < need do
        cap =
          if cap > (Int.MaxValue >> 1) then
            Int.MaxValue
          else
            cap << 1
      buf = java.util.Arrays.copyOf(buf, cap)

  def writeByte(b: Int): Unit =
    ensure(1)
    buf(len) = b.toByte
    len += 1

  def write(src: Array[Byte]): Unit = write(src, 0, src.length)

  def write(src: Array[Byte], off: Int, n: Int): Unit =
    ensure(n)
    if n <= ByteBuf.ShortCopy && off >= 0 && off + n <= src.length then
      // On Scala.js, System.arraycopy of bytes makes a typed-array view per call, which costs more
      // than a loop for a short run (a string value, mostly)
      var i = 0
      while i < n do
        buf(len + i) = src(off + i)
        i += 1
    else
      System.arraycopy(src, off, buf, len, n)
    len += n

  def write(other: ByteBuf): Unit = write(other.buf, 0, other.len)

  def writeIntLE(v: Int): Unit =
    ensure(4)
    buf(len) = v.toByte
    buf(len + 1) = (v >>> 8).toByte
    buf(len + 2) = (v >>> 16).toByte
    buf(len + 3) = (v >>> 24).toByte
    len += 4

  def writeLongLE(v: Long): Unit =
    ensure(8)
    var i = 0
    while i < 8 do
      buf(len + i) = (v >>> (8 * i)).toByte
      i += 1
    len += 8

  def writeUnsignedVarInt(v: Int): Unit =
    var x = v
    while (x & ~0x7f) != 0 do
      writeByte((x & 0x7f) | 0x80)
      x >>>= 7
    writeByte(x)

  def writeUnsignedVarLong(v: Long): Unit =
    var x = v
    while (x & ~0x7fL) != 0L do
      writeByte(((x & 0x7f) | 0x80).toInt)
      x >>>= 7
    writeByte(x.toInt)

  /** Overwrites 4 bytes at `pos` with `v` in little-endian order */
  def setIntLE(pos: Int, v: Int): Unit =
    buf(pos) = v.toByte
    buf(pos + 1) = (v >>> 8).toByte
    buf(pos + 2) = (v >>> 16).toByte
    buf(pos + 3) = (v >>> 24).toByte

end ByteBuf

/** A growable array of ints */
private[parquet] object ByteBuf:
  /** Copies up to this many bytes take a loop rather than System.arraycopy */
  final val ShortCopy = 32

private[parquet] final class IntBuf(initialCapacity: Int = 64):
  private var buf: Array[Int] = new Array[Int](math.max(8, initialCapacity))
  private var len: Int        = 0

  def size: Int          = len
  def apply(i: Int): Int = buf(i)
  def array: Array[Int]  = buf
  def reset(): Unit      = len = 0

  def add(v: Int): Unit =
    if len == buf.length then
      buf = java.util.Arrays.copyOf(buf, buf.length * 2)
    buf(len) = v
    len += 1

  def max: Int =
    var m = 0
    var i = 0
    while i < len do
      if buf(i) > m then
        m = buf(i)
      i += 1
    m

end IntBuf

/** A growable array of longs */
private[parquet] final class LongBuf(initialCapacity: Int = 64):
  private var buf: Array[Long] = new Array[Long](math.max(8, initialCapacity))
  private var len: Int         = 0

  def size: Int           = len
  def apply(i: Int): Long = buf(i)
  def reset(): Unit       = len = 0

  def add(v: Long): Unit =
    if len == buf.length then
      buf = java.util.Arrays.copyOf(buf, buf.length * 2)
    buf(len) = v
    len += 1

end LongBuf

/** A growable array of doubles */
private[parquet] final class DoubleBuf(initialCapacity: Int = 64):
  private var buf: Array[Double] = new Array[Double](math.max(8, initialCapacity))
  private var len: Int           = 0

  def size: Int             = len
  def apply(i: Int): Double = buf(i)
  def reset(): Unit         = len = 0

  def add(v: Double): Unit =
    if len == buf.length then
      buf = java.util.Arrays.copyOf(buf, buf.length * 2)
    buf(len) = v
    len += 1

end DoubleBuf
