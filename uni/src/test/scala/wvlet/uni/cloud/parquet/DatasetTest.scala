package wvlet.uni.cloud.parquet

import wvlet.uni.io.FileSystem
import wvlet.uni.io.FileSystemInit
import wvlet.uni.io.IOPath
import wvlet.uni.cloud.parquet.ParquetFilter.*
import wvlet.uni.test.UniTest
import wvlet.uni.weaver.Weaver

import java.time.LocalDate

case class Visit(id: Long, user: String) derives Weaver
case class VisitV2(id: Long, user: String, durationMs: Option[Long]) derives Weaver
case class VisitPaid(id: Long, user: String, paid: Option[BigDecimal]) derives Weaver
case class VisitWithDt(id: Long, user: String, dt: String, country: String) derives Weaver
case class VisitTyped(id: Long, year: Long) derives Weaver
case class VisitOtherType(id: String, user: String) derives Weaver
case class VisitIntId(id: Int, user: String) derives Weaver
case class VisitShortId(id: Short, user: String) derives Weaver
case class Score(id: Long, value: Float) derives Weaver
case class ScoreDouble(id: Long, value: Double) derives Weaver
case class ScoreInt(id: Long, value: Int) derives Weaver
case class ScoreLong(id: Long, value: Long) derives Weaver

class DatasetTest extends UniTest:
  FileSystemInit.init()

  private var dirs: List[IOPath] = Nil

  override protected def afterAll: Unit = dirs.foreach(FileSystem.deleteRecursively)

  /** A new temp directory with Parquet files at the given relative paths */
  private def datasetDir(files: (String, Array[Byte])*): IOPath =
    val root = FileSystem.createTempDirectory("uni-parquet-dataset")
    dirs = root :: dirs
    files.foreach { (rel, bytes) =>
      val path = root.resolve(rel)
      mkdirs(path.parent.get)
      FileSystem.writeBytes(path, bytes)
    }
    root

  private def mkdirs(p: IOPath): Unit =
    if !FileSystem.exists(p) then
      p.parent.foreach(mkdirs)
      FileSystem.createDirectory(p)

  private def visits(ids: Range, user: String): Array[Byte] = Parquet.writeBytes(
    ids.map(i => Visit(i.toLong, s"${user}-${i}"))
  )

  private def withDataset[A](
      root: IOPath,
      options: ParquetDatasetOptions = ParquetDatasetOptions.default
  )(body: ParquetDataset => A): A =
    val ds = Parquet.dataset(root.path, options)
    try body(ds)
    finally ds.close()

  // The path below the root, with `/` separators on every platform
  private def relativePath(ds: ParquetDataset, f: DatasetFile): String = f
    .path
    .stripPrefix(ds.root)
    .replace('\\', '/')

  private def skipInBrowser(): Unit =
    if FileSystem.isBrowser then
      skip("Datasets list local directories, which browsers don't have")

  private val lake = Seq(
    "dt=2026-11-19/country=JP/part-0.parquet" -> visits(0 until 3, "a"),
    "dt=2026-11-19/country=US/part-0.parquet" -> visits(3 until 5, "b"),
    "dt=2026-11-20/country=JP/part-0.parquet" -> visits(5 until 9, "c"),
    "dt=2026-11-20/country=JP/000001_0"       -> visits(9 until 10, "d"),
    "dt=2026-11-20/_SUCCESS"                  -> Array.emptyByteArray,
    "dt=2026-11-20/country=JP/.part-0.crc"    -> Array[Byte](1, 2, 3),
    "_temporary/0/part-9.parquet"             -> visits(100 until 101, "tmp")
  )

  test("find files and partition columns") {
    skipInBrowser()
    withDataset(datasetDir(lake*)) { ds =>
      ds.files.map(relativePath(ds, _)) shouldBe
        Seq(
          "dt=2026-11-19/country=JP/part-0.parquet",
          "dt=2026-11-19/country=US/part-0.parquet",
          "dt=2026-11-20/country=JP/000001_0",
          "dt=2026-11-20/country=JP/part-0.parquet"
        )
      ds.partitionColumns.map(_.name) shouldBe Seq("dt", "country")
      ds.files.head.partitions shouldBe Map("dt" -> "2026-11-19", "country" -> "JP")
      ds.schema.fields.map(_.name) shouldBe Seq("id", "user", "dt", "country")
    }
  }

  test("read rows with partition values as columns") {
    skipInBrowser()
    withDataset(datasetDir(lake*)) { ds =>
      val rows = ds.readAs[VisitWithDt]().toSeq
      // Files are read in path order, so 000001_0 (id 9) comes before part-0 (ids 5 to 8)
      rows.map(_.id) shouldBe Seq(0L, 1L, 2L, 3L, 4L, 9L, 5L, 6L, 7L, 8L)
      rows.find(_.id == 4).get shouldBe VisitWithDt(4, "b-4", "2026-11-19", "US")
      ds.readRows().next() shouldBe
        Map("id" -> 0L, "user" -> "a-0", "dt" -> "2026-11-19", "country" -> "JP")
    }
  }

  test("skip files by partition values") {
    skipInBrowser()
    withDataset(datasetDir(lake*)) { ds =>
      val opts = ParquetReadOptions
        .default
        .withRowGroupFilter(col("dt") >= "2026-11-20" && col("country") === "JP")
      ds.selectFiles(opts).size shouldBe 2
      ds.readAs[Visit](opts).map(_.id).toSeq shouldBe Seq(9L, 5L, 6L, 7L, 8L)

      // A filter on a file column still reads every partition
      val byId = ParquetReadOptions.default.withRowGroupFilter(col("id") > 100L)
      ds.selectFiles(byId).size shouldBe 4

      // OR with a file column keeps a file whose partition doesn't match
      val either = ParquetReadOptions
        .default
        .withRowGroupFilter(col("country") === "US" || col("id") >= 9L)
      ds.selectFiles(either).size shouldBe 4
      ds.readAs[Visit](either).size shouldBe 3
    }
  }

  test("read only partition columns") {
    skipInBrowser()
    withDataset(datasetDir(lake*)) { ds =>
      val rows = ds.readRows(ParquetReadOptions.default.withColumns("country", "id")).toSeq
      rows.size shouldBe 10
      rows.head.keys.toSeq shouldBe Seq("country", "id")
      ds.readRows(ParquetReadOptions.default.withColumns("dt")).map(_("dt")).toSeq.distinct shouldBe
        Seq("2026-11-19", "2026-11-20")
    }
  }

  test("decode escaped and default partition values") {
    skipInBrowser()
    val root = datasetDir(
      "city=New%20York/part-0.parquet"                 -> visits(0 until 1, "a"),
      "city=__HIVE_DEFAULT_PARTITION__/part-0.parquet" -> visits(1 until 2, "b")
    )
    withDataset(root) { ds =>
      ds.readRows().map(_("city")).toSeq shouldBe Seq("New York", null)
      ds.selectFiles(ParquetReadOptions.default.withRowGroupFilter(col("city").isNull))
        .size shouldBe 1
      // A comparison with null is never true
      ds.selectFiles(ParquetReadOptions.default.withRowGroupFilter(col("city") < "Z")).size shouldBe
        1
    }
  }

  test("declare partition types") {
    skipInBrowser()
    val root = datasetDir(
      "year=2025/day=2025-12-31/part-0.parquet" -> visits(0 until 2, "a"),
      "year=2026/day=2026-01-01/part-0.parquet" -> visits(2 until 4, "b")
    )
    val options = ParquetDatasetOptions
      .default
      .withPartitionType("year", ParquetType.INT64)
      .withPartitionType("day", ParquetType.DATE)
    withDataset(root, options) { ds =>
      ds.readAs[VisitTyped]().toSeq.last shouldBe VisitTyped(3, 2026L)
      ds.readRows().toSeq.last("day") shouldBe LocalDate.of(2026, 1, 1)
      ds.selectFiles(ParquetReadOptions.default.withRowGroupFilter(col("year") >= 2026))
        .size shouldBe 1
      // Lossless conversions of the literal
      ds.selectFiles(ParquetReadOptions.default.withRowGroupFilter(col("year") === "2025"))
        .size shouldBe 1
      ds.selectFiles(ParquetReadOptions.default.withRowGroupFilter(col("day") < "2026-01-01"))
        .size shouldBe 1
      intercept[IllegalArgumentException] {
        ds.selectFiles(ParquetReadOptions.default.withRowGroupFilter(col("year") === 2026.5))
      }
    }
    intercept[IllegalArgumentException] {
      Parquet.dataset(
        root.path,
        ParquetDatasetOptions.default.withPartitionType("month", ParquetType.INT64)
      )
    }
    intercept[IllegalArgumentException] {
      ParquetDatasetOptions.default.withPartitionType("year", ParquetType.DOUBLE)
    }
  }

  test("reject literals that don't match a STRING partition") {
    skipInBrowser()
    withDataset(datasetDir(lake*)) { ds =>
      val e = intercept[IllegalArgumentException] {
        ds.selectFiles(ParquetReadOptions.default.withRowGroupFilter(col("dt") >= 2026))
      }
      e.getMessage.contains("withPartitionType") shouldBe true
    }
  }

  test("tell plain files of folders: a partitioned folder's files lack its partition values") {
    skipInBrowser()
    withDataset(datasetDir(lake*)) { ds =>
      ds.files.map(ds.readsAsPlainFile).toSet shouldBe Set(false)
    }
    val plain = datasetDir(
      "a.parquet" -> visits(0 until 2, "a"),
      "b.parquet" -> visits(2 until 4, "b")
    )
    withDataset(plain) { ds =>
      ds.files.map(ds.readsAsPlainFile).toSet shouldBe Set(true)
      intercept[IllegalArgumentException](
        ds.readsAsPlainFile(
          DatasetFile("other.parquet", 0L, scala.collection.immutable.VectorMap.empty)
        )
      )
    }
  }

  test("read columns added in newer files as nulls in older ones") {
    skipInBrowser()
    val root = datasetDir(
      "dt=2026-11-19/part-0.parquet" -> visits(0 until 2, "a"),
      "dt=2026-11-20/part-0.parquet" -> Parquet.writeBytes(Seq(VisitV2(2, "b", Some(150L))))
    )
    withDataset(root) { ds =>
      ds.schema.field("durationMs").get.isOptional shouldBe true
      ds.readAs[VisitV2]().toSeq shouldBe
        Seq(VisitV2(0, "a-0", None), VisitV2(1, "a-1", None), VisitV2(2, "b", Some(150L)))
      // Pruning to the old file leaves only its columns, plus partitions
      val old = ParquetReadOptions.default.withRowGroupFilter(col("dt") === "2026-11-19")
      ds.readRows(old).next().keys.toSeq shouldBe Seq("id", "user", "dt")
    }
  }

  test("read INT32 decimals as Long values in files with and without the column") {
    skipInBrowser()
    val root = datasetDir(
      "a.parquet" -> visits(0 until 2, "a"),
      "b.parquet" ->
        Parquet.writeBytes(
          Seq(VisitPaid(2, "b", Some(BigDecimal("1.25")))),
          ParquetWriteOptions.default.withDecimal(7, 2)
        )
    )
    withDataset(root) { ds =>
      val opts    = ParquetReadOptions.default.withInt32DecimalAsLong(true)
      val batches = ds.readBatches(opts).toSeq
      // The file lacking the column gives nulls of the same vector type
      batches.foreach(b =>
        b.column("paid") shouldMatch { case _: ColumnVector.LongColumn =>
        }
      )
      ds.readAs[VisitPaid](opts).toSeq.sortBy(_.id) shouldBe
        Seq(
          VisitPaid(0, "a-0", None),
          VisitPaid(1, "a-1", None),
          VisitPaid(2, "b", Some(BigDecimal("1.25")))
        )
    }
  }

  test("fail on a column whose type differs between files") {
    skipInBrowser()
    val root = datasetDir(
      "a.parquet" -> visits(0 until 1, "a"),
      "b.parquet" -> Parquet.writeBytes(Seq(VisitOtherType("x", "b")))
    )
    withDataset(root) { ds =>
      val e = intercept[ParquetException](ds.readRows().toSeq)
      e.getMessage.contains("a.parquet") shouldBe true
      e.getMessage.contains("b.parquet") shouldBe true
      // Not selecting the column avoids the conflict
      ds.readRows(ParquetReadOptions.default.withColumns("user")).size shouldBe 2
    }
  }

  test("filter on a column the read doesn't select") {
    skipInBrowser()
    withDataset(datasetDir(lake*)) { ds =>
      val opts = ParquetReadOptions.default.withColumns("user").withRowGroupFilter(col("id") >= 9L)
      ds.readRows(opts).map(_("user")).toSeq shouldBe Seq("d-9")
    }
  }

  test("fail on unknown column names before reading rows") {
    skipInBrowser()
    withDataset(datasetDir(lake*)) { ds =>
      intercept[IllegalArgumentException] {
        ds.readRows(ParquetReadOptions.default.withRowGroupFilter(col("contry") === "JP"))
      }
      intercept[IllegalArgumentException] {
        ds.readRows(ParquetReadOptions.default.withColumns("usr"))
      }
    }
  }

  test("prefer the folder value over a file column with the same name") {
    skipInBrowser()
    val root = datasetDir("user=folder/part-0.parquet" -> visits(0 until 2, "file"))
    withDataset(root) { ds =>
      ds.schema.fields.map(_.name) shouldBe Seq("id", "user")
      ds.readAs[Visit]().map(_.user).toSeq shouldBe Seq("folder", "folder")
    }
  }

  test("fail when partition folders differ") {
    skipInBrowser()
    val root = datasetDir(
      "dt=2026-11-19/part-0.parquet"            -> visits(0 until 1, "a"),
      "dt=2026-11-20/country=JP/part-0.parquet" -> visits(1 until 2, "b")
    )
    intercept[ParquetException](Parquet.dataset(root.path))
  }

  test("fail when no files are found") {
    skipInBrowser()
    val root = datasetDir("_SUCCESS" -> Array.emptyByteArray)
    intercept[ParquetException](Parquet.dataset(root.path))
  }

  test("keep only files with an extension") {
    skipInBrowser()
    withDataset(datasetDir(lake*), ParquetDatasetOptions.default.withFileExtension(".parquet")) {
      ds =>
        ds.files.size shouldBe 3
    }
  }

  test("split batches across files") {
    skipInBrowser()
    withDataset(datasetDir(lake*)) { ds =>
      val batches = ds.readBatches(ParquetReadOptions.default.withBatchSize(2)).toSeq
      batches.map(_.numRows) shouldBe Seq(2, 1, 2, 1, 2, 2)
      batches.head.columnNames shouldBe Seq("id", "user", "dt", "country")
    }
  }

  test("read asynchronously") {
    skipInBrowser()
    val root = datasetDir(lake*)
    val opts = ParquetReadOptions.default.withRowGroupFilter(col("country") === "JP")
    // The test returns the Rx, so it also runs on Scala.js, where await is unavailable
    Parquet
      .datasetAsync(root.path, ParquetDatasetOptions.default.withPrefetch(2))
      .flatMap { ds =>
        ds.readAllAsync[VisitWithDt](opts)
          .flatMap { jp =>
            jp.map(_.id) shouldBe Seq(0L, 1L, 2L, 9L, 5L, 6L, 7L, 8L)
            ds.readAllRowsAsync()
          }
          .flatMap { all =>
            all.size shouldBe 10
            ds.schemaAsync
          }
          .map { schema =>
            schema.fields.map(_.name) shouldBe Seq("id", "user", "dt", "country")
          }
          .tapOn { case _ =>
            ds.close()
          }
      }
  }

  test("name the file that fails to read") {
    skipInBrowser()
    val root = datasetDir(
      "a.parquet" -> visits(0 until 1, "a"),
      "b.parquet" -> "not parquet".getBytes("UTF-8")
    )
    withDataset(root) { ds =>
      val e = intercept[ParquetException](ds.readRows().toSeq)
      e.getMessage.contains("b.parquet") shouldBe true
    }
  }

  test("list object prefixes with a trailing slash") {
    ParquetDataset.objectRoot("s3://bucket/events").toString shouldBe "s3://bucket/events/"
    ParquetDataset.objectRoot("s3://bucket/events/").toString shouldBe "s3://bucket/events/"
    ParquetDataset.objectRoot("s3://bucket").toString shouldBe "s3://bucket/"
  }

  test("build null vectors for nested columns") {
    val schema = ParquetSchema.of[Order]
    schema
      .fields
      .foreach { f =>
        val v = ParquetDataset.nullVector(f, 3)
        v.size shouldBe 3
        (0 until 3).foreach { i =>
          val value = v.getValue(i)
          (value == null || value == Seq.empty) shouldBe true
        }
      }
  }

  private def globFiles(root: IOPath, pattern: String): Seq[String] =
    val ds = Parquet.dataset(s"${root.path}/${pattern}")
    try ds.files.map(relativePath(ds, _))
    finally ds.close()

  test("read files that match a glob pattern") {
    skipInBrowser()
    val root = datasetDir(lake*)
    globFiles(root, "dt=2026-11-2*/") shouldBe
      Seq("dt=2026-11-20/country=JP/000001_0", "dt=2026-11-20/country=JP/part-0.parquet")
    globFiles(root, "dt=*/country=US") shouldBe Seq("dt=2026-11-19/country=US/part-0.parquet")
    globFiles(root, "**/*.parquet").size shouldBe 3
    globFiles(root, "dt=2026-11-1?/country=[JU]*/part-0.parquet").size shouldBe 2
    globFiles(root, "dt=2026-11-{19,20}/country=[!U]*").size shouldBe 3
    // Hidden and underscore paths stay skipped, even when the pattern matches them
    globFiles(root, "**").size shouldBe 4

    // Partition columns are still parsed below the listed root, and pruning still applies
    val ds = Parquet.dataset(s"${root.path}/dt=*/country=JP/*.parquet")
    try
      ds.partitionColumns.map(_.name) shouldBe Seq("dt", "country")
      val opts = ParquetReadOptions.default.withRowGroupFilter(col("dt") === "2026-11-20")
      ds.readAs[Visit](opts).map(_.id).toSeq shouldBe Seq(5L, 6L, 7L, 8L)
    finally
      ds.close()

    val e = intercept[ParquetException](globFiles(root, "dt=2027-*"))
    e.getMessage.contains("matching") shouldBe true
  }

  test("a glob below a Windows extended-length path") {
    skipInBrowser()
    val root = datasetDir(lake*)
    // `?` in the `\\?\` prefix isn't a glob character
    if ParquetDataset.isWindows then
      val ds = Parquet.dataset(s"\\\\?\\${root.path}\\dt=*\\country=US")
      try ds.files.map(relativePath(ds, _)) shouldBe Seq("dt=2026-11-19/country=US/part-0.parquet")
      finally ds.close()
  }

  test("escape glob characters") {
    // The listed base has its escapes removed; the pattern keeps them
    ParquetDataset.splitGlob("s3://b/a\\*b/c*") shouldBe Some(("s3://b/a*b/", "c*"))
    intercept[IllegalArgumentException](ParquetDataset.splitGlob("s3://logs-*/x"))
    // A set never matches the folder separator
    ParquetDataset.globMatcher("x[!a]y")(Seq("x", "y")) shouldBe false
    ParquetDataset.globMatcher("x[!a]y")(Seq("xby")) shouldBe true
    ParquetDataset.splitGlob("s3://b/events/") shouldBe None
    val m = ParquetDataset.globMatcher("a\\*b/x.parquet")
    m(Seq("a*b", "x.parquet")) shouldBe true
    m(Seq("aXb", "x.parquet")) shouldBe false
    ParquetDataset.globMatcher("k=a.b")(Seq("k=aXb", "f")) shouldBe false
    intercept[IllegalArgumentException](ParquetDataset.globMatcher("{a,b"))
  }

  test("read an explicit list of files") {
    skipInBrowser()
    val root  = datasetDir(lake*)
    val files = Seq(
      "dt=2026-11-20/country=JP/part-0.parquet",
      "dt=2026-11-19/country=US/part-0.parquet",
      "dt=2026-11-19/country=US/part-0.parquet"
    ).map(f => root.resolve(f).path)
    val ds = Parquet.dataset(files)
    try
      ds.files.size shouldBe 2
      ds.partitionColumns.map(_.name) shouldBe Seq("dt", "country")
      ds.readAs[Visit]().map(_.id).toSeq shouldBe Seq(3L, 4L, 5L, 6L, 7L, 8L)
    finally
      ds.close()

    // Files in one folder have no partitions; files the caller names are read even when hidden
    val one = Parquet.dataset(Seq(root.resolve("_temporary/0/part-9.parquet").path))
    try
      one.partitionColumns shouldBe IndexedSeq.empty
      one.readAs[Visit]().map(_.id).toSeq shouldBe Seq(100L)
    finally
      one.close()

    intercept[ParquetException](Parquet.dataset(Seq(root.resolve("missing.parquet").path)))
    intercept[IllegalArgumentException](Parquet.dataset(Seq.empty[String]))
    intercept[IllegalArgumentException] {
      Parquet.dataset(Seq(files.head, "s3://bucket/x.parquet"))
    }
  }

  test("list objects of one bucket") {
    val (root, listed) = ParquetDataset.listedObjects(
      Seq("s3://b/t/dt=1/a.parquet", "s3://b/t/dt=2/b.parquet").map(
        wvlet.uni.cloud.storage.ObjectUri.parse
      )
    )
    root.toString shouldBe "s3://b/t/"
    listed.map(_.segments) shouldBe Seq(Seq("dt=1", "a.parquet"), Seq("dt=2", "b.parquet"))
    intercept[IllegalArgumentException] {
      ParquetDataset.listedObjects(
        Seq("s3://b/a.parquet", "s3://c/b.parquet").map(wvlet.uni.cloud.storage.ObjectUri.parse)
      )
    }
  }

  test("prefetch footers on sync reads") {
    skipInBrowser()
    val many = (0 until 12).map(i => s"part=${i}/f.parquet" -> visits(i * 2 until i * 2 + 2, "p"))
    val root = datasetDir(many*)
    val seq  =
      withDataset(root, ParquetDatasetOptions.default.withPrefetch(1))(_.readAs[Visit]().toSeq)
    val par =
      withDataset(root, ParquetDatasetOptions.default.withPrefetch(8))(_.readAs[Visit]().toSeq)
    par shouldBe seq
    par.size shouldBe 24
  }

  test("widen integer columns across files") {
    skipInBrowser()
    val root = datasetDir(
      "a/old.parquet"   -> Parquet.writeBytes(Seq(VisitIntId(1, "a"), VisitIntId(2, "b"))),
      "b/new.parquet"   -> visits(3 until 5, "c"),
      "c/small.parquet" -> Parquet.writeBytes(Seq(VisitShortId(5.toShort, "d")))
    )
    withDataset(root) { ds =>
      ds.schema.field("id").get.asInstanceOf[ParquetField.Primitive].physicalType shouldBe
        format.PhysicalType.INT64
      ds.readAs[Visit]().map(_.id).toSeq shouldBe Seq(1L, 2L, 3L, 4L, 5L)
      ds.readRows().map(_("id")).toSeq shouldBe Seq(1L, 2L, 3L, 4L, 5L)
      ds.readBatches().forall(_.column("id").isInstanceOf[ColumnVector.LongColumn]) shouldBe true
      // An integer filter compares against both widths
      ds.readAs[Visit](ParquetReadOptions.default.withRowGroupFilter(col("id") >= 3L))
        .map(_.id)
        .toSeq shouldBe Seq(3L, 4L, 5L)
    }
  }

  test("widen FLOAT and INT32 columns to DOUBLE") {
    skipInBrowser()
    val root = datasetDir(
      "1.parquet" -> Parquet.writeBytes(Seq(ScoreDouble(1L, 0.25))),
      "2.parquet" -> Parquet.writeBytes(Seq(Score(2L, 1.5f))),
      "3.parquet" -> Parquet.writeBytes(Seq(ScoreInt(3L, 7)))
    )
    withDataset(root) { ds =>
      ds.readAs[ScoreDouble]().toSeq shouldBe
        Seq(ScoreDouble(1L, 0.25), ScoreDouble(2L, 1.5), ScoreDouble(3L, 7.0))
    }
  }

  test("keep type errors for changes that can lose values") {
    skipInBrowser()
    // INT64 doesn't widen to DOUBLE: values above 2^53 would round
    val root = datasetDir(
      "1.parquet" -> Parquet.writeBytes(Seq(ScoreDouble(1L, 0.5))),
      "2.parquet" -> Parquet.writeBytes(Seq(Visit(2L, "x")).map(v => ScoreLong(v.id, v.id)))
    )
    withDataset(root) { ds =>
      intercept[ParquetException](ds.schema).getMessage.contains("different types") shouldBe true
    }
  }

end DatasetTest
