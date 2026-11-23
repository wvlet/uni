package wvlet.uni.cloud.parquet

import wvlet.uni.io.FileSystem
import wvlet.uni.io.FileSystemInit
import wvlet.uni.io.IOPath
import wvlet.uni.json.JSON
import wvlet.uni.json.JSON.*
import wvlet.uni.cloud.parquet.ParquetFilter.*
import wvlet.uni.cloud.parquet.format.LogicalType
import wvlet.uni.rx.Rx
import wvlet.uni.storage.ObjectUri
import wvlet.uni.test.UniTest
import wvlet.uni.weaver.Weaver

import java.nio.charset.StandardCharsets
import java.time.LocalDate

case class IcebergEvent(id: Long, name: String, dt: Option[LocalDate], country: String)
    derives Weaver

case class IcebergEvolved(id: Long, n: Long, added: Option[String]) derives Weaver

case class IcebergIdName(id: Long, name: String) derives Weaver

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

  test("read a renamed column by its field ID") {
    val ds = Parquet.dataset(fixture("renamed"))
    try
      ds.schema.fields.map(_.name) shouldBe IndexedSeq("id", "new")
      ds.readRows().toSeq.forall(r => r.contains("new") && r("new") != null) shouldBe true
    finally
      ds.close()
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

  test("partition columns keep their place in the table schema") {
    val ds = Parquet.dataset(fixture("leading"))
    try
      ds.schema.fields.map(_.name) shouldBe IndexedSeq("dt", "id")
      ds.readRows().toSeq shouldBe Seq(Map("dt" -> LocalDate.of(2026, 11, 21), "id" -> 1L))
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

  test("read a format version 3 table") {
    val ds = Parquet.dataset(fixture("v3"))
    try
      ds.partitionColumns.map(_.name) shouldBe IndexedSeq()
      ds.schema.fields.map(_.name) shouldBe IndexedSeq("id", "ts", "tz", "country", "n", "s")
      val rows          = ds.readRows().toSeq.sortBy(_("id").asInstanceOf[Long])
      def ts(s: String) = java.time.Instant.parse(s)
      rows shouldBe
        Seq(
          // The first file was written before n and s were added: they read as initial-default
          Map(
            "id"      -> 1L,
            "ts"      -> ts("2026-11-22T01:02:03.123456789Z"),
            "tz"      -> ts("2026-11-22T01:02:03Z"),
            "country" -> "JP",
            "n"       -> 7,
            "s"       -> "x"
          ),
          Map("id" -> 2L, "ts" -> null, "tz" -> null, "country" -> "US", "n" -> 7, "s" -> "x"),
          Map(
            "id"      -> 3L,
            "ts"      -> ts("1969-12-31T23:59:59.999999999Z"),
            "tz"      -> null,
            "country" -> "JP",
            "n"       -> 8,
            "s"       -> "y"
          ),
          // Nulls written after the columns were added stay null
          Map("id" -> 4L, "ts" -> null, "tz" -> null, "country" -> "US", "n" -> null, "s" -> null)
        )
      // Filters see the filled defaults, and nanosecond bounds skip files
      def ids(f: ParquetFilter): Seq[Any] = ds
        .readRows(ParquetReadOptions.default.withFilter(f))
        .map(_("id"))
        .toSeq
        .sortBy(_.asInstanceOf[Long])
      ids(col("n") === 7) shouldBe Seq(1L, 2L)
      ids(col("s") === "x") shouldBe Seq(1L, 2L)
      ids(col("ts") < ts("1970-01-01T00:00:00Z")) shouldBe Seq(3L)
      ds.selectFiles(ParquetReadOptions.default.withFilter(col("ts") < ts("1970-01-01T00:00:00Z")))
        .size shouldBe 1
    finally
      ds.close()
    end try
  }

  test("initial-default of every primitive type, and unknown columns") {
    val newest = metadataFiles("v3").last
    val added  = Seq(
      """{"id":20,"name":"b","required":false,"type":"boolean","initial-default":true}""",
      """{"id":21,"name":"l","required":false,"type":"long","initial-default":9000000000}""",
      """{"id":22,"name":"fl","required":false,"type":"float","initial-default":0.25}""",
      """{"id":23,"name":"db","required":false,"type":"double","initial-default":1.5}""",
      """{"id":24,"name":"d","required":false,"type":"date","initial-default":"2026-11-22"}""",
      """{"id":25,"name":"tm","required":false,"type":"time","initial-default":"01:02:03.5"}""",
      """{"id":26,"name":"t","required":false,"type":"timestamp","initial-default":"2026-11-22T01:02:03.123456"}""",
      """{"id":27,"name":"tzn","required":false,"type":"timestamptz_ns","initial-default":"2026-11-22T01:02:03.123456789+00:00"}""",
      """{"id":28,"name":"dec","required":false,"type":"decimal(9, 2)","initial-default":"14.20"}""",
      """{"id":29,"name":"big","required":false,"type":"decimal(20, 3)","initial-default":"-1.500"}""",
      """{"id":30,"name":"u","required":false,"type":"uuid","initial-default":"f79c3e09-677c-4bbd-a479-3f349cb785e7"}""",
      """{"id":31,"name":"bin","required":false,"type":"binary","initial-default":"0aff"}""",
      """{"id":32,"name":"unk","required":false,"type":"unknown"}"""
    )
    val s =
      """{"id":6,"name":"s","required":false,"type":"string","initial-default":"x","write-default":"x"}"""
    val root =
      copyOf("v3")((rel, b) =>
        if rel == newest then
          Some(utf8(text(b).replace(s, (s +: added).mkString(","))))
        else
          Some(b)
      )()
    val ds = Parquet.dataset(root)
    try
      val row = ds.readRows().toSeq.find(_("id") == 4L).get
      row("bin").asInstanceOf[Array[Byte]].toSeq shouldBe Seq[Byte](10, -1)
      row
        .view
        .filterKeys(k => !Set("id", "ts", "tz", "country", "n", "s", "bin").contains(k))
        .toMap
        .map((k, v) => k -> String.valueOf(v)) shouldBe
        Map(
          "b"   -> "true",
          "l"   -> "9000000000",
          "fl"  -> "0.25",
          "db"  -> "1.5",
          "d"   -> "2026-11-22",
          "tm"  -> "01:02:03.500",
          "t"   -> "2026-11-22T01:02:03.123456Z",
          "tzn" -> "2026-11-22T01:02:03.123456789Z",
          "dec" -> "14.20",
          "big" -> "-1.500",
          "u"   -> "f79c3e09-677c-4bbd-a479-3f349cb785e7",
          // An unknown column is always null
          "unk" -> "null"
        )
    finally
      ds.close()
  }

  private def ids(ds: ParquetDataset, opts: ParquetReadOptions = ParquetReadOptions.default) =
    ds.readRows(opts).map(_("id").asInstanceOf[Long]).toSeq.sorted

  /** The snapshot IDs of a fixture's newest metadata file, oldest first */
  private def snapshotIds(name: String): Seq[Long] =
    val newest = metadataFiles(name).last
    val json   = text(fixtureFiles(name).find(_._1 == newest).get._2)
    JSON.parse(json).asInstanceOf[JSONObject].get("snapshots") match
      case Some(JSONArray(vs)) =>
        vs.collect { case o: JSONObject =>
          o.get("snapshot-id") match
            case Some(JSONLong(id)) =>
              id
            case _ =>
              -1L
        }
      case _ =>
        Seq.empty

  test("apply deletion vectors") {
    val ds = Parquet.dataset(fixture("v3_deletes"))
    try
      ids(ds) shouldBe Seq(1L, 2L, 4L, 5L, 7L, 8L)
      // A row filter runs after the deletes, which name rows by position
      ids(ds, ParquetReadOptions.default.withFilter(col("id") >= 4L)) shouldBe Seq(4L, 5L, 7L, 8L)
      ds.readRows(ParquetReadOptions.default.withColumns("name")).size shouldBe 6
      ds.readBatches(ParquetReadOptions.default.withBatchSize(4)).map(_.numRows).sum shouldBe 6
    finally
      ds.close()
    // The snapshot before the delete has every row
    val before = Parquet.dataset(
      fixture("v3_deletes"),
      ParquetDatasetOptions.default.withSnapshotId(snapshotIds("v3_deletes").head)
    )
    try ids(before) shouldBe (0L until 10L)
    finally before.close()
  }

  test("apply a deletion vector with bitmap and array containers") {
    val ds = Parquet.dataset(fixture("v3_dv_large"))
    try
      // Row i holds a = i / 1000 and b = i % 1000
      val rows =
        ds.readBatches(ParquetReadOptions.default.withBatchSize(10000))
          .flatMap(_.rows)
          .map(r => r("a").asInstanceOf[Int] * 1000 + r("b").asInstanceOf[Int])
          .toSeq
      val deleted = (0 until 60000 by 2) ++ (70000 to 100000) ++ Seq(130000, 139999)
      rows shouldBe (0 until 140000).filterNot(deleted.toSet)
    finally
      ds.close()
  }

  test("apply position delete files") {
    val ds = Parquet.dataset(fixture("v2_deletes"))
    try ids(ds) shouldBe (0L until 20L).filterNot(i => i % 4 == 1 || i == 12)
    finally ds.close()
  }

  test("apply equality delete files") {
    // A partitioned delete on name ('a' and null in JP), and an unpartitioned delete on (id, name)
    // whose sequence number reaches only the first commit; DuckDB's position delete removes id 7
    val ds = Parquet.dataset(fixture("equality_deletes"))
    try
      ds.readRows()
        .map(r => (r("id"), r("name"), r("country")))
        .toSeq
        .sortBy(_._1.toString) shouldBe
        Seq((2L, "b", "JP"), (4L, "d", "US"), (5L, "e", "US"), (6L, null, "US"))
      // Key columns are read when the caller doesn't select them, and stay out of the rows
      val opts = ParquetReadOptions.default.withColumns("id")
      ds.readRows(opts).toSeq.map(_.keySet).distinct shouldBe Seq(Set("id"))
      ids(ds, opts) shouldBe Seq(2L, 4L, 5L, 6L)
      ds.readRows(ParquetReadOptions.default.withColumns("country"))
        .map(_("country").toString)
        .toSeq
        .sorted shouldBe Seq("JP", "US", "US", "US")
      // Deletes apply before row filters
      ids(ds, opts.withFilter(col("name") === "a")) shouldBe Seq.empty
      ids(ds, opts.withFilter(col("id") === 4L)) shouldBe Seq(4L)
      ids(ds, opts.withFilter(col("country") === "JP")) shouldBe Seq(2L)
    finally
      ds.close()
    // The snapshot before the equality deletes has only the position delete
    val before = Parquet.dataset(
      fixture("equality_deletes"),
      ParquetDatasetOptions.default.withSnapshotId(snapshotIds("equality_deletes").init.last)
    )
    try ids(before) shouldBe Seq(1L, 2L, 3L, 4L, 4L, 5L, 6L, 8L)
    finally before.close()
  }

  test("equality delete keys compare stored values as Iceberg does") {
    val schema = ParquetSchema
      .builder()
      .optional("i", ParquetType.INT32)
      .optional("l", ParquetType.INT64)
      .optional("d", ParquetType.DOUBLE)
      .build()
    def f(n: String)                    = schema.field(n).get
    def keys(v: ColumnVector): Seq[Any] =
      val r = IcebergDeletes.KeyReader(IndexedSeq(v))
      (0 until v.size).map(r.key)
    // An int written before widening to long matches the long; nulls match nulls
    keys(ColumnVector.IntColumn(f("i"), Array(1, 0), Array(false, true))) shouldBe
      keys(ColumnVector.LongColumn(f("l"), Array(1L, 5L), Array(false, true)))
    // NaN equals NaN, and -0.0 differs from 0.0
    val d = keys(ColumnVector.DoubleColumn(f("d"), Array(Double.NaN, Double.NaN, 0.0, -0.0), null))
    d(0) shouldBe d(1)
    d(2) shouldNotBe d(3)
  }

  test("apply equality deletes when reading asynchronously") {
    val expected = Seq(2L, 4L, 5L, 6L)
    storeWith("equality_deletes").flatMap { store =>
      Parquet
        .datasetAsync(store, bucketRoot)
        .flatMap { ds =>
          ds.readAllRowsAsync(ParquetReadOptions.default.withColumns("id"))
            .map(_.map(_("id").asInstanceOf[Long]).sorted shouldBe expected)
            .flatMap(_ => ds.batchReaderAsync(ParquetReadOptions.default.withColumns("id")))
            .flatMap { reader =>
              def pull(acc: Seq[Long]): Rx[Seq[Long]] = reader
                .nextRowGroup()
                .flatMap {
                  case Some(bs) =>
                    pull(acc ++ bs.flatMap(_.rows).map(_("id").asInstanceOf[Long]))
                  case None =>
                    Rx.single(acc)
                }
              pull(Seq.empty).map(_.sorted shouldBe expected)
            }
        }
    }
  }

  test("apply deletes when reading asynchronously") {
    val expected = Seq(1L, 2L, 4L, 5L, 7L, 8L)
    storeWith("v3_deletes").flatMap { store =>
      Parquet
        .datasetAsync(store, bucketRoot)
        .flatMap { ds =>
          ds.readAllAsync[IcebergIdName]()
            .map(_.map(_.id).sorted shouldBe expected)
            .flatMap(_ => ds.batchReaderAsync(ParquetReadOptions.default.withColumns("id")))
            .flatMap { reader =>
              // The reader that pulls one row group at a time
              def pull(acc: Seq[Long]): Rx[Seq[Long]] = reader
                .nextRowGroup()
                .flatMap {
                  case Some(bs) =>
                    pull(acc ++ bs.flatMap(_.rows).map(_("id").asInstanceOf[Long]))
                  case None =>
                    Rx.single(acc)
                }
              pull(Seq.empty).map(_.sorted shouldBe expected)
            }
        }
    }
  }

  test("deletion vector blobs: runs, several buckets, and checksums") {
    // A portable 64-bit roaring bitmap: bucket 0 with run and array containers, and bucket 1 with
    // a bitmap container
    val out               = java.io.ByteArrayOutputStream()
    def u16(v: Int): Unit =
      out.write(v & 0xff);
      out.write((v >>> 8) & 0xff)
    def i32(v: Int): Unit =
      u16(v & 0xffff);
      u16(v >>> 16)
    def i64(v: Long): Unit =
      i32(v.toInt);
      i32((v >>> 32).toInt)
    i64(2)
    i32(0)
    // Two containers with runs: cookie 12347 with size - 1 in the high bits, and the run bitmap
    i32(12347 | (1 << 16))
    out.write(0x01) // container 0 is a run container
    u16(0);
    u16(20 - 1) // key 0, cardinality 20
    u16(3);
    u16(2 - 1) // key 3, cardinality 2
    u16(2);
    u16(5);
    u16(9);
    u16(100);
    u16(9) // runs 5..14 and 100..109
    u16(7);
    u16(65535) // array container
    i32(1)
    i32(12346)
    i32(1)
    u16(2);
    u16(4097 - 1) // key 2, a bitmap container
    i32(0)        // offset, skipped
    val words = Array.fill(1024)(0L)
    (0 until 4097).foreach(v => words(v / 64) |= 1L << (v % 64))
    words.foreach(i64)
    val bitmap                  = out.toByteArray
    val magic                   = Array(0xd1, 0xd3, 0x39, 0x64).map(_.toByte)
    def be(v: Int): Array[Byte] = Array(v >>> 24, v >>> 16, v >>> 8, v).map(_.toByte)
    val body                    = magic ++ bitmap
    val blob                    = be(body.length) ++ body ++ be(wvlet.uni.io.CRC32.compute(body))
    val file                    = "PFA1".getBytes(StandardCharsets.US_ASCII) ++ blob
    val positions               = IcebergDeletes.deletionVector(file, 4, blob.length, "t.puffin")
    val bucket1                 = 1L << 32
    positions.toSeq shouldBe (
      (5L to 14L) ++
        (100L to 109L) ++ Seq(3L << 16 | 7L, 3L << 16 | 65535L) ++
        (0L until 4097L).map(v => bucket1 | 2L << 16 | v)
    )
    val corrupt = file.clone()
    corrupt(20) = (corrupt(20) ^ 1).toByte
    intercept[ParquetException](IcebergDeletes.deletionVector(corrupt, 4, blob.length, "t.puffin"))
      .getMessage shouldContain "checksum mismatch"
  }

  test("read a shredded variant column of a format version 3 table") {
    val ds = Parquet.dataset(fixture("v3_variant"))
    try
      ds.readRows().map(r => r("id") -> r("v")).toSeq.sortBy(_._1.asInstanceOf[Long]) shouldBe
        Seq(
          1L -> Map("n" -> 1, "s" -> "a"),
          2L -> Map("n" -> 2, "s" -> "b", "tags" -> Seq("x", "y")),
          3L -> "plain",
          4L -> null
        )
    finally ds.close()
  }

  test("read a geometry column of a format version 3 table as WKB") {
    val ds = Parquet.dataset(fixture("v3_geo"))
    try
      ds.schema.field("g").flatMap(_.logicalType) shouldMatch {
        case Some(LogicalType.Geometry(Some(crs))) if crs.contains("CRS84") =>
      }
      val g = ds
        .readRows()
        .map(r => r("id").asInstanceOf[Long] -> Option(r("g").asInstanceOf[Array[Byte]]))
        .toSeq
        .sortBy(_._1)
      g.map(_._2.map(_.length)) shouldBe Seq(Some(21), None, Some(41))
      // POINT(1 2) as little-endian WKB
      g.head._2.get.take(5).toSeq shouldBe Seq[Byte](1, 1, 0, 0, 0)
    finally
      ds.close()
    // geography, with an edge algorithm, reads the same way
    val newest    = metadataFiles("v3_geo").last
    val geography =
      copyOf("v3_geo")((rel, b) =>
        if rel == newest then
          Some(utf8(text(b).replace("geometry(OGC:CRS84)", "geography(OGC:CRS84, karney)")))
        else
          Some(b)
      )()
    val gs = Parquet.dataset(geography)
    try gs.readRows().count(_("g") != null) shouldBe 2
    finally gs.close()
  }

  test("refuse what format version 3 tables have that Uni doesn't read") {
    val newest                                   = metadataFiles("v3").last
    def edited(from: String, to: String): String =
      copyOf("v3")((rel, b) =>
        if rel == newest then
          Some(utf8(text(b).replace(from, to)))
        else
          Some(b)
      )()
    val encrypted = edited(
      ""","last-column-id":6""",
      ""","last-column-id":6,"encryption-keys":[{"key-id":"k","encrypted-key-metadata":"AA=="}]"""
    )
    intercept[ParquetException](Parquet.dataset(encrypted)).getMessage shouldContain "encrypted"
    val badDefault = edited(""""initial-default":7""", """"initial-default":"seven"""")
    intercept[ParquetException](Parquet.dataset(badDefault)).getMessage shouldContain
      "initial-default \"seven\""
    val structDefault = edited(
      """{"id":6,"name":"s",""",
      """{"id":40,"name":"st","required":false,"type":{"type":"struct","fields":[{"id":41,"name":"a","required":false,"type":"int","initial-default":1}]}},{"id":6,"name":"s","""
    )
    intercept[ParquetException](Parquet.dataset(structDefault)).getMessage shouldContain
      "only for top-level columns"
  }

  test("refuse what changes meaning") {
    intercept[ParquetException](Parquet.dataset(fixture("orc"))).getMessage shouldContain
      "ORC data files"
    val newest = metadataFiles("events").last
    val v4     =
      copyOf("events")((rel, b) =>
        if rel == newest then
          Some(utf8(text(b).replace("\"format-version\":2", "\"format-version\":4")))
        else
          Some(b)
      )()
    intercept[ParquetException](Parquet.dataset(v4)).getMessage shouldContain "format version 4"
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

  test("read gzip-compressed metadata of a catalog table") {
    // iceberg-java names these NNNNN-<uuid>.gz.metadata.json
    metadataFiles("gzip_catalog").map(_.split("-").head) shouldBe
      Seq("metadata/00000", "metadata/00001", "metadata/00002")
    metadataFiles("gzip_catalog").forall(_.endsWith(".gz.metadata.json")) shouldBe true
    ids(Parquet.dataset(fixture("gzip_catalog"))) shouldBe Seq(1L, 2L, 3L)
  }

  test("read gzip-compressed metadata of a Hadoop table") {
    // The hint names v2; v3 is the legacy v3.metadata.json.gz name, which the reader rolls to
    ids(Parquet.dataset(fixture("gzip_hadoop"))) shouldBe Seq(1L, 2L, 3L)
    ids(Parquet.dataset(s"${fixture("gzip_hadoop")}/metadata/v2.gz.metadata.json")) shouldBe
      Seq(1L, 2L)
    ids(Parquet.dataset(s"${fixture("gzip_hadoop")}/metadata/v3.metadata.json.gz")) shouldBe
      Seq(1L, 2L, 3L)
    val hinted =
      copyOf("gzip_hadoop")((rel, b) =>
        if rel == "metadata/v3.metadata.json.gz" then
          None
        else
          Some(b)
      )()
    ids(Parquet.dataset(hinted)) shouldBe Seq(1L, 2L)
    val unhinted =
      copyOf("gzip_hadoop")((rel, b) =>
        if rel == "metadata/version-hint.text" then
          None
        else
          Some(b)
      )()
    ids(Parquet.dataset(unhinted)) shouldBe Seq(1L, 2L, 3L)
  }

  test("refuse corrupt gzip metadata") {
    val root =
      copyOf("gzip_hadoop")((rel, b) =>
        if rel == "metadata/v3.metadata.json.gz" then
          Some(b.take(20))
        else
          Some(b)
      )()
    intercept[ParquetException](Parquet.dataset(root)).getMessage shouldContain
      "v3.metadata.json.gz is corrupt gzip data"
  }

  test("append to a table with gzip-compressed metadata") {
    val root = copyOf("gzip_hadoop")((_, b) => Some(b))()
    Parquet.writeDataset(
      root,
      Seq(IcebergIdName(4L, "d")),
      ParquetDatasetWriteOptions
        .default
        .withTableFormat(ParquetTableFormat.Iceberg)
        .withMode(ParquetWriteMode.Append)
    )
    // The next version is plain JSON
    FileSystem.exists(IOPath.parse(s"${root}/metadata/v4.metadata.json")) shouldBe true
    ids(Parquet.dataset(root)) shouldBe Seq(1L, 2L, 3L, 4L)
  }

  test("rename a table with gzip-compressed metadata") {
    val root = copyOf("gzip_hadoop")((_, b) => Some(b))()
    val to   = s"${root}-moved"
    dirs = IOPath.parse(to) :: dirs
    Parquet.renameIcebergTable(root, to)
    ids(Parquet.dataset(to)) shouldBe Seq(1L, 2L, 3L)
    // The compressed versions are replaced by one plain version for the new location
    FileSystem
      .list(IOPath.parse(s"${to}/metadata"))
      .map(_.fileName)
      .filter(IcebergTable.isMetadataFile) shouldBe Seq("v4.metadata.json")
  }

end IcebergTableTest
