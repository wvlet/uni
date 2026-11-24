package wvlet.uni.cloud.parquet

import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
  * Runs the independent tasks of a write (columns, or partition files) on a shared pool of daemon
  * threads (JVM and Scala Native). The calling thread takes tasks too, and a helper that hasn't
  * started when the tasks run out is cancelled rather than awaited. So a run never waits for a free
  * pool thread, and runs can nest: a task may start a run of its own.
  */
private[parquet] object WriteParallelism:
  /** The default `ParquetWriteOptions.parallelism`: the available processors, up to 8 */
  val default: Int = math.max(1, math.min(8, Runtime.getRuntime.availableProcessors()))

  private lazy val pool = Executors.newFixedThreadPool(
    math.max(1, Runtime.getRuntime.availableProcessors() - 1),
    DaemonThreads
  )

  /**
    * Runs `f(0)` to `f(tasks - 1)` with up to `parallelism` running at once; rethrows the first
    * error
    */
  def run(tasks: Int, parallelism: Int)(f: Int => Unit): Unit =
    if tasks <= 1 || parallelism <= 1 then
      var i = 0
      while i < tasks do
        f(i)
        i += 1
    else
      val next             = AtomicInteger(0)
      val failure          = AtomicReference[Throwable](null)
      val worker: Runnable =
        () =>
          var i = next.getAndIncrement()
          while i < tasks && failure.get() == null do
            try
              f(i)
            catch
              case e: Throwable =>
                failure.compareAndSet(null, e)
            i = next.getAndIncrement()
      // Each helper claims its slot before running (1); the caller skips the unclaimed ones (2)
      val helpers = (1 until math.min(parallelism, tasks)).map { _ =>
        val slot             = AtomicInteger(0)
        val helper: Runnable =
          () =>
            if slot.compareAndSet(0, 1) then
              worker.run()
        val fu = pool.submit(helper)
        (slot, fu)
      }
      worker.run()
      // Every task is claimed. A helper that hasn't started has nothing left to do, so it is
      // skipped rather than awaited; waiting on the ones that started publishes their writes.
      helpers.foreach { (slot, fu) =>
        if !slot.compareAndSet(0, 2) then
          fu.get()
      }
      val e = failure.get()
      if e != null then
        throw e

  private object DaemonThreads extends ThreadFactory:
    private val count                           = AtomicInteger(0)
    override def newThread(r: Runnable): Thread =
      val t = Thread(r, s"uni-parquet-write-${count.incrementAndGet()}")
      t.setDaemon(true)
      t

end WriteParallelism
