package example.parquetbench

import java.lang.management.ManagementFactory
import wvlet.uni.cloud.parquet.ColumnVector
import wvlet.uni.cloud.parquet.ColumnVector.*
import wvlet.uni.cloud.parquet.Parquet

/**
  * Times full column scans of one Parquet file, such as TPC-DS store_sales, and reports the heap
  * each scan allocates.
  *
  * Usage: `exampleParquetBenchJVM/runMain example.parquetbench.ScanProbe <file> [iterations] [--no-digest]`
  */
object ScanProbe:
  def main(args: Array[String]): Unit =
    wvlet.uni.io.FileSystemInit.init()
    val path       = args(0)
    val iterations = args.drop(1).find(!_.startsWith("--")).map(_.toInt).getOrElse(15)
    val threads    = ManagementFactory.getThreadMXBean.asInstanceOf[com.sun.management.ThreadMXBean]
    val tid        = Thread.currentThread().threadId()
    if !args.contains("--no-digest") then
      println(f"digest of every value and null flag: ${digest(path)}%016x")
    val times      = Array.newBuilder[Double]
    val allocs     = Array.newBuilder[Long]
    var check      = 0L
    var rows       = 0L
    var i          = 0
    while i < iterations do
      val a0       = threads.getThreadAllocatedBytes(tid)
      val t0       = System.nanoTime()
      val (r, sum) = UniReaders.columns(path)
      val t1       = System.nanoTime()
      val a1       = threads.getThreadAllocatedBytes(tid)
      rows = r
      if i == 0 then
        check = sum
      else if sum != check then
        throw IllegalStateException(s"checksum changed: ${check} -> ${sum}")
      times += (t1 - t0) / 1e6
      allocs += a1 - a0
      println(f"iteration ${i}%2d: ${(t1 - t0) / 1e6}%8.1f ms, ${(a1 - a0) / 1e6}%8.1f MB allocated")
      i += 1
    // The first third warms up the JIT
    val warm   = iterations / 3
    val sorted = times.result().drop(warm).sorted
    val alloc  = allocs.result().drop(warm).sorted
    println(
      f"${path.split('/').last}: ${rows} rows, checksum ${check}, median ${sorted(
          sorted.length / 2
        )}%.1f ms, best ${sorted.head}%.1f ms, median ${alloc(alloc.length / 2) / 1e6}%.1f MB allocated"
    )

  /** A hash of every value and null flag, for checking that two builds read the same data */
  private def digest(path: String): Long =
    val f = Parquet.open(path)
    try
      var h = 17L
      f.readBatches()
        .foreach { b =>
          b.columns
            .foreach { c =>
              var i = 0
              while i < c.size do
                h = h * 31 + valueHash(c, i)
                i += 1
            }
        }
      h
    finally
      f.close()

  private def valueHash(c: ColumnVector, i: Int): Long =
    if c.isNull(i) then
      -1L
    else
      c match
        case v: BinaryColumn =>
          java.util.Arrays.hashCode(v.getBytes(i)).toLong
        case v =>
          v.getValue(i).hashCode.toLong

end ScanProbe
