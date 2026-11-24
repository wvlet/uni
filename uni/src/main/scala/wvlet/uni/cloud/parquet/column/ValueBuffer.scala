package wvlet.uni.cloud.parquet.column

import wvlet.uni.cloud.parquet.ParquetException

/** Growable typed storage for the non-null values of one column */
private[parquet] sealed abstract class ValueBuffer:
  def size: Int

  /** The number of values the buffer holds without growing */
  def capacity: Int

  /** Grows the buffer, if needed, to hold `n` values in all without growing again */
  def reserve(n: Int): Unit

  /** Bytes the values take, for deciding where a batch ends */
  def byteSize: Long

  /** A new buffer with values [from, until) */
  def slice(from: Int, until: Int): ValueBuffer

  /** Removes values [0, n), keeping the rest at the front */
  def dropFirst(n: Int): Unit

  /** Appends `dict(indices(i))` for i in [0, n) */
  def appendFromDictionary(dict: ValueBuffer, indices: Array[Int], n: Int): Unit

  /**
    * Appends `dict(index)` `n` times, for an RLE run of dictionary indices. Fixed-size buffers fill
    * the value once; others expand the run into `indices` (a block-sized scratch array).
    */
  def appendRepeated(dict: ValueBuffer, index: Int, n: Int, indices: Array[Int]): Unit =
    var done = 0
    while done < n do
      val k = math.min(indices.length, n - done)
      java.util.Arrays.fill(indices, 0, k, index)
      appendFromDictionary(dict, indices, k)
      done += k

  protected final def checkIndex(i: Int, dictSize: Int): Unit =
    if i < 0 || i >= dictSize then
      throw ParquetException.corrupt(s"dictionary index ${i} is out of range (size ${dictSize})")

  /**
    * Fails unless every index of `indices(0)` to `indices(n - 1)` is in a dictionary of `dictSize`
    * entries. One branch-free pass, so the lookup loop that follows needs no check per value.
    */
  protected final def checkIndices(indices: Array[Int], n: Int, dictSize: Int): Unit =
    var bad = 0
    var i   = 0
    while i < n do
      val k = indices(i)
      // Negative when k < 0 or k >= dictSize
      bad |= k | (dictSize - 1 - k)
      i += 1
    if bad < 0 then
      var j = 0
      while j < n do
        checkIndex(indices(j), dictSize)
        j += 1

end ValueBuffer

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

private[parquet] final class BooleanBuffer(initial: Int = 16) extends FixedValueBuffer:
  var values: Array[Boolean] = new Array[Boolean](math.max(initial, 1))
  var size: Int              = 0

  def ensure(extra: Int): Unit =
    if size + extra > values.length then
      values = java.util.Arrays.copyOf(values, ValueBuffer.grow(values.length, size + extra))

  def capacity: Int = values.length

  def reserve(n: Int): Unit =
    if n > values.length then
      values = java.util.Arrays.copyOf(values, n)

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
    checkIndices(indices, n, d.size)
    ensure(n)
    val dv = d.values
    val v  = values
    val s  = size
    var i  = 0
    while i < n do
      v(s + i) = dv(indices(i))
      i += 1
    size += n

  override def appendRepeated(dict: ValueBuffer, index: Int, n: Int, indices: Array[Int]): Unit =
    val d = dict.asInstanceOf[BooleanBuffer]
    checkIndex(index, d.size)
    ensure(n)
    java.util.Arrays.fill(values, size, size + n, d.values(index))
    size += n

  def appendNulls(n: Int): Unit =
    ensure(n)
    java.util.Arrays.fill(values, size, size + n, false)
    size += n

  def spreadSlots(from: Int, count: Int, to: Int, nulls: Array[Boolean]): Unit =
    ensure(to - size)
    val a = values
    FixedValueBuffer.spread(from, count, to, nulls)((i, j, isNull) =>
      val v = a(j)
      a(i) =
        if isNull then
          false
        else
          v
    )
    size = to

  def placeFromDictionary(
      dict: ValueBuffer,
      indices: Array[Int],
      n: Int,
      nulls: Array[Boolean],
      row: Int,
      to: Int
  ): Int =
    val d = dict.asInstanceOf[BooleanBuffer]
    checkIndices(indices, n, d.size)
    val dv = d.values
    val a  = values
    FixedValueBuffer.place(indices, n, nulls, row, to)((i, k, isNull) =>
      val v = dv(k)
      a(i) =
        if isNull then
          false
        else
          v
    )

  def placeRepeated(
      dict: ValueBuffer,
      index: Int,
      n: Int,
      nulls: Array[Boolean],
      row: Int,
      to: Int
  ): Int =
    val d = dict.asInstanceOf[BooleanBuffer]
    checkIndex(index, d.size)
    val v = d.values(index)
    val a = values
    FixedValueBuffer.placeEach(n, nulls, row, to)((i, isNull) =>
      a(i) =
        if isNull then
          false
        else
          v
    )

end BooleanBuffer

private[parquet] final class IntBuffer(initial: Int = 16) extends FixedValueBuffer:
  var values: Array[Int] = new Array[Int](math.max(initial, 1))
  var size: Int          = 0

  def ensure(extra: Int): Unit =
    if size + extra > values.length then
      values = java.util.Arrays.copyOf(values, ValueBuffer.grow(values.length, size + extra))

  def capacity: Int = values.length

  def reserve(n: Int): Unit =
    if n > values.length then
      values = java.util.Arrays.copyOf(values, n)

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
    checkIndices(indices, n, d.size)
    ensure(n)
    val dv = d.values
    val v  = values
    val s  = size
    var i  = 0
    while i < n do
      v(s + i) = dv(indices(i))
      i += 1
    size += n

  override def appendRepeated(dict: ValueBuffer, index: Int, n: Int, indices: Array[Int]): Unit =
    val d = dict.asInstanceOf[IntBuffer]
    checkIndex(index, d.size)
    ensure(n)
    java.util.Arrays.fill(values, size, size + n, d.values(index))
    size += n

  def appendNulls(n: Int): Unit =
    ensure(n)
    java.util.Arrays.fill(values, size, size + n, 0)
    size += n

  def spreadSlots(from: Int, count: Int, to: Int, nulls: Array[Boolean]): Unit =
    ensure(to - size)
    val a = values
    FixedValueBuffer.spread(from, count, to, nulls)((i, j, isNull) =>
      val v = a(j)
      a(i) =
        if isNull then
          0
        else
          v
    )
    size = to

  def placeFromDictionary(
      dict: ValueBuffer,
      indices: Array[Int],
      n: Int,
      nulls: Array[Boolean],
      row: Int,
      to: Int
  ): Int =
    val d = dict.asInstanceOf[IntBuffer]
    checkIndices(indices, n, d.size)
    val dv = d.values
    val a  = values
    FixedValueBuffer.place(indices, n, nulls, row, to)((i, k, isNull) =>
      val v = dv(k)
      a(i) =
        if isNull then
          0
        else
          v
    )

  def placeRepeated(
      dict: ValueBuffer,
      index: Int,
      n: Int,
      nulls: Array[Boolean],
      row: Int,
      to: Int
  ): Int =
    val d = dict.asInstanceOf[IntBuffer]
    checkIndex(index, d.size)
    val v = d.values(index)
    val a = values
    FixedValueBuffer.placeEach(n, nulls, row, to)((i, isNull) =>
      a(i) =
        if isNull then
          0
        else
          v
    )

end IntBuffer

private[parquet] final class LongBuffer(initial: Int = 16) extends FixedValueBuffer:
  var values: Array[Long] = new Array[Long](math.max(initial, 1))
  var size: Int           = 0

  def ensure(extra: Int): Unit =
    if size + extra > values.length then
      values = java.util.Arrays.copyOf(values, ValueBuffer.grow(values.length, size + extra))

  def capacity: Int = values.length

  def reserve(n: Int): Unit =
    if n > values.length then
      values = java.util.Arrays.copyOf(values, n)

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
    checkIndices(indices, n, d.size)
    ensure(n)
    val dv = d.values
    val v  = values
    val s  = size
    var i  = 0
    while i < n do
      v(s + i) = dv(indices(i))
      i += 1
    size += n

  override def appendRepeated(dict: ValueBuffer, index: Int, n: Int, indices: Array[Int]): Unit =
    val d = dict.asInstanceOf[LongBuffer]
    checkIndex(index, d.size)
    ensure(n)
    java.util.Arrays.fill(values, size, size + n, d.values(index))
    size += n

  def appendNulls(n: Int): Unit =
    ensure(n)
    java.util.Arrays.fill(values, size, size + n, 0L)
    size += n

  def spreadSlots(from: Int, count: Int, to: Int, nulls: Array[Boolean]): Unit =
    ensure(to - size)
    val a = values
    FixedValueBuffer.spread(from, count, to, nulls)((i, j, isNull) =>
      val v = a(j)
      a(i) =
        if isNull then
          0L
        else
          v
    )
    size = to

  def placeFromDictionary(
      dict: ValueBuffer,
      indices: Array[Int],
      n: Int,
      nulls: Array[Boolean],
      row: Int,
      to: Int
  ): Int =
    val d = dict.asInstanceOf[LongBuffer]
    checkIndices(indices, n, d.size)
    val dv = d.values
    val a  = values
    FixedValueBuffer.place(indices, n, nulls, row, to)((i, k, isNull) =>
      val v = dv(k)
      a(i) =
        if isNull then
          0L
        else
          v
    )

  def placeRepeated(
      dict: ValueBuffer,
      index: Int,
      n: Int,
      nulls: Array[Boolean],
      row: Int,
      to: Int
  ): Int =
    val d = dict.asInstanceOf[LongBuffer]
    checkIndex(index, d.size)
    val v = d.values(index)
    val a = values
    FixedValueBuffer.placeEach(n, nulls, row, to)((i, isNull) =>
      a(i) =
        if isNull then
          0L
        else
          v
    )

end LongBuffer

private[parquet] final class FloatBuffer(initial: Int = 16) extends FixedValueBuffer:
  var values: Array[Float] = new Array[Float](math.max(initial, 1))
  var size: Int            = 0

  def ensure(extra: Int): Unit =
    if size + extra > values.length then
      values = java.util.Arrays.copyOf(values, ValueBuffer.grow(values.length, size + extra))

  def capacity: Int = values.length

  def reserve(n: Int): Unit =
    if n > values.length then
      values = java.util.Arrays.copyOf(values, n)

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
    checkIndices(indices, n, d.size)
    ensure(n)
    val dv = d.values
    val v  = values
    val s  = size
    var i  = 0
    while i < n do
      v(s + i) = dv(indices(i))
      i += 1
    size += n

  override def appendRepeated(dict: ValueBuffer, index: Int, n: Int, indices: Array[Int]): Unit =
    val d = dict.asInstanceOf[FloatBuffer]
    checkIndex(index, d.size)
    ensure(n)
    java.util.Arrays.fill(values, size, size + n, d.values(index))
    size += n

  def appendNulls(n: Int): Unit =
    ensure(n)
    java.util.Arrays.fill(values, size, size + n, 0f)
    size += n

  def spreadSlots(from: Int, count: Int, to: Int, nulls: Array[Boolean]): Unit =
    ensure(to - size)
    val a = values
    FixedValueBuffer.spread(from, count, to, nulls)((i, j, isNull) =>
      val v = a(j)
      a(i) =
        if isNull then
          0f
        else
          v
    )
    size = to

  def placeFromDictionary(
      dict: ValueBuffer,
      indices: Array[Int],
      n: Int,
      nulls: Array[Boolean],
      row: Int,
      to: Int
  ): Int =
    val d = dict.asInstanceOf[FloatBuffer]
    checkIndices(indices, n, d.size)
    val dv = d.values
    val a  = values
    FixedValueBuffer.place(indices, n, nulls, row, to)((i, k, isNull) =>
      val v = dv(k)
      a(i) =
        if isNull then
          0f
        else
          v
    )

  def placeRepeated(
      dict: ValueBuffer,
      index: Int,
      n: Int,
      nulls: Array[Boolean],
      row: Int,
      to: Int
  ): Int =
    val d = dict.asInstanceOf[FloatBuffer]
    checkIndex(index, d.size)
    val v = d.values(index)
    val a = values
    FixedValueBuffer.placeEach(n, nulls, row, to)((i, isNull) =>
      a(i) =
        if isNull then
          0f
        else
          v
    )

end FloatBuffer

private[parquet] final class DoubleBuffer(initial: Int = 16) extends FixedValueBuffer:
  var values: Array[Double] = new Array[Double](math.max(initial, 1))
  var size: Int             = 0

  def ensure(extra: Int): Unit =
    if size + extra > values.length then
      values = java.util.Arrays.copyOf(values, ValueBuffer.grow(values.length, size + extra))

  def capacity: Int = values.length

  def reserve(n: Int): Unit =
    if n > values.length then
      values = java.util.Arrays.copyOf(values, n)

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
    checkIndices(indices, n, d.size)
    ensure(n)
    val dv = d.values
    val v  = values
    val s  = size
    var i  = 0
    while i < n do
      v(s + i) = dv(indices(i))
      i += 1
    size += n

  override def appendRepeated(dict: ValueBuffer, index: Int, n: Int, indices: Array[Int]): Unit =
    val d = dict.asInstanceOf[DoubleBuffer]
    checkIndex(index, d.size)
    ensure(n)
    java.util.Arrays.fill(values, size, size + n, d.values(index))
    size += n

  def appendNulls(n: Int): Unit =
    ensure(n)
    java.util.Arrays.fill(values, size, size + n, 0d)
    size += n

  def spreadSlots(from: Int, count: Int, to: Int, nulls: Array[Boolean]): Unit =
    ensure(to - size)
    val a = values
    FixedValueBuffer.spread(from, count, to, nulls)((i, j, isNull) =>
      val v = a(j)
      a(i) =
        if isNull then
          0d
        else
          v
    )
    size = to

  def placeFromDictionary(
      dict: ValueBuffer,
      indices: Array[Int],
      n: Int,
      nulls: Array[Boolean],
      row: Int,
      to: Int
  ): Int =
    val d = dict.asInstanceOf[DoubleBuffer]
    checkIndices(indices, n, d.size)
    val dv = d.values
    val a  = values
    FixedValueBuffer.place(indices, n, nulls, row, to)((i, k, isNull) =>
      val v = dv(k)
      a(i) =
        if isNull then
          0d
        else
          v
    )

  def placeRepeated(
      dict: ValueBuffer,
      index: Int,
      n: Int,
      nulls: Array[Boolean],
      row: Int,
      to: Int
  ): Int =
    val d = dict.asInstanceOf[DoubleBuffer]
    checkIndex(index, d.size)
    val v = d.values(index)
    val a = values
    FixedValueBuffer.placeEach(n, nulls, row, to)((i, isNull) =>
      a(i) =
        if isNull then
          0d
        else
          v
    )

end DoubleBuffer

/**
  * Values of a fixed-size type, which can also be held one slot per row: a flat nullable column's
  * values decode straight into their rows, with null rows holding the type's default value.
  */
private[parquet] sealed abstract class FixedValueBuffer extends ValueBuffer:
  def size_=(n: Int): Unit

  /** Grows the buffer, if needed, to hold `extra` more values */
  def ensure(extra: Int): Unit

  /** Appends `n` default values, for null rows */
  def appendNulls(n: Int): Unit

  /**
    * Moves the `count` values at [from, from + count) out to the rows [from, to) that `nulls` does
    * not flag, in place from the last row back, and puts the default value in the flagged rows. The
    * buffer then holds `to` values.
    */
  def spreadSlots(from: Int, count: Int, to: Int, nulls: Array[Boolean]): Unit

  /**
    * Puts `dict(indices(k))` for k in [0, n) in the rows from `row` on that `nulls` does not flag,
    * in order, and the default value in the flagged rows among them. Returns the row after the one
    * that took the last value. Rows must not pass `to`, and the buffer must have room for them; the
    * size is left to the caller.
    */
  def placeFromDictionary(
      dict: ValueBuffer,
      indices: Array[Int],
      n: Int,
      nulls: Array[Boolean],
      row: Int,
      to: Int
  ): Int

  /** As [[placeFromDictionary]] for `n` values that are all `dict(index)`, an RLE run */
  def placeRepeated(
      dict: ValueBuffer,
      index: Int,
      n: Int,
      nulls: Array[Boolean],
      row: Int,
      to: Int
  ): Int

end FixedValueBuffer

private[parquet] object FixedValueBuffer:

  /**
    * The loop of [[FixedValueBuffer.spreadSlots]]: calls `move(i, j, isNull)` for each row i from
    * `to - 1` down to `from`, where j is the value for row i when it is not null. `move` selects
    * the value or the default without branching on the flag, so scattered nulls cost no
    * mispredictions; j stays in range for null rows too.
    */
  inline def spread(from: Int, count: Int, to: Int, nulls: Array[Boolean])(
      inline move: (Int, Int, Boolean) => Unit
  ): Unit =
    var j = from + count - 1
    var i = to - 1
    while i >= from do
      val isNull = nulls(i)
      move(i, math.max(j, from), isNull)
      j -= (
        if isNull then
          0
        else
          1
      )
      i -= 1
    if j != from - 1 then
      throw ParquetException.corrupt(
        s"${count} values do not match the ${to - from} rows' definition levels"
      )

  /**
    * Calls `put(i, isNull)` for each row i from `row` until `n` rows not flagged by `nulls` are
    * passed. Returns the row after the last of them.
    */
  inline def placeEach(n: Int, nulls: Array[Boolean], row: Int, to: Int)(
      inline put: (Int, Boolean) => Unit
  ): Int =
    var i = row
    var j = 0
    while j < n do
      if i >= to then
        throw ParquetException.corrupt(
          s"${n} values do not fit the rows their definition levels allow"
        )
      val isNull = nulls(i)
      put(i, isNull)
      j += (
        if isNull then
          0
        else
          1
      )
      i += 1
    i

  /**
    * The loop of [[FixedValueBuffer.placeFromDictionary]]: calls `put(i, index, isNull)` for each
    * row i from `row` until `n` values are placed, where `index` is the dictionary index of row i's
    * value when it is not null.
    */
  inline def place(indices: Array[Int], n: Int, nulls: Array[Boolean], row: Int, to: Int)(
      inline put: (Int, Int, Boolean) => Unit
  ): Int =
    var i = row
    var j = 0
    while j < n do
      if i >= to then
        throw ParquetException.corrupt(
          s"${n} values do not fit the rows their definition levels allow"
        )
      val isNull = nulls(i)
      put(i, indices(j), isNull)
      j += (
        if isNull then
          0
        else
          1
      )
      i += 1
    i

end FixedValueBuffer

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
  var dict: BinaryBuffer  = null
  var dictIdx: Array[Int] = null
  var dictCount: Int      = 0
  private var noDict      = false

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

  def capacity: Int = offsets.length - 1

  def reserve(n: Int): Unit =
    if n + 1 > offsets.length then
      offsets = java.util.Arrays.copyOf(offsets, n + 1)

  /** Grows the data array, if needed, to hold `bytes` bytes in all without growing again */
  def reserveData(bytes: Int): Unit =
    if bytes > data.length then
      data = java.util.Arrays.copyOf(data, bytes)

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
          dictIdx = java
            .util
            .Arrays
            .copyOf(
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
