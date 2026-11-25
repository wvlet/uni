package wvlet.uni.cloud.parquet

import java.lang.management.ManagementFactory
import wvlet.uni.cloud.parquet.ColumnVector.*
import wvlet.uni.cloud.parquet.ParquetFilter.*

/**
  * Times the store_sales probe scan of TPC-DS q73 with the build keys of its three dimension joins
  * passed as `isIn` conjuncts, against reading every row and testing the keys afterwards (what an
  * engine does with its own runtime join filters). Runs back to back in one process.
  *
  * It lives in the parquet package to switch [[ParquetFile.lateMaterialization]].
  *
  * Usage:
  * `exampleParquetBenchJVM/runMain wvlet.uni.cloud.parquet.JoinFilterProbe <tpcds dir> [rounds]`
  */
object JoinFilterProbe:
  private val columns = Seq(
    "ss_sold_date_sk",
    "ss_store_sk",
    "ss_hdemo_sk",
    "ss_customer_sk",
    "ss_ticket_number"
  )

  private def long(v: Any): Long =
    v match
      case n: Number =>
        n.longValue
      case other =>
        throw IllegalStateException(s"Not a number: ${other}")

  /** The keys of the dimension rows a predicate keeps */
  private def keys(path: String, key: String)(p: Map[String, Any] => Boolean): Seq[Long] =
    val f = Parquet.open(path)
    try
      f.readRows(ParquetReadOptions.default.withBinaryAsString(true))
        .filter(p)
        .map(r => long(r(key)))
        .toSeq
    finally f.close()

  def main(args: Array[String]): Unit =
    wvlet.uni.io.FileSystemInit.init()
    val dir    = args(0)
    val rounds = args.lift(1).map(_.toInt).getOrElse(7)
    // q73's dimension predicates
    val dates = IntegerSet.of(
      keys(s"${dir}/date_dim.parquet", "d_date_sk") { r =>
        val dom = r("d_dom")
        dom != null && long(dom) >= 1 && long(dom) <= 2 && r("d_year") != null &&
        Set(1999L, 2000L, 2001L).contains(long(r("d_year")))
      }
    )
    val stores = IntegerSet.of(
      keys(s"${dir}/store.parquet", "s_store_sk") { r =>
        Set("Williamson County", "Franklin Parish", "Bronx County", "Orange County").contains(
          r("s_county")
        )
      }
    )
    val hdemo = IntegerSet.of(
      keys(s"${dir}/household_demographics.parquet", "hd_demo_sk") { r =>
        val vc = r("hd_vehicle_count")
        val dc = r("hd_dep_count")
        Set(">10000", "Unknown").contains(r("hd_buy_potential")) && vc != null && dc != null &&
        long(vc) > 0 && long(dc).toDouble / long(vc) > 1
      }
    )
    println(s"keys: dates ${dates}, stores ${stores}, hdemo ${hdemo}")
    val file     = Parquet.open(s"${dir}/store_sales.parquet")
    val threads  = ManagementFactory.getThreadMXBean.asInstanceOf[com.sun.management.ThreadMXBean]
    val tid      = Thread.currentThread().threadId()
    val base     = ParquetReadOptions.default.withColumns(columns*)
    val filtered = base.withFilter(
      col("ss_sold_date_sk").isIn(dates) && col("ss_store_sk").isIn(stores) &&
        col("ss_hdemo_sk").isIn(hdemo)
    )
    // Each variant: name, how it reads, and whether filter-first decoding is on
    val all = Seq(
      ("read all, test after", () => scanThenTest(file, base, dates, stores, hdemo), true),
      ("isIn filter-first", () => scan(file, filtered), true),
      ("isIn decode-all", () => scan(file, filtered), false),
      ("date column only", () => scanOnly(file, dates), true)
    )
    // An optional comma-separated list of variant numbers to run, such as 1 for profiling
    val only     = args.lift(2).map(_.split(',').map(_.toInt).toSet)
    val variants = all.zipWithIndex.filter((_, i) => only.forall(_.contains(i))).map(_._1)
    val times    = variants.map(_ => Array.newBuilder[Double])
    val walls    = variants.map(_ => Array.newBuilder[Double])
    var result   = Option.empty[(Long, Long)]
    try
      (0 until rounds).foreach { r =>
        // Rotate which variant goes first, so drift in machine load hits all alike
        variants
          .indices
          .map(i => (i + r) % variants.size)
          .foreach { i =>
            val (name, run, late) = variants(i)
            file.lateMaterialization = late
            val w0  = System.nanoTime()
            val t0  = threads.getThreadCpuTime(tid)
            val out = run()
            val t1  = threads.getThreadCpuTime(tid)
            val w1  = System.nanoTime()
            if variants(i)._1.startsWith("date") == false then
              result.foreach { c =>
                if c != out then
                  throw IllegalStateException(s"${name}: result ${out} != ${c}")
              }
              result = Some(out)
            if r > 0 then
              times(i) += (t1 - t0) / 1e6
              walls(i) += (w1 - w0) / 1e6
          }
      }
      def median(b: scala.collection.mutable.Builder[Double, Array[Double]]) =
        val a = b.result().sorted
        a(a.length / 2)
      val cpu  = times.map(median)
      val wall = walls.map(median)
      println(f"${result.get._1}%,d of ${file.numRows}%,d rows kept; median ms, CPU / wall:")
      variants
        .indices
        .foreach { i =>
          println(
            f"  ${variants(i)._1}%-22s ${cpu(i)}%8.1f (${cpu(i) / cpu(0) * 100}%3.0f%%) ${wall(
                i
              )}%8.1f (${wall(i) / wall(0) * 100}%3.0f%%)"
          )
        }
    finally
      file.lateMaterialization = true
      file.close()
    end try

  end main

  /** The rows a scan returns and a checksum of their values */
  private def scan(file: ParquetFile, opts: ParquetReadOptions): (Long, Long) =
    var rows = 0L
    var sum  = 0L
    file
      .readBatches(opts)
      .foreach { b =>
        rows += b.numRows
        var i = 0
        while i < b.numRows do
          sum += rowSum(b, i)
          i += 1
      }
    (rows, sum)

  /** Reads only the first filter column, with its conjunct: the floor of filter-first decoding */
  private def scanOnly(file: ParquetFile, dates: IntegerSet): (Long, Long) = scan(
    file,
    ParquetReadOptions
      .default
      .withColumns("ss_sold_date_sk")
      .withFilter(col("ss_sold_date_sk").isIn(dates))
  )

  /** Reads every row, then keeps the rows whose keys the sets accept */
  private def scanThenTest(
      file: ParquetFile,
      opts: ParquetReadOptions,
      dates: IntegerSet,
      stores: IntegerSet,
      hdemo: IntegerSet
  ): (Long, Long) =
    var rows = 0L
    var sum  = 0L
    file
      .readBatches(opts)
      .foreach { b =>
        val d = b.columns(0)
        val s = b.columns(1)
        val h = b.columns(2)
        var i = 0
        while i < b.numRows do
          if accepts(d, i, dates) && accepts(s, i, stores) && accepts(h, i, hdemo) then
            rows += 1
            sum += rowSum(b, i)
          i += 1
      }
    (rows, sum)

  private def value(c: ColumnVector, i: Int): Long =
    c match
      case c: IntColumn =>
        c.values(i)
      case c: LongColumn =>
        c.values(i)
      case other =>
        throw IllegalStateException(s"Unexpected ${other.getClass.getSimpleName}")

  private def accepts(c: ColumnVector, i: Int, set: IntegerSet): Boolean =
    (c.nulls == null || !c.nulls(i)) && set.contains(value(c, i))

  private def rowSum(b: ColumnBatch, i: Int): Long =
    var s = 0L
    b.columns
      .foreach { c =>
        if c.nulls == null || !c.nulls(i) then
          s = s * 31 + value(c, i)
      }
    s

end JoinFilterProbe
