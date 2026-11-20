package wvlet.uni.parquet.column

import wvlet.uni.parquet.ParquetException

/** Growable typed storage for the non-null values of one column */
private[parquet] sealed abstract class ValueBuffer:
  def size: Int

  /** Appends `dict(indices(i))` for i in [0, n) */
  def appendFromDictionary(dict: ValueBuffer, indices: Array[Int], n: Int): Unit

  protected final def checkIndex(i: Int, dictSize: Int): Unit =
    if i < 0 || i >= dictSize then
      throw ParquetException.corrupt(s"dictionary index ${i} is out of range (size ${dictSize})")

private[parquet] object ValueBuffer:
  private[column] def grow(current: Int, needed: Int): Int =
    if needed < 0 then
      throw ParquetException.unsupported("column chunks with more than 2 GiB of values")
    var n = math.max(current, 16)
    while n < needed do
      n =
        if n > (Int.MaxValue >> 1) then
          Int.MaxValue
        else
          n << 1
    n

private[parquet] final class BooleanBuffer(initial: Int = 16) extends ValueBuffer:
  var values: Array[Boolean] = new Array[Boolean](math.max(initial, 1))
  var size: Int              = 0

  def ensure(extra: Int): Unit =
    if size + extra > values.length then
      values = java.util.Arrays.copyOf(values, ValueBuffer.grow(values.length, size + extra))

  def add(v: Boolean): Unit =
    ensure(1)
    values(size) = v
    size += 1

  def appendFromDictionary(dict: ValueBuffer, indices: Array[Int], n: Int): Unit =
    val d = dict.asInstanceOf[BooleanBuffer]
    ensure(n)
    var i = 0
    while i < n do
      val k = indices(i)
      checkIndex(k, d.size)
      values(size + i) = d.values(k)
      i += 1
    size += n

private[parquet] final class IntBuffer(initial: Int = 16) extends ValueBuffer:
  var values: Array[Int] = new Array[Int](math.max(initial, 1))
  var size: Int          = 0

  def ensure(extra: Int): Unit =
    if size + extra > values.length then
      values = java.util.Arrays.copyOf(values, ValueBuffer.grow(values.length, size + extra))

  def add(v: Int): Unit =
    ensure(1)
    values(size) = v
    size += 1

  def appendFromDictionary(dict: ValueBuffer, indices: Array[Int], n: Int): Unit =
    val d = dict.asInstanceOf[IntBuffer]
    ensure(n)
    var i = 0
    while i < n do
      val k = indices(i)
      checkIndex(k, d.size)
      values(size + i) = d.values(k)
      i += 1
    size += n

private[parquet] final class LongBuffer(initial: Int = 16) extends ValueBuffer:
  var values: Array[Long] = new Array[Long](math.max(initial, 1))
  var size: Int           = 0

  def ensure(extra: Int): Unit =
    if size + extra > values.length then
      values = java.util.Arrays.copyOf(values, ValueBuffer.grow(values.length, size + extra))

  def add(v: Long): Unit =
    ensure(1)
    values(size) = v
    size += 1

  def appendFromDictionary(dict: ValueBuffer, indices: Array[Int], n: Int): Unit =
    val d = dict.asInstanceOf[LongBuffer]
    ensure(n)
    var i = 0
    while i < n do
      val k = indices(i)
      checkIndex(k, d.size)
      values(size + i) = d.values(k)
      i += 1
    size += n

private[parquet] final class FloatBuffer(initial: Int = 16) extends ValueBuffer:
  var values: Array[Float] = new Array[Float](math.max(initial, 1))
  var size: Int            = 0

  def ensure(extra: Int): Unit =
    if size + extra > values.length then
      values = java.util.Arrays.copyOf(values, ValueBuffer.grow(values.length, size + extra))

  def add(v: Float): Unit =
    ensure(1)
    values(size) = v
    size += 1

  def appendFromDictionary(dict: ValueBuffer, indices: Array[Int], n: Int): Unit =
    val d = dict.asInstanceOf[FloatBuffer]
    ensure(n)
    var i = 0
    while i < n do
      val k = indices(i)
      checkIndex(k, d.size)
      values(size + i) = d.values(k)
      i += 1
    size += n

private[parquet] final class DoubleBuffer(initial: Int = 16) extends ValueBuffer:
  var values: Array[Double] = new Array[Double](math.max(initial, 1))
  var size: Int             = 0

  def ensure(extra: Int): Unit =
    if size + extra > values.length then
      values = java.util.Arrays.copyOf(values, ValueBuffer.grow(values.length, size + extra))

  def add(v: Double): Unit =
    ensure(1)
    values(size) = v
    size += 1

  def appendFromDictionary(dict: ValueBuffer, indices: Array[Int], n: Int): Unit =
    val d = dict.asInstanceOf[DoubleBuffer]
    ensure(n)
    var i = 0
    while i < n do
      val k = indices(i)
      checkIndex(k, d.size)
      values(size + i) = d.values(k)
      i += 1
    size += n

/**
  * Variable-length binary values: value i is data[offsets(i), offsets(i + 1)). Fixed-length values
  * (FIXED_LEN_BYTE_ARRAY, INT96) use the same layout so all binary columns share one code path.
  */
private[parquet] final class BinaryBuffer(initial: Int = 16) extends ValueBuffer:
  var offsets: Array[Int] = new Array[Int](math.max(initial, 1) + 1)
  var data: Array[Byte]   = new Array[Byte](math.max(initial, 1) * 8)
  var size: Int           = 0

  inline def dataSize: Int = offsets(size)

  def ensureValues(extra: Int): Unit =
    if size + extra + 1 > offsets.length then
      offsets = java.util.Arrays.copyOf(offsets, ValueBuffer.grow(offsets.length, size + extra + 1))

  def ensureData(extra: Int): Unit =
    val needed = dataSize + extra
    if needed > data.length || needed < 0 then
      data = java.util.Arrays.copyOf(data, ValueBuffer.grow(data.length, needed))

  def add(src: Array[Byte], off: Int, len: Int): Unit =
    ensureValues(1)
    ensureData(len)
    val start = dataSize
    System.arraycopy(src, off, data, start, len)
    offsets(size + 1) = start + len
    size += 1

  def length(i: Int): Int = offsets(i + 1) - offsets(i)

  def bytes(i: Int): Array[Byte] = java.util.Arrays.copyOfRange(data, offsets(i), offsets(i + 1))

  def appendFromDictionary(dict: ValueBuffer, indices: Array[Int], n: Int): Unit =
    val d = dict.asInstanceOf[BinaryBuffer]
    ensureValues(n)
    var i = 0
    while i < n do
      val k = indices(i)
      checkIndex(k, d.size)
      add(d.data, d.offsets(k), d.length(k))
      i += 1

end BinaryBuffer
