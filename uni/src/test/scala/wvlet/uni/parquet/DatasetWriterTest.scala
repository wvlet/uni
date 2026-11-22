package wvlet.uni.parquet

import wvlet.uni.io.FileSystem
import wvlet.uni.io.FileSystemInit
import wvlet.uni.io.IOPath
import wvlet.uni.io.ListOptions
import wvlet.uni.parquet.ColumnVector.*
import wvlet.uni.parquet.ParquetFilter.*
import wvlet.uni.parquet.format.LogicalType
import wvlet.uni.test.UniTest
import wvlet.uni.weaver.Weaver

import java.time.LocalDate

case class Sale(id: Long, amount: Double, dt: String, country: String) derives Weaver
case class SaleRow(id: Long, amount: Double) derives Weaver
case class DatedSale(id: Long, amount: Double, dt: LocalDate) derives Weaver

class DatasetWriterTest extends UniTest:
  FileSystemInit.init()

  private var dirs: List[IOPath] = Nil

  override protected def afterAll: Unit = dirs.foreach(FileSystem.deleteRecursively)

  private def newRoot(): IOPath =
    val root = FileSystem.createTempDirectory("uni-parquet-dataset-writer")
    dirs = root :: dirs
    root

  private def skipInBrowser(): Unit =
    if FileSystem.isBrowser then
      skip("Dataset writers use local directories, which browsers don't have")

  /** Every file under the root, as paths relative to it, except the root's `_` files */
  private def filesUnder(root: IOPath): Seq[String] = allFilesUnder(root).filterNot(
    _.startsWith("_")
  )

  private def allFilesUnder(root: IOPath): Seq[String] =
    FileSystem
      .list(root, ListOptions.default.withRecursive(true).withIncludeHidden(true))
      .filter(FileSystem.isFile)
      .map(_.relativeTo(root).segments.mkString("/"))
      .sorted

  private val days  = Seq("2026-11-19", "2026-11-20")
  private val dates = days.map(LocalDate.parse)

  private val sales =
    for i <- 0 until 40
    yield Sale(
      i.toLong,
      i * 1.5,
      days(i % 2),
      if i % 3 == 0 then
        "JP"
      else
        "US"
    )

  private val byDtCountry = ParquetDatasetWriteOptions.default.withPartitionBy("dt", "country")

  private def readSales(root: IOPath, options: ParquetReadOptions = ParquetReadOptions.default) =
    val ds = Parquet.dataset(root.path)
    try (ds.selectFiles(options).size, ds.readAs[Sale](options).toSeq.sortBy(_.id))
    finally ds.close()

  test("write case classes to partition folders and read them back") {
    skipInBrowser()
    val root    = newRoot()
    val written = Parquet.writeDataset(root.path, sales, byDtCountry)

    written.size shouldBe 4
    written.map(_.numRows).sum shouldBe 40L
    written.map(_.partitions("dt")).toSet shouldBe days.toSet
    val files = filesUnder(root)
    files.size shouldBe 4
    files.forall(f =>
      f.matches("dt=2026-11-(19|20)/country=(JP|US)/part-[0-9a-f]{8}-\\d{5}\\.parquet")
    ) shouldBe true

    // Partition columns are left out of the files
    val one = Parquet.open(written.head.path)
    try one.schema.fields.map(_.name) shouldBe Seq("id", "amount")
    finally one.close()

    readSales(root)._2 shouldBe sales
  }

  test("write LocalDate partitions from case classes") {
    skipInBrowser()
    val root  = newRoot()
    val rows  = sales.map(s => DatedSale(s.id, s.amount, LocalDate.parse(s.dt)))
    val opts  = ParquetDatasetWriteOptions.default.withPartitionBy("dt")
    val files = Parquet.writeDataset(root.path, rows, opts)
    files.map(_.partitions("dt")).toSet shouldBe dates.toSet
    filesUnder(root).map(_.takeWhile(_ != '/')).toSet shouldBe days.map(d => s"dt=${d}").toSet

    // The writer recorded dt as DATE; readAs works with either type
    val ds = Parquet.dataset(root.path)
    try ds.readAs[DatedSale]().toSeq.sortBy(_.id) shouldBe rows
    finally ds.close()

    val typed = Parquet.dataset(
      root.path,
      ParquetDatasetOptions.default.withPartitionType("dt", ParquetType.DATE)
    )
    try
      typed.readAs[DatedSale]().toSeq.sortBy(_.id) shouldBe rows
      typed.readRows().toSeq.head("dt") shouldMatch { case _: LocalDate =>
      }
    finally
      typed.close()
  }

  test("write LocalDate fields as DATE columns") {
    skipInBrowser()
    val root = newRoot()
    val rows = sales.take(4).map(s => DatedSale(s.id, s.amount, LocalDate.parse(s.dt)))
    Parquet.writeDataset(root.path, rows, ParquetDatasetWriteOptions.default)
    val ds = Parquet.dataset(root.path)
    try
      ds.schema.field("dt") shouldMatch {
        case Some(f: ParquetField.Primitive) if f.logicalType.contains(LogicalType.Date) =>
      }
      ds.readAs[DatedSale]().toSeq.sortBy(_.id) shouldBe rows
    finally
      ds.close()
  }

  test("prune files written by the writer") {
    skipInBrowser()
    val root = newRoot()
    Parquet.writeDataset(root.path, sales, byDtCountry)
    val opts = ParquetReadOptions
      .default
      .withRowGroupFilter(col("dt") === "2026-11-20" && col("country") === "JP")
    val (selected, rows) = readSales(root, opts)
    selected shouldBe 1
    rows shouldBe sales.filter(s => s.dt == days(1) && s.country == "JP")
  }

  test("write records with INT32 and INT64 partitions") {
    skipInBrowser()
    val root   = newRoot()
    val schema = ParquetSchema
      .builder()
      .required("id", ParquetType.INT64)
      .required("year", ParquetType.INT32)
      .optional("bucket", ParquetType.INT64)
      .optional("name", ParquetType.STRING)
      .build()
    val w = Parquet.newDatasetRecordWriter(
      root.path,
      schema,
      ParquetDatasetWriteOptions.default.withPartitionBy("year", "bucket")
    )
    w.write(Map("id" -> 1L, "year" -> 2025, "bucket" -> 7L, "name" -> "a"))
    w.write("""{"id": 2, "year": 2026, "bucket": 8, "name": "b"}""")
    // A Seq row in schema order, with the partition values inside it
    w.write(Seq(3L, 2026, null, "c"))
    w.close()

    filesUnder(root).map(_.split('/').init.mkString("/")).distinct.sorted shouldBe
      Seq("year=2025/bucket=7", "year=2026/bucket=8", "year=2026/bucket=__HIVE_DEFAULT_PARTITION__")

    val ds = Parquet.dataset(
      root.path,
      ParquetDatasetOptions
        .default
        .withPartitionType("year", ParquetType.INT32)
        .withPartitionType("bucket", ParquetType.INT64)
    )
    try
      val rows = ds.readRows().toSeq.sortBy(_("id").asInstanceOf[Long])
      rows.map(r => (r("id"), r("name"), r("year"), r("bucket"))) shouldBe
        Seq((1L, "a", 2025, 7L), (2L, "b", 2026, 8L), (3L, "c", 2026, null))
      ds.selectFiles(ParquetReadOptions.default.withRowGroupFilter(col("year") === 2026))
        .size shouldBe 2
    finally
      ds.close()
  }

  test("escape partition values and read them back") {
    skipInBrowser()
    val root   = newRoot()
    val schema = ParquetSchema
      .builder()
      .required("id", ParquetType.INT64)
      .optional("tag", ParquetType.STRING)
      .build()
    val tags = Seq("a/b", "x=y%z", "日本", "q?#*", "", null)
    val w    = Parquet.newDatasetRecordWriter(
      root.path,
      schema,
      ParquetDatasetWriteOptions.default.withPartitionBy("tag")
    )
    tags.zipWithIndex.foreach((t, i) => w.write(Map("id" -> i.toLong, "tag" -> t)))
    w.close()

    filesUnder(root).exists(_.startsWith("tag=a%2Fb/")) shouldBe true
    filesUnder(root).exists(_.startsWith("tag=x%3Dy%25z/")) shouldBe true
    filesUnder(root).exists(_.startsWith("tag=日本/")) shouldBe true

    val ds = Parquet.dataset(root.path)
    try
      val got = ds.readRows().toSeq.map(r => r("id").asInstanceOf[Long] -> r("tag")).toMap
      // Empty strings are written as the Hive default partition, so they read back as null
      got shouldBe Map(0L -> "a/b", 1L -> "x=y%z", 2L -> "日本", 3L -> "q?#*", 4L -> null, 5L -> null)
    finally
      ds.close()
  }

  test("split column batches by partition") {
    skipInBrowser()
    val root   = newRoot()
    val schema = ParquetSchema
      .builder()
      .required("id", ParquetType.INT64)
      .required("dt", ParquetType.DATE)
      .optional("tags", ParquetType.list(ParquetType.STRING))
      .build()
    val f   = (n: String) => schema.field(n).get
    val ids = LongColumn(f("id"), Array(0L, 1L, 2L, 3L), null)
    val dts = IntColumn(
      f("dt"),
      dates.map(_.toEpochDay.toInt).flatMap(d => Seq(d, d)).reverse.toArray,
      null
    )
    val tagF = f("tags").asInstanceOf[ParquetField.Group]
    val elem = tagF.children(0).asInstanceOf[ParquetField.Group].children(0)
    val tags = ListColumn(
      tagF,
      Array(0, 1, 1, 3, 4),
      BinaryColumn(elem, Array(0, 1, 2, 3, 4), "abcd".getBytes("UTF-8"), null),
      Array(false, true, false, false)
    )
    val w = Parquet.newDatasetBatchWriter(
      root.path,
      schema,
      ParquetDatasetWriteOptions.default.withPartitionBy("dt")
    )
    w.write(ColumnBatch(4, IndexedSeq(ids, dts, tags)))
    w.close()
    w.writtenFiles.size shouldBe 2

    val ds = Parquet.dataset(
      root.path,
      ParquetDatasetOptions.default.withPartitionType("dt", ParquetType.DATE)
    )
    try
      val rows = ds.readRows().toSeq.sortBy(_("id").asInstanceOf[Long])
      rows.map(r => (r("id"), r("dt"), r("tags"))) shouldBe
        Seq(
          (0L, dates(1), Seq("a")),
          (1L, dates(1), null),
          (2L, dates(0), Seq("b", "c")),
          (3L, dates(0), Seq("d"))
        )
    finally
      ds.close()
  }

  test("roll files by row count") {
    skipInBrowser()
    val root    = newRoot()
    val written = Parquet.writeDataset(
      root.path,
      (0 until 25).map(i => SaleRow(i.toLong, i.toDouble)),
      ParquetDatasetWriteOptions.default.withMaxRowsPerFile(10)
    )
    written.map(_.numRows) shouldBe Seq(10L, 10L, 5L)
    Parquet.dataset(root.path).readAs[SaleRow]().size shouldBe 25
  }

  test("roll batches by row count") {
    skipInBrowser()
    val root   = newRoot()
    val schema = ParquetSchema.of[SaleRow]
    val w      = Parquet.newDatasetBatchWriter(
      root.path,
      schema,
      ParquetDatasetWriteOptions.default.withMaxRowsPerFile(4)
    )
    val n = 10
    w.write(
      ColumnBatch(
        n,
        IndexedSeq(
          LongColumn(schema.field("id").get, Array.tabulate(n)(_.toLong), null),
          DoubleColumn(schema.field("amount").get, Array.tabulate(n)(_.toDouble), null)
        )
      )
    )
    w.close()
    w.writtenFiles.map(_.numRows) shouldBe Seq(4L, 4L, 2L)
  }

  test("roll files by size") {
    skipInBrowser()
    val root    = newRoot()
    val written = Parquet.writeDataset(
      root.path,
      (0 until 5000).map(i => SaleRow(i.toLong, i.toDouble)),
      ParquetDatasetWriteOptions
        .default
        .withMaxFileSize(16 * 1024)
        .withWriteOptions(ParquetWriteOptions.default.withRowGroupSize(4096).noCompression())
    )
    (written.size > 1) shouldBe true
    written.map(_.numRows).sum shouldBe 5000L
    Parquet.dataset(root.path).readAs[SaleRow]().map(_.id).toSeq.sorted shouldBe (0L until 5000L)
  }

  test("finish the least recently written file at the open-file limit") {
    skipInBrowser()
    val root    = newRoot()
    val written = Parquet.writeDataset(
      root.path,
      sales,
      byDtCountry.withMaxOpenFiles(1).withMaxBufferedBytes(1)
    )
    // Rows alternate between partitions, so each switch finishes a file
    (written.size > 4) shouldBe true
    readSales(root)._2 shouldBe sales
  }

  test("fail when the root already holds data") {
    skipInBrowser()
    val root = newRoot()
    Parquet.writeDataset(root.path, sales, byDtCountry)
    val e = intercept[ParquetException] {
      Parquet.writeDataset(root.path, sales, byDtCountry)
    }
    e.getMessage.contains("already holds data files") shouldBe true
    filesUnder(root).size shouldBe 4
  }

  test("ignore files the reader skips when checking the root") {
    skipInBrowser()
    val root = newRoot()
    FileSystem.writeString(root.resolve("_SUCCESS"), "")
    Parquet.writeDataset(root.path, sales, byDtCountry).size shouldBe 4
  }

  test("append files next to existing ones") {
    skipInBrowser()
    val root = newRoot()
    Parquet.writeDataset(root.path, sales, byDtCountry)
    Parquet.writeDataset(root.path, sales, byDtCountry.withMode(ParquetWriteMode.Append))
    filesUnder(root).size shouldBe 8
    readSales(root)._2.size shouldBe 80
  }

  test("refuse to append with different partition keys") {
    skipInBrowser()
    val root = newRoot()
    Parquet.writeDataset(root.path, sales, byDtCountry)
    intercept[ParquetException] {
      Parquet.writeDataset(
        root.path,
        sales,
        ParquetDatasetWriteOptions.default.withPartitionBy("dt").withMode(ParquetWriteMode.Append)
      )
    }
    filesUnder(root).size shouldBe 4
  }

  test("overwrite the whole dataset") {
    skipInBrowser()
    val root = newRoot()
    Parquet.writeDataset(root.path, sales, byDtCountry)
    FileSystem.writeString(root.resolve("_notes"), "")
    val jp = sales.filter(_.country == "JP")
    Parquet.writeDataset(root.path, jp, byDtCountry.withMode(ParquetWriteMode.Overwrite))
    readSales(root)._2 shouldBe jp
    // Files the reader skips are left alone
    allFilesUnder(root).contains("_notes") shouldBe true
  }

  test("overwrite only the partitions a write touches") {
    skipInBrowser()
    val root = newRoot()
    Parquet.writeDataset(root.path, sales, byDtCountry)
    val replacement = Seq(Sale(100L, 1.0, days(1), "JP"))
    Parquet.writeDataset(
      root.path,
      replacement,
      byDtCountry.withMode(ParquetWriteMode.OverwritePartitions)
    )
    val expected = sales.filterNot(s => s.dt == days(1) && s.country == "JP") ++ replacement
    readSales(root)._2 shouldBe expected.sortBy(_.id)
  }

  test("refuse to overwrite a file system root") {
    intercept[IllegalArgumentException] {
      Parquet.newDatasetWriter[Sale]("/", byDtCountry.withMode(ParquetWriteMode.Overwrite))
    }
  }

  test("abort discards every file of the write") {
    skipInBrowser()
    val root = newRoot()
    val w    = Parquet.newDatasetWriter[Sale](root.path, byDtCountry.withMaxRowsPerFile(3))
    w.writeAll(sales)
    (w.writtenFiles.size > 0) shouldBe true
    w.abort()
    allFilesUnder(root) shouldBe Seq.empty
    intercept[ParquetException] {
      w.write(sales.head)
    }
  }

  test("discard written files when a write fails") {
    skipInBrowser()
    val root = newRoot()
    val rows =
      sales.iterator ++ Iterator(sales.head).map(_ => throw IllegalStateException("source failed"))
    intercept[IllegalStateException] {
      Parquet.writeDataset(root.path, rows, byDtCountry.withMaxRowsPerFile(2))
    }
    allFilesUnder(root) shouldBe Seq.empty
  }

  test("reject a row that doesn't fit and keep writing") {
    skipInBrowser()
    val root   = newRoot()
    val schema = ParquetSchema
      .builder()
      .required("id", ParquetType.INT64)
      .required("dt", ParquetType.DATE)
      .build()
    val w = Parquet.newDatasetRecordWriter(
      root.path,
      schema,
      ParquetDatasetWriteOptions.default.withPartitionBy("dt")
    )
    intercept[ParquetException] {
      w.write(Map("id" -> 1L, "dt" -> "not a date"))
    }
    intercept[ParquetException] {
      w.write(Map("id" -> 1L))
    }
    w.write(Map("id" -> 2L, "dt" -> "2026-11-20"))
    w.close()
    filesUnder(root).size shouldBe 1
  }

  test("read partition values from rows in any key order") {
    skipInBrowser()
    val schema = ParquetSchema
      .builder()
      .required("id", ParquetType.INT64)
      .optional("tag", ParquetType.STRING)
      .required("year", ParquetType.INT32)
      .optional("name", ParquetType.STRING)
      .build()
    val rows: Seq[Any] = Seq(
      // In schema order
      Map("id" -> 1L, "tag" -> "a", "year" -> 2025, "name" -> "x"),
      // Keys out of order, so the positional match gives way to lookups by name
      Map("year" -> 2026, "name" -> "y", "id" -> 2L, "tag" -> "b"),
      // A loose key name, and a number for the string column
      Map("id" -> 3L, "Tag" -> 7, "year" -> 2025),
      // Partitions alternate from row to row
      Map("id" -> 4L, "tag" -> "a", "year" -> 2025, "name" -> "z"),
      Map("id" -> 5L, "tag" -> "b", "year" -> 2026),
      Map("id" -> 6L, "tag" -> "a", "year" -> 2025)
    )
    def check(
        options: ParquetDatasetWriteOptions,
        folder: String => String,
        readOptions: ParquetDatasetOptions
    ): Unit =
      val root = newRoot()
      val w    = Parquet.newDatasetRecordWriter(root.path, schema, options)
      rows.foreach(w.write)
      // Past INT32's range: the converter names the column
      intercept[ParquetException] {
        w.write(Map("id" -> 9L, "tag" -> "a", "year" -> (Int.MaxValue.toLong + 1)))
      }.getMessage shouldContain "year"
      w.close()
      // One file per partition, though the rows alternate between them
      filesUnder(root)
        .filter(_.endsWith(".parquet"))
        .map(folder)
        .groupBy(identity)
        .view
        .mapValues(_.size)
        .toMap shouldBe Map("tag=a/year=2025" -> 1, "tag=b/year=2026" -> 1, "tag=7/year=2025" -> 1)
      val ds = Parquet.dataset(root.path, readOptions)
      try
        ds.readRows()
          .toSeq
          .map(r => (r("id"), r("tag"), r("year"), r("name")))
          .sortBy(_._1.asInstanceOf[Long]) shouldBe
          Seq(
            (1L, "a", 2025, "x"),
            (2L, "b", 2026, "y"),
            (3L, "7", 2025, null),
            (4L, "a", 2025, "z"),
            (5L, "b", 2026, null),
            (6L, "a", 2025, null)
          )
      finally ds.close()
    end check

    val byTagYear = ParquetDatasetWriteOptions.default.withPartitionBy("tag", "year")
    check(
      byTagYear,
      _.split('/').init.mkString("/"),
      ParquetDatasetOptions.default.withPartitionType("year", ParquetType.INT32)
    )
    // Iceberg rows keep the partition columns, so the scan stops after the last partition value.
    // Data files go under data/.
    check(
      byTagYear.withTableFormat(ParquetTableFormat.Iceberg),
      _.split('/').drop(1).init.mkString("/"),
      // The table's schema types the partition columns
      ParquetDatasetOptions.default
    )
  }

  test("intern partition strings by their bytes") {
    val interner = DatasetWriterCore.StringInterner()
    val buf      = "xxabcabcxx".getBytes("UTF-8")
    val first    = interner.intern(buf, 2, 3)
    first shouldBe "abc"
    // The same bytes elsewhere give the same instance
    (interner.intern(buf, 5, 3) eq first) shouldBe true
    interner.intern(buf, 2, 2) shouldBe "ab"
    interner.intern(buf, 0, 0) shouldBe ""
    // Past the entry limit, values still decode; earlier entries keep their instances
    val n      = DatasetWriterCore.StringInterner.MaxEntries + 100
    val values = (0 until n).map(i => s"v${i}")
    values.foreach { v =>
      val b = v.getBytes("UTF-8")
      interner.intern(b, 0, b.length) shouldBe v
    }
    values.foreach { v =>
      val b = v.getBytes("UTF-8")
      interner.intern(b, 0, b.length) shouldBe v
    }
    val again = "abc".getBytes("UTF-8")
    (interner.intern(again, 0, 3) eq first) shouldBe true
  }

  test("check partition columns") {
    val schema = ParquetSchema
      .builder()
      .required("id", ParquetType.INT64)
      .required("score", ParquetType.DOUBLE)
      .build()
    def writer(keys: String*) = Parquet.newDatasetRecordWriter(
      "unused-dataset-root",
      schema,
      ParquetDatasetWriteOptions.default.withPartitionBy(keys*)
    )
    intercept[IllegalArgumentException](writer("missing"))
      .getMessage
      .contains("not in the schema") shouldBe true
    intercept[IllegalArgumentException](writer("score"))
      .getMessage
      .contains("STRING, INT32") shouldBe true
    // A file needs a column besides the partition columns
    intercept[IllegalArgumentException] {
      Parquet.newDatasetRecordWriter(
        "unused-dataset-root",
        ParquetSchema.builder().required("dt", ParquetType.STRING).build(),
        ParquetDatasetWriteOptions.default.withPartitionBy("dt")
      )
    }
    intercept[IllegalArgumentException] {
      ParquetDatasetWriteOptions.default.withPartitionBy("_dt")
    }
  }

end DatasetWriterTest
