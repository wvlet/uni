package wvlet.uni.parquet

import wvlet.uni.io.FileSystemInit
import wvlet.uni.test.UniTest
import wvlet.uni.weaver.Weaver

case class BatchLine(sku: String, qty: Int) derives Weaver

case class BatchRow(
    id: Long,
    name: String,
    note: Option[String],
    tags: Seq[String],
    lines: Seq[BatchLine],
    attrs: Map[String, Int]
) derives Weaver

/**
  * Row groups read in batches: by row count, by the byte limits that keep each column below 2 GiB
  * (made small here), and with chunks read in windows. Every way must give the same rows.
  */
class BatchTest extends UniTest:
  FileSystemInit.init()

  private val rows: Seq[BatchRow] = (0 until 500).map { i =>
    BatchRow(
      id = i.toLong,
      name = s"name-${i % 37}",
      note = Option.when(i % 3 == 0)(s"note ${i}"),
      // Some rows have no tags or lines, so rows end at every kind of level
      tags = (0 until i  % 4).map(j => s"t${j}"),
      lines = (0 until i % 5).map(j => BatchLine(s"sku-${i}-${j}", j)),
      attrs = (0 until i % 3).map(j => s"k${j}" -> (i * j)).toMap
    )
  }

  // Small pages, so batches end both inside and at the end of pages, and two row groups
  private val bytes: Array[Byte] =
    val out = java.io.ByteArrayOutputStream()
    val w   = Parquet.newWriter[BatchRow](
      out,
      ParquetWriteOptions.default.withPageSize(256).withRowGroupSize(16 * 1024)
    )
    try w.writeAll(rows)
    finally w.close()
    out.toByteArray

  private def open(): ParquetFile = Parquet.open(bytes)

  private def read(f: ParquetFile, options: ParquetReadOptions): (Seq[Int], Seq[BatchRow]) =
    val batches = f.readBatches(options).toSeq
    (
      batches.map(_.numRows),
      batches.flatMap(b => ParquetFile.rows(b, Weaver.of[BatchRow], options))
    )

  test("write a file with several row groups and pages") {
    val f = open()
    try (f.numRowGroups > 1) shouldBe true
    finally f.close()
  }

  test("read a row group in batches of a given size") {
    val f = open()
    try
      val (sizes, out) = read(f, ParquetReadOptions.default.withBatchSize(7))
      out shouldBe rows
      sizes.forall(_ <= 7) shouldBe true
      // Only the last batch of a row group can be shorter
      (sizes.count(_ < 7) <= f.numRowGroups) shouldBe true
    finally
      f.close()
  }

  test("read batches of one row") {
    val f = open()
    try
      val (sizes, out) = read(f, ParquetReadOptions.default.withBatchSize(1))
      out shouldBe rows
      sizes.toSet shouldBe Set(1)
    finally
      f.close()
  }

  test("end a batch at a row once a column passes the byte limit") {
    val f = open()
    try
      f.batchSoftLimit = 300
      val (sizes, out) = read(f, ParquetReadOptions.default)
      out shouldBe rows
      (sizes.size > f.numRowGroups) shouldBe true
    finally
      f.close()
  }

  test("keep the row group whole without a batch size") {
    val f = open()
    try
      val (sizes, out) = read(f, ParquetReadOptions.default)
      out shouldBe rows
      sizes shouldBe f.rowGroups.map(_.numRows.toInt)
    finally
      f.close()
  }

  test("readRowGroup points to readBatches when a row group does not fit in one batch") {
    val f = open()
    try
      f.batchHardLimit = 300
      val e = intercept[ParquetException] {
        f.readRowGroup(0)
      }
      e.getMessage shouldContain "readBatches"
    finally
      f.close()
  }

  test("read chunks in windows") {
    val f = open()
    try
      f.chunkWindowSize = 100
      f.batchSoftLimit = 500
      read(f, ParquetReadOptions.default)._2 shouldBe rows
      f.readRowGroup(0).numRows shouldBe f.rowGroups(0).numRows.toInt
    finally
      f.close()
  }

  test("read batches and windowed chunks asynchronously") {
    Parquet
      .openAsync(ByteSource.ofBytes(bytes))
      .flatMap { f =>
        // Each window is one fetch, and a row group's fetches are chained, so keep them few
        f.chunkWindowSize = 4096
        f.batchSoftLimit = 500
        f.readAllAsync[BatchRow](ParquetReadOptions.default.withBatchSize(3))
      }
      .map { out =>
        out shouldBe rows
      }
  }

  test("emit batches asynchronously") {
    val out = Seq.newBuilder[BatchRow]
    Parquet
      .openAsync(ByteSource.ofBytes(bytes))
      .flatMap(_.readAsAsync[BatchRow](ParquetReadOptions.default.withBatchSize(4)))
      .map(out += _)
      .lastOption
      .map { _ =>
        out.result() shouldBe rows
      }
  }

  // Every test file, read in batches of the smallest byte limit, gives the rows it gives whole
  private val corpus: Seq[String] = Seq(
    "apache/alltypes_plain.parquet",
    "apache/alltypes_dictionary.parquet",
    "apache/nested_lists.snappy.parquet",
    "apache/nested_maps.snappy.parquet",
    "apache/nested_structs.rust.parquet",
    "apache/nullable.impala.parquet",
    "apache/repeated_no_annotation.parquet",
    "apache/repeated_primitive_no_list.parquet",
    "apache/old_list_structure.parquet",
    "apache/list_columns.parquet",
    "apache/null_list.parquet",
    "apache/map_no_value.parquet",
    "apache/delta_byte_array.parquet",
    "apache/delta_encoding_optional_column.parquet",
    "apache/datapage_v2.snappy.parquet",
    "apache/datapage_v2_empty_datapage.snappy.parquet",
    "apache/int32_with_null_pages.parquet",
    "duckdb/all_snappy_V1.parquet",
    "duckdb/all_zstd_V2.parquet"
  )

  corpus.foreach { name =>
    test(s"read ${name} in small batches") {
      // These two files hold every column type, which takes ~2 seconds each in Native's debug
      // build. The reader is shared Scala code, and the smaller files cover it on Native.
      if isScalaNative && name.startsWith("duckdb/") then
        skip("Checked on the JVM")
      def json(f: ParquetFile): Seq[String] = f.readJson().map(_.toJSON).toSeq
      val whole                             =
        val f = Parquet.open(TestFiles.bytes(name))
        try json(f)
        finally f.close()
      val f = Parquet.open(TestFiles.bytes(name))
      try
        f.batchSoftLimit = 1
        f.chunkWindowSize = 64
        json(f) shouldBe whole
        // Batches of a few rows end inside pages
        f.readJson(ParquetReadOptions.default.withBatchSize(3)).map(_.toJSON).toSeq shouldBe whole
      finally
        f.close()
    }
  }

  private val largeStringMap = "apache/large_string_map.brotli.parquet"

  test("large_string_map has a column chunk with more than 2 GiB of values") {
    val f = Parquet.open(TestFiles.bytes(largeStringMap))
    try
      f.numRows shouldBe 2
      (f.rowGroups(0).columns.map(_.totalUncompressedSize).max > Int.MaxValue) shouldBe true
    finally
      f.close()
  }

  test("read large_string_map one row per batch") {
    if !sys.env.get("UNI_PARQUET_LARGE_TESTS").contains("1") then
      skip("needs about 5 GiB of heap; set UNI_PARQUET_LARGE_TESTS=1 to run it")
    val f = Parquet.open(TestFiles.bytes(largeStringMap))
    try
      val sizes =
        f.readBatches()
          .map { b =>
            val m = b.column("arr").asInstanceOf[ColumnVector.MapColumn]
            val k = m.keys.asInstanceOf[ColumnVector.BinaryColumn]
            k.length(0) shouldBe (1 << 30)
            k.data(k.offsets(0)) shouldBe 'a'.toByte
            m.values.getValue(0) shouldBe 1
            b.numRows
          }
          .toSeq
      sizes shouldBe Seq(1, 1)
      val e = intercept[ParquetException] {
        f.readRowGroup(0)
      }
      e.getMessage shouldContain "readBatches"
    finally
      f.close()
  }

end BatchTest
