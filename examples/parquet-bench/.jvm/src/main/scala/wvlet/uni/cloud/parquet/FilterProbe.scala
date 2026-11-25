package wvlet.uni.cloud.parquet

import java.lang.management.ManagementFactory
import java.time.LocalDate
import wvlet.uni.cloud.parquet.ColumnVector.*
import wvlet.uni.cloud.parquet.ParquetFilter.*

/**
  * Times filtered scans of TPC-H lineitem, decoding filter columns first (late materialization) and
  * decoding every column then filtering, back to back in one process. The scans read the columns
  * and the literal conjuncts the wvlet engine can hand to uni for TPC-H q1, q6, q12, q14, and q19
  * The q6d and q19d scans add the DECIMAL conjuncts on `l_discount` and `l_quantity` (q19d the
  * union of its three branches' quantity ranges), so their rows are decoded only where every
  * conjunct holds. The q09 scan keeps a fifth of the rows by a quantity range.
  *
  * It lives in the parquet package to switch [[ParquetFile.lateMaterialization]].
  *
  * Usage:
  * `exampleParquetBenchJVM/runMain wvlet.uni.cloud.parquet.FilterProbe <lineitem.parquet> [rounds]`
  */
object FilterProbe:
  private def day(s: String) = LocalDate.parse(s)

  private case class Scan(name: String, columns: Seq[String], filter: ParquetFilter)

  private val scans = Seq(
    // Keeps ~98% of the rows: the case where filter-first decoding must not cost more
    Scan(
      "q1",
      Seq(
        "l_returnflag",
        "l_linestatus",
        "l_quantity",
        "l_extendedprice",
        "l_discount",
        "l_tax",
        "l_shipdate"
      ),
      col("l_shipdate") <= day("1998-09-02")
    ),
    Scan(
      "q6",
      Seq("l_extendedprice", "l_discount", "l_shipdate", "l_quantity"),
      col("l_shipdate") >= day("1994-01-01") && col("l_shipdate") < day("1995-01-01")
    ),
    Scan(
      "q6d",
      Seq("l_extendedprice", "l_discount", "l_shipdate", "l_quantity"),
      col("l_shipdate") >= day("1994-01-01") && col("l_shipdate") < day("1995-01-01") &&
        col("l_discount") >= BigDecimal("0.05") && col("l_discount") <= BigDecimal("0.07") &&
        col("l_quantity") < 24
    ),
    Scan(
      "q12",
      Seq("l_orderkey", "l_shipmode", "l_commitdate", "l_receiptdate", "l_shipdate"),
      (col("l_shipmode") === "MAIL" || col("l_shipmode") === "SHIP") &&
        col("l_receiptdate") >= day("1994-01-01") && col("l_receiptdate") < day("1995-01-01")
    ),
    Scan(
      "q14",
      Seq("l_partkey", "l_extendedprice", "l_discount", "l_shipdate"),
      col("l_shipdate") >= day("1995-09-01") && col("l_shipdate") < day("1995-10-01")
    ),
    Scan(
      "q19",
      Seq(
        "l_partkey",
        "l_quantity",
        "l_extendedprice",
        "l_discount",
        "l_shipinstruct",
        "l_shipmode"
      ),
      col("l_shipinstruct") === "DELIVER IN PERSON" &&
        (col("l_shipmode") === "AIR" || col("l_shipmode") === "AIR REG")
    ),
    Scan(
      "q19d",
      Seq(
        "l_partkey",
        "l_quantity",
        "l_extendedprice",
        "l_discount",
        "l_shipinstruct",
        "l_shipmode"
      ),
      col("l_shipinstruct") === "DELIVER IN PERSON" &&
        (col("l_shipmode") === "AIR" || col("l_shipmode") === "AIR REG") &&
        col("l_quantity") >= 1 && col("l_quantity") <= 30
    ),
    // Keeps ~20% of the rows, as TPC-DS q09's quantity ranges do: a filter selective enough to
    // skip decoding, but loose enough that evaluating it costs as much as the decoding it saves
    Scan(
      "q09",
      Seq("l_orderkey", "l_partkey", "l_quantity", "l_extendedprice", "l_discount", "l_tax"),
      col("l_quantity") >= 11 && col("l_quantity") <= 20
    )
  )

  def main(args: Array[String]): Unit =
    wvlet.uni.io.FileSystemInit.init()
    val path    = args(0)
    val rounds  = args.lift(1).map(_.toInt).getOrElse(9)
    val only    = args.lift(2).map(_.split(',').toSet)
    val threads = ManagementFactory.getThreadMXBean.asInstanceOf[com.sun.management.ThreadMXBean]
    val tid     = Thread.currentThread().threadId()
    val file    = Parquet.open(path)
    try scans
        .filter(s => only.forall(_.contains(s.name)))
        .foreach { s =>
          val opts = ParquetReadOptions
            .default
            .withColumns(s.columns*)
            .withBinaryAsString(true)
            .withInt32DecimalAsLong(true)
            .withFilter(s.filter)
          val times = Map(true -> Array.newBuilder[Double], false -> Array.newBuilder[Double])
          val alloc = Map(true -> Array.newBuilder[Long], false -> Array.newBuilder[Long])
          var check = Map.empty[Boolean, (Long, Long)]
          // The first round's filter-first time, before the JIT has compiled the hot loops
          var firstLate = 0.0
          (0 until rounds).foreach { r =>
            // Alternate which mode goes first, so drift in machine load hits both alike
            val order =
              if r % 2 == 0 then
                Seq(true, false)
              else
                Seq(false, true)
            order.foreach { late =>
              file.lateMaterialization = late
              val a0     = threads.getThreadAllocatedBytes(tid)
              val t0     = System.nanoTime()
              val result = scan(file, opts)
              val t1     = System.nanoTime()
              val a1     = threads.getThreadAllocatedBytes(tid)
              check
                .get(late)
                .foreach { c =>
                  if c != result then
                    throw IllegalStateException(s"${s.name}: result changed: ${c} -> ${result}")
                }
              check += late -> result
              if r == 0 && late then
                firstLate = (t1 - t0) / 1e6
              if r > 0 then
                times(late) += (t1 - t0) / 1e6
                alloc(late) += a1 - a0
            }
          }
          if check(true) != check(false) then
            throw IllegalStateException(s"${s.name}: late ${check(true)} != eager ${check(false)}")
          def median[A: Ordering](b: scala.collection.mutable.Builder[A, Array[A]]): A =
            val a = b.result().sorted
            a(a.length / 2)
          val (late, eager) = (median(times(true)), median(times(false)))
          println(
            f"${s.name}: ${check(true)
                ._1}%,d rows; filter-first ${late}%.1f ms, decode-all ${eager}%.1f ms (${(
                1 - late / eager
              ) * 100}%.0f%% less); allocated ${median(alloc(true)) / 1e6}%.0f MB vs ${median(
                alloc(false)
              ) / 1e6}%.0f MB; first filter-first round ${firstLate}%.0f ms"
          )
        }
    finally file.close()
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
        b.columns
          .foreach {
            case c: LongColumn =>
              var i = 0
              while i < c.size do
                sum += c.values(i) * 31 + i
                i += 1
            case c: IntColumn =>
              var i = 0
              while i < c.size do
                sum += c.values(i)
                i += 1
            case c: BinaryColumn =>
              sum += c.offsets(c.size) - c.offsets(0) + c.size
            case _ =>
          }
      }
    (rows, sum)

end FilterProbe
