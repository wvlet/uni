package wvlet.uni.cloud.parquet

import wvlet.uni.io.FileSystem
import wvlet.uni.io.FileSystemInit
import wvlet.uni.io.IOPath
import wvlet.uni.cloud.parquet.ParquetFilter.*
import wvlet.uni.cloud.parquet.format.LogicalType
import wvlet.uni.test.UniTest

import java.time.LocalDate

class DatasetMetadataTest extends UniTest:
  FileSystemInit.init()

  private var dirs: List[IOPath] = Nil

  override protected def afterAll: Unit = dirs.foreach(FileSystem.deleteRecursively)

  private def newRoot(): IOPath =
    val root = FileSystem.createTempDirectory("uni-parquet-dataset-metadata")
    dirs = root :: dirs
    root

  private def skipInBrowser(): Unit =
    if FileSystem.isBrowser then
      skip("Dataset writers use local directories, which browsers don't have")

  private val schema = ParquetSchema
    .builder()
    .required("id", ParquetType.INT64)
    .required("dt", ParquetType.DATE)
    .required("hour", ParquetType.INT32)
    .build()

  private val day1 = LocalDate.of(2026, 11, 20)
  private val day2 = LocalDate.of(2026, 11, 21)

  private val rows = (0 until 8).map(i =>
    Map(
      "id" -> i.toLong,
      "dt" -> (
        if i % 2 == 0 then
          day1
        else
          day2
      ),
      "hour" -> (i % 3)
    )
  )

  private val byDtHour = ParquetDatasetWriteOptions.default.withPartitionBy("dt", "hour")

  private def write(
      root: IOPath,
      options: ParquetDatasetWriteOptions = byDtHour,
      records: Seq[Any] = rows,
      writeSchema: ParquetSchema = schema
  ): Unit =
    val w = Parquet.newDatasetRecordWriter(root.path, writeSchema, options)
    try w.writeAll(records)
    finally w.close()

  private def rootFiles(root: IOPath): Seq[String] =
    FileSystem.list(root).map(_.fileName).filter(_.startsWith("_")).sorted

  private def types(root: IOPath, options: ParquetDatasetOptions = ParquetDatasetOptions.default) =
    val ds = Parquet.dataset(root.path, options)
    try ds.partitionColumns.map(c => c.name -> c.logicalType.orElse(Some(c.physicalType)))
    finally ds.close()

  private val recorded = IndexedSeq(
    "dt"   -> Some(LogicalType.Date),
    "hour" -> Some(format.PhysicalType.INT32)
  )

  test("read the partition types the writer recorded") {
    skipInBrowser()
    val root = newRoot()
    write(root)
    rootFiles(root) shouldBe Seq("_SUCCESS", "_common_metadata")
    FileSystem.readBytes(root.resolve("_SUCCESS")).length shouldBe 0
    types(root) shouldBe recorded

    val ds = Parquet.dataset(root.path)
    try
      val opts = ParquetReadOptions
        .default
        .withRowGroupFilter(col("dt") === day2 && col("hour") === 1)
      ds.selectFiles(opts).size shouldBe 1
      ds.readRows(opts).map(r => (r("id"), r("dt"), r("hour"))).toSeq.sortBy(_._1.toString) shouldBe
        Seq((1L, day2, 1), (7L, day2, 1))
    finally
      ds.close()
  }

  test("declared types win, and the hint can be ignored") {
    skipInBrowser()
    val root = newRoot()
    write(root)
    types(
      root,
      ParquetDatasetOptions.default.withPartitionType("hour", ParquetType.STRING)
    ) shouldBe IndexedSeq("dt" -> Some(LogicalType.Date), "hour" -> Some(LogicalType.String))
    types(root, ParquetDatasetOptions.default.withReadCommonMetadata(false)) shouldBe
      IndexedSeq("dt" -> Some(LogicalType.String), "hour" -> Some(LogicalType.String))
  }

  test("read the hint for a glob pattern") {
    skipInBrowser()
    val root = newRoot()
    write(root)
    val ds = Parquet.dataset(s"${root.path}/dt=2026-11-2*/hour=1")
    try
      ds.partitionColumns.map(_.logicalType) shouldBe IndexedSeq(Some(LogicalType.Date), None)
      ds.readRows().size shouldBe rows.count(_("hour") == 1)
    finally
      ds.close()
  }

  test("write no root files when turned off") {
    skipInBrowser()
    val root = newRoot()
    write(root, byDtHour.withWriteSuccessMarker(false).withWriteCommonMetadata(false))
    rootFiles(root) shouldBe Seq.empty
    types(root) shouldBe
      IndexedSeq("dt" -> Some(LogicalType.String), "hour" -> Some(LogicalType.String))
  }

  test("append keeps a matching hint and rejects different types") {
    skipInBrowser()
    val root = newRoot()
    write(root)
    write(root, byDtHour.withMode(ParquetWriteMode.Append))
    types(root) shouldBe recorded

    val asString = ParquetSchema
      .builder()
      .required("id", ParquetType.INT64)
      .required("dt", ParquetType.STRING)
      .required("hour", ParquetType.INT32)
      .build()
    val before = FileSystem.list(root, wvlet.uni.io.ListOptions.default.withRecursive(true)).size
    val e      = intercept[ParquetException] {
      write(
        root,
        byDtHour.withMode(ParquetWriteMode.Append),
        Seq(Map("id" -> 9L, "dt" -> "2026-11-22", "hour" -> 0)),
        asString
      )
    }
    e.getMessage shouldContain "records partitions (dt DATE"
    FileSystem.list(root, wvlet.uni.io.ListOptions.default.withRecursive(true)).size shouldBe before
  }

  test("append to folders another tool wrote adds no hint") {
    skipInBrowser()
    val root = newRoot()
    write(root, byDtHour.withWriteCommonMetadata(false).withWriteSuccessMarker(false))
    write(root, byDtHour.withMode(ParquetWriteMode.Append))
    rootFiles(root) shouldBe Seq("_SUCCESS")
  }

  test("overwrite replaces stale root files") {
    skipInBrowser()
    val root = newRoot()
    write(root)
    write(
      root,
      byDtHour
        .withMode(ParquetWriteMode.Overwrite)
        .withWriteSuccessMarker(false)
        .withWriteCommonMetadata(false)
    )
    rootFiles(root) shouldBe Seq.empty

    write(root, byDtHour.withMode(ParquetWriteMode.Overwrite))
    write(
      root,
      byDtHour.withMode(ParquetWriteMode.OverwritePartitions).withWriteSuccessMarker(false)
    )
    rootFiles(root) shouldBe Seq("_common_metadata")
  }

  test("an aborted write leaves no root files") {
    skipInBrowser()
    val root = newRoot()
    val w    = Parquet.newDatasetRecordWriter(root.path, schema, byDtHour)
    w.writeAll(rows)
    w.abort()
    rootFiles(root) shouldBe Seq.empty
  }

  test("read a write without rows as an empty dataset") {
    skipInBrowser()
    val root = newRoot()
    write(root, records = Seq.empty)
    rootFiles(root) shouldBe Seq("_SUCCESS", "_common_metadata")
    val ds = Parquet.dataset(root.path)
    try
      ds.files shouldBe IndexedSeq.empty
      ds.schema.fields.map(_.name) shouldBe Seq("id", "dt", "hour")
      ds.partitionColumns.map(_.name) shouldBe IndexedSeq("dt", "hour")
      ds.readRows().toSeq shouldBe Seq.empty
    finally
      ds.close()
    // Without the hint, an empty root is still an error
    intercept[ParquetException] {
      Parquet.dataset(root.path, ParquetDatasetOptions.default.withReadCommonMetadata(false))
    }
  }

  test("a glob that matches nothing is still an error") {
    skipInBrowser()
    val root = newRoot()
    write(root)
    intercept[ParquetException] {
      Parquet.dataset(s"${root.path}/dt=2099-*")
    }
    intercept[ParquetException] {
      Parquet.dataset(root.path, ParquetDatasetOptions.default.withFileExtension(".pq"))
    }
  }

  test("ignore an unreadable hint") {
    skipInBrowser()
    val root = newRoot()
    write(root)
    FileSystem.writeBytes(root.resolve("_common_metadata"), Array[Byte](1, 2, 3))
    types(root) shouldBe
      IndexedSeq("dt" -> Some(LogicalType.String), "hour" -> Some(LogicalType.String))
    // An overwrite replaces it
    write(root, byDtHour.withMode(ParquetWriteMode.Overwrite))
    types(root) shouldBe recorded
  }

end DatasetMetadataTest
