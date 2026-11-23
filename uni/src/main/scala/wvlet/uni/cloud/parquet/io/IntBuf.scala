package wvlet.uni.cloud.parquet.io

/** A growable array of ints */
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
