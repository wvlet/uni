package example.parquetbench

import scala.scalajs.js

/** uni-parquet alone on Node.js. Scala.js passes no arguments to main, so read process.argv. */
object UniBench:
  def main(args: Array[String]): Unit =
    wvlet.uni.io.FileSystemInit.init()
    val argv = js.Dynamic.global.process.argv.asInstanceOf[js.Array[String]].toSeq.drop(2)
    val (dir, warmup, iterations, filter) = BenchRunner.parse(argv)
    BenchRunner.run(
      BenchRunner.uniCases(dir) ++ BenchRunner.uniWriteCases(dir),
      warmup,
      iterations,
      filter
    )
