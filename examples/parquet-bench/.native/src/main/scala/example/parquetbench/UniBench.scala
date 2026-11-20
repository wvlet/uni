package example.parquetbench

/** uni-parquet alone on Scala Native */
object UniBench:
  def main(args: Array[String]): Unit =
    wvlet.uni.io.FileSystemInit.init()
    // `codec <dir> [rounds]` runs the isolated ZSTD decompression benchmark instead
    if args.headOption.contains("codec") then
      wvlet.uni.parquet.codec.CodecBench.main(args.tail)
      return
    val (dir, warmup, iterations, filter) = BenchRunner.parse(args.toSeq)
    BenchRunner.run(
      BenchRunner.uniCases(dir) ++ BenchRunner.uniWriteCases(dir),
      warmup,
      iterations,
      filter
    )
