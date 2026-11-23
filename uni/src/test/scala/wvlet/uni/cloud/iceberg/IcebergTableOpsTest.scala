package wvlet.uni.cloud.iceberg

import wvlet.uni.cloud.parquet.*

import wvlet.uni.io.FileSystem
import wvlet.uni.io.FileSystemInit
import wvlet.uni.io.IOPath
import wvlet.uni.io.ListOptions
import wvlet.uni.json.JSON
import wvlet.uni.json.JSON.*
import wvlet.uni.cloud.parquet.ParquetFilter.*
import wvlet.uni.cloud.parquet.format.Repetition
import wvlet.uni.rx.Rx
import wvlet.uni.test.UniTest
import wvlet.uni.weaver.Weaver

import java.nio.charset.StandardCharsets
import java.time.LocalDate

case class IcebergSaleV2(
    id: Long,
    item: String,
    dt: LocalDate,
    country: Option[String],
    qty: Option[Int]
) derives Weaver

case class IcebergRenamed(id: Long, name: String, dt: LocalDate, country: Option[String])
    derives Weaver

case class IcebergItemOnly(id: Long, item: Option[String]) derives Weaver

case class IcebergCount(id: Long, n: Int) derives Weaver

case class IcebergLongCount(id: Long, n: Long) derives Weaver

/** Overwrites, copy-on-write file replacement, schema changes, and drops of Iceberg tables */
class IcebergTableOpsTest extends UniTest:
  FileSystemInit.init()

  private var dirs: List[IOPath] = Nil

  override protected def afterAll: Unit = dirs.foreach(FileSystem.deleteRecursively)

  private def newRoot(): String =
    val root = FileSystem.createTempDirectory("uni-iceberg-ops")
    dirs = root :: dirs
    root.resolve("sales").path

  private val asIceberg = ParquetDatasetWriteOptions
    .default
    .withTableFormat(ParquetTableFormat.Iceberg)
    .withPartitionBy("dt")

  private val append    = ParquetDatasetWriteOptions.default.withMode(ParquetWriteMode.Append)
  private val overwrite = ParquetDatasetWriteOptions.default.withMode(ParquetWriteMode.Overwrite)

  private val d1 = LocalDate.of(2026, 11, 20)
  private val d2 = LocalDate.of(2026, 11, 21)

  private val first = Seq(
    IcebergSale(1, "apple", d1, Some("JP")),
    IcebergSale(2, "banana", d1, None),
    IcebergSale(3, "cherry", d2, Some("US"))
  )

  private def text(p: String): String = String(
    FileSystem.readBytes(IOPath.parse(p)),
    StandardCharsets.UTF_8
  )

  private def latest(root: String): JSONObject =
    val v = text(s"${root}/metadata/version-hint.text").trim
    JSON.parse(text(s"${root}/metadata/v${v}.metadata.json")).asInstanceOf[JSONObject]

  private def summary(root: String): JSONObject =
    val m       = latest(root)
    val current =
      m.get("current-snapshot-id")
        .collect { case JSONLong(v) =>
          v
        }
        .get
    m.get("snapshots") match
      case Some(JSONArray(vs)) =>
        vs.collect { case s: JSONObject =>
            s
          }
          .find(_.get("snapshot-id").contains(JSONLong(current)))
          .flatMap(_.get("summary"))
          .collect { case o: JSONObject =>
            o
          }
          .get
      case _ =>
        fail("no snapshots")

  private def str(o: JSONObject, key: String): String =
    o.get(key)
      .collect { case JSONString(s) =>
        s
      }
      .orNull

  private def snapshotIds(root: String): Seq[Long] =
    latest(root).get("snapshots") match
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

  private def read[A: Weaver](
      root: String,
      options: ParquetDatasetOptions = ParquetDatasetOptions.default
  ): Seq[A] =
    val ds = Parquet.dataset(root, options)
    try ds.readAs[A]().toSeq
    finally ds.close()

  private def sales(root: String): Seq[IcebergSale] = read[IcebergSale](root).sortBy(_.id)

  test("overwrite replaces the rows in a new snapshot") {
    val root = newRoot()
    Parquet.writeDataset(root, first, asIceberg)
    val replacement = Seq(IcebergSale(9, "fig", d2, Some("FR")))
    Parquet.writeDataset(root, replacement, overwrite)
    sales(root) shouldBe replacement
    val s = summary(root)
    str(s, "operation") shouldBe "overwrite"
    str(s, "deleted-data-files") shouldBe "2"
    str(s, "deleted-records") shouldBe "3"
    str(s, "total-records") shouldBe "1"
    str(s, "total-data-files") shouldBe "1"
    // The replaced snapshot stays readable
    val ids = snapshotIds(root)
    ids.size shouldBe 2
    read[IcebergSale](root, ParquetDatasetOptions.default.withSnapshotId(ids.head)).sortBy(
      _.id
    ) shouldBe first
  }

  test("repeated overwrites don't keep manifests without live files") {
    val root = newRoot()
    Parquet.writeDataset(root, first, asIceberg)
    (1 to 3).foreach(i => Parquet.writeDataset(root, Seq(IcebergSale(i, "x", d1, None)), overwrite))
    val m       = latest(root)
    val current =
      m.get("current-snapshot-id")
        .collect { case JSONLong(v) =>
          v
        }
        .get
    val list =
      m.get("snapshots") match
        case Some(JSONArray(vs)) =>
          vs.collect { case s: JSONObject =>
              s
            }
            .find(_.get("snapshot-id").contains(JSONLong(current)))
            .flatMap(_.get("manifest-list"))
            .collect { case JSONString(p) =>
              p
            }
            .get
        case _ =>
          fail("no snapshots")
    val path  = ParquetDataset.localPathOfFileUri(list).getOrElse(list)
    val lists = Avro.read(FileSystem.readBytes(IOPath.parse(path)), path).records
    // The new manifest, and the last overwrite's DELETED entries
    lists.size shouldBe 2
    sales(root) shouldBe Seq(IcebergSale(3, "x", d1, None))
  }

  test("overwrite with other columns commits a new schema") {
    val root = newRoot()
    Parquet.writeDataset(root, first, asIceberg)
    val rows = Seq(IcebergItemOnly(10, Some("grape")), IcebergItemOnly(11, None))
    Parquet.writeDataset(root, rows, overwrite)
    read[IcebergItemOnly](root).sortBy(_.id) shouldBe rows
    val m = latest(root)
    m.get("current-schema-id") shouldBe Some(JSONLong(1))
    // The old partition column is gone, so the table is unpartitioned now
    val ds = Parquet.dataset(root)
    try ds.schema.fields.map(_.name) shouldBe Seq("id", "item")
    finally ds.close()
    // New field IDs follow the old ones
    m.get("last-column-id") shouldBe Some(JSONLong(6))
    Parquet.writeDataset(root, Seq(IcebergItemOnly(12, Some("kiwi"))), append)
    read[IcebergItemOnly](root).map(_.id).sorted shouldBe Seq(10L, 11L, 12L)
  }

  test("overwrite without rows empties the table") {
    val root = newRoot()
    Parquet.writeDataset(root, first, asIceberg)
    Parquet.writeDataset(root, Seq.empty[IcebergSale], overwrite)
    sales(root) shouldBe Seq.empty
    str(summary(root), "operation") shouldBe "delete"
    str(summary(root), "total-records") shouldBe "0"
  }

  test("overwrite creates the table when there is none") {
    val root = newRoot()
    Parquet.writeDataset(root, first, overwrite.withTableFormat(ParquetTableFormat.Iceberg))
    sales(root) shouldBe first
  }

  test("replace only the files a copy-on-write delete touched") {
    val root = newRoot()
    Parquet.writeDataset(root, first, asIceberg)
    val ds      = Parquet.dataset(root)
    val touched =
      try ds.files.filter(_.partitions("dt") == d1)
      finally ds.close()
    touched.size shouldBe 1
    // Keep banana, drop apple
    Parquet.writeDataset(
      root,
      Seq(IcebergSale(2, "banana", d1, None)),
      overwrite.withReplacedFiles(touched.map(_.path))
    )
    sales(root) shouldBe Seq(first(1), first(2))
    val s = summary(root)
    str(s, "operation") shouldBe "overwrite"
    str(s, "deleted-data-files") shouldBe "1"
    str(s, "deleted-records") shouldBe "2"
    str(s, "total-records") shouldBe "2"
    str(s, "total-data-files") shouldBe "2"

    // A delete without rows to keep
    val after =
      val d = Parquet.dataset(root)
      try d.files.filter(_.partitions("dt") == d2)
      finally d.close()
    Parquet.writeDataset(
      root,
      Seq.empty[IcebergSale],
      overwrite.withReplacedFiles(after.map(_.path))
    )
    sales(root) shouldBe Seq(first(1))
    str(summary(root), "operation") shouldBe "delete"
    str(summary(root), "total-data-files") shouldBe "1"
    // Appends after a rewrite still see the kept file
    Parquet.writeDataset(root, Seq(IcebergSale(5, "elder", d2, None)), append)
    sales(root).map(_.id) shouldBe Seq(2L, 5L)
  }

  test("refuse to replace a file that isn't live") {
    val root = newRoot()
    Parquet.writeDataset(root, first, asIceberg)
    val old =
      val d = Parquet.dataset(root)
      try d.files.map(_.path)
      finally d.close()
    Parquet.writeDataset(root, Seq.empty[IcebergSale], overwrite.withReplacedFiles(old.take(1)))
    val e = intercept[ParquetException] {
      Parquet.writeDataset(root, Seq.empty[IcebergSale], overwrite.withReplacedFiles(old.take(1)))
    }
    e.getMessage shouldContain "is not a live data file"
    intercept[IllegalArgumentException] {
      Parquet.writeDataset(root, first, append.withReplacedFiles(old))
    }
  }

  test("replace files of a table pyiceberg wrote") {
    val root = FileSystem.createTempDirectory("uni-iceberg-ops-py")
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
    val ds               = Parquet.dataset(root.path)
    val (target, before) =
      try
        val f = ds.files.head
        val d = ds.withFiles(Seq(f))
        try (f, d.readAs[IcebergEvent]().toSeq)
        finally d.close()
      finally
        ds.close()
    val all = read[IcebergEvent](root.path).map(_.id).sorted
    before.nonEmpty shouldBe true
    Parquet.writeDataset(root.path, before.tail, overwrite.withReplacedFiles(Seq(target.path)))
    read[IcebergEvent](root.path).map(_.id).sorted shouldBe all.filterNot(_ == before.head.id)
  }

  test("add a column") {
    val root = newRoot()
    Parquet.writeDataset(root, first, asIceberg)
    Iceberg.alterTable(
      root,
      IcebergTableChange.AddColumn(ParquetColumn("qty", ParquetType.INT32, Repetition.OPTIONAL))
    )
    text(s"${root}/metadata/version-hint.text") shouldBe "2"
    snapshotIds(root).size shouldBe 1
    Parquet.writeDataset(root, Seq(IcebergSaleV2(4, "durian", d2, None, Some(3))), append)
    read[IcebergSaleV2](root).sortBy(_.id) shouldBe
      first.map(s => IcebergSaleV2(s.id, s.item, s.dt, s.country, None)) :+
      IcebergSaleV2(4, "durian", d2, None, Some(3))
  }

  test("rename a column and add the old name back") {
    val root = newRoot()
    Parquet.writeDataset(root, first, asIceberg)
    Iceberg.alterTable(root, IcebergTableChange.RenameColumn("item", "name"))
    read[IcebergRenamed](root).sortBy(_.id).map(_.name) shouldBe Seq("apple", "banana", "cherry")
    // Filters use the new name too
    val ds = Parquet.dataset(root)
    try
      ds.readAs[IcebergRenamed](ParquetReadOptions.default.withFilter(col("name") === "banana"))
        .map(_.id)
        .toSeq shouldBe Seq(2L)
    finally ds.close()

    Iceberg.alterTable(
      root,
      IcebergTableChange.AddColumn(ParquetColumn("item", ParquetType.STRING, Repetition.OPTIONAL))
    )
    // The older files' item column is the renamed one, so the new item reads as null
    read[IcebergItemOnly](root).map(_.item).distinct shouldBe Seq(None)
  }

  test("drop a column") {
    val root = newRoot()
    Parquet.writeDataset(root, first, asIceberg)
    Iceberg.alterTable(root, IcebergTableChange.DropColumn("country"))
    val ds = Parquet.dataset(root)
    try ds.schema.fields.map(_.name) shouldBe Seq("id", "item", "dt")
    finally ds.close()
    Iceberg.alterTable(
      root,
      IcebergTableChange.AddColumn(
        ParquetColumn("country", ParquetType.STRING, Repetition.OPTIONAL)
      )
    )
    // The dropped values don't come back
    sales(root).map(_.country).distinct shouldBe Seq(None)
  }

  test("widen a column") {
    val root = newRoot()
    Parquet.writeDataset(
      root,
      Seq(IcebergCount(1, 10)),
      ParquetDatasetWriteOptions.default.withTableFormat(ParquetTableFormat.Iceberg)
    )
    Iceberg.alterTable(root, IcebergTableChange.WidenColumn("n", ParquetType.INT64))
    // The widened type shows before any file has it
    val widened = Parquet.dataset(root)
    try
      widened.schema.field("n").map(_.asInstanceOf[ParquetField.Primitive].physicalType) shouldBe
        Some(wvlet.uni.cloud.parquet.format.PhysicalType.INT64)
    finally widened.close()
    read[IcebergLongCount](root) shouldBe Seq(IcebergLongCount(1, 10))
    Parquet.writeDataset(root, Seq(IcebergLongCount(2, 5000000000L)), append)
    read[IcebergLongCount](root).sortBy(_.id) shouldBe
      Seq(IcebergLongCount(1, 10), IcebergLongCount(2, 5000000000L))
    val e = intercept[ParquetException] {
      Iceberg.alterTable(root, IcebergTableChange.WidenColumn("n", ParquetType.INT32))
    }
    e.getMessage shouldContain "Can't change column n"
  }

  test("apply several changes in one schema") {
    val root = newRoot()
    Parquet.writeDataset(root, first, asIceberg)
    Iceberg.alterTable(
      root,
      IcebergTableChange.MakeOptional("item"),
      IcebergTableChange.RenameColumn("item", "name"),
      IcebergTableChange.DropColumn("country")
    )
    latest(root).get("schemas") match
      case Some(JSONArray(vs)) =>
        vs.size shouldBe 2
      case _ =>
        fail("no schemas")
    val ds = Parquet.dataset(root)
    try ds.schema.fields.map(_.name) shouldBe Seq("id", "name", "dt")
    finally ds.close()
  }

  test("refuse schema changes Iceberg can't make") {
    val root = newRoot()
    Parquet.writeDataset(root, first, asIceberg)
    intercept[ParquetException] {
      Iceberg.alterTable(root, IcebergTableChange.DropColumn("dt"))
    }.getMessage shouldContain "partitioned by it"
    intercept[ParquetException] {
      Iceberg.alterTable(
        root,
        IcebergTableChange.AddColumn(ParquetColumn("qty", ParquetType.INT32, Repetition.REQUIRED))
      )
    }.getMessage shouldContain "required"
    intercept[ParquetException] {
      Iceberg.alterTable(root, IcebergTableChange.RenameColumn("item", "id"))
    }.getMessage shouldContain "already has a column id"
    intercept[ParquetException] {
      Iceberg.alterTable(root, IcebergTableChange.DropColumn("missing"))
    }.getMessage shouldContain "has no column missing"
    // Nothing was committed
    text(s"${root}/metadata/version-hint.text") shouldBe "1"
  }

  test("overwrite and replace files asynchronously in an object store") {
    val store                        = MemoryObjectStore()
    val root                         = "s3://lake/ops"
    def rows(): Rx[Seq[IcebergSale]] = Parquet
      .datasetAsync(store, root)
      .flatMap(ds =>
        ds.readAllAsync[IcebergSale]()
          .map { r =>
            ds.close();
            r.sortBy(_.id)
          }
      )
    Parquet
      .writeDatasetAsync(store, root, first, asIceberg)
      .flatMap(_ => Parquet.writeDatasetAsync(store, root, first.take(2), overwrite))
      .flatMap(_ => rows())
      .flatMap { r =>
        r shouldBe first.take(2)
        Parquet
          .datasetAsync(store, root)
          .map { ds =>
            try ds.files.head.path
            finally ds.close()
          }
      }
      .flatMap(file =>
        Parquet.writeDatasetAsync(
          store,
          root,
          Seq(first(0)),
          overwrite.withReplacedFiles(Seq(file))
        )
      )
      .flatMap(_ => rows())
      .map { r =>
        r shouldBe Seq(first(0))
      }
  }

  test("set and read table properties") {
    val root = newRoot()
    Parquet.writeDataset(
      root,
      first,
      asIceberg.withTableProperties(Map("owner" -> "a", "k" -> "1"))
    )
    def props(): Map[String, String] =
      val ds = Parquet.dataset(root)
      try ds.tableProperties
      finally ds.close()
    props() shouldBe Map("owner" -> "a", "k" -> "1")
    Parquet.writeDataset(root, first.take(1), append.withTableProperties(Map("k" -> "2")))
    props() shouldBe Map("owner" -> "a", "k" -> "2")
    Iceberg.alterTable(
      root,
      IcebergTableChange.SetProperty("note", "x"),
      IcebergTableChange.RemoveProperty("owner")
    )
    props() shouldBe Map("k" -> "2", "note" -> "x")
    // A property change adds no schema
    latest(root).get("current-schema-id") shouldBe Some(JSONLong(0))
  }

  test("drop a table") {
    val root = newRoot()
    Parquet.writeDataset(root, first, asIceberg)
    Iceberg.dropTable(root)
    FileSystem.exists(root) shouldBe false
    intercept[ParquetException] {
      Iceberg.dropTable(root)
    }.getMessage shouldContain "is not an Iceberg table"
  }

  private def filesUnder(root: String): Set[String] =
    val base = IOPath.parse(root)
    FileSystem
      .list(base, ListOptions(recursive = true))
      .filterNot(FileSystem.isDirectory)
      .map(p => p.path.substring(base.path.length + 1).replace('\\', '/'))
      .toSet

  test("expire old snapshots and delete the files only they reference") {
    val root = newRoot()
    Parquet.writeDataset(root, first, asIceberg)
    val firstFiles = filesUnder(root).filter(_.startsWith("data/"))
    // Replaces every row: the first files are only in the expired snapshot
    Parquet.writeDataset(root, Seq(IcebergSale(4, "durian", d2, Some("JP"))), overwrite)
    Parquet.writeDataset(root, Seq(IcebergSale(5, "elder", d1, None)), append)
    val ids = snapshotIds(root)
    ids.size shouldBe 3

    Iceberg.expireSnapshots(root, retainLast = 2) shouldBe 1
    snapshotIds(root) shouldBe ids.drop(1)
    sales(root).map(_.id) shouldBe Seq(4L, 5L)
    val after = filesUnder(root)
    firstFiles.intersect(after) shouldBe Set.empty
    // The metadata written before the oldest kept snapshot is gone
    after.contains("metadata/v1.metadata.json") shouldBe false
    after.contains("metadata/v2.metadata.json") shouldBe true
    // Time travel works for the kept snapshots only
    read[IcebergSale](root, ParquetDatasetOptions.default.withSnapshotId(ids(1))).map(_.id) shouldBe
      Seq(4L)
    intercept[ParquetException] {
      read[IcebergSale](root, ParquetDatasetOptions.default.withSnapshotId(ids.head))
    }

    // Nothing more to expire
    Iceberg.expireSnapshots(root, retainLast = 2) shouldBe 0
    Iceberg.expireSnapshots(root, retainLast = 1) shouldBe 1
    snapshotIds(root) shouldBe ids.drop(2)
    sales(root).map(_.id) shouldBe Seq(4L, 5L)
  }

  test("keep data files that a kept snapshot still lists") {
    val root = newRoot()
    Parquet.writeDataset(root, first, asIceberg)
    Parquet.writeDataset(root, Seq(IcebergSale(4, "durian", d2, Some("JP"))), append)
    val ds      = Parquet.dataset(root)
    val touched =
      try ds.files.filter(_.partitions("dt") == d1)
      finally ds.close()
    // A copy-on-write delete of apple: banana's new file replaces the d1 file
    Parquet.writeDataset(
      root,
      Seq(IcebergSale(2, "banana", d1, None)),
      overwrite.withReplacedFiles(touched.map(_.path))
    )
    val before = filesUnder(root).filter(_.startsWith("data/"))
    Iceberg.expireSnapshots(root, retainLast = 1) shouldBe 2
    sales(root).map(_.id) shouldBe Seq(2L, 3L, 4L)
    val removed = before -- filesUnder(root)
    // Only the replaced d1 file goes; the d2 files are still live
    removed.size shouldBe 1
    removed.head shouldContain "dt=2026-11-20"
  }

  test("refuse to expire with retainLast below 1") {
    val root = newRoot()
    Parquet.writeDataset(root, first, asIceberg)
    intercept[IllegalArgumentException] {
      Iceberg.expireSnapshots(root, retainLast = 0)
    }
  }

  test("rename a table and point its metadata at the new folder") {
    val root = newRoot()
    Parquet.writeDataset(root, first, asIceberg)
    Parquet.writeDataset(root, Seq(IcebergSale(4, "durian", d2, Some("JP"))), overwrite)
    Parquet.writeDataset(root, Seq(IcebergSale(5, "elder", d1, None)), append)
    val ids = snapshotIds(root)
    val to  = s"${root}-moved"
    dirs = IOPath.parse(to) :: dirs
    Iceberg.renameTable(root, to)
    FileSystem.exists(root) shouldBe false
    sales(to).map(_.id) shouldBe Seq(4L, 5L)
    snapshotIds(to) shouldBe Seq(ids.last)
    // No metadata file names the old folder any more
    val oldPath = IOPath.parse(root).path.replace('\\', '/')
    filesUnder(to)
      .filter(_.startsWith("metadata/"))
      .foreach { f =>
        val bytes = String(
          FileSystem.readBytes(IOPath.parse(s"${to}/${f}")),
          StandardCharsets.ISO_8859_1
        )
        (
          bytes.contains(s"${oldPath}/") || bytes.contains(s"${oldPath}${IOPath.separator}")
        ) shouldBe false
      }
    str(latest(to), "location") shouldContain "-moved"
    // Only the current snapshot's data files are left
    filesUnder(to).count(_.startsWith("data/")) shouldBe 2
    // Writes after the rename go under the new folder
    Parquet.writeDataset(to, Seq(IcebergSale(6, "fig", d2, None)), append)
    sales(to).map(_.id) shouldBe Seq(4L, 5L, 6L)
  }

  test("refuse to rename onto an existing folder or a non-table") {
    val root = newRoot()
    Parquet.writeDataset(root, first, asIceberg)
    val other = newRoot()
    Parquet.writeDataset(other, first, asIceberg)
    intercept[ParquetException] {
      Iceberg.renameTable(root, other)
    }
    sales(root) shouldBe first
    intercept[ParquetException] {
      Iceberg.renameTable(s"${root}/data", s"${root}-x")
    }
    intercept[ParquetException] {
      Iceberg.renameTable(root, "s3://bucket/sales")
    }
    // Checked before the folder moves: a write path would still name the old folder
    Iceberg.alterTable(
      root,
      IcebergTableChange.SetProperty("write.data.path", s"${root}/data")
    )
    val e = intercept[ParquetException] {
      Iceberg.renameTable(root, s"${root}-y")
    }
    e.getMessage shouldContain "write.data.path"
    FileSystem.exists(root) shouldBe true
    FileSystem.exists(s"${root}-y") shouldBe false
    sales(root) shouldBe first
  }

end IcebergTableOpsTest
