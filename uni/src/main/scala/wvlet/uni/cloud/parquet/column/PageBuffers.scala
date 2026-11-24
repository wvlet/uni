package wvlet.uni.cloud.parquet.column

/**
  * Arrays kept per thread, so that the next column chunk read on a thread reuses the arrays of the
  * chunks read before it instead of allocating (and zero-filling) new ones.
  *
  * An array belongs to its borrower from [[borrow]] until [[release]]. One that is never released
  * is freed by the GC. A thread's pool keeps at most `maxPooled` arrays of `maxPooledBytes` bytes
  * in all, and no array larger than `maxArrayBytes`, so an idle thread holds little memory.
  */
private[parquet] final class ArrayPool[A <: AnyRef](
    maxPooled: Int,
    maxPooledBytes: Long,
    maxArrayBytes: Long,
    bytesPerElement: Int,
    newArray: Int => A,
    lengthOf: A => Int
):
  private final class Pool:
    val arrays = new Array[AnyRef](maxPooled)
    var count  = 0
    var bytes  = 0L

    def at(i: Int): A = arrays(i).asInstanceOf[A]

    /** Removes array i, moving the last one into its slot */
    def drop(i: Int): A =
      val a = at(i)
      count -= 1
      arrays(i) = arrays(count)
      arrays(count) = null
      bytes -= sizeOf(a)
      a

    /** The index of the smallest array, or -1 when empty */
    def smallest: Int =
      var s = -1
      var i = 0
      while i < count do
        if s < 0 || lengthOf(at(i)) < lengthOf(at(s)) then
          s = i
        i += 1
      s

  private def sizeOf(a: A): Long = lengthOf(a).toLong * bytesPerElement

  private val pools =
    new ThreadLocal[Pool]:
      override def initialValue(): Pool = Pool()

  /**
    * An array of at least `size` elements: the smallest pooled one that fits, else a new one. A
    * pooled array holds what its last borrower left in it.
    */
  def borrow(size: Int): A =
    val pool = pools.get()
    var best = -1
    var i    = 0
    while i < pool.count do
      val n = lengthOf(pool.at(i))
      if n >= size && (best < 0 || n < lengthOf(pool.at(best))) then
        best = i
      i += 1
    if best >= 0 then
      pool.drop(best)
    else
      newArray(size)

  /** Returns an array from [[borrow]] (or null), which the caller must not use afterwards */
  def release(a: A): Unit =
    if a != null && sizeOf(a) <= maxArrayBytes then
      val pool = pools.get()
      val size = sizeOf(a)
      // Make room by dropping smaller arrays, which the next chunks are less likely to need
      var keep = true
      while keep && (pool.count >= maxPooled || pool.bytes + size > maxPooledBytes) do
        val s = pool.smallest
        if s < 0 || lengthOf(pool.at(s)) >= lengthOf(a) then
          keep = false
        else
          pool.drop(s)
      if keep then
        pool.arrays(pool.count) = a
        pool.count += 1
        pool.bytes += size

end ArrayPool

/**
  * Buffers for decompressed pages. A buffer belongs to one [[ColumnChunkCursor]] from [[borrow]]
  * until [[release]]. The cursor releases it once no decoded page still points into it: every
  * decoder copies values out of the page. Reading a row group otherwise allocates a new array for
  * every page of every column.
  */
private[parquet] object PageBuffers:
  final val MaxPooled      = 64
  final val MaxPooledBytes = 16L * 1024 * 1024
  final val MaxBufferBytes = 4 * 1024 * 1024

  private val pool = ArrayPool[Array[Byte]](
    MaxPooled,
    MaxPooledBytes,
    MaxBufferBytes,
    1,
    new Array[Byte](_),
    _.length
  )

  /** A buffer of at least `size` bytes: the smallest pooled one that fits, else a new one */
  def borrow(size: Int): Array[Byte] = pool.borrow(size)

  /** Returns a buffer from [[borrow]], which the caller must not use afterwards */
  def release(buf: Array[Byte]): Unit = pool.release(buf)

/**
  * Arrays that a [[ColumnChunkCursor]] decodes levels into: definition and repetition levels, and
  * the positions of a page's nulls. Assembly reads the levels of a batch and keeps none of them, so
  * [[RowGroupBatches]] releases them once the batch's vectors are built.
  */
private[parquet] object LevelBuffers:
  final val MaxPooled      = 256
  final val MaxPooledBytes = 16L * 1024 * 1024
  final val MaxArrayBytes  = 4 * 1024 * 1024

  val bytes: ArrayPool[Array[Byte]] = ArrayPool[Array[Byte]](
    MaxPooled,
    MaxPooledBytes,
    MaxArrayBytes,
    1,
    new Array[Byte](_),
    _.length
  )

  val ints: ArrayPool[Array[Int]] = ArrayPool[Array[Int]](
    MaxPooled,
    MaxPooledBytes,
    MaxArrayBytes,
    4,
    new Array[Int](_),
    _.length
  )

end LevelBuffers
