package wvlet.uni.parquet

import wvlet.uni.io.FileSystem
import wvlet.uni.io.FileSystemInit
import wvlet.uni.io.IOPath
import wvlet.uni.json.JSON
import wvlet.uni.json.JSON.*
import wvlet.uni.test.UniTest

import java.nio.charset.StandardCharsets

/** Merge-on-read row deletes: deletion vectors (format version 3) and position-delete files (2) */
class IcebergRowDeleteTest extends UniTest:
  FileSystemInit.init()

  private var dirs: List[IOPath] = Nil

  override protected def afterAll: Unit = dirs.foreach(FileSystem.deleteRecursively)

  private def newRoot(): String =
    val root = FileSystem.createTempDirectory("uni-iceberg-delete")
    dirs = root :: dirs
    root.resolve("sales").path

  private val d1 = java.time.LocalDate.of(2026, 11, 20)
  private val d2 = java.time.LocalDate.of(2026, 11, 21)

  private val sales = Seq(
    IcebergSale(1, "apple", d1, Some("JP")),
    IcebergSale(2, "banana", d1, None),
    IcebergSale(3, "cherry", d2, Some("US")),
    IcebergSale(4, "durian", d2, Some("JP")),
    IcebergSale(5, "elderberry", d2, Some("US"))
  )

  private def create(properties: Map[String, String] = Map.empty): String =
    val root = newRoot()
    Parquet.writeDataset(
      root,
      sales,
      ParquetDatasetWriteOptions
        .default
        .withTableFormat(ParquetTableFormat.Iceberg)
        .withPartitionBy("dt")
        .withTableProperties(properties)
    )
    root

  private val v3 = Map(IcebergWrite.FormatVersionProperty -> "3")

  private def col(name: String) = ParquetFilter.col(name)

  private def ids(root: String): Seq[Long] =
    val ds = Parquet.dataset(root)
    try ds.readRows().map(_("id").asInstanceOf[Long]).toSeq.sorted
    finally ds.close()

  private def metadata(root: String, version: Int): JSONObject = JSON
    .parse(
      String(
        FileSystem.readBytes(IOPath.parse(s"${root}/metadata/v${version}.metadata.json")),
        StandardCharsets.UTF_8
      )
    )
    .asInstanceOf[JSONObject]

  private def newestVersion(root: String): Int =
    String(
      FileSystem.readBytes(IOPath.parse(s"${root}/metadata/version-hint.text")),
      StandardCharsets.UTF_8
    ).trim.toInt

  private def currentSnapshot(m: JSONObject): JSONObject =
    val id = m.get("current-snapshot-id").get.asInstanceOf[JSONLong].v
    m.get("snapshots")
      .get
      .asInstanceOf[JSONArray]
      .v
      .collect { case s: JSONObject =>
        s
      }
      .find(_.get("snapshot-id").contains(JSONLong(id)))
      .get

  private def summary(root: String, key: String): Option[String] =
    currentSnapshot(metadata(root, newestVersion(root)))
      .get("summary")
      .collect { case o: JSONObject =>
        o.get(key)
          .collect { case JSONString(v) =>
            v
          }
      }
      .flatten

  /** A metadata file by the name in its recorded URI, which may name the table's old location */
  private def read(root: String, uri: String): Avro.Container =
    val local = IOPath.parse(s"${root}/metadata/${uri.split('/').last}")
    Avro.read(FileSystem.readBytes(local), local.path)

  /** The live entries of the current snapshot's delete manifests */
  private def liveDeletes(root: String): Seq[Map[String, Any]] =
    val s = currentSnapshot(metadata(root, newestVersion(root)))
    read(root, s.get("manifest-list").get.asInstanceOf[JSONString].v)
      .records
      .map(_.asInstanceOf[Map[String, Any]])
      .filter(_("content") == 1)
      .flatMap(e => read(root, e("manifest_path").toString).records)
      .map(_.asInstanceOf[Map[String, Any]])
      .filter(_("status") != 2)
      .map(_("data_file").asInstanceOf[Map[String, Any]])

  test("delete rows of a version 2 table with position-delete files") {
    val root = create()
    Parquet.deleteIcebergRows(root, col("id") === 2L || col("id") === 4L) shouldBe 2L
    ids(root) shouldBe Seq(1L, 3L, 5L)
    summary(root, "operation") shouldBe Some("delete")
    summary(root, "added-position-deletes") shouldBe Some("2")
    summary(root, "added-position-delete-files") shouldBe Some("2")
    summary(root, "total-delete-files") shouldBe Some("2")
    summary(root, "total-records") shouldBe Some("5")
    val deletes = liveDeletes(root)
    deletes.map(_("file_format")) shouldBe Seq("PARQUET", "PARQUET")
    // Each file is scoped to its data file and sits next to it, in the partition folder
    deletes.foreach { d =>
      val ref  = d("referenced_data_file").toString
      val path = d("file_path").toString
      path.substring(0, path.lastIndexOf('/')) shouldBe ref.substring(0, ref.lastIndexOf('/'))
      val rows = Parquet.readRows(
        FileSystem.readBytes(IOPath.parse(ParquetDataset.localPathOfFileUri(path).get))
      )
      rows.map(_("file_path")).distinct shouldBe Seq(ref)
    }
  }

  test("delete nothing when no row matches") {
    val root = create()
    Parquet.deleteIcebergRows(root, col("id") === 99L) shouldBe 0L
    newestVersion(root) shouldBe 1
  }

  test("delete rows of a version 3 table with deletion vectors") {
    val root = create(v3)
    Parquet.deleteIcebergRows(root, col("id") === 3L) shouldBe 1L
    ids(root) shouldBe Seq(1L, 2L, 4L, 5L)
    summary(root, "added-dvs") shouldBe Some("1")
    // A second delete in the same file merges the old vector, which it replaces
    Parquet.deleteIcebergRows(root, col("id") >= 3L) shouldBe 2L
    ids(root) shouldBe Seq(1L, 2L)
    summary(root, "removed-dvs") shouldBe Some("1")
    summary(root, "total-position-deletes") shouldBe Some("3")
    val vectors = liveDeletes(root)
    vectors.map(_("file_format")) shouldBe Seq("PUFFIN")
    vectors.head("record_count") shouldBe 3L
    // Rows already deleted aren't counted again
    Parquet.deleteIcebergRows(root, col("id") === 3L) shouldBe 0L
  }

  test("delete a whole partition and keep time travel") {
    val root   = create(v3)
    val ds     = Parquet.dataset(root)
    val before =
      try ds.selectFiles().size
      finally ds.close()
    val first = metadata(root, 1).get("current-snapshot-id").get.asInstanceOf[JSONLong].v
    Parquet.deleteIcebergRows(root, col("dt") === d2) shouldBe 3L
    ids(root) shouldBe Seq(1L, 2L)
    val old = Parquet.dataset(root, ParquetDatasetOptions.default.withSnapshotId(first))
    try old.readRows().size shouldBe 5
    finally old.close()
    // The data files stay: merge-on-read
    val after = Parquet.dataset(root)
    try after.selectFiles().size shouldBe before
    finally after.close()
  }

  test("delete rows with a filter on a column the rows lack") {
    val root = create()
    Parquet.deleteIcebergRows(root, col("country").isNull) shouldBe 1L
    ids(root) shouldBe Seq(1L, 3L, 4L, 5L)
  }

  test("expire the snapshots of replaced deletion vectors") {
    val root = create(v3)
    Parquet.deleteIcebergRows(root, col("id") === 3L) shouldBe 1L
    val oldVector = liveDeletes(root).head("file_path").toString
    Parquet.deleteIcebergRows(root, col("id") === 4L) shouldBe 1L
    Parquet.expireIcebergSnapshots(root, retainLast = 1) shouldBe 2
    FileSystem.exists(IOPath.parse(ParquetDataset.localPathOfFileUri(oldVector).get)) shouldBe false
    ids(root) shouldBe Seq(1L, 2L, 5L)
  }

  test("a delete that loses the race finds its positions again") {
    val root   = create(v3)
    val target = DatasetTarget.Local(IOPath.parse(root))
    val layout = DatasetWriterCore.icebergLayout(target)
    val t      = DatasetWriterCore.runStepsSync(target, IcebergWrite.load(layout)).get
    val ds     = Parquet.dataset(
      root,
      ParquetDatasetOptions.default.withSnapshotId(IcebergWrite.currentSnapshotId(t).get)
    )
    val found =
      try ds.matchingPositions(col("id") === 3L)
      finally ds.close()
    val stale = IcebergWrite.RowDeleteCommit(
      layout,
      t,
      found.map((f, added, existing) => IcebergWrite.RowDeletes(f.path, added, existing))
    )
    // Another writer deletes a row of the same file first
    Parquet.deleteIcebergRows(root, col("id") === 4L) shouldBe 1L
    intercept[IcebergWrite.LostRace](DatasetWriterCore.runStepsSync(target, stale.run()))
    // A retry from the new version keeps both deletes
    Parquet.deleteIcebergRows(root, col("id") === 3L) shouldBe 1L
    ids(root) shouldBe Seq(1L, 2L, 5L)
    liveDeletes(root).map(_("record_count")) shouldBe Seq(2L)
  }

  test("refuse a root that isn't an Iceberg table") {
    val root = newRoot()
    Parquet.writeDataset(root, sales, ParquetDatasetWriteOptions.default)
    intercept[ParquetException](Parquet.deleteIcebergRows(root, col("id") === 1L))
      .getMessage shouldContain "is not an Iceberg table"
  }

  // ---- Tables other engines wrote ----

  /**
    * A Hadoop-table copy of a catalog fixture: its newest metadata file becomes `v1.metadata.json`
    */
  private def hadoopCopy(name: String): String =
    val root   = newRoot()
    val listed = ParquetDataset.listLocal(TestFiles.path(s"iceberg/${name}"))
    val newest = listed.map(_.segments.mkString("/")).filter(_.endsWith(".metadata.json")).max
    listed.foreach { l =>
      val rel = l.segments.mkString("/")
      if !rel.endsWith(".metadata.json") || rel == newest then
        val to =
          if rel == newest then
            "metadata/v1.metadata.json"
          else
            rel
        val path = IOPath.parse(s"${root}/${to}")
        FileSystem.createDirectory(path.parent.get)
        FileSystem.writeBytes(path, FileSystem.readBytes(IOPath.parse(l.path)))
    }
    FileSystem.writeString(IOPath.parse(s"${root}/metadata/version-hint.text"), "1")
    root

  test("delete on top of DuckDB's deletion vectors") {
    val root = hadoopCopy("v3_deletes")
    ids(root) shouldBe Seq(1L, 2L, 4L, 5L, 7L, 8L)
    Parquet.deleteIcebergRows(root, col("id") === 4L || col("id") === 8L) shouldBe 2L
    ids(root) shouldBe Seq(1L, 2L, 5L, 7L)
    // One live vector per data file
    val refs = liveDeletes(root)
      .filter(_("file_format").toString.equalsIgnoreCase("puffin"))
      .map(_("referenced_data_file"))
    refs.distinct.size shouldBe refs.size
  }

  test("delete on top of DuckDB's position-delete files") {
    val root     = hadoopCopy("v2_deletes")
    val expected = (0L until 20L).filterNot(i => i % 4 == 1 || i == 12)
    ids(root) shouldBe expected
    Parquet.deleteIcebergRows(root, col("id") < 4L) shouldBe 3L
    ids(root) shouldBe expected.filter(_ >= 4L)
  }

  test("deletion vectors replace file-scoped deletes and keep partition-scoped ones") {
    val root = hadoopCopy("v2_deletes")
    // Merge the manifest's two file-scoped delete files into one that names both data files, as
    // a partition-scoped position-delete file does
    val manifest = IOPath.parse(s"${root}/metadata/810d5664-4c40-4281-aa32-b8b7aac69a3d-m0.avro")
    val c        = Avro.read(FileSystem.readBytes(manifest), manifest.path)
    val entries  = c.records.map(_.asInstanceOf[Map[String, Any]])
    def local(uri: String): IOPath = IOPath.parse(s"${root}/data/${uri.split('/').last}")
    val files                      = entries.map(e => e("data_file").asInstanceOf[Map[String, Any]])
    val rows                       =
      files
        .flatMap(f => Parquet.readRows(FileSystem.readBytes(local(f("file_path").toString))))
        .map(r => (r("file_path").toString, r("pos").asInstanceOf[Long]))
        .sorted
    val out = java.io.ByteArrayOutputStream()
    val w   = Parquet.newRecordWriter(
      out,
      Parquet.open(FileSystem.readBytes(local(files.head("file_path").toString))).schema
    )
    try rows.foreach((p, n) => w.write(Map("file_path" -> p, "pos" -> n)))
    finally w.close()
    val merged = out.toByteArray
    FileSystem.writeBytes(IOPath.parse(s"${root}/data/merged-deletes.parquet"), merged)
    def bound(p: String): Seq[Map[String, Any]] = Seq(
      Map("key" -> 2147483546, "value" -> p.getBytes(StandardCharsets.UTF_8))
    )
    val location = files.head("file_path").toString.split("/data/").head
    val scoped   = entries
      .head
      .updated(
        "data_file",
        files.head ++
          Map(
            "file_path"            -> s"${location}/data/merged-deletes.parquet",
            "record_count"         -> rows.size.toLong,
            "file_size_in_bytes"   -> merged.length.toLong,
            "referenced_data_file" -> null,
            "lower_bounds"         -> bound(rows.head._1),
            "upper_bounds"         -> bound(rows.last._1)
          )
      )
    FileSystem.writeBytes(
      manifest,
      Avro.write(
        String(c.metadata("avro.schema"), StandardCharsets.UTF_8),
        c.metadata
          .toSeq
          .filterNot(_._1.startsWith("avro."))
          .map((k, v) => k -> String(v, StandardCharsets.UTF_8)),
        Seq(scoped)
      )
    )
    val expected = (0L until 20L).filterNot(i => i % 4 == 1 || i == 12)
    ids(root) shouldBe expected
    Parquet.alterIcebergTable(root, IcebergTableChange.SetProperty("format-version", "3"))
    Parquet.deleteIcebergRows(root, col("id") === 0L || col("id") === 19L) shouldBe 2L
    ids(root) shouldBe expected.filterNot(i => i == 0L || i == 19L)
    val live = liveDeletes(root)
    // The partition-scoped file stays; each touched data file has one vector and no other
    // file-scoped delete
    live.count(_("file_path").toString.endsWith("merged-deletes.parquet")) shouldBe 1
    val vectors = live.filter(_("file_format") == "PUFFIN").map(_("referenced_data_file"))
    vectors.distinct.size shouldBe vectors.size
    live
      .filter(_("file_format") != "PUFFIN")
      .flatMap(d => Option(d.getOrElse("referenced_data_file", null)))
      .foreach(ref => vectors.contains(ref) shouldBe false)
  }

  test("rows that equality deletes removed aren't deleted again") {
    val root = hadoopCopy("equality_deletes")
    ids(root) shouldBe Seq(2L, 4L, 5L, 6L)
    Parquet.deleteIcebergRows(root, col("id") <= 4L) shouldBe 2L
    ids(root) shouldBe Seq(5L, 6L)
  }

  test("delete rows of a table with two partition specs") {
    val root   = hadoopCopy("respec")
    val before = ids(root)
    before.size > 1 shouldBe true
    Parquet.deleteIcebergRows(root, col("id") === before.head || col("id") === before.last) shouldBe
      2L
    ids(root) shouldBe before.drop(1).dropRight(1)
  }

end IcebergRowDeleteTest
