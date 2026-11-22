package wvlet.uni.parquet

/**
  * Scala Native runs writes on one thread: the Immix GC that Uni uses can still crash under
  * concurrent allocation (scala-native#5046, #5160), so column tasks run one after another
  */
private[parquet] object WriteParallelism:
  val default: Int = 1

  def run(tasks: Int, parallelism: Int)(f: Int => Unit): Unit =
    var i = 0
    while i < tasks do
      f(i)
      i += 1
