package wvlet.uni.cloud.parquet

import wvlet.uni.io.FileSystem
import wvlet.uni.io.FileSystemInit
import wvlet.uni.io.IOPath
import wvlet.uni.cloud.parquet.ColumnVector.*
import wvlet.uni.cloud.parquet.format.LogicalType
import wvlet.uni.cloud.parquet.format.PhysicalType
import wvlet.uni.cloud.parquet.format.SortingColumn
import wvlet.uni.test.UniTest

/** Sorting rows within partitions: `ParquetDatasetWriteOptions.withSortBy` */
class DatasetSortTest extends UniTest:
  FileSystemInit.init()

  private var dirs: List[IOPath] = Nil

  override protected def afterAll: Unit = dirs.foreach(FileSystem.deleteRecursively)

  private def newRoot(): IOPath =
    if FileSystem.isBrowser then
      skip("Dataset writers use local directories, which browsers don't have")
    val root = FileSystem.createTempDirectory("uni-parquet-dataset-sort")
    dirs = root :: dirs
    root

  // Ids shuffled, so arrival order is not the sort order
  private val sales =
    for i <- scala.util.Random(42).shuffle((0 until 60).toList)
    yield Sale(
      i.toLong,
      i * 1.5,
      Seq("2026-11-19", "2026-11-20")(i % 2),
      if i % 3 == 0 then
        "JP"
      else
        "US"
    )

  private val byDt = ParquetDatasetWriteOptions.default.withPartitionBy("dt")

  /** The rows of each written file, in file order */
  private def fileRows(written: Seq[WrittenFile]): Seq[(WrittenFile, Seq[Map[String, Any]])] =
    written.map { w =>
      val f = Parquet.open(w.path)
      try (w, f.readRows().toSeq)
      finally f.close()
    }

  private def sortingColumns(path: String): Seq[IndexedSeq[SortingColumn]] =
    val f = Parquet.open(path)
    try f.rowGroups.map(_.sortingColumns)
    finally f.close()

  test("sort each partition's rows and record sorting_columns") {
    val root    = newRoot()
    val written = Parquet.writeDataset(
      root.path,
      sales,
      byDt.withSortBy(ParquetSortKey.desc("amount"))
    )
    written.size shouldBe 2
    fileRows(written).foreach { (w, rows) =>
      val amounts = rows.map(_("amount").asInstanceOf[Double])
      amounts shouldBe amounts.sorted.reverse
      rows.size shouldBe 30
    }
    // The file schema is id, amount, country: amount is leaf 1
    sortingColumns(written.head.path) shouldBe
      Seq(IndexedSeq(SortingColumn(1, descending = true, nullsFirst = false)))
  }

  test("roll files within a run so they cover consecutive ranges") {
    val root    = newRoot()
    val written = Parquet.writeDataset(
      root.path,
      sales,
      byDt.withSortBy("id").withMaxRowsPerFile(7)
    )
    written.map(_.numRows).sum shouldBe 60L
    written
      .groupBy(_.partitions("dt"))
      .values
      .foreach { files =>
        // Files are named in write order, so their ids continue where the previous file ended
        val ids = fileRows(files.sortBy(_.path)).flatMap(_._2.map(_("id").asInstanceOf[Long]))
        ids shouldBe ids.sorted
        ids.size shouldBe 30
      }
  }

  test("sort by several keys with nulls and keep ties in arrival order") {
    val root   = newRoot()
    val schema = ParquetSchema
      .builder()
      .required("seq", ParquetType.INT32)
      .optional("group", ParquetType.STRING)
      .optional("score", ParquetType.DOUBLE)
      .build()
    val rows = Seq(
      (0, "b", 1.0),
      (1, null, 2.0),
      (2, "a", Double.NaN),
      (3, "a", 1.0),
      (4, "b", 1.0),
      (5, "a", 3.0),
      (6, null, 1.0)
    )
    def write(options: ParquetDatasetWriteOptions): Seq[Int] =
      val dir = newRoot()
      val w   = Parquet.newDatasetRecordWriter(dir.path, schema, options)
      rows.foreach((s, g, x) => w.write(Map("seq" -> s, "group" -> g, "score" -> x)))
      w.close()
      fileRows(w.writtenFiles).flatMap(_._2.map(_("seq").asInstanceOf[Int]))

    // Nulls last, NaN after every number, ties in arrival order
    write(ParquetDatasetWriteOptions.default.withSortBy("group", "score")) shouldBe
      Seq(3, 5, 2, 0, 4, 6, 1)
    write(
      ParquetDatasetWriteOptions
        .default
        .withSortBy(ParquetSortKey.desc("group").withNullsFirst(true), ParquetSortKey.asc("score"))
    ) shouldBe Seq(6, 1, 0, 4, 3, 5, 2)
  }

  test("order strings by UTF-8 bytes and unsigned integers as unsigned") {
    val schema = ParquetSchema
      .builder()
      .required("s", ParquetType.STRING)
      .required(
        "u",
        ParquetType.Primitive(PhysicalType.INT32, Some(LogicalType.Integer(32, false)))
      )
      .build()
    // U+FFFF sorts before U+1F600 in UTF-8, but after it in UTF-16 (String.compareTo)
    val strings = Seq("😀", "￿", "a")
    // 0xF0000000 is stored as a negative Int
    val unsigned                     = Seq(0xf0000000L, 1L, 0x7fffffffL)
    def write(key: String): Seq[Any] =
      val dir = newRoot()
      val w   = Parquet.newDatasetRecordWriter(
        dir.path,
        schema,
        ParquetDatasetWriteOptions.default.withSortBy(key)
      )
      strings.zip(unsigned).foreach((s, u) => w.write(Map("s" -> s, "u" -> u)))
      w.close()
      fileRows(w.writtenFiles).flatMap(_._2.map(_(key)))

    write("s") shouldBe Seq("a", "￿", "😀")
    write("u").map(_.asInstanceOf[Long]) shouldBe Seq(1L, 0x7fffffffL, 0xf0000000L)
  }

  test("sort column batches across several batches") {
    val root   = newRoot()
    val schema = ParquetSchema
      .builder()
      .required("id", ParquetType.INT64)
      .required("dt", ParquetType.STRING)
      .optional("tags", ParquetType.list(ParquetType.STRING))
      .build()
    val f    = (n: String) => schema.field(n).get
    val tagF = f("tags").asInstanceOf[ParquetField.Group]
    val elem = tagF.children(0).asInstanceOf[ParquetField.Group].children(0)
    def batch(ids: Seq[Long]): ColumnBatch =
      val n = ids.size
      ColumnBatch(
        n,
        IndexedSeq(
          LongColumn(f("id"), ids.toArray, null),
          BinaryColumn(
            f("dt"),
            Array.tabulate(n + 1)(i => i * 10),
            ids.map(i => s"2026-11-${19 + (i % 2)}").mkString.getBytes("UTF-8"),
            null
          ),
          // Each row's tag is its id, so the test can check tags travel with their rows
          {
            val tags = ids.map(_.toString)
            ListColumn(
              tagF,
              Array.range(0, n + 1),
              BinaryColumn(
                elem,
                tags.scanLeft(0)(_ + _.length).toArray,
                tags.mkString.getBytes("UTF-8"),
                null
              ),
              null
            )
          }
        )
      )
    val w = Parquet.newDatasetBatchWriter(root.path, schema, byDt.withSortBy("id"))
    w.write(batch(Seq(9L, 3L, 4L, 0L)))
    w.write(batch(Seq(7L, 2L, 1L)))
    // Columns in another order: held batches are joined in schema order
    val last = batch(Seq(8L, 5L, 6L))
    w.write(ColumnBatch(last.numRows, last.columns.reverse))
    w.close()
    w.numRows shouldBe 10L
    fileRows(w.writtenFiles).foreach { (wf, rows) =>
      val ids = rows.map(_("id").asInstanceOf[Long])
      ids shouldBe ids.sorted
      rows.map(_("tags")) shouldBe ids.map(i => Seq(i.toString))
    }
    w.writtenFiles.map(_.numRows).sum shouldBe 10L
  }

  test("write the largest partition early when held rows exceed the budget") {
    val root    = newRoot()
    val written = Parquet.writeDataset(
      root.path,
      sales,
      byDt.withSortBy("id").withMaxBufferedBytes(300)
    )
    written.map(_.numRows).sum shouldBe 60L
    // More than one run per partition, and every file still sorted
    (written.size > 2) shouldBe true
    fileRows(written).foreach { (_, rows) =>
      val ids = rows.map(_("id").asInstanceOf[Long])
      ids shouldBe ids.sorted
    }
    val ds = Parquet.dataset(root.path)
    try ds.readAs[Sale]().toSeq.sortBy(_.id) shouldBe sales.sortBy(_.id)
    finally ds.close()
  }

  test("check sort columns when the writer is created") {
    val root   = newRoot()
    val schema = ParquetSchema
      .builder()
      .required("id", ParquetType.INT64)
      .required("dt", ParquetType.STRING)
      .optional("tags", ParquetType.list(ParquetType.STRING))
      .optional(
        "half",
        ParquetType.Primitive(PhysicalType.FIXED_LEN_BYTE_ARRAY, Some(LogicalType.Float16), 2)
      )
      .build()
    def fails(options: ParquetDatasetWriteOptions): String =
      intercept[IllegalArgumentException] {
        Parquet.newDatasetRecordWriter(root.path, schema, options).abort()
      }.getMessage

    fails(byDt.withSortBy("nope")) shouldContain "not a column of the files"
    fails(byDt.withSortBy("dt")) shouldContain "is a partition column"
    fails(byDt.withSortBy("tags")) shouldContain "top-level primitive"
    fails(byDt.withSortBy("half")) shouldContain "no defined sort order"
    intercept[IllegalArgumentException] {
      byDt.withSortBy("id", "id")
    }
  }

  test("reject a row that doesn't fit when it is written") {
    val root   = newRoot()
    val schema = ParquetSchema
      .builder()
      .required("id", ParquetType.INT64)
      .required("name", ParquetType.STRING)
      .build()
    val w = Parquet.newDatasetRecordWriter(
      root.path,
      schema,
      ParquetDatasetWriteOptions.default.withSortBy("id")
    )
    w.write(Map("id" -> 2L, "name" -> "b"))
    intercept[ParquetException] {
      w.write(Map("id" -> 1L))
    }
    w.write(Map("id" -> 1L, "name" -> "a"))
    w.close()
    fileRows(w.writtenFiles).flatMap(_._2.map(_("name"))) shouldBe Seq("a", "b")
  }

end DatasetSortTest
