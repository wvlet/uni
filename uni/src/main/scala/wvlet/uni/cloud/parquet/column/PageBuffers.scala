package wvlet.uni.cloud.parquet.column

/**
  * Buffers for decompressed pages, kept per thread so that the next column chunk read on a thread
  * reuses the buffers of the chunks read before it. Reading a row group otherwise allocates a new
  * array for every page of every column.
  *
  * A buffer belongs to one [[ColumnChunkCursor]] from [[borrow]] until [[release]]. The cursor
  * releases it once no decoded page still points into it: every decoder copies values out of the
  * page. A cursor that is dropped before its chunk ends never releases its buffer, which the GC
  * then frees. The pool keeps at most [[MaxPooled]] buffers of [[MaxPooledBytes]] bytes in all, and
  * no buffer larger than [[MaxBufferBytes]], so an idle thread holds little memory.
  */
private[parquet] object PageBuffers:
  final val MaxPooled      = 64
  final val MaxPooledBytes = 16L * 1024 * 1024
  final val MaxBufferBytes = 4 * 1024 * 1024

  private final class Pool:
    val buffers = new Array[Array[Byte]](MaxPooled)
    var count   = 0
    var bytes   = 0L

    /** Removes buffer i, moving the last one into its slot */
    def drop(i: Int): Array[Byte] =
      val b = buffers(i)
      count -= 1
      buffers(i) = buffers(count)
      buffers(count) = null
      bytes -= b.length
      b

    /** The index of the smallest buffer, or -1 when empty */
    def smallest: Int =
      var s = -1
      var i = 0
      while i < count do
        if s < 0 || buffers(i).length < buffers(s).length then
          s = i
        i += 1
      s

  private val pools =
    new ThreadLocal[Pool]:
      override def initialValue(): Pool = Pool()

  /** A buffer of at least `size` bytes: the smallest pooled one that fits, else a new one */
  def borrow(size: Int): Array[Byte] =
    val pool = pools.get()
    var best = -1
    var i    = 0
    while i < pool.count do
      val n = pool.buffers(i).length
      if n >= size && (best < 0 || n < pool.buffers(best).length) then
        best = i
      i += 1
    if best >= 0 then
      pool.drop(best)
    else
      new Array[Byte](size)

  /** Returns a buffer from [[borrow]], which the caller must not use afterwards */
  def release(buf: Array[Byte]): Unit =
    if buf != null && buf.length <= MaxBufferBytes then
      val pool = pools.get()
      // Make room by dropping smaller buffers, which the next chunks are less likely to need
      var keep = true
      while keep && (pool.count >= MaxPooled || pool.bytes + buf.length > MaxPooledBytes) do
        val s = pool.smallest
        if s < 0 || pool.buffers(s).length >= buf.length then
          keep = false
        else
          pool.drop(s)
      if keep then
        pool.buffers(pool.count) = buf
        pool.count += 1
        pool.bytes += buf.length

end PageBuffers
