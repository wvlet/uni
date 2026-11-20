package example.parquetbench

/** uni-parquet and parquet-java on the JVM, over the same files */
object JvmBench:
  def main(args: Array[String]): Unit =
    wvlet.uni.io.FileSystemInit.init()
    val (dir, warmup, iterations, filter) = BenchRunner.parse(args.toSeq)
    val javaCases                         =
      BenchRunner
        .flatFiles
        .flatMap(f =>
          Seq(
            BenchCase("parquet-java columns", s"${dir}/${f}", ParquetJavaReaders.columns),
            BenchCase("parquet-java groups", s"${dir}/${f}", ParquetJavaReaders.rows)
          )
        ) ++
        BenchRunner
          .nestedFiles
          .flatMap(f =>
            Seq(
              BenchCase("parquet-java columns", s"${dir}/${f}", ParquetJavaReaders.columns),
              BenchCase("parquet-java groups", s"${dir}/${f}", ParquetJavaReaders.rows)
            )
          )
    // Interleave so each file's readers run next to each other
    val uni   = BenchRunner.uniCases(dir).grouped(2).toSeq
    val java  = javaCases.grouped(2).toSeq
    val cases = uni
      .zip(java)
      .flatMap { case (u, j) =>
        u ++ j
      }
    BenchRunner.run(cases, warmup, iterations, filter)

end JvmBench
