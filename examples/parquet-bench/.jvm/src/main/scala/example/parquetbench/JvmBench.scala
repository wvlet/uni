package example.parquetbench

/** Uni and parquet-java on the JVM, over the same files */
object JvmBench:
  def main(args: Array[String]): Unit =
    wvlet.uni.io.FileSystemInit.init()
    val (dir, warmup, iterations, filter) = BenchRunner.parse(args.toSeq)
    val javaCases                         =
      BenchRunner
        .flatFiles
        .flatMap(f =>
          Seq(
            BenchCase.read("parquet-java columns", s"${dir}/${f}", ParquetJavaReaders.columns),
            BenchCase.read("parquet-java groups", s"${dir}/${f}", ParquetJavaReaders.rows)
          )
        ) ++
        BenchRunner
          .nestedFiles
          .flatMap(f =>
            Seq(
              BenchCase.read("parquet-java columns", s"${dir}/${f}", ParquetJavaReaders.columns),
              BenchCase.read("parquet-java groups", s"${dir}/${f}", ParquetJavaReaders.rows)
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
    // Each uni case-class write next to the parquet-java one for the same data and codec. The
    // batch cases come after them, so each table is held in memory once.
    val out    = BenchRunner.writeDir(dir)
    val writes = BenchRunner
      .uniWriteCases(dir)
      .flatMap { u =>
        val name = u.path.split('/').last
        if u.reader != "write uni case class" then
          Seq(u)
        else
          val codec = name.split('.')(1)
          val java  =
            if name.startsWith("lineitem") then
              BenchCase(
                "write parquet-java groups",
                u.path,
                () =>
                  ParquetJavaWriters.lineItems(
                    BenchData.lineItems(dir),
                    s"${out}/java.lineitem.${codec}.parquet",
                    codec
                  )
              )
            else
              BenchCase(
                "write parquet-java groups",
                u.path,
                () =>
                  ParquetJavaWriters.orders(
                    BenchData.orders(dir),
                    s"${out}/java.orders.${codec}.parquet",
                    codec
                  )
              )
          Seq(u, java)
      }
    BenchRunner.run(cases ++ writes ++ IcebergWriters.cases(dir), warmup, iterations, filter)

  end main

end JvmBench
