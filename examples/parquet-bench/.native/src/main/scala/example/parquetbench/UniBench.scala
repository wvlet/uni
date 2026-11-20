package example.parquetbench

/** uni-parquet alone on Scala Native */
object UniBench:
  def main(args: Array[String]): Unit =
    wvlet.uni.io.FileSystemInit.init()
    val (dir, warmup, iterations, filter) = BenchRunner.parse(args.toSeq)
    BenchRunner.run(BenchRunner.uniCases(dir), warmup, iterations, filter)
