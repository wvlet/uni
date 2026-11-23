package wvlet.uni.cloud.parquet

/**
  * Sync reads on Scala Native fetch footers one at a time: the Immix GC that Uni uses can still
  * crash under concurrent allocation (scala-native#5046, #5160). Revisit on 0.5.13.
  */
private[parquet] object ParallelMap:
  def apply[A, B](items: IndexedSeq[A], parallelism: Int)(f: A => B): IndexedSeq[B] = items.map(f)
