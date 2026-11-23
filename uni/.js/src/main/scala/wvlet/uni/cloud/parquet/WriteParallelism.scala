package wvlet.uni.cloud.parquet

/** Scala.js has no threads, so column tasks run one after another */
private[parquet] object WriteParallelism:
  val default: Int = 1

  def run(tasks: Int, parallelism: Int)(f: Int => Unit): Unit =
    var i = 0
    while i < tasks do
      f(i)
      i += 1
