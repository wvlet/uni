package wvlet.uni.parquet

/** Scala.js has no threads, so sync reads run one at a time; async reads prefetch instead */
private[parquet] object ParallelMap:
  def apply[A, B](items: IndexedSeq[A], parallelism: Int)(f: A => B): IndexedSeq[B] = items.map(f)
