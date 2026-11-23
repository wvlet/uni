package wvlet.uni.cloud.iceberg

import wvlet.uni.cloud.parquet.*

import wvlet.uni.io.FileSystem
import wvlet.uni.io.FileSystemInit
import wvlet.uni.io.IOPath
import wvlet.uni.json.JSON
import wvlet.uni.json.JSON.*
import wvlet.uni.test.UniTest

import java.nio.charset.StandardCharsets

/** Merge-on-read row deletes: deletion vectors (format version 3) and position-delete files (2) */
class IcebergRowDeleteTest extends UniTest with IcebergTableFixtures:
  FileSystemInit.init()

  test("delete rows of a version 2 table with position-delete files") {
    val root = create()
    Iceberg.deleteRows(root, col("id") === 2L || col("id") === 4L) shouldBe 2L
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
    Iceberg.deleteRows(root, col("id") === 99L) shouldBe 0L
    newestVersion(root) shouldBe 1
  }

  test("delete rows of a version 3 table with deletion vectors") {
    val root = create(v3)
    Iceberg.deleteRows(root, col("id") === 3L) shouldBe 1L
    ids(root) shouldBe Seq(1L, 2L, 4L, 5L)
    summary(root, "added-dvs") shouldBe Some("1")
    // A second delete in the same file merges the old vector, which it replaces
    Iceberg.deleteRows(root, col("id") >= 3L) shouldBe 2L
    ids(root) shouldBe Seq(1L, 2L)
    summary(root, "removed-dvs") shouldBe Some("1")
    summary(root, "total-position-deletes") shouldBe Some("3")
    val vectors = liveDeletes(root)
    vectors.map(_("file_format")) shouldBe Seq("PUFFIN")
    vectors.head("record_count") shouldBe 3L
    // Rows already deleted aren't counted again
    Iceberg.deleteRows(root, col("id") === 3L) shouldBe 0L
  }

  test("delete a whole partition and keep time travel") {
    val root   = create(v3)
    val ds     = Parquet.dataset(root)
    val before =
      try ds.selectFiles().size
      finally ds.close()
    val first = metadata(root, 1).get("current-snapshot-id").get.asInstanceOf[JSONLong].v
    Iceberg.deleteRows(root, col("dt") === d2) shouldBe 3L
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
    Iceberg.deleteRows(root, col("country").isNull) shouldBe 1L
    ids(root) shouldBe Seq(1L, 3L, 4L, 5L)
  }

  test("expire the snapshots of replaced deletion vectors") {
    val root = create(v3)
    Iceberg.deleteRows(root, col("id") === 3L) shouldBe 1L
    val oldVector = liveDeletes(root).head("file_path").toString
    Iceberg.deleteRows(root, col("id") === 4L) shouldBe 1L
    Iceberg.expireSnapshots(root, retainLast = 1) shouldBe 2
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
    Iceberg.deleteRows(root, col("id") === 4L) shouldBe 1L
    intercept[IcebergWrite.LostRace](DatasetWriterCore.runStepsSync(target, stale.run()))
    // A retry from the new version keeps both deletes
    Iceberg.deleteRows(root, col("id") === 3L) shouldBe 1L
    ids(root) shouldBe Seq(1L, 2L, 5L)
    liveDeletes(root).map(_("record_count")) shouldBe Seq(2L)
  }

  test("refuse a root that isn't an Iceberg table") {
    val root = newRoot()
    Parquet.writeDataset(root, sales, ParquetDatasetWriteOptions.default)
    intercept[ParquetException](Iceberg.deleteRows(root, col("id") === 1L))
      .getMessage shouldContain "is not an Iceberg table"
  }

  // ---- Tables other engines wrote ----

  test("delete on top of DuckDB's deletion vectors") {
    val root = hadoopCopy("v3_deletes")
    ids(root) shouldBe Seq(1L, 2L, 4L, 5L, 7L, 8L)
    Iceberg.deleteRows(root, col("id") === 4L || col("id") === 8L) shouldBe 2L
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
    Iceberg.deleteRows(root, col("id") < 4L) shouldBe 3L
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
    Iceberg.alterTable(root, IcebergTableChange.SetProperty("format-version", "3"))
    Iceberg.deleteRows(root, col("id") === 0L || col("id") === 19L) shouldBe 2L
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
    Iceberg.deleteRows(root, col("id") <= 4L) shouldBe 2L
    ids(root) shouldBe Seq(5L, 6L)
  }

  test("delete rows of a table with two partition specs") {
    val root   = hadoopCopy("respec")
    val before = ids(root)
    before.size > 1 shouldBe true
    Iceberg.deleteRows(root, col("id") === before.head || col("id") === before.last) shouldBe
      2L
    ids(root) shouldBe before.drop(1).dropRight(1)
  }

end IcebergRowDeleteTest
