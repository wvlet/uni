package wvlet.uni.cloud.parquet.column

import wvlet.uni.cloud.parquet.ParquetException
import wvlet.uni.cloud.parquet.encoding.RleBitPackedDecoder
import wvlet.uni.io.codec.WideCopy

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

  /**
    * Moves the values [start, start + n - m), the non-null values of `n` rows, to their row slots
    * [start, start + n). `nulls(0)` to `nulls(m - 1)` are the null rows, in ascending order; they
    * get the type's zero (an empty value for binary). Afterwards the buffer holds start + n values.
    */
  def spreadToRows(start: Int, n: Int, nulls: Array[Int], m: Int): Unit

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

  /**
    * Appends `dict(index)` for the next `n` dictionary indices of `dec`: an RLE run is looked up
    * once, bit-packed indices a block of `indices` at a time. Fixed-size buffers override this with
    * one typed loop per page, since writers such as DuckDB emit RLE runs of a few values each (rows
    * of one order share a date or store), where a virtual call per run costs more than its values.
    */
  def appendDictionaryRuns(
      dec: RleBitPackedDecoder,
      dict: ValueBuffer,
      n: Int,
      indices: Array[Int]
  ): Unit =
    dec.foreachIndexRun(n, indices)(
      (index, k) => appendRepeated(dict, index, k, indices),
      k => appendFromDictionary(dict, indices, k)
    )

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

  // Row slots (see beginSlots): the null rows of the page being decoded, in ascending order, the
  // next one not yet passed, and the values written so far. slotNulls is null outside slot mode.
  protected var slotNulls: Array[Int] = null
  protected var slotCount             = 0
  protected var slotNext              = 0
  private var slotValues              = 0

  /** Whether appends can write straight to row slots (see [[beginSlots]]) */
  def writesSlots: Boolean = false

  /** Whether the buffer is in slot mode */
  final def inSlots: Boolean = slotNulls != null

  /**
    * Starts slot mode for a page: until [[endSlots]], appends write each value to its row slot,
    * passing the rows listed in `nulls(0)` to `nulls(m - 1)` (ascending, at or after `size`), which
    * get the type's zero. This saves moving the values out to their rows afterwards. Only buffers
    * whose [[writesSlots]] is true support it.
    */
  final def beginSlots(nulls: Array[Int], m: Int): Unit =
    slotNulls = nulls
    slotCount = m
    slotNext = 0
    slotValues = 0

  /**
    * Ends slot mode: clears the null rows left after the last value, so the buffer holds `rows`
    * values, and returns the number of values written since [[beginSlots]].
    */
  final def endSlots(rows: Int): Int =
    while slotNext < slotCount do
      clearSlot(slotNulls(slotNext))
      slotNext += 1
    slotNulls = null
    setSize(rows)
    slotValues

  protected def clearSlot(row: Int): Unit = ()
  protected def setSize(n: Int): Unit     = ()

  /**
    * Writes `k` values from row `row` on, run by run between null rows: `write(dst, i, c)` writes
    * values i to i + c - 1 to rows dst to dst + c - 1, and each null row passed is cleared. Returns
    * the row after the last value. The work is per run, so random nulls cost no branch per value.
    */
  protected final inline def writeRuns(row: Int, k: Int)(
      inline write: (Int, Int, Int) => Unit
  ): Int =
    var r  = row
    var i  = 0
    var t  = slotNext
    var np =
      if t < slotCount then
        slotNulls(t)
      else
        Int.MaxValue
    while i < k do
      if r == np then
        clearSlot(r)
        r += 1
        t += 1
        np =
          if t < slotCount then
            slotNulls(t)
          else
            Int.MaxValue
      else
        val c = math.min(np - r, k - i)
        write(r, i, c)
        r += c
        i += c
    slotNext = t
    slotValues += k
    r

end ValueBuffer

private[parquet] object ValueBuffer:
  // The largest array every platform allocates; the JVM refuses lengths close to Int.MaxValue
  final val MaxArrayLength = Int.MaxValue - 8

  /**
    * Calls `shift(from, until, k)` to move the values of rows [from, until) down by `k` slots, for
    * each run of rows between nulls, and `clear(row, k)` for each null, `k` counting it and the
    * nulls below, from the last row back, so a value never lands on one not yet moved. Rows below
    * the first null stay in place. The work is per run, not per row, so random nulls cost no branch
    * mispredictions on the rows with values.
    */
  private[column] inline def spreadRuns(start: Int, n: Int, nulls: Array[Int], m: Int)(
      inline shift: (Int, Int, Int) => Unit,
      inline clear: (Int, Int) => Unit
  ): Unit =
    var end = start + n
    var t   = m - 1
    while t >= 0 do
      val p = nulls(t)
      shift(p + 1, end, t + 1)
      clear(p, t + 1)
      end = p
      t -= 1

  /**
    * Runs shorter than this move or fill in a loop: System.arraycopy and Arrays.fill have a fixed
    * cost per call
    */
  private[column] final val ShortRun = 8

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

end ValueBuffer

private[parquet] final class BooleanBuffer(initial: Int = 16) extends ValueBuffer:
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

  def spreadToRows(start: Int, n: Int, nulls: Array[Int], m: Int): Unit =
    if start + n > values.length then
      values = java.util.Arrays.copyOf(values, ValueBuffer.grow(values.length, start + n))
    val v = values
    ValueBuffer.spreadRuns(start, n, nulls, m)(
      (from, until, k) =>
        if until - from < ValueBuffer.ShortRun then
          var r = until - 1
          while r >= from do
            v(r) = v(r - k)
            r -= 1
        else
          System.arraycopy(v, from - k, v, from, until - from)
      ,
      (r, _) => v(r) = false
    )
    size = start + n

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

end BooleanBuffer

private[parquet] final class IntBuffer(initial: Int = 16) extends ValueBuffer:
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

  def spreadToRows(start: Int, n: Int, nulls: Array[Int], m: Int): Unit =
    if start + n > values.length then
      values = java.util.Arrays.copyOf(values, ValueBuffer.grow(values.length, start + n))
    val v = values
    ValueBuffer.spreadRuns(start, n, nulls, m)(
      (from, until, k) =>
        if until - from < ValueBuffer.ShortRun then
          var r = until - 1
          while r >= from do
            v(r) = v(r - k)
            r -= 1
        else
          System.arraycopy(v, from - k, v, from, until - from)
      ,
      (r, _) => v(r) = 0
    )
    size = start + n

  override def writesSlots: Boolean = true

  override protected def clearSlot(row: Int): Unit = values(row) = 0
  override protected def setSize(n: Int): Unit     = size = n

  /** Writes `n` values to row slots in slot mode, calling `copy(dst, i, c)` for each run */
  private inline def appendRuns(n: Int)(inline copy: (Int, Int, Int) => Unit): Unit =
    // Through a local: `size = writeRuns(...)` would hold `this` on the stack over the inlined
    // loops, and the JIT can't compile a long page loop there on stack replacement
    val row = writeRuns(size, n)(copy)
    size = row

  def appendFromDictionary(dict: ValueBuffer, indices: Array[Int], n: Int): Unit =
    val d = dict.asInstanceOf[IntBuffer]
    checkIndices(indices, n, d.size)
    ensure(n)
    val dv = d.values
    val v  = values
    if slotNulls != null then
      appendRuns(n) { (dst, i, c) =>
        var q = 0
        while q < c do
          v(dst + q) = dv(indices(i + q))
          q += 1
      }
    else
      val s = size
      var i = 0
      while i < n do
        v(s + i) = dv(indices(i))
        i += 1
      size += n

  override def appendRepeated(dict: ValueBuffer, index: Int, n: Int, indices: Array[Int]): Unit =
    val d = dict.asInstanceOf[IntBuffer]
    checkIndex(index, d.size)
    ensure(n)
    val x = d.values(index)
    if slotNulls != null then
      val v = values
      appendRuns(n) { (dst, _, c) =>
        if c < ValueBuffer.ShortRun then
          var q = dst
          while q < dst + c do
            v(q) = x
            q += 1
        else
          java.util.Arrays.fill(v, dst, dst + c, x)
      }
    else
      java.util.Arrays.fill(values, size, size + n, x)
      size += n

  /** One typed loop per page: a run costs no virtual call or capacity check */
  override def appendDictionaryRuns(
      dec: RleBitPackedDecoder,
      dict: ValueBuffer,
      n: Int,
      indices: Array[Int]
  ): Unit =
    val d = dict.asInstanceOf[IntBuffer]
    ensure(n)
    // Separate methods, so the JIT profiles and compiles each loop on its own
    if slotNulls != null then
      appendDictionarySlots(dec, d, n, indices)
    else
      appendDictionaryDense(dec, d, n, indices)

  // The run loops fill with a loop, not Arrays.fill: they are too large for the JIT to inline it

  private def appendDictionarySlots(
      dec: RleBitPackedDecoder,
      d: IntBuffer,
      n: Int,
      indices: Array[Int]
  ): Unit =
    val dv = d.values
    val ds = d.size
    val v  = values
    dec.foreachIndexRun(n, indices)(
      (index, k) =>
        checkIndex(index, ds)
        val x = dv(index)
        appendRuns(k) { (dst, _, c) =>
          var q = dst
          while q < dst + c do
            v(q) = x
            q += 1
        }
      ,
      k => appendFromDictionary(d, indices, k)
    )

  private def appendDictionaryDense(
      dec: RleBitPackedDecoder,
      d: IntBuffer,
      n: Int,
      indices: Array[Int]
  ): Unit =
    val dv = d.values
    val ds = d.size
    val v  = values
    var s  = size
    dec.foreachIndexRun(n, indices)(
      (index, k) =>
        checkIndex(index, ds)
        val x = dv(index)
        var q = s
        while q < s + k do
          v(q) = x
          q += 1
        s += k
      ,
      k =>
        size = s
        appendFromDictionary(d, indices, k)
        s += k
    )
    size = s

end IntBuffer

private[parquet] final class LongBuffer(initial: Int = 16) extends ValueBuffer:
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

  def spreadToRows(start: Int, n: Int, nulls: Array[Int], m: Int): Unit =
    if start + n > values.length then
      values = java.util.Arrays.copyOf(values, ValueBuffer.grow(values.length, start + n))
    val v = values
    ValueBuffer.spreadRuns(start, n, nulls, m)(
      (from, until, k) =>
        if until - from < ValueBuffer.ShortRun then
          var r = until - 1
          while r >= from do
            v(r) = v(r - k)
            r -= 1
        else
          System.arraycopy(v, from - k, v, from, until - from)
      ,
      (r, _) => v(r) = 0L
    )
    size = start + n

  override def writesSlots: Boolean = true

  override protected def clearSlot(row: Int): Unit = values(row) = 0L
  override protected def setSize(n: Int): Unit     = size = n

  /** Writes `n` values to row slots in slot mode, calling `copy(dst, i, c)` for each run */
  private inline def appendRuns(n: Int)(inline copy: (Int, Int, Int) => Unit): Unit =
    // Through a local: `size = writeRuns(...)` would hold `this` on the stack over the inlined
    // loops, and the JIT can't compile a long page loop there on stack replacement
    val row = writeRuns(size, n)(copy)
    size = row

  def appendFromDictionary(dict: ValueBuffer, indices: Array[Int], n: Int): Unit =
    val d = dict.asInstanceOf[LongBuffer]
    checkIndices(indices, n, d.size)
    ensure(n)
    val dv = d.values
    val v  = values
    if slotNulls != null then
      appendRuns(n) { (dst, i, c) =>
        var q = 0
        while q < c do
          v(dst + q) = dv(indices(i + q))
          q += 1
      }
    else
      val s = size
      var i = 0
      while i < n do
        v(s + i) = dv(indices(i))
        i += 1
      size += n

  override def appendRepeated(dict: ValueBuffer, index: Int, n: Int, indices: Array[Int]): Unit =
    val d = dict.asInstanceOf[LongBuffer]
    checkIndex(index, d.size)
    ensure(n)
    val x = d.values(index)
    if slotNulls != null then
      val v = values
      appendRuns(n) { (dst, _, c) =>
        if c < ValueBuffer.ShortRun then
          var q = dst
          while q < dst + c do
            v(q) = x
            q += 1
        else
          java.util.Arrays.fill(v, dst, dst + c, x)
      }
    else
      java.util.Arrays.fill(values, size, size + n, x)
      size += n

  /** One typed loop per page: a run costs no virtual call or capacity check */
  override def appendDictionaryRuns(
      dec: RleBitPackedDecoder,
      dict: ValueBuffer,
      n: Int,
      indices: Array[Int]
  ): Unit =
    val d = dict.asInstanceOf[LongBuffer]
    ensure(n)
    // Separate methods, so the JIT profiles and compiles each loop on its own
    if slotNulls != null then
      appendDictionarySlots(dec, d, n, indices)
    else
      appendDictionaryDense(dec, d, n, indices)

  // The run loops fill with a loop, not Arrays.fill: they are too large for the JIT to inline it

  private def appendDictionarySlots(
      dec: RleBitPackedDecoder,
      d: LongBuffer,
      n: Int,
      indices: Array[Int]
  ): Unit =
    val dv = d.values
    val ds = d.size
    val v  = values
    dec.foreachIndexRun(n, indices)(
      (index, k) =>
        checkIndex(index, ds)
        val x = dv(index)
        appendRuns(k) { (dst, _, c) =>
          var q = dst
          while q < dst + c do
            v(q) = x
            q += 1
        }
      ,
      k => appendFromDictionary(d, indices, k)
    )

  private def appendDictionaryDense(
      dec: RleBitPackedDecoder,
      d: LongBuffer,
      n: Int,
      indices: Array[Int]
  ): Unit =
    val dv = d.values
    val ds = d.size
    val v  = values
    var s  = size
    dec.foreachIndexRun(n, indices)(
      (index, k) =>
        checkIndex(index, ds)
        val x = dv(index)
        var q = s
        while q < s + k do
          v(q) = x
          q += 1
        s += k
      ,
      k =>
        size = s
        appendFromDictionary(d, indices, k)
        s += k
    )
    size = s

end LongBuffer

private[parquet] final class FloatBuffer(initial: Int = 16) extends ValueBuffer:
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

  def spreadToRows(start: Int, n: Int, nulls: Array[Int], m: Int): Unit =
    if start + n > values.length then
      values = java.util.Arrays.copyOf(values, ValueBuffer.grow(values.length, start + n))
    val v = values
    ValueBuffer.spreadRuns(start, n, nulls, m)(
      (from, until, k) =>
        if until - from < ValueBuffer.ShortRun then
          var r = until - 1
          while r >= from do
            v(r) = v(r - k)
            r -= 1
        else
          System.arraycopy(v, from - k, v, from, until - from)
      ,
      (r, _) => v(r) = 0f
    )
    size = start + n

  override def writesSlots: Boolean = true

  override protected def clearSlot(row: Int): Unit = values(row) = 0f
  override protected def setSize(n: Int): Unit     = size = n

  /** Writes `n` values to row slots in slot mode, calling `copy(dst, i, c)` for each run */
  private inline def appendRuns(n: Int)(inline copy: (Int, Int, Int) => Unit): Unit =
    // Through a local: `size = writeRuns(...)` would hold `this` on the stack over the inlined
    // loops, and the JIT can't compile a long page loop there on stack replacement
    val row = writeRuns(size, n)(copy)
    size = row

  def appendFromDictionary(dict: ValueBuffer, indices: Array[Int], n: Int): Unit =
    val d = dict.asInstanceOf[FloatBuffer]
    checkIndices(indices, n, d.size)
    ensure(n)
    val dv = d.values
    val v  = values
    if slotNulls != null then
      appendRuns(n) { (dst, i, c) =>
        var q = 0
        while q < c do
          v(dst + q) = dv(indices(i + q))
          q += 1
      }
    else
      val s = size
      var i = 0
      while i < n do
        v(s + i) = dv(indices(i))
        i += 1
      size += n

  override def appendRepeated(dict: ValueBuffer, index: Int, n: Int, indices: Array[Int]): Unit =
    val d = dict.asInstanceOf[FloatBuffer]
    checkIndex(index, d.size)
    ensure(n)
    val x = d.values(index)
    if slotNulls != null then
      val v = values
      appendRuns(n) { (dst, _, c) =>
        if c < ValueBuffer.ShortRun then
          var q = dst
          while q < dst + c do
            v(q) = x
            q += 1
        else
          java.util.Arrays.fill(v, dst, dst + c, x)
      }
    else
      java.util.Arrays.fill(values, size, size + n, x)
      size += n

  /** One typed loop per page: a run costs no virtual call or capacity check */
  override def appendDictionaryRuns(
      dec: RleBitPackedDecoder,
      dict: ValueBuffer,
      n: Int,
      indices: Array[Int]
  ): Unit =
    val d = dict.asInstanceOf[FloatBuffer]
    ensure(n)
    // Separate methods, so the JIT profiles and compiles each loop on its own
    if slotNulls != null then
      appendDictionarySlots(dec, d, n, indices)
    else
      appendDictionaryDense(dec, d, n, indices)

  // The run loops fill with a loop, not Arrays.fill: they are too large for the JIT to inline it

  private def appendDictionarySlots(
      dec: RleBitPackedDecoder,
      d: FloatBuffer,
      n: Int,
      indices: Array[Int]
  ): Unit =
    val dv = d.values
    val ds = d.size
    val v  = values
    dec.foreachIndexRun(n, indices)(
      (index, k) =>
        checkIndex(index, ds)
        val x = dv(index)
        appendRuns(k) { (dst, _, c) =>
          var q = dst
          while q < dst + c do
            v(q) = x
            q += 1
        }
      ,
      k => appendFromDictionary(d, indices, k)
    )

  private def appendDictionaryDense(
      dec: RleBitPackedDecoder,
      d: FloatBuffer,
      n: Int,
      indices: Array[Int]
  ): Unit =
    val dv = d.values
    val ds = d.size
    val v  = values
    var s  = size
    dec.foreachIndexRun(n, indices)(
      (index, k) =>
        checkIndex(index, ds)
        val x = dv(index)
        var q = s
        while q < s + k do
          v(q) = x
          q += 1
        s += k
      ,
      k =>
        size = s
        appendFromDictionary(d, indices, k)
        s += k
    )
    size = s

end FloatBuffer

private[parquet] final class DoubleBuffer(initial: Int = 16) extends ValueBuffer:
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

  def spreadToRows(start: Int, n: Int, nulls: Array[Int], m: Int): Unit =
    if start + n > values.length then
      values = java.util.Arrays.copyOf(values, ValueBuffer.grow(values.length, start + n))
    val v = values
    ValueBuffer.spreadRuns(start, n, nulls, m)(
      (from, until, k) =>
        if until - from < ValueBuffer.ShortRun then
          var r = until - 1
          while r >= from do
            v(r) = v(r - k)
            r -= 1
        else
          System.arraycopy(v, from - k, v, from, until - from)
      ,
      (r, _) => v(r) = 0d
    )
    size = start + n

  override def writesSlots: Boolean = true

  override protected def clearSlot(row: Int): Unit = values(row) = 0d
  override protected def setSize(n: Int): Unit     = size = n

  /** Writes `n` values to row slots in slot mode, calling `copy(dst, i, c)` for each run */
  private inline def appendRuns(n: Int)(inline copy: (Int, Int, Int) => Unit): Unit =
    // Through a local: `size = writeRuns(...)` would hold `this` on the stack over the inlined
    // loops, and the JIT can't compile a long page loop there on stack replacement
    val row = writeRuns(size, n)(copy)
    size = row

  def appendFromDictionary(dict: ValueBuffer, indices: Array[Int], n: Int): Unit =
    val d = dict.asInstanceOf[DoubleBuffer]
    checkIndices(indices, n, d.size)
    ensure(n)
    val dv = d.values
    val v  = values
    if slotNulls != null then
      appendRuns(n) { (dst, i, c) =>
        var q = 0
        while q < c do
          v(dst + q) = dv(indices(i + q))
          q += 1
      }
    else
      val s = size
      var i = 0
      while i < n do
        v(s + i) = dv(indices(i))
        i += 1
      size += n

  override def appendRepeated(dict: ValueBuffer, index: Int, n: Int, indices: Array[Int]): Unit =
    val d = dict.asInstanceOf[DoubleBuffer]
    checkIndex(index, d.size)
    ensure(n)
    val x = d.values(index)
    if slotNulls != null then
      val v = values
      appendRuns(n) { (dst, _, c) =>
        if c < ValueBuffer.ShortRun then
          var q = dst
          while q < dst + c do
            v(q) = x
            q += 1
        else
          java.util.Arrays.fill(v, dst, dst + c, x)
      }
    else
      java.util.Arrays.fill(values, size, size + n, x)
      size += n

  /** One typed loop per page: a run costs no virtual call or capacity check */
  override def appendDictionaryRuns(
      dec: RleBitPackedDecoder,
      dict: ValueBuffer,
      n: Int,
      indices: Array[Int]
  ): Unit =
    val d = dict.asInstanceOf[DoubleBuffer]
    ensure(n)
    // Separate methods, so the JIT profiles and compiles each loop on its own
    if slotNulls != null then
      appendDictionarySlots(dec, d, n, indices)
    else
      appendDictionaryDense(dec, d, n, indices)

  // The run loops fill with a loop, not Arrays.fill: they are too large for the JIT to inline it

  private def appendDictionarySlots(
      dec: RleBitPackedDecoder,
      d: DoubleBuffer,
      n: Int,
      indices: Array[Int]
  ): Unit =
    val dv = d.values
    val ds = d.size
    val v  = values
    dec.foreachIndexRun(n, indices)(
      (index, k) =>
        checkIndex(index, ds)
        val x = dv(index)
        appendRuns(k) { (dst, _, c) =>
          var q = dst
          while q < dst + c do
            v(q) = x
            q += 1
        }
      ,
      k => appendFromDictionary(d, indices, k)
    )

  private def appendDictionaryDense(
      dec: RleBitPackedDecoder,
      d: DoubleBuffer,
      n: Int,
      indices: Array[Int]
  ): Unit =
    val dv = d.values
    val ds = d.size
    val v  = values
    var s  = size
    dec.foreachIndexRun(n, indices)(
      (index, k) =>
        checkIndex(index, ds)
        val x = dv(index)
        var q = s
        while q < s + k do
          v(q) = x
          q += 1
        s += k
      ,
      k =>
        size = s
        appendFromDictionary(d, indices, k)
        s += k
    )
    size = s

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
  var dict: BinaryBuffer  = null
  var dictIdx: Array[Int] = null
  var dictCount: Int      = 0
  private var noDict      = false

  // Values from `dict` keep only their entry, as empty values here, without copying its bytes
  // (ParquetReadOptions.dictionaryCodesOnly). Values that lose their entry get their bytes back
  var codesOnly: Boolean = false

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
    // Values are short in most columns, where System.arraycopy's fixed cost per call dominates. A
    // wide copy may write past the value, into data no value holds yet
    WideCopy.literal(src, off, data, start, len, data.length)
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
    b.codesOnly = codesOnly
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
    if n < dictCount then
      if dictIdx != null then
        System.arraycopy(dictIdx, n, dictIdx, 0, dictCount - n)
      dictCount -= n
    else
      dictCount = 0

  def spreadToRows(start: Int, n: Int, nulls: Array[Int], m: Int): Unit =
    if start + n + 1 > offsets.length then
      offsets = java.util.Arrays.copyOf(offsets, ValueBuffer.grow(offsets.length, start + n + 1))
    // The dictionary entries cover these rows when they covered every value so far (also when
    // there is no dictionary yet, as after pages of nulls only): null rows get -1
    val withIndices = !noDict && dictCount == start + n - m
    if withIndices && dictIdx != null && dictIdx.length < start + n then
      dictIdx = java.util.Arrays.copyOf(dictIdx, math.max(start + n, capacity))
    val idx =
      if withIndices then
        dictIdx
      else
        null
    // Row r ends where value r - k ends, k being the nulls at or below r, for a null row (empty,
    // so it ends where the value before it ends) and a non-null one alike
    val o = offsets
    ValueBuffer.spreadRuns(start, n, nulls, m)(
      (from, until, k) =>
        var r = until - 1
        while r >= from do
          o(r + 1) = o(r + 1 - k)
          r -= 1
        if idx != null then
          r = until - 1
          while r >= from do
            idx(r) = idx(r - k)
            r -= 1
      ,
      (r, k) =>
        o(r + 1) = o(r + 1 - k)
        if idx != null then
          idx(r) = -1
    )
    size = start + n
    if withIndices then
      dictCount = size

  end spreadToRows

  def bytes(i: Int): Array[Byte] = java.util.Arrays.copyOfRange(data, offsets(i), offsets(i + 1))

  def appendFromDictionary(dict: ValueBuffer, indices: Array[Int], n: Int): Unit =
    val d = dict.asInstanceOf[BinaryBuffer]
    checkIndices(indices, n, d.size)
    ensureValues(n)
    recordIndices(d, indices, n)
    if codesOnly && (this.dict eq d) then
      // The entries stand for the values: each is an empty value here
      java.util.Arrays.fill(offsets, size + 1, size + n + 1, dataSize)
      size += n
    else
      copyFromDictionary(d, indices, n)

  end appendFromDictionary

  private def copyFromDictionary(d: BinaryBuffer, indices: Array[Int], n: Int): Unit =
    var bytes = 0L
    var i     = 0
    while i < n do
      bytes += d.length(indices(i))
      i += 1
    if dataSize + bytes > data.length && n > 0 then
      // Grow once for the values the cursor reserved, at this block's bytes per value, instead of
      // doubling block by block: each doubling zero-fills a new array and copies the old one
      val rest = math.max(0, capacity - size - n)
      val want = dataSize + bytes + bytes.toDouble / n * rest
      reserveData(math.min(want, ValueBuffer.MaxArrayLength.toDouble).toInt)
    ensureData(math.min(bytes, Int.MaxValue.toLong).toInt)
    // The values and their bytes fit now, so the loop needs no capacity checks
    val src = d.data
    val so  = d.offsets
    val out = data
    var pos = dataSize
    var v   = size
    i = 0
    while i < n do
      val k     = indices(i)
      val start = so(k)
      val len   = so(k + 1) - start
      WideCopy.literal(src, start, out, pos, len, out.length)
      pos += len
      v += 1
      offsets(v) = pos
      i += 1
    size = v

  end copyFromDictionary

  /**
    * Copies the bytes of the values [0, dictCount) kept as dictionary entries only (`codesOnly`)
    * into the data, before the entries are dropped
    */
  private def materializeEntries(): Unit =
    if codesOnly && dict != null && dictCount > 0 then
      val d     = dict
      var total = 0L
      var v     = 0
      while v < size do
        total += valueLength(v)
        v += 1
      if total > ValueBuffer.MaxArrayLength then
        throw ParquetException.unsupported(s"a binary column of ${total} bytes in one batch")
      val out  = new Array[Byte](math.max(total.toInt, 1))
      val outO = new Array[Int](offsets.length)
      var pos  = 0
      v = 0
      while v < size do
        val k = dictIndex(v)
        if k >= 0 then
          val len = d.length(k)
          System.arraycopy(d.data, d.offsets(k), out, pos, len)
          pos += len
        else
          val len = length(v)
          System.arraycopy(data, offsets(v), out, pos, len)
          pos += len
        v += 1
        outO(v) = pos
      data = out
      offsets = outO
    codesOnly = false

  // The length of value v, read from its dictionary entry when only the entry is kept
  private def valueLength(v: Int): Int =
    val k = dictIndex(v)
    if codesOnly && k >= 0 then
      dict.length(k)
    else
      length(v)

  private def recordIndices(d: BinaryBuffer, indices: Array[Int], n: Int): Unit =
    if !noDict then
      if dictCount != size || (dict != null && (dict ne d)) then
        materializeEntries()
        noDict = true
        dict = null
      else
        dict = d
        if dictIdx == null || dictIdx.length < size + n then
          // Sized like the offsets, which the cursor reserves for the batch, so the indices grow
          // with them instead of doubling on their own, and the vector can take the array
          val next = new Array[Int](math.max(size + n, capacity))
          if dictIdx != null then
            System.arraycopy(dictIdx, 0, next, 0, size)
          else
            // Rows before the first dictionary page can only be nulls, which have no entry
            java.util.Arrays.fill(next, 0, size, -1)
          dictIdx = next
        System.arraycopy(indices, 0, dictIdx, size, n)
        dictCount += n

end BinaryBuffer
