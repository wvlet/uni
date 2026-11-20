package example.parquetbench

import wvlet.uni.io.FileSystem
import wvlet.uni.io.IOPath
import wvlet.uni.parquet.Parquet

/** A reader to time: it reads one file and returns (rows, checksum) */
case class BenchCase(reader: String, path: String, read: String => (Long, Long))

/** Times readers over the generated files and prints a Markdown table */
object BenchRunner:
  val flatFiles: Seq[String] = Seq("uncompressed", "snappy", "zstd").map(c =>
    s"lineitem.${c}.parquet"
  )

  val nestedFiles: Seq[String] = Seq("uncompressed", "snappy", "zstd").map(c =>
    s"orders.${c}.parquet"
  )

  /** The uni-parquet cases for the files in `dir` */
  def uniCases(dir: String): Seq[BenchCase] =
    flatFiles.flatMap(f =>
      Seq(
        BenchCase("uni columns", s"${dir}/${f}", UniReaders.columns),
        BenchCase("uni case class", s"${dir}/${f}", UniReaders.lineItems)
      )
    ) ++
      nestedFiles.flatMap(f =>
        Seq(
          BenchCase("uni columns", s"${dir}/${f}", UniReaders.columns),
          BenchCase("uni case class", s"${dir}/${f}", UniReaders.orders)
        )
      )

  /** Uncompressed bytes of the column data, from the footer, so MB/s is comparable across codecs */
  private def uncompressedBytes(path: String): Long =
    val f = Parquet.open(path)
    try f.rowGroups.flatMap(_.columns).map(_.totalUncompressedSize).sum
    finally f.close()

  /**
    * Runs each case `warmup` times untimed, then `iterations` times, and reports the median.
    * `filter` keeps the cases whose reader or file name contains it.
    */
  def run(cases: Seq[BenchCase], warmup: Int, iterations: Int, filter: Option[String]): Unit =
    val selected = cases.filter(c => filter.forall(s => c.reader.contains(s) || c.path.contains(s)))
    println("| file | reader | rows | MB (uncompressed) | median ms | rows/s | MB/s |")
    println("|------|--------|-----:|------------------:|----------:|-------:|-----:|")
    selected.foreach { c =>
      if !FileSystem.exists(IOPath.parse(c.path)) then
        throw IllegalArgumentException(s"${c.path} not found. Generate the files first.")
      val bytes = uncompressedBytes(c.path)
      var rows  = 0L
      (0 until warmup).foreach(_ => rows = c.read(c.path)._1)
      val times = (0 until iterations).map { _ =>
        val start = System.nanoTime()
        rows = c.read(c.path)._1
        System.nanoTime() - start
      }
      val median = times.sorted.apply(times.size / 2) / 1e9
      val mb     = bytes / 1e6
      val name   = c.path.split('/').last
      println(
        f"| ${name} | ${c.reader} | ${rows} | ${mb}%.1f | ${median * 1000}%.0f | ${rows /
            median}%,.0f | ${mb / median}%.1f |"
      )
    }

  /** Parses `[dir] [--warmup N] [--iterations N] [--filter S]` */
  def parse(args: Seq[String]): (String, Int, Int, Option[String]) =
    var dir        = "examples/parquet-bench/out/data"
    var warmup     = 3
    var iterations = 5
    var filter     = Option.empty[String]
    var rest       = args.toList
    while rest.nonEmpty do
      rest match
        case "--warmup" :: n :: tail =>
          warmup = n.toInt
          rest = tail
        case "--iterations" :: n :: tail =>
          iterations = n.toInt
          rest = tail
        case "--filter" :: s :: tail =>
          filter = Some(s)
          rest = tail
        case d :: tail =>
          dir = d
          rest = tail
        case Nil =>
    (dir, warmup, iterations, filter)

end BenchRunner
