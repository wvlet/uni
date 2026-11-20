package wvlet.uni.parquet.column

import wvlet.uni.parquet.ParquetException

/** Growable typed storage for the non-null values of one column */
private[parquet] sealed abstract class ValueBuffer:
  def size: Int

  /** Bytes the values take, for deciding where a batch ends */
  def byteSize: Long

  /** A new buffer with values [from, until) */
  def slice(from: Int, until: Int): ValueBuffer

  /** Removes values [0, n), keeping the rest at the front */
  def dropFirst(n: Int): Unit

  /** Appends `dict(indices(i))` for i in [0, n) */
  def appendFromDictionary(dict: ValueBuffer, indices: Array[Int], n: Int): Unit

  protected final def checkIndex(i: Int, dictSize: Int): Unit =
    if i < 0 || i >= dictSize then
      throw ParquetException.corrupt(s"dictionary index ${i} is out of range (size ${dictSize})")

private[parquet] object ValueBuffer:
  // The largest array every platform allocates; the JVM refuses lengths close to Int.MaxValue
  final val MaxArrayLength = Int.MaxValue - 8

  private[column] def grow(current: Int, needed: Int): Int =
    if needed < 0 || needed > MaxArrayLength then
      throw ParquetException.unsupported(
        "a batch with more than 2 GiB of values in one column. One row may be too large, or a page decodes to more than 2 GiB"
      )
    var n = math.max(current, 16)
    while n < needed do
      n =
        if n > (MaxArrayLength >> 1) then
          MaxArrayLength
        else
          n << 1
    n

private[parquet] final class BooleanBuffer(initial: Int = 16) extends ValueBuffer:
  var values: Array[Boolean] = new Array[Boolean](math.max(initial, 1))
  var size: Int              = 0

  def ensure(extra: Int): Unit =
    if size + extra > values.length then
      values = java.util.Arrays.copyOf(values, ValueBuffer.grow(values.length, size + extra))

  def byteSize: Long = size.toLong * 1

  def slice(from: Int, until: Int): BooleanBuffer =
    val b = BooleanBuffer(until - from)
    System.arraycopy(values, from, b.values, 0, until - from)
    b.size = until - from
    b

  def dropFirst(n: Int): Unit =
    System.arraycopy(values, n, values, 0, size - n)
    size -= n

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

end BooleanBuffer

private[parquet] final class IntBuffer(initial: Int = 16) extends ValueBuffer:
  var values: Array[Int] = new Array[Int](math.max(initial, 1))
  var size: Int          = 0

  def ensure(extra: Int): Unit =
    if size + extra > values.length then
      values = java.util.Arrays.copyOf(values, ValueBuffer.grow(values.length, size + extra))

  def byteSize: Long = size.toLong * 4

  def slice(from: Int, until: Int): IntBuffer =
    val b = IntBuffer(until - from)
    System.arraycopy(values, from, b.values, 0, until - from)
    b.size = until - from
    b

  def dropFirst(n: Int): Unit =
    System.arraycopy(values, n, values, 0, size - n)
    size -= n

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

end IntBuffer

private[parquet] final class LongBuffer(initial: Int = 16) extends ValueBuffer:
  var values: Array[Long] = new Array[Long](math.max(initial, 1))
  var size: Int           = 0

  def ensure(extra: Int): Unit =
    if size + extra > values.length then
      values = java.util.Arrays.copyOf(values, ValueBuffer.grow(values.length, size + extra))

  def byteSize: Long = size.toLong * 8

  def slice(from: Int, until: Int): LongBuffer =
    val b = LongBuffer(until - from)
    System.arraycopy(values, from, b.values, 0, until - from)
    b.size = until - from
    b

  def dropFirst(n: Int): Unit =
    System.arraycopy(values, n, values, 0, size - n)
    size -= n

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

end LongBuffer

private[parquet] final class FloatBuffer(initial: Int = 16) extends ValueBuffer:
  var values: Array[Float] = new Array[Float](math.max(initial, 1))
  var size: Int            = 0

  def ensure(extra: Int): Unit =
    if size + extra > values.length then
      values = java.util.Arrays.copyOf(values, ValueBuffer.grow(values.length, size + extra))

  def byteSize: Long = size.toLong * 4

  def slice(from: Int, until: Int): FloatBuffer =
    val b = FloatBuffer(until - from)
    System.arraycopy(values, from, b.values, 0, until - from)
    b.size = until - from
    b

  def dropFirst(n: Int): Unit =
    System.arraycopy(values, n, values, 0, size - n)
    size -= n

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

end FloatBuffer

private[parquet] final class DoubleBuffer(initial: Int = 16) extends ValueBuffer:
  var values: Array[Double] = new Array[Double](math.max(initial, 1))
  var size: Int             = 0

  def ensure(extra: Int): Unit =
    if size + extra > values.length then
      values = java.util.Arrays.copyOf(values, ValueBuffer.grow(values.length, size + extra))

  def byteSize: Long = size.toLong * 8

  def slice(from: Int, until: Int): DoubleBuffer =
    val b = DoubleBuffer(until - from)
    System.arraycopy(values, from, b.values, 0, until - from)
    b.size = until - from
    b

  def dropFirst(n: Int): Unit =
    System.arraycopy(values, n, values, 0, size - n)
    size -= n

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

end DoubleBuffer

/**
  * Variable-length binary values: value i is data[offsets(i), offsets(i + 1)). Fixed-length values
  * (FIXED_LEN_BYTE_ARRAY, INT96) use the same layout so all binary columns share one code path.
  */
private[parquet] final class BinaryBuffer(initial: Int = 16) extends ValueBuffer:
  var offsets: Array[Int] = new Array[Int](math.max(initial, 1) + 1)
  var data: Array[Byte]   = new Array[Byte](math.max(initial, 1) * 8)
  var size: Int           = 0

  // The dictionary entry of each of the values [0, dictCount), which came from `dict`. A chunk's
  // dictionary-encoded pages come before any PLAIN ones, so the entries are a prefix; when they are
  // not, `noDict` turns the indices off for this buffer. Case classes use them to build each
  // distinct String once.
  var dict: BinaryBuffer    = null
  var dictIdx: Array[Int]   = null
  var dictCount: Int        = 0
  private var noDict        = false

  /** The dictionary entry of value v, or -1 */
  def dictIndex(v: Int): Int =
    if dict != null && v < dictCount then
      dictIdx(v)
    else
      -1

  /** Strings of a dictionary's entries, filled in as case classes read them */
  lazy val strings: Array[String] = new Array[String](size)

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

  def byteSize: Long = dataSize.toLong + 4L * size

  /**
    * The longest value, for bounding how much a dictionary page can expand. Computed once, so use
    * it only on dictionaries, which do not change after they are decoded.
    */
  lazy val maxLength: Int =
    var m = 0
    var i = 0
    while i < size do
      m = math.max(m, length(i))
      i += 1
    m

  def slice(from: Int, until: Int): BinaryBuffer =
    val n     = until - from
    val start = offsets(from)
    val b     = BinaryBuffer(1)
    b.offsets = new Array[Int](n + 1)
    var i = 0
    while i <= n do
      b.offsets(i) = offsets(from + i) - start
      i += 1
    b.data = java.util.Arrays.copyOfRange(data, start, math.max(offsets(until), start + 1))
    b.size = n
    if dict != null && from < dictCount then
      b.dict = dict
      b.dictCount = math.min(until, dictCount) - from
      b.dictIdx = java.util.Arrays.copyOfRange(dictIdx, from, from + b.dictCount)
    b

  def dropFirst(n: Int): Unit =
    val base = offsets(n)
    System.arraycopy(data, base, data, 0, dataSize - base)
    var i = 0
    while i <= size - n do
      offsets(i) = offsets(i + n) - base
      i += 1
    size -= n
    if dict != null then
      if n < dictCount then
        System.arraycopy(dictIdx, n, dictIdx, 0, dictCount - n)
        dictCount -= n
      else
        dictCount = 0

  def bytes(i: Int): Array[Byte] = java.util.Arrays.copyOfRange(data, offsets(i), offsets(i + 1))

  def appendFromDictionary(dict: ValueBuffer, indices: Array[Int], n: Int): Unit =
    val d = dict.asInstanceOf[BinaryBuffer]
    ensureValues(n)
    recordIndices(d, indices, n)
    var i = 0
    while i < n do
      val k = indices(i)
      checkIndex(k, d.size)
      add(d.data, d.offsets(k), d.length(k))
      i += 1

  private def recordIndices(d: BinaryBuffer, indices: Array[Int], n: Int): Unit =
    if !noDict then
      if dictCount != size || (dict != null && (dict ne d)) then
        noDict = true
        dict = null
      else
        dict = d
        if dictIdx == null || dictIdx.length < size + n then
          dictIdx = java.util.Arrays.copyOf(
            if dictIdx == null then
              new Array[Int](0)
            else
              dictIdx
            ,
            ValueBuffer.grow(
              if dictIdx == null then
                0
              else
                dictIdx.length
              ,
              size + n
            )
          )
        System.arraycopy(indices, 0, dictIdx, size, n)
        dictCount += n

end BinaryBuffer
