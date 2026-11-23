package wvlet.uni.cloud.iceberg

import wvlet.uni.cloud.parquet.*

import wvlet.uni.io.FileSystem
import wvlet.uni.io.FileSystemInit
import wvlet.uni.io.IOPath
import wvlet.uni.json.JSON
import wvlet.uni.json.JSON.*
import wvlet.uni.cloud.parquet.format.Repetition
import wvlet.uni.cloud.parquet.format.TimeUnit
import wvlet.uni.test.UniTest

import java.nio.charset.StandardCharsets
import java.time.Instant

/** Writes Iceberg format version 3 tables: row lineage, nanosecond timestamps, and upgrades */
class IcebergV3WriteTest extends UniTest:
  FileSystemInit.init()

  private var dirs: List[IOPath] = Nil

  override protected def afterAll: Unit = dirs.foreach(FileSystem.deleteRecursively)

  private def newRoot(): String =
    val root = FileSystem.createTempDirectory("uni-iceberg-v3")
    dirs = root :: dirs
    root.resolve("sales").path

  private val v3 = Map(IcebergWrite.FormatVersionProperty -> "3")

  private val asIceberg = ParquetDatasetWriteOptions
    .default
    .withTableFormat(ParquetTableFormat.Iceberg)
    .withPartitionBy("dt")

  private val append = asIceberg.withMode(ParquetWriteMode.Append)

  private val d1 = java.time.LocalDate.of(2026, 11, 20)
  private val d2 = java.time.LocalDate.of(2026, 11, 21)

  private val first = Seq(
    IcebergSale(1, "apple", d1, Some("JP")),
    IcebergSale(2, "banana", d1, None),
    IcebergSale(3, "cherry", d2, Some("US"))
  )

  private val second = Seq(IcebergSale(4, "durian", d2, Some("JP")))

  private def metadata(root: String, version: Int): JSONObject = JSON
    .parse(
      String(
        FileSystem.readBytes(IOPath.parse(s"${root}/metadata/v${version}.metadata.json")),
        StandardCharsets.UTF_8
      )
    )
    .asInstanceOf[JSONObject]

  private def long(o: JSONObject, key: String): Option[Long] = o
    .get(key)
    .collect { case JSONLong(v) =>
      v
    }

  private def snapshots(m: JSONObject): Seq[JSONObject] =
    m.get("snapshots") match
      case Some(JSONArray(vs)) =>
        vs.collect { case s: JSONObject =>
          s
        }
      case _ =>
        Seq.empty

  private def avro(uri: String): Avro.Container =
    val path = ParquetDataset.localPathOfFileUri(uri).getOrElse(uri)
    Avro.read(FileSystem.readBytes(IOPath.parse(path)), path)

  /** The current snapshot's manifest list entries */
  private def manifestList(m: JSONObject): Seq[Map[String, Any]] =
    val current = long(m, "current-snapshot-id").get
    val s       = snapshots(m).find(s => long(s, "snapshot-id").contains(current)).get
    s.get("manifest-list") match
      case Some(JSONString(list)) =>
        avro(list).records.map(_.asInstanceOf[Map[String, Any]])
      case _ =>
        Seq.empty

  private def readSales(root: String): Seq[IcebergSale] =
    val ds = Parquet.dataset(root)
    try ds.readAs[IcebergSale]().toSeq.sortBy(_.id)
    finally ds.close()

  test("create a format version 3 table with row lineage") {
    val root = newRoot()
    Parquet.writeDataset(root, first, asIceberg.withTableProperties(v3))
    val m1 = metadata(root, 1)
    long(m1, "format-version") shouldBe Some(3L)
    long(m1, "next-row-id") shouldBe Some(3L)
    // format-version is the metadata's field, not a property
    m1.get("properties") shouldMatch {
      case Some(JSONObject(ps)) if ps.isEmpty =>
    }
    val s1 = snapshots(m1).head
    long(s1, "first-row-id") shouldBe Some(0L)
    long(s1, "added-rows") shouldBe Some(3L)
    val list1 = manifestList(m1)
    list1.map(_("first_row_id")) shouldBe Seq(0L)
    val manifest = avro(list1.head("manifest_path").asInstanceOf[String])
    manifest.meta("format-version") shouldBe Some("3")

    // An append assigns the next IDs
    Parquet.writeDataset(root, second, append)
    val m2 = metadata(root, 2)
    long(m2, "format-version") shouldBe Some(3L)
    long(m2, "next-row-id") shouldBe Some(4L)
    val s2 = snapshots(m2).last
    long(s2, "first-row-id") shouldBe Some(3L)
    long(s2, "added-rows") shouldBe Some(1L)
    // The new manifest comes first; the parent's keeps its first row ID
    manifestList(m2).map(_("first_row_id")) shouldBe Seq(3L, 0L)
    readSales(root) shouldBe first ++ second
  }

  test("a rewritten manifest keeps its rows' IDs") {
    val root = newRoot()
    Parquet.writeDataset(root, first, asIceberg.withTableProperties(v3))
    val ds      = Parquet.dataset(root)
    val removed =
      try ds.files.filter(_.partitions("dt") == d1).map(_.path)
      finally ds.close()
    Parquet.writeDataset(
      root,
      Seq(IcebergSale(1, "apple", d1, Some("JP"))),
      asIceberg.withMode(ParquetWriteMode.Overwrite).withReplacedFiles(removed)
    )
    val m2 = metadata(root, 2)
    // 3 IDs for the first snapshot, 1 for the new file, then 1 for the rewritten manifest's file
    long(m2, "next-row-id") shouldBe Some(5L)
    val list = manifestList(m2)
    list.map(_("first_row_id")) shouldBe Seq(3L, 4L)
    val rewritten = avro(list(1)("manifest_path").asInstanceOf[String])
      .records
      .map(_.asInstanceOf[Map[String, Any]])
      .map(e => e("status") -> e("data_file").asInstanceOf[Map[String, Any]]("first_row_id"))
    // The kept dt=2026-11-21 file had row IDs from 0, after the 2 rows of the dt=2026-11-20 file
    rewritten.toSet shouldBe Set(0 -> 2L, 2 -> 0L) // EXISTING keeps its ID, DELETED too
    readSales(root) shouldBe first.filterNot(_.id == 2)
  }

  test("upgrade a version 2 table to version 3") {
    val root = newRoot()
    Parquet.writeDataset(root, first, asIceberg)
    long(metadata(root, 1), "format-version") shouldBe Some(2L)
    Parquet.writeDataset(root, second, append.withTableProperties(v3))
    val m2 = metadata(root, 2)
    long(m2, "format-version") shouldBe Some(3L)
    // The new manifest, then the version 2 table's manifest, get IDs
    manifestList(m2).map(_("first_row_id")) shouldBe Seq(0L, 1L)
    long(m2, "next-row-id") shouldBe Some(4L)
    readSales(root) shouldBe first ++ second
    // A version can't go down
    intercept[ParquetException](
      Parquet.writeDataset(
        root,
        second,
        append.withTableProperties(Map(IcebergWrite.FormatVersionProperty -> "2"))
      )
    ).getMessage shouldContain "can't go down"
    intercept[IllegalArgumentException](
      Parquet.writeDataset(
        newRoot(),
        first,
        asIceberg.withTableProperties(Map(IcebergWrite.FormatVersionProperty -> "4"))
      )
    ).getMessage shouldContain "must be 2 or 3"
  }

  test("a manifest from before an upgrade keeps its rows' IDs when rewritten") {
    val root = newRoot()
    Parquet.writeDataset(root, first, asIceberg)
    Parquet.writeDataset(root, second, append.withTableProperties(v3))
    // The version 2 manifest got IDs from 1 in the upgrade commit
    val old = manifestList(metadata(root, 2))(1)
    old("first_row_id") shouldBe 1L
    val oldFiles = avro(old("manifest_path").asInstanceOf[String])
      .records
      .map(_.asInstanceOf[Map[String, Any]]("data_file").asInstanceOf[Map[String, Any]])
    val inherited =
      oldFiles
        .map(_("file_path"))
        .zip(oldFiles.scanLeft(1L)((id, d) => id + d("record_count").asInstanceOf[Long]))
        .toMap
    val ds      = Parquet.dataset(root)
    val removed =
      try ds.files.filter(_.partitions("dt") == d1).map(_.path)
      finally ds.close()
    Parquet.writeDataset(
      root,
      Seq.empty[IcebergSale],
      asIceberg.withMode(ParquetWriteMode.Overwrite).withReplacedFiles(removed)
    )
    val list      = manifestList(metadata(root, 3))
    val rewritten = avro(list.find(_("existing_files_count") == 1).get("manifest_path").toString)
    rewritten.meta("format-version") shouldBe Some("3")
    val ids =
      rewritten
        .records
        .map(_.asInstanceOf[Map[String, Any]]("data_file").asInstanceOf[Map[String, Any]])
        .map(d => d("file_path") -> d("first_row_id"))
        .toMap
    // Every entry, kept or deleted, carries the ID it inherited from the old manifest
    ids shouldBe inherited
    readSales(root) shouldBe Seq(first(2)) ++ second
  }

  test("alterIcebergTable upgrades with format-version and checks types") {
    val root = newRoot()
    Parquet.writeDataset(root, first, asIceberg)
    val ns = ParquetColumn("ts", ParquetType.timestamp(TimeUnit.NANOS), Repetition.OPTIONAL)
    intercept[ParquetException](Parquet.alterIcebergTable(root, IcebergTableChange.AddColumn(ns)))
      .getMessage shouldContain "format version 2 can't hold"
    Parquet.alterIcebergTable(
      root,
      IcebergTableChange.SetProperty(IcebergWrite.FormatVersionProperty, "3"),
      IcebergTableChange.AddColumn(ns)
    )
    val m = metadata(root, 2)
    long(m, "format-version") shouldBe Some(3L)
    long(m, "next-row-id") shouldBe Some(0L)
    m.get("properties") shouldMatch {
      case Some(JSONObject(ps)) if ps.isEmpty =>
    }
    // The next commit assigns IDs to the version 2 manifest
    Parquet.writeDataset(root, second, append)
    long(metadata(root, 3), "next-row-id") shouldBe Some(4L)
    intercept[ParquetException](
      Parquet.alterIcebergTable(
        root,
        IcebergTableChange.SetProperty(IcebergWrite.FormatVersionProperty, "2")
      )
    ).getMessage shouldContain "can't go down"
  }

  test("nanosecond timestamps need format version 3") {
    val schema = ParquetSchema.fromColumns(
      "schema",
      Seq(
        ParquetColumn("id", ParquetType.INT64, Repetition.REQUIRED),
        ParquetColumn("ts", ParquetType.timestamp(TimeUnit.NANOS), Repetition.OPTIONAL)
      )
    )
    val options = ParquetDatasetWriteOptions.default.withTableFormat(ParquetTableFormat.Iceberg)
    intercept[ParquetException](Parquet.newDatasetRecordWriter(newRoot(), schema, options))
      .getMessage shouldContain "format version 2 can't hold"
    val root = newRoot()
    val ts   = Instant.parse("2026-11-22T01:02:03.123456789Z")
    val w    = Parquet.newDatasetRecordWriter(root, schema, options.withTableProperties(v3))
    try w.write(Map("id" -> 1L, "ts" -> ts))
    finally w.close()
    val m = metadata(root, 1)
    m.toJSON shouldContain "\"timestamptz_ns\""
    val ds = Parquet.dataset(root)
    try ds.readRows().toSeq shouldBe Seq(Map("id" -> 1L, "ts" -> ts))
    finally ds.close()
  }

  test("alter, expire, and rename keep a table's version 3 fields") {
    val root = newRoot()
    Parquet.writeDataset(root, first, asIceberg.withTableProperties(v3))
    Parquet.writeDataset(root, second, append)
    Parquet.alterIcebergTable(root, IcebergTableChange.SetProperty("owner", "uni"))
    val m3 = metadata(root, 3)
    long(m3, "format-version") shouldBe Some(3L)
    long(m3, "next-row-id") shouldBe Some(4L)
    Parquet.expireIcebergSnapshots(root, retainLast = 1)
    long(metadata(root, 4), "next-row-id") shouldBe Some(4L)
    val to = s"${root}-renamed"
    Parquet.renameIcebergTable(root, to)
    readSales(to) shouldBe first ++ second
    val m = metadata(to, 5)
    long(m, "format-version") shouldBe Some(3L)
    long(m, "next-row-id") shouldBe Some(4L)
    manifestList(m).map(_("first_row_id")) shouldBe Seq(3L, 0L)
  }

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

  private def ids(root: String): Seq[Long] =
    val ds = Parquet.dataset(root)
    try ds.readRows().map(_("id").asInstanceOf[Long]).toSeq.sorted
    finally ds.close()

  test("rename a table with deletion vectors") {
    val root = hadoopCopy("v3_deletes")
    ids(root) shouldBe Seq(1L, 2L, 4L, 5L, 7L, 8L)
    val to = s"${root}-renamed"
    Parquet.renameIcebergTable(root, to)
    // The deletion vector names its data file by the new path
    ids(to) shouldBe Seq(1L, 2L, 4L, 5L, 7L, 8L)
  }

  test("rename a table with equality deletes and a position delete") {
    val root = hadoopCopy("equality_deletes")
    ids(root) shouldBe Seq(2L, 4L, 5L, 6L)
    val to = s"${root}-renamed"
    Parquet.renameIcebergTable(root, to)
    ids(to) shouldBe Seq(2L, 4L, 5L, 6L)
  }

  /** A metadata/ file of a table, by the file name of its recorded URI */
  private def metadataFile(root: String, uri: String): IOPath = IOPath.parse(
    s"${root}/metadata/${uri.split('/').last}"
  )

  /** The delete manifests of a table's current snapshot */
  private def deleteManifests(root: String, version: Int): Seq[IOPath] =
    val m       = metadata(root, version)
    val current = long(m, "current-snapshot-id").get
    val s       = snapshots(m).find(s => long(s, "snapshot-id").contains(current)).get
    val list    = metadataFile(root, s.get("manifest-list").get.asInstanceOf[JSONString].v)
    Avro
      .read(FileSystem.readBytes(list), list.path)
      .records
      .map(_.asInstanceOf[Map[String, Any]])
      .filter(_("content") == 1)
      .map(e => metadataFile(root, e("manifest_path").toString))

  /** The live position-delete files of a table's current snapshot, as recorded */
  private def positionDeleteFiles(root: String, version: Int): Seq[String] = deleteManifests(
    root,
    version
  ).flatMap { p =>
    Avro
      .read(FileSystem.readBytes(p), p.path)
      .records
      .map(_.asInstanceOf[Map[String, Any]])
      .filter(_("status") != 2)
      .map(_("data_file").asInstanceOf[Map[String, Any]]("file_path").toString)
  }

  test("rename a table with position delete files") {
    val root     = hadoopCopy("v2_deletes")
    val expected = (0L until 20L).filterNot(i => i % 4 == 1 || i == 12)
    ids(root) shouldBe expected
    val old = positionDeleteFiles(root, 1).map(_.split('/').last)
    old.nonEmpty shouldBe true
    val to = s"${root}-renamed"
    Parquet.renameIcebergTable(root, to)
    FileSystem.exists(IOPath.parse(root)) shouldBe false
    ids(to) shouldBe expected
    // The delete files were written again with the moved paths, and the old ones deleted
    val rewritten = positionDeleteFiles(to, 2)
    rewritten.size shouldBe old.size
    val toPath = IOPath.parse(to).path.replace('\\', '/')
    rewritten.foreach { f =>
      f shouldContain toPath
      val local = IOPath.parse(s"${to}/${f.substring(f.indexOf("/data/") + 1)}")
      Parquet
        .readRows(FileSystem.readBytes(local))
        .map(_("file_path").toString)
        .foreach(_ shouldContain toPath)
    }
    old.foreach { name =>
      ParquetDataset.listLocal(IOPath.parse(to)).exists(_.segments.last == name) shouldBe false
    }
  }

  test("refuse to rename a table with position delete files Uni can't rewrite") {
    val root = hadoopCopy("v2_deletes")
    // Mark the delete files as ORC, which Uni doesn't write
    deleteManifests(root, 1).foreach { p =>
      val c       = Avro.read(FileSystem.readBytes(p), p.path)
      val records = c
        .records
        .map { r =>
          val m  = r.asInstanceOf[Map[String, Any]]
          val df = m("data_file").asInstanceOf[Map[String, Any]]
          m.updated("data_file", df.updated("file_format", "ORC"))
        }
      val meta = c
        .metadata
        .toSeq
        .filterNot(_._1.startsWith("avro."))
        .map((k, v) => k -> String(v, StandardCharsets.UTF_8))
      FileSystem.writeBytes(
        p,
        Avro.write(String(c.metadata("avro.schema"), StandardCharsets.UTF_8), meta, records)
      )
    }
    intercept[ParquetException](Parquet.renameIcebergTable(root, s"${root}-renamed"))
      .getMessage shouldContain "is ORC; Uni rewrites only Parquet ones"
    // Refused before moving anything
    FileSystem.exists(IOPath.parse(s"${root}-renamed")) shouldBe false
    FileSystem.exists(IOPath.parse(root)) shouldBe true
  }

end IcebergV3WriteTest
