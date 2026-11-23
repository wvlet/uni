package wvlet.uni.cloud.iceberg

import wvlet.uni.cloud.parquet.*

import wvlet.uni.io.FileSystem
import wvlet.uni.io.FileSystemInit
import wvlet.uni.io.IOPath
import wvlet.uni.json.JSON
import wvlet.uni.json.JSON.*
import wvlet.uni.cloud.parquet.ParquetFilter.*
import wvlet.uni.cloud.parquet.format.TimeUnit
import wvlet.uni.rx.Rx
import wvlet.uni.cloud.storage.ObjectUri
import wvlet.uni.test.UniTest
import wvlet.uni.weaver.Weaver

import java.nio.charset.StandardCharsets
import java.time.LocalDate

case class IcebergSale(id: Long, item: String, dt: LocalDate, country: Option[String])
    derives Weaver

case class IcebergNested(
    id: Long,
    tags: Seq[String],
    attrs: Map[String, Long],
    point: IcebergPoint,
    note: Option[String]
) derives Weaver

case class IcebergPoint(x: Int, y: Double) derives Weaver

case class IcebergWide(id: Long, item: String, dt: LocalDate, country: Option[String], extra: Int)
    derives Weaver

/** Writes Apache Iceberg tables and reads them back */
class IcebergWriteTest extends UniTest:
  FileSystemInit.init()

  private var dirs: List[IOPath] = Nil

  override protected def afterAll: Unit = dirs.foreach(FileSystem.deleteRecursively)

  private def newRoot(): String =
    val root = FileSystem.createTempDirectory("uni-iceberg-write")
    dirs = root :: dirs
    root.resolve("sales").path

  private val asIceberg = ParquetDatasetWriteOptions
    .default
    .withTableFormat(ParquetTableFormat.Iceberg)
    .withPartitionBy("dt")

  private val append = asIceberg.withMode(ParquetWriteMode.Append)

  private val d1 = LocalDate.of(2026, 11, 20)
  private val d2 = LocalDate.of(2026, 11, 21)

  private val first = Seq(
    IcebergSale(1, "apple", d1, Some("JP")),
    IcebergSale(2, "banana", d1, None),
    IcebergSale(3, "cherry", d2, Some("US"))
  )

  private val second = Seq(IcebergSale(4, "durian", d2, Some("JP")))

  private def text(p: String): String = String(
    FileSystem.readBytes(IOPath.parse(p)),
    StandardCharsets.UTF_8
  )

  private def metadata(root: String, version: Int): JSONObject = JSON
    .parse(text(s"${root}/metadata/v${version}.metadata.json"))
    .asInstanceOf[JSONObject]

  private def snapshotIds(m: JSONObject): Seq[Long] =
    m.get("snapshots") match
      case Some(JSONArray(vs)) =>
        vs.collect { case s: JSONObject =>
          s.get("snapshot-id")
            .collect { case JSONLong(v) =>
              v
            }
            .get
        }
      case _ =>
        Seq.empty

  private def readSales(
      root: String,
      options: ParquetDatasetOptions = ParquetDatasetOptions.default
  ): Seq[IcebergSale] =
    val ds = Parquet.dataset(root, options)
    try ds.readAs[IcebergSale]().toSeq.sortBy(_.id)
    finally ds.close()

  test("create a partitioned table and read it back") {
    val root  = newRoot()
    val files = Parquet.writeDataset(root, first, asIceberg)
    files.size shouldBe 2
    files.foreach(f => f.path shouldContain s"${IOPath.separator}data${IOPath.separator}dt=")
    FileSystem.exists(s"${root}/metadata/v1.metadata.json") shouldBe true
    text(s"${root}/metadata/version-hint.text") shouldBe "1"
    FileSystem.exists(s"${root}/_SUCCESS") shouldBe false

    readSales(root) shouldBe first
    val ds = Parquet.dataset(root)
    try
      ds.files.size shouldBe 2
      val opts = ParquetReadOptions.default.withFilter(col("dt") === d2)
      ds.selectFiles(opts).size shouldBe 1
      ds.readAs[IcebergSale](opts).toSeq.map(_.id) shouldBe Seq(3L)
    finally
      ds.close()

    val m = metadata(root, 1)
    m.get("format-version") shouldBe Some(JSONLong(2))
    m.get("location") shouldMatch {
      case Some(JSONString(l)) if l.startsWith("file:") =>
    }
  }

  test("data files keep the partition columns and carry field IDs") {
    val root  = newRoot()
    val files = Parquet.writeDataset(root, first, asIceberg)
    val f     = Parquet.open(files.head.path)
    try
      f.schema.fields.map(_.name) shouldBe Seq("id", "item", "dt", "country")
      f.schema.fields.map(_.fieldId) shouldBe Seq(Some(1), Some(2), Some(3), Some(4))
    finally
      f.close()
  }

  test("append snapshots and time travel") {
    val root = newRoot()
    Parquet.writeDataset(root, first, asIceberg)
    Parquet.writeDataset(root, second, append)
    text(s"${root}/metadata/version-hint.text") shouldBe "2"
    readSales(root) shouldBe first ++ second

    val ids = snapshotIds(metadata(root, 2))
    ids.size shouldBe 2
    readSales(root, ParquetDatasetOptions.default.withSnapshotId(ids.head)) shouldBe first
  }

  test("an append without withPartitionBy uses the table's spec") {
    val root = newRoot()
    Parquet.writeDataset(root, first, asIceberg)
    // Auto detects the table at the root
    Parquet
      .writeDataset(
        root,
        second,
        ParquetDatasetWriteOptions.default.withMode(ParquetWriteMode.Append)
      )
      .head
      .path shouldContain "dt=2026-11-21"
    readSales(root) shouldBe first ++ second
  }

  test("append creates the table when there is none") {
    val root = newRoot()
    Parquet.writeDataset(root, first, append)
    readSales(root) shouldBe first
  }

  test("record column metrics in the manifest") {
    val root = newRoot()
    Parquet.writeDataset(root, first, asIceberg)
    val manifests = FileSystem
      .list(IOPath.parse(s"${root}/metadata"))
      .map(_.path)
      .filter(p => p.endsWith("-m0.avro"))
    manifests.size shouldBe 1
    val c = Avro.read(FileSystem.readBytes(IOPath.parse(manifests.head)), manifests.head)
    c.meta("format-version") shouldBe Some("2")
    c.meta("content") shouldBe Some("data")
    c.records.size shouldBe 2
    val entry =
      c.records
        .map(_.asInstanceOf[Map[String, Any]]("data_file").asInstanceOf[Map[String, Any]])
        .find(_("partition").asInstanceOf[Map[String, Any]]("dt") == d1.toEpochDay.toInt)
        .get
    entry("record_count") shouldBe 2L
    def byId(key: String): Map[Int, Any] =
      entry(key)
        .asInstanceOf[Seq[Map[String, Any]]]
        .map(e => e("key").asInstanceOf[Int] -> e("value"))
        .toMap
    byId("value_counts")(1) shouldBe 2L
    byId("null_value_counts")(4) shouldBe 1L
    def long(b: Any): Long =
      val bytes = b.asInstanceOf[Array[Byte]]
      (0 until 8).map(i => (bytes(i) & 0xffL) << (8 * i)).sum
    long(byId("lower_bounds")(1)) shouldBe 1L
    long(byId("upper_bounds")(1)) shouldBe 2L
    String(byId("lower_bounds")(2).asInstanceOf[Array[Byte]], StandardCharsets.UTF_8) shouldBe
      "apple"
  }

  test("skip data files by the column bounds in the manifest") {
    val root  = newRoot()
    val plain = asIceberg.withPartitionBy()
    Parquet.writeDataset(root, first, plain)
    Parquet.writeDataset(root, second, plain.withMode(ParquetWriteMode.Append))
    val ds = Parquet.dataset(root)
    try
      ds.files.size shouldBe 2
      ds.files.foreach(_.stats.map(_.numRows).isDefined shouldBe true)
      def selected(f: ParquetFilter): Seq[Long] = ds
        .selectFiles(ParquetReadOptions.default.withFilter(f))
        .flatMap(_.stats.map(_.numRows))
      // ids 1-3 are in the first file, 4 in the second
      selected(col("id") > 3L) shouldBe Seq(1L)
      selected(col("id") <= 3L) shouldBe Seq(3L)
      selected(col("id") === 10L) shouldBe Seq.empty
      selected(col("item") === "durian") shouldBe Seq(1L)
      selected(col("dt") === d1) shouldBe Seq(3L)
      // Only the first file has a null country
      selected(col("country").isNull) shouldBe Seq(3L)
      selected(col("id") === 2L || col("id") === 4L).size shouldBe 2
      // An unknown column is still an error, even when the bounds would skip every file
      intercept[IllegalArgumentException] {
        ds.readAs[IcebergSale](
            ParquetReadOptions.default.withFilter(col("id") === 10L && col("nmae") === "x")
          )
          .toSeq
      }
      ds.readAs[IcebergSale](ParquetReadOptions.default.withFilter(col("id") > 3L)).toSeq shouldBe
        second
    finally
      ds.close()
  }

  test("skip data files by bounds written before an int column became long") {
    val root = newRoot()
    Parquet.writeDataset(
      root,
      Seq(IcebergPoint(-5, 0.5), IcebergPoint(2, 1.5)),
      asIceberg.withPartitionBy()
    )
    Iceberg.alterTable(root, IcebergTableChange.WidenColumn("x", ParquetType.INT64))
    val ds = Parquet.dataset(root)
    try
      ds.selectFiles(ParquetReadOptions.default.withFilter(col("x") === 2L)).size shouldBe 1
      ds.selectFiles(ParquetReadOptions.default.withFilter(col("x") > 2L)).size shouldBe 0
      // A negative bound is sign-extended
      ds.selectFiles(ParquetReadOptions.default.withFilter(col("x") < -5L)).size shouldBe 0
      ds.selectFiles(ParquetReadOptions.default.withFilter(col("x") === -5L)).size shouldBe 1
      // Floating-point bounds aren't used
      ds.selectFiles(ParquetReadOptions.default.withFilter(col("y") > 10.0)).size shouldBe 1
    finally
      ds.close()
  }

  test("keep empty strings apart from nulls in partition values") {
    val root = newRoot()
    val rows = Seq(
      IcebergSale(1, "a", d1, Some("")),
      IcebergSale(2, "b", d1, None),
      IcebergSale(3, "c", d1, Some("JP"))
    )
    Parquet.writeDataset(
      root,
      rows,
      ParquetDatasetWriteOptions
        .default
        .withTableFormat(ParquetTableFormat.Iceberg)
        .withPartitionBy("country")
    )
    readSales(root) shouldBe rows
  }

  test("write nested columns") {
    val root = newRoot()
    val rows = Seq(
      IcebergNested(1, Seq("a", "b"), Map("k" -> 1L), IcebergPoint(1, 2.5), Some("x")),
      IcebergNested(2, Seq.empty, Map.empty, IcebergPoint(3, 4.5), None)
    )
    Parquet.writeDataset(
      root,
      rows,
      ParquetDatasetWriteOptions.default.withTableFormat(ParquetTableFormat.Iceberg)
    )
    val ds = Parquet.dataset(root)
    try ds.readAs[IcebergNested]().toSeq.sortBy(_.id) shouldBe rows
    finally ds.close()
    val schema = metadata(root, 1).get("schemas").get.asInstanceOf[JSONArray].v.head.toJSON
    schema shouldContain """"element-id""""
    schema shouldContain """"key-id""""
  }

  test("refuse what an Iceberg write can't do") {
    val root = newRoot()
    Parquet.writeDataset(root, first, asIceberg)
    intercept[ParquetException](Parquet.writeDataset(root, second, asIceberg))
      .getMessage shouldContain "already an Iceberg table"
    intercept[ParquetException](
      Parquet.writeDataset(root, second, asIceberg.withMode(ParquetWriteMode.OverwritePartitions))
    ).getMessage shouldContain "doesn't overwrite partitions of Iceberg tables yet"
    intercept[ParquetException](
      Parquet.writeDataset(root, second, append.withPartitionBy("country"))
    ).getMessage shouldContain "partitions by (dt)"
    intercept[ParquetException](
      Parquet.writeDataset(root, Seq(IcebergWide(5, "e", d1, None, 1)), append)
    ).getMessage shouldContain "Iceberg.alterTable"
    // Nothing was written by the refused writes
    readSales(root) shouldBe first
    snapshotIds(metadata(root, 1)).size shouldBe 1
    FileSystem.exists(s"${root}/metadata/v2.metadata.json") shouldBe false
  }

  test("refuse types Iceberg can't hold") {
    val root   = newRoot()
    val schema = ParquetSchema.fromColumns(
      "schema",
      Seq(
        ParquetColumn(
          "ts",
          ParquetType.timestamp(TimeUnit.MILLIS),
          wvlet.uni.cloud.parquet.format.Repetition.OPTIONAL
        )
      )
    )
    intercept[ParquetException](
      Parquet.newDatasetRecordWriter(
        root,
        schema,
        ParquetDatasetWriteOptions.default.withTableFormat(ParquetTableFormat.Iceberg)
      )
    ).getMessage shouldContain "Iceberg stores microseconds"
  }

  test("refuse catalog tables and Delta tables") {
    intercept[ParquetException](
      Parquet.writeDataset(TestFiles.path("iceberg/events").path, second, append)
    ).getMessage shouldContain "a catalog owns"
    intercept[ParquetException](
      Parquet.writeDataset(
        TestFiles.path("delta/partitioned").path,
        second,
        ParquetDatasetWriteOptions.default.withMode(ParquetWriteMode.Append)
      )
    ).getMessage shouldContain "Delta Lake table"
  }

  test("append to a table pyiceberg wrote") {
    // The events fixture as a Hadoop table: its newest metadata file as v1.metadata.json
    val root = FileSystem.createTempDirectory("uni-iceberg-append")
    dirs = root :: dirs
    val src    = TestFiles.path("iceberg/events")
    val files  = ParquetDataset.listLocal(src)
    val newest = files.map(_.segments.last).filter(_.endsWith(".metadata.json")).sorted.last
    files.foreach { l =>
      val name =
        if l.segments.last == newest then
          "v1.metadata.json"
        else
          l.segments.last
      if !l.segments.last.endsWith(".metadata.json") || l.segments.last == newest then
        FileSystem.writeBytes(
          root.resolve((l.segments.init :+ name).mkString("/")),
          FileSystem.readBytes(IOPath.parse(l.path))
        )
    }
    Parquet.writeDataset(
      root.path,
      Seq(IcebergEvent(7, "n7", Some(d1), "JP")),
      ParquetDatasetWriteOptions.default.withMode(ParquetWriteMode.Append)
    )
    val ds = Parquet.dataset(root.path)
    try
      ds.readAs[IcebergEvent]().map(_.id).toSeq.sorted shouldBe Seq(1L, 3L, 4L, 5L, 6L, 7L)
      ds.readAs[IcebergEvent](ParquetReadOptions.default.withFilter(col("country") === "JP"))
        .map(_.id)
        .toSeq
        .sorted shouldBe Seq(1L, 3L, 6L, 7L)
    finally
      ds.close()
    // The new snapshot's partition values use the table's spec field names
    ParquetDataset
      .listLocal(root)
      .exists(_.segments.mkString("/").startsWith("data/dt=2026-11-20/country=JP/part-")) shouldBe
      true
  }

  test("roll forward past a stale version hint") {
    val root = newRoot()
    Parquet.writeDataset(root, first, asIceberg)
    Parquet.writeDataset(root, second, append)
    FileSystem.writeBytes(IOPath.parse(s"${root}/metadata/version-hint.text"), "1".getBytes)
    readSales(root) shouldBe first ++ second
  }

  // ---- Object stores ----

  private val bucketRoot = "s3://lake/sales"

  private def readAsync(store: MemoryObjectStore): Rx[Seq[IcebergSale]] = Parquet
    .datasetAsync(store, bucketRoot)
    .flatMap(_.readAllAsync[IcebergSale]())
    .map(_.sortBy(_.id))

  private def metadataKeys(store: MemoryObjectStore): Seq[String] = store
    .keys(s"${bucketRoot}/metadata/")
    .map(_.stripPrefix(s"${bucketRoot}/metadata/"))

  test("create and append asynchronously in an object store") {
    val store = MemoryObjectStore()
    Parquet
      .writeDatasetAsync(store, bucketRoot, first, asIceberg)
      .flatMap(_ => Parquet.writeDatasetAsync(store, bucketRoot, second, append))
      .flatMap(_ => readAsync(store))
      .map { rows =>
        rows shouldBe first ++ second
        metadataKeys(store).filter(_.endsWith(".metadata.json")) shouldBe
          Seq("v1.metadata.json", "v2.metadata.json")
        String(store.getObject(s"${bucketRoot}/metadata/version-hint.text").get) shouldBe "2"
        store.keys(s"${bucketRoot}/data/dt=").nonEmpty shouldBe true
      }
  }

  test("retry a commit that lost the race") {
    val store = MemoryObjectStore()
    Parquet
      .writeDatasetAsync(store, bucketRoot, first, asIceberg)
      .flatMap { _ =>
        // Another writer commits v2 (the same table state) just before this write's commit
        store.beforeCreate =
          uri =>
            if uri.toString.endsWith("/v2.metadata.json") then
              store.putObject(
                uri.toString,
                store.getObject(s"${bucketRoot}/metadata/v1.metadata.json").get
              )
        Parquet.writeDatasetAsync(store, bucketRoot, second, append)
      }
      .flatMap { _ =>
        store.beforeCreate = _ => ()
        readAsync(store)
      }
      .map { rows =>
        rows shouldBe first ++ second
        metadataKeys(store).filter(_.endsWith(".metadata.json")).sorted shouldBe
          Seq("v1.metadata.json", "v2.metadata.json", "v3.metadata.json")
        // The manifest list of the lost attempt was deleted
        metadataKeys(store).count(_.startsWith("snap-")) shouldBe 2
      }
  }

  test("a conflicting schema change fails the commit and deletes its files") {
    val store = MemoryObjectStore()
    Parquet
      .writeDatasetAsync(store, bucketRoot, first, asIceberg)
      .flatMap { _ =>
        val before = store.keys(s"${bucketRoot}/").toSet
        store.beforeCreate =
          uri =>
            if uri.toString.endsWith("/v2.metadata.json") then
              val v1 = String(store.getObject(s"${bucketRoot}/metadata/v1.metadata.json").get)
              store.putObject(
                uri.toString,
                v1.replace("\"current-schema-id\":0", "\"current-schema-id\":1").getBytes
              )
        Parquet
          .writeDatasetAsync(store, bucketRoot, second, append)
          .map(_ => fail("the commit should fail"))
          .recover { case e: ParquetException =>
            e.getMessage shouldContain "changed the schema or partition spec"
            // Only the racing v2 is new: data files, the manifest, and the list were deleted
            (store.keys(s"${bucketRoot}/").toSet -- before) shouldBe
              Set(s"${bucketRoot}/metadata/v2.metadata.json")
          }
      }
  }

  test("a create whose response is lost still commits") {
    val store = MemoryObjectStore()
    Parquet
      .writeDatasetAsync(store, bucketRoot, first, asIceberg)
      .flatMap { _ =>
        store.loseCreateResponse = true
        Parquet.writeDatasetAsync(store, bucketRoot, second, append)
      }
      .flatMap { written =>
        store.loseCreateResponse = false
        written.size shouldBe 1
        readAsync(store)
      }
      .map(_ shouldBe first ++ second)
  }

  test("refuse an Iceberg table in a folder of plain files") {
    val root = newRoot()
    Parquet.writeDataset(root, first, ParquetDatasetWriteOptions.default.withPartitionBy("dt"))
    intercept[ParquetException](Parquet.writeDataset(root, second, asIceberg))
      .getMessage shouldContain "holds plain Parquet files"
  }

  test("an append records paths under the table's own location") {
    val store = MemoryObjectStore()
    Parquet
      .writeDatasetAsync(store, bucketRoot, first, asIceberg)
      .flatMap { _ =>
        val key = s"${bucketRoot}/metadata/v1.metadata.json"
        val v1  = String(store.getObject(key).get, StandardCharsets.UTF_8)
        store.putObject(key, v1.replace("\"s3://lake/sales\"", "\"s3a://lake/sales\"").getBytes)
        Parquet.writeDatasetAsync(store, bucketRoot, second, append)
      }
      .flatMap { _ =>
        val v2 = String(
          store.getObject(s"${bucketRoot}/metadata/v2.metadata.json").get,
          StandardCharsets.UTF_8
        )
        v2 shouldContain "\"manifest-list\":\"s3a://lake/sales/metadata/snap-"
        readAsync(store)
      }
      .map(_ shouldBe first ++ second)
  }

end IcebergWriteTest

/**
  * Writes tables for other engines to read when `UNI_ICEBERG_INTEROP_DIR` is set (see
  * `src/test/resources/parquet/iceberg/check_uni_tables.py`)
  */
class IcebergInteropTables extends UniTest:
  FileSystemInit.init()

  private val dir = sys.env.get("UNI_ICEBERG_INTEROP_DIR")

  test("write tables for other engines") {
    dir match
      case None =>
        skip("UNI_ICEBERG_INTEROP_DIR is not set")
      case Some(d) =>
        val opts = ParquetDatasetWriteOptions
          .default
          .withTableFormat(ParquetTableFormat.Iceberg)
          .withPartitionBy("dt")
        val sales = s"${d}/sales"
        Parquet.writeDataset(
          sales,
          Seq(
            IcebergSale(1, "apple", LocalDate.of(2026, 11, 20), Some("JP")),
            IcebergSale(2, "banana", LocalDate.of(2026, 11, 20), None)
          ),
          opts
        )
        Parquet.writeDataset(
          sales,
          Seq(IcebergSale(3, "cherry", LocalDate.of(2026, 11, 21), Some(""))),
          opts.withMode(ParquetWriteMode.Append)
        )
        Parquet.writeDataset(
          s"${d}/nested",
          Seq(
            IcebergNested(1, Seq("a", "b"), Map("k" -> 1L), IcebergPoint(1, 2.5), Some("x")),
            IcebergNested(2, Seq.empty, Map.empty, IcebergPoint(3, 4.5), None)
          ),
          ParquetDatasetWriteOptions.default.withTableFormat(ParquetTableFormat.Iceberg)
        )
        // Format version 3: create, append, then replace the files of one partition
        val salesV3 = s"${d}/sales_v3"
        Parquet.writeDataset(
          salesV3,
          Seq(
            IcebergSale(1, "apple", LocalDate.of(2026, 11, 20), Some("JP")),
            IcebergSale(2, "banana", LocalDate.of(2026, 11, 20), None)
          ),
          opts.withTableProperties(Map(IcebergWrite.FormatVersionProperty -> "3"))
        )
        Parquet.writeDataset(
          salesV3,
          Seq(IcebergSale(3, "cherry", LocalDate.of(2026, 11, 21), Some("US"))),
          opts.withMode(ParquetWriteMode.Append)
        )
        val ds      = Parquet.dataset(salesV3)
        val replace =
          try ds.files.filter(_.partitions("dt") == LocalDate.of(2026, 11, 20)).map(_.path)
          finally ds.close()
        Parquet.writeDataset(
          salesV3,
          Seq(IcebergSale(1, "apple", LocalDate.of(2026, 11, 20), Some("JP"))),
          opts.withMode(ParquetWriteMode.Overwrite).withReplacedFiles(replace)
        )
        // Merge-on-read deletes: deletion vectors (v3, the second merging the first) and
        // position-delete files (v2)
        val rows = (1 to 6).map { i =>
          IcebergSale(
            i,
            s"item${i}",
            LocalDate.of(2026, 11, 20 + i % 2),
            Some(
              if i % 3 == 0 then
                "US"
              else
                "JP"
            )
          )
        }
        val dv = s"${d}/v3_dv"
        Parquet.writeDataset(
          dv,
          rows,
          opts.withTableProperties(Map(IcebergWrite.FormatVersionProperty -> "3"))
        )
        Iceberg.deleteRows(dv, ParquetFilter.col("id") === 2L)
        Iceberg.deleteRows(dv, ParquetFilter.col("country") === "US")
        val posdel = s"${d}/v2_posdel"
        Parquet.writeDataset(posdel, rows, opts)
        Iceberg.deleteRows(posdel, ParquetFilter.col("id") === 2L)
        Iceberg.deleteRows(posdel, ParquetFilter.col("country") === "US")
        // Merge-on-read updates: rows 2 and 3 change item, and row 4 moves to another partition
        def updated(root: String, properties: Map[String, String]): Unit =
          Parquet.writeDataset(root, rows, opts.withTableProperties(properties))
          Iceberg.updateRows(
            root,
            ParquetFilter.col("id") === 2L || ParquetFilter.col("id") === 3L
          )(r => r.updated("item", r("item").toString.toUpperCase))
          Iceberg.updateRows(root, ParquetFilter.col("id") === 4L)(
            _.updated("dt", LocalDate.of(2026, 11, 30))
          )
        updated(s"${d}/v3_update", Map(IcebergWrite.FormatVersionProperty -> "3"))
        updated(s"${d}/v2_update", Map.empty)
        // Compacted copies: the deletes are applied and their files removed
        Seq(
          "v3_compacted" -> Map(IcebergWrite.FormatVersionProperty -> "3"),
          "v2_compacted" -> Map.empty[String, String]
        ).foreach { (name, props) =>
          val root = s"${d}/${name}"
          Parquet.writeDataset(root, rows, opts.withTableProperties(props))
          Iceberg.deleteRows(root, ParquetFilter.col("id") === 2L)
          Iceberg.deleteRows(root, ParquetFilter.col("country") === "US")
          Iceberg.compactTable(root)
        }
  }

end IcebergInteropTables
