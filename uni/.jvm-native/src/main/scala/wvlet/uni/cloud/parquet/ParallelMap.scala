package wvlet.uni.cloud.parquet

import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ThreadFactory

/** Runs blocking reads a few at a time on a short-lived thread pool (JVM and Scala Native) */
private[parquet] object ParallelMap:

  /** `items.map(f)` with up to `parallelism` calls in flight; results keep the input order */
  def apply[A, B](items: IndexedSeq[A], parallelism: Int)(f: A => B): IndexedSeq[B] =
    if items.size <= 1 || parallelism <= 1 then
      items.map(f)
    else
      val pool = Executors.newFixedThreadPool(math.min(parallelism, items.size), DaemonThreads)
      try
        val futures: IndexedSeq[Future[B]] = items.map(a => pool.submit(() => f(a)))
        futures.map { fu =>
          try
            fu.get()
          catch
            case e: ExecutionException if e.getCause != null =>
              futures.foreach(_.cancel(true))
              throw e.getCause
        }
      finally
        pool.shutdownNow()

  private object DaemonThreads extends ThreadFactory:
    override def newThread(r: Runnable): Thread =
      val t = Thread(r, "uni-parquet-prefetch")
      t.setDaemon(true)
      t

end ParallelMap
