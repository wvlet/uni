package wvlet.uni.cloud.parquet

import wvlet.uni.io.FileSystem
import wvlet.uni.io.FileSystemInit
import wvlet.uni.io.IOPath
import wvlet.uni.io.ListOptions
import wvlet.uni.cloud.parquet.format.CompressionCodec
import wvlet.uni.test.UniTest
import wvlet.uni.weaver.Weaver

import java.util.concurrent.atomic.AtomicLong

case class ParallelPoint(x: Int, label: Option[String]) derives Weaver

case class ParallelRow(
    id: Long,
    part: String,
    price: Double,
    comment: Option[String],
    tags: Seq[String],
    attrs: Map[String, Long],
    point: ParallelPoint
) derives Weaver

/** Writes with several threads produce the same bytes as writes on one thread */
class ParallelWriteTest extends UniTest:
  FileSystemInit.init()

  private val rows = (0 until 30000).map { i =>
    ParallelRow(
      i.toLong,
      Seq("a", "b", "c", "d")(i % 4),
      i * 0.5,
      Option.when(i % 7 != 0)(s"comment-${i}"),
      (0 until i % 3).map(j => s"t${(i + j) % 11}"),
      (0 until i % 2).map(j => s"k${j}" -> (i * j).toLong).toMap,
      ParallelPoint(i % 100, Option.when(i % 5 != 0)(s"p${i % 13}"))
    )
  }

  // Several row groups, and batches large enough to be written in parallel
  private val batches: Seq[ColumnBatch] =
    val f = Parquet.open(Parquet.writeBytes(rows))
    try f.readBatches(ParquetReadOptions.default.withBatchSize(8000)).toIndexedSeq
    finally f.close()

  private val schema = Parquet.open(Parquet.writeBytes(rows.take(1))).schema

  private def write(options: ParquetWriteOptions): Array[Byte] =
    val out = java.io.ByteArrayOutputStream()
    val w   = Parquet.newBatchWriter(out, schema, options)
    try w.writeAll(batches)
    finally w.close()
    out.toByteArray

  test("write the same file at every parallelism") {
    Seq(CompressionCodec.SNAPPY, CompressionCodec.ZSTD).foreach { codec =>
      val base = ParquetWriteOptions.default.withCompression(codec).withRowGroupSize(256 * 1024)
      val one  = write(base.withParallelism(1))
      write(base.withParallelism(4)).toSeq shouldBe one.toSeq
      write(base.withParallelism(16)).toSeq shouldBe one.toSeq
    }
  }

  test("write the same partition files at every parallelism") {
    if FileSystem.isBrowser then
      skip("Dataset writers use local directories, which browsers don't have")
    def contents(threads: Int, iceberg: Boolean): Map[String, Seq[Byte]] =
      val root = FileSystem.createTempDirectory("uni-parquet-parallel")
      try
        val options = ParquetDatasetWriteOptions
          .default
          .withPartitionBy("part")
          .withWriteOptions(
            ParquetWriteOptions.default.withRowGroupSize(128 * 1024).withParallelism(threads)
          )
        val w = Parquet.newDatasetBatchWriter(
          root.path,
          schema,
          if iceberg then
            options.withTableFormat(ParquetTableFormat.Iceberg)
          else
            options
        )
        try w.writeAll(batches)
        finally w.close()
        // File names carry a random write ID, so key each data file by its partition folder
        FileSystem
          .list(root, ListOptions.default.withRecursive(true))
          .filter(p => FileSystem.isFile(p) && p.path.endsWith(".parquet"))
          .map(p => p.relativeTo(root).segments.init.mkString("/") -> FileSystem.readBytes(p).toSeq)
          .toMap
      finally
        FileSystem.deleteRecursively(root)
    Seq(false, true).foreach { iceberg =>
      val one = contents(1, iceberg)
      one.size shouldBe 4
      contents(4, iceberg) shouldBe one
    }
  }

  test("run nested tasks and report the first failure") {
    val sum = AtomicLong(0)
    WriteParallelism.run(50, 4) { i =>
      WriteParallelism.run(20, 4)(j => sum.addAndGet(i * 20L + j))
    }
    sum.get() shouldBe (0L until 1000L).sum
    val e = intercept[IllegalStateException] {
      WriteParallelism.run(30, 4) { i =>
        if i == 17 then
          throw IllegalStateException("task 17")
      }
    }
    e.getMessage shouldBe "task 17"
    intercept[IllegalArgumentException](ParquetWriteOptions.default.withParallelism(0))
  }

end ParallelWriteTest
