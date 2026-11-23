package example.parquetbench

import wvlet.uni.io.FileSystem
import wvlet.uni.io.IOPath
import wvlet.uni.cloud.parquet.Parquet

/**
  * A case to time: `run` reads `path` (or writes the data of `path` to another file) and returns
  * (rows, checksum). MB/s is computed from the uncompressed column size of `path`.
  */
case class BenchCase(reader: String, path: String, run: () => (Long, Long))

object BenchCase:
  def read(reader: String, path: String, read: String => (Long, Long)): BenchCase = BenchCase(
    reader,
    path,
    () => read(path)
  )

/** Times readers over the generated files and prints a Markdown table */
object BenchRunner:
  val flatFiles: Seq[String] = Seq("uncompressed", "snappy", "zstd").map(c =>
    s"lineitem.${c}.parquet"
  )

  val nestedFiles: Seq[String] = Seq("uncompressed", "snappy", "zstd").map(c =>
    s"orders.${c}.parquet"
  )

  /** The Uni cases for the files in `dir` */
  def uniCases(dir: String): Seq[BenchCase] =
    flatFiles.flatMap(f =>
      Seq(
        BenchCase.read("uni columns", s"${dir}/${f}", UniReaders.columns),
        BenchCase.read("uni case class", s"${dir}/${f}", UniReaders.lineItems)
      )
    ) ++
      nestedFiles.flatMap(f =>
        Seq(
          BenchCase.read("uni columns", s"${dir}/${f}", UniReaders.columns),
          BenchCase.read("uni case class", s"${dir}/${f}", UniReaders.orders)
        )
      )

  /** The codecs both writers support (Uni does not write ZSTD) */
  val writeCodecs: Seq[String] = Seq("uncompressed", "snappy")

  /** The directory the write cases write to */
  def writeDir(dir: String): String = s"${dir}/../write"

  /**
    * The Uni write cases: the rows of each file, read once into memory, written again with
    * the file's codec. Reader names start with "write" so `--filter write` selects them.
    */
  def uniWriteCases(dir: String): Seq[BenchCase] =
    val out = writeDir(dir)
    // Grouped by table, so each table is read into memory once
    writeCodecs.map { c =>
      BenchCase(
        "write uni case class",
        s"${dir}/lineitem.${c}.parquet",
        () => UniWriters.lineItems(BenchData.lineItems(dir), s"${out}/uni.lineitem.${c}.parquet", c)
      )
    } ++
      writeCodecs.flatMap { c =>
        batchCases(
          s"${dir}/lineitem.${c}.parquet",
          () => BenchBatches.lineItems(dir),
          s"${out}/uni-batch.lineitem.${c}.parquet",
          c
        )
      } ++
      writeCodecs.map { c =>
        BenchCase(
          "write uni case class",
          s"${dir}/orders.${c}.parquet",
          () => UniWriters.orders(BenchData.orders(dir), s"${out}/uni.orders.${c}.parquet", c)
        )
      } ++
      writeCodecs.flatMap { c =>
        batchCases(
          s"${dir}/orders.${c}.parquet",
          () => BenchBatches.orders(dir),
          s"${out}/uni-batch.orders.${c}.parquet",
          c
        )
      }

  end uniWriteCases

  /**
    * The batch writer over batches read from `path`, and over the same batches rebuilt without the
    * reader's dictionary entries. Back to back, so the pair compares well on a busy machine.
    */
  private def batchCases(
      path: String,
      data: () => BenchBatches,
      out: String,
      codec: String
  ): Seq[BenchCase] = Seq(
    BenchCase("write uni batch", path, () => UniWriters.batches(data(), out, codec)),
    BenchCase("write uni batch, rebuilt", path, () => UniWriters.batches(data().rebuilt, out, codec))
  )

  /** Uncompressed bytes of the column data, from the footer, so MB/s is comparable across codecs */
  private def uncompressedBytes(path: String): Long =
    val f = Parquet.open(path)
    try f.rowGroups.flatMap(_.columns).map(_.totalUncompressedSize).sum
    finally f.close()

  /**
    * Runs each case `warmup` times untimed, then `iterations` times, and reports the median.
    * `filter` keeps the cases whose reader or file name contains it, or with a leading `=`, the
    * cases whose reader name is the rest exactly.
    */
  def run(cases: Seq[BenchCase], warmup: Int, iterations: Int, filter: Option[String]): Unit =
    val selected = cases.filter(c =>
      filter.forall { s =>
        if s.startsWith("=") then
          c.reader == s.drop(1)
        else
          c.reader.contains(s) || c.path.contains(s)
      }
    )
    println("| file | reader | rows | MB (uncompressed) | median ms | rows/s | MB/s |")
    println("|------|--------|-----:|------------------:|----------:|-------:|-----:|")
    selected.foreach { c =>
      if !FileSystem.exists(IOPath.parse(c.path)) then
        throw IllegalArgumentException(s"${c.path} not found. Generate the files first.")
      val bytes = uncompressedBytes(c.path)
      var rows  = 0L
      (0 until warmup).foreach(_ => rows = c.run()._1)
      val times = (0 until iterations).map { _ =>
        val start = System.nanoTime()
        rows = c.run()._1
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
