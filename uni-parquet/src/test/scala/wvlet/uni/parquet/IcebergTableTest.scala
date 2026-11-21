package wvlet.uni.parquet

import wvlet.uni.io.FileSystem
import wvlet.uni.io.FileSystemInit
import wvlet.uni.io.IOPath
import wvlet.uni.json.JSON
import wvlet.uni.json.JSON.*
import wvlet.uni.parquet.ParquetFilter.*
import wvlet.uni.rx.Rx
import wvlet.uni.storage.ObjectUri
import wvlet.uni.test.UniTest
import wvlet.uni.weaver.Weaver

import java.nio.charset.StandardCharsets
import java.time.LocalDate

case class IcebergEvent(id: Long, name: String, dt: Option[LocalDate], country: String)
    derives Weaver

case class IcebergEvolved(id: Long, n: Long, added: Option[String]) derives Weaver

/**
  * Reads Apache Iceberg tables. The tables under `src/test/resources/parquet/iceberg` were written
  * by pyiceberg (`make_iceberg.py` there) under another folder, so every read also resolves the
  * recorded absolute paths against the folder the table is in now. Other cases edit copies.
  */
class IcebergTableTest extends UniTest:
  FileSystemInit.init()

  private var dirs: List[IOPath] = Nil

  override protected def afterAll: Unit = dirs.foreach(FileSystem.deleteRecursively)

  private def fixture(name: String): String = TestFiles.path(s"iceberg/${name}").path

  private def fixtureFiles(name: String): Seq[(String, Array[Byte])] = ParquetDataset
    .listLocal(TestFiles.path(s"iceberg/${name}"))
    .map(l => l.segments.mkString("/") -> FileSystem.readBytes(IOPath.parse(l.path)))

  /** Metadata file names of a fixture, oldest first */
  private def metadataFiles(name: String): Seq[String] =
    fixtureFiles(name).map(_._1).filter(_.endsWith(".metadata.json")).sorted

  /** A copy of a fixture in a new temp directory, with files changed or added */
  private def copyOf(name: String)(edit: (String, Array[Byte]) => Option[Array[Byte]])(
      extra: (String, String)*
  ): String =
    val root = FileSystem.createTempDirectory("uni-parquet-iceberg")
    dirs = root :: dirs
    def write(rel: String, bytes: Array[Byte]): Unit =
      val path = root.resolve(rel)
      mkdirs(path.parent.get)
      FileSystem.writeBytes(path, bytes)
    fixtureFiles(name).foreach((rel, bytes) => edit(rel, bytes).foreach(write(rel, _)))
    extra.foreach((rel, text) => write(rel, utf8(text)))
    root.path

  private def mkdirs(p: IOPath): Unit =
    if !FileSystem.exists(p) then
      p.parent.foreach(mkdirs)
      FileSystem.createDirectory(p)

  private def utf8(s: String): Array[Byte] = s.getBytes(StandardCharsets.UTF_8)

  private def text(b: Array[Byte]): String = String(b, StandardCharsets.UTF_8)

  private def ids(ds: ParquetDataset): Seq[Long] =
    try ds.readRows().map(_("id").asInstanceOf[Long]).toSeq.sorted
    finally ds.close()

  /** The snapshot ids of the events table, oldest first */
  private lazy val snapshots: IndexedSeq[Long] =
    val newest = metadataFiles("events").last
    JSON.parse(text(FileSystem.readBytes(IOPath.parse(s"${fixture("events")}/${newest}")))) match
      case m: JSONObject =>
        m.get("snapshots") match
          case Some(JSONArray(vs)) =>
            vs.collect { case s: JSONObject =>
              s.get("snapshot-id") match
                case Some(JSONLong(id)) =>
                  id
                case other =>
                  fail(s"snapshot-id: ${other}")
            }
          case _ =>
            fail("no snapshots")
      case _ =>
        fail("not an object")

  private def firstSnapshot  = snapshots(0)
  private def secondSnapshot = snapshots(1)

  test("read the live files of the current snapshot") {
    val ds = Parquet.dataset(fixture("events"))
    try
      ds.partitionColumns.map(_.name) shouldBe IndexedSeq("dt", "country")
      // The delete rewrote a file, which is still on disk
      ds.files.size shouldBe 3
      ds.schema.fields.map(_.name) shouldBe IndexedSeq("id", "name", "dt", "country")
      ds.readAs[IcebergEvent]().toSeq.sortBy(_.id) shouldBe
        Seq(
          IcebergEvent(1, "n1", Some(LocalDate.of(2026, 11, 19)), "JP"),
          IcebergEvent(3, "n3", Some(LocalDate.of(2026, 11, 19)), "JP"),
          IcebergEvent(4, "n4", Some(LocalDate.of(2026, 11, 20)), "US"),
          IcebergEvent(5, "n5", Some(LocalDate.of(2026, 11, 20)), "US"),
          IcebergEvent(6, "n6", None, "JP")
        )
    finally
      ds.close()
  }

  test("read the raw files with withTableFormat(Files)") {
    val ds = Parquet.dataset(
      fixture("events"),
      ParquetDatasetOptions
        .default
        .withTableFormat(ParquetTableFormat.Files)
        .withFileExtension(".parquet")
    )
    // The replaced file is read too, so ids 1 and 3 show up twice
    ids(ds) shouldBe Seq(1L, 1L, 2L, 3L, 3L, 4L, 5L, 6L)
  }

  test("prune files by identity partition values") {
    val ds = Parquet.dataset(fixture("events"))
    try
      val opts = ParquetReadOptions.default.withFilter(col("dt") === LocalDate.of(2026, 11, 20))
      ds.selectFiles(opts).size shouldBe 1
      ds.readRows(opts).map(_("id")).toSeq.sortBy(_.asInstanceOf[Long]) shouldBe Seq(4L, 5L)
    finally
      ds.close()
  }

  test("time travel with withSnapshotId") {
    ids(
      Parquet.dataset(
        fixture("events"),
        ParquetDatasetOptions.default.withSnapshotId(firstSnapshot)
      )
    ) shouldBe Seq(1L, 2L, 3L)
    val e = intercept[ParquetException] {
      Parquet.dataset(fixture("events"), ParquetDatasetOptions.default.withSnapshotId(42))
    }
    e.getMessage shouldContain "has no snapshot 42"
    e.getMessage shouldContain firstSnapshot.toString
  }

  test("read one metadata file as the root") {
    val second = metadataFiles("events")(2)
    ids(Parquet.dataset(s"${fixture("events")}/${second}")) shouldBe Seq(1L, 2L, 3L, 4L, 5L)
  }

  test("follow version-hint.text") {
    val first = metadataFiles("events")(1)
    // A Hadoop table names its versions v<N>
    val hinted =
      copyOf("events")((rel, b) =>
        if rel == first then
          None
        else
          Some(b)
      )(
        "metadata/v1.metadata.json" ->
          text(FileSystem.readBytes(IOPath.parse(s"${fixture("events")}/${first}"))),
        "metadata/version-hint.text" -> "1\n"
      )
    ids(Parquet.dataset(hinted)) shouldBe Seq(1L, 2L, 3L)

    val missing = copyOf("events")((_, b) => Some(b))("metadata/version-hint.text" -> "7")
    intercept[ParquetException](Parquet.dataset(missing)).getMessage shouldContain "names version 7"
  }

  test("refuse to guess between two metadata files of the newest version") {
    val newest = metadataFiles("events").last
    val twin   = "metadata/00004-twin.metadata.json"
    val root   =
      copyOf("events")((_, b) => Some(b))(
        twin -> text(FileSystem.readBytes(IOPath.parse(s"${fixture("events")}/${newest}")))
      )
    intercept[ParquetException](Parquet.dataset(root)).getMessage shouldContain
      "several metadata files for version 4"
  }

  test("schema evolution: promoted, added, and dropped columns") {
    val ds = Parquet.dataset(fixture("evolved"))
    try
      // `gone` was dropped, and the first file still holds it
      ds.schema.fields.map(_.name) shouldBe IndexedSeq("id", "n", "added")
      ds.readAs[IcebergEvolved]().toSeq.sortBy(_.id) shouldBe
        Seq(IcebergEvolved(1, 10, None), IcebergEvolved(2, 20, Some("y")))
      intercept[IllegalArgumentException](
        ds.readRows(ParquetReadOptions.default.withColumns("gone")).toSeq
      ).getMessage shouldContain "No column named gone"
    finally
      ds.close()
  }

  test("refuse renamed columns") {
    intercept[ParquetException](Parquet.dataset(fixture("renamed"))).getMessage shouldContain
      "was renamed to new"
  }

  test("a column that stopped being a partition is read from the files") {
    val ds = Parquet.dataset(fixture("respec"))
    try
      ds.partitionColumns shouldBe IndexedSeq.empty
      ds.readRows().map(r => r("id") -> r("country")).toSeq.sortBy(_._1.asInstanceOf[Long]) shouldBe
        Seq(1L -> "JP", 2L -> "US")
    finally
      ds.close()
  }

  test("read a format version 1 table") {
    val ds = Parquet.dataset(fixture("v1"))
    try
      ds.partitionColumns.map(_.name) shouldBe IndexedSeq("country")
      ds.readRows().map(r => r("id") -> r("country")).toSeq.sortBy(_._1.asInstanceOf[Long]) shouldBe
        Seq(1L -> "JP", 2L -> "US")
    finally
      ds.close()
  }

  test("an empty table has the schema from the metadata") {
    val ds = Parquet.dataset(fixture("empty"))
    try
      ds.files shouldBe IndexedSeq.empty
      ds.schema.fields.map(_.name) shouldBe IndexedSeq("id", "tags", "attrs", "point")
      ds.readRows().toSeq shouldBe Seq.empty
    finally
      ds.close()
  }

  test("refuse what changes meaning") {
    intercept[ParquetException](Parquet.dataset(fixture("deletes"))).getMessage shouldContain
      "delete files"
    intercept[ParquetException](Parquet.dataset(fixture("orc"))).getMessage shouldContain
      "ORC data files"
    val newest = metadataFiles("events").last
    val v3     =
      copyOf("events")((rel, b) =>
        if rel == newest then
          Some(utf8(text(b).replace("\"format-version\":2", "\"format-version\":3")))
        else
          Some(b)
      )()
    intercept[ParquetException](Parquet.dataset(v3)).getMessage shouldContain "format version 3"
  }

  test("refuse paths outside the table and its file system") {
    val newest  = metadataFiles("events").last
    val outside =
      copyOf("events")((rel, b) =>
        if rel == newest then
          Some(
            utf8(
              text(b).replaceAll(
                "\"manifest-list\":\"[^\"]+\"",
                "\"manifest-list\":\"s3://other/list.avro\""
              )
            )
          )
        else
          Some(b)
      )()
    intercept[ParquetException](Parquet.dataset(outside)).getMessage shouldContain
      "outside the table location"
  }

  test("table-format options need the right table") {
    intercept[IllegalArgumentException] {
      Parquet.dataset(fixture("events"), ParquetDatasetOptions.default.withVersion(1))
    }.getMessage shouldContain "Use withSnapshotId"
    intercept[IllegalArgumentException] {
      Parquet.dataset(
        TestFiles.path("delta/partitioned").path,
        ParquetDatasetOptions.default.withSnapshotId(1)
      )
    }.getMessage shouldContain "Use withVersion"
    intercept[IllegalArgumentException] {
      Parquet.dataset(
        fixture("events"),
        ParquetDatasetOptions.default.withPartitionType("dt", ParquetType.STRING)
      )
    }.getMessage shouldContain "withPartitionType doesn't apply"
    intercept[IllegalArgumentException] {
      Parquet.dataset(
        s"${fixture("events")}/data/*",
        ParquetDatasetOptions.default.withTableFormat(ParquetTableFormat.Iceberg)
      )
    }.getMessage shouldContain "withTableFormat(Iceberg)"
    intercept[ParquetException] {
      Parquet.dataset(
        TestFiles.path("delta/partitioned").path,
        ParquetDatasetOptions.default.withTableFormat(ParquetTableFormat.Iceberg)
      )
    }.getMessage shouldContain "is not an Iceberg table"
  }

  test("read Avro container files in every codec") {
    Seq("null", "deflate", "snappy", "zstandard").foreach { codec =>
      val c = Avro.read(
        FileSystem.readBytes(TestFiles.path(s"iceberg/avro/${codec}.avro")),
        s"${codec}.avro"
      )
      c.meta("avro.codec") shouldBe Some(codec)
      c.records.size shouldBe 2000
      val r = c.records(1).asInstanceOf[Map[String, Any]]
      r("s") shouldBe "row1row1"
      r("l") shouldBe -1000003L
      r("a") shouldBe IndexedSeq(0)
      r("m") shouldBe Map("k" -> 0.5)
      r("e") shouldBe "GREEN"
      r("f").asInstanceOf[Array[Byte]].toSeq shouldBe Seq[Byte](1, 7)
      r("b") shouldBe false
      r("x") shouldBe 1.5f
      r("y").asInstanceOf[Array[Byte]].toSeq shouldBe Seq[Byte](0, -1)
      r("next").asInstanceOf[Map[String, Any]]("s") shouldBe "inner"
      c.records(3).asInstanceOf[Map[String, Any]]("l") shouldBe null
      c.records(1999).asInstanceOf[Map[String, Any]]("s") shouldBe "row1999" * 5
    }
  }

  private val bucketRoot = "s3://lake/events"

  private def storeWith(name: String): Rx[MemoryObjectStore] =
    val store = MemoryObjectStore()
    fixtureFiles(name)
      .foldLeft(Rx.single(())) { case (done, (p, bytes)) =>
        done.flatMap(_ => store.putAsync(ObjectUri.parse(s"${bucketRoot}/${p}"), bytes, ""))
      }
      .map(_ => store)

  test("read an Iceberg table in an object store asynchronously") {
    storeWith("events").flatMap { store =>
      Parquet
        .datasetAsync(store, bucketRoot)
        .flatMap { ds =>
          ds.files.size shouldBe 3
          ds.files.head.path shouldMatch {
            case p: String if p.startsWith("s3://lake/events/data/dt=") =>
          }
          ds.readAllAsync[IcebergEvent]().map(_.map(_.id).sorted)
        }
        .map(_ shouldBe Seq(1L, 3L, 4L, 5L, 6L))
    }
  }

  test("list only metadata/ with withTableFormat(Iceberg), and time travel") {
    storeWith("events").flatMap { store =>
      Parquet
        .datasetAsync(
          store,
          bucketRoot,
          ParquetDatasetOptions
            .default
            .withTableFormat(ParquetTableFormat.Iceberg)
            .withSnapshotId(secondSnapshot)
        )
        .flatMap(_.readAllAsync[IcebergEvent]())
        .map(_.map(_.id).sorted shouldBe Seq(1L, 2L, 3L, 4L, 5L))
    }
  }

  test("read a metadata file in an object store") {
    storeWith("events").flatMap { store =>
      Parquet
        .datasetAsync(store, s"${bucketRoot}/${metadataFiles("events").last}")
        .flatMap { ds =>
          ds.root shouldBe s"${bucketRoot}/"
          ds.readAllAsync[IcebergEvent]().map(_.map(_.id).sorted)
        }
        .map(_ shouldBe Seq(1L, 3L, 4L, 5L, 6L))
    }
  }

end IcebergTableTest
