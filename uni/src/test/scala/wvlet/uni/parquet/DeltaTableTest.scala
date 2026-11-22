package wvlet.uni.parquet

import wvlet.uni.io.FileSystem
import wvlet.uni.io.FileSystemInit
import wvlet.uni.io.IOPath
import wvlet.uni.json.JSON.JSONString
import wvlet.uni.parquet.ParquetFilter.*
import wvlet.uni.rx.Rx
import wvlet.uni.storage.ObjectUri
import wvlet.uni.test.UniTest
import wvlet.uni.weaver.Weaver

import java.nio.charset.StandardCharsets
import java.time.LocalDate

case class DeltaEvent(id: Long, name: String, dt: Option[LocalDate], country: String) derives Weaver

case class DeltaPoint(id: Int, v: Double) derives Weaver

// The columns of a Delta checkpoint that the reader uses, for generated checkpoints
case class CpAdd(path: String, partitionValues: Map[String, String], size: Long) derives Weaver
case class CpProtocol(minReaderVersion: Int, minWriterVersion: Int) derives Weaver
case class CpMetaData(id: String, schemaString: String, partitionColumns: Seq[String])
    derives Weaver

case class CpRow(add: Option[CpAdd], protocol: Option[CpProtocol], metaData: Option[CpMetaData])
    derives Weaver

/**
  * Reads Delta Lake tables. `partitioned`, `checkpoint`, and `empty` under
  * `src/test/resources/parquet/delta` were written by delta-rs (`make_delta.py` there); other cases
  * write their logs by hand.
  */
class DeltaTableTest extends UniTest:
  FileSystemInit.init()

  private var dirs: List[IOPath] = Nil

  override protected def afterAll: Unit = dirs.foreach(FileSystem.deleteRecursively)

  private def fixture(name: String): String = TestFiles.path(s"delta/${name}").path

  /** The files of a fixture table, as relative paths and bytes */
  private def fixtureFiles(name: String): Seq[(String, Array[Byte])] = ParquetDataset
    .listLocal(TestFiles.path(s"delta/${name}"))
    .map(l => l.segments.mkString("/") -> FileSystem.readBytes(IOPath.parse(l.path)))

  /** A new temp directory with the given files */
  private def tableDir(files: (String, Array[Byte])*): IOPath =
    val root = FileSystem.createTempDirectory("uni-parquet-delta")
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

  private def utf8(s: String): Array[Byte] = s.getBytes(StandardCharsets.UTF_8)

  private def commit(v: Long): String = f"_delta_log/${v}%020d.json"

  private def events(ds: ParquetDataset): Seq[DeltaEvent] =
    try ds.readAs[DeltaEvent]().toSeq.sortBy(_.id)
    finally ds.close()

  private val pointSchema =
    """{"type":"struct","fields":[{"name":"id","type":"integer","nullable":true,"metadata":{}},{"name":"v","type":"double","nullable":true,"metadata":{}}]}"""

  private def protocolLine(reader: Int, features: Seq[String] = Nil): String =
    val f =
      if features.isEmpty then
        ""
      else
        s""","readerFeatures":[${features.map(x => s"\"${x}\"").mkString(",")}]"""
    s"""{"protocol":{"minReaderVersion":${reader},"minWriterVersion":7${f}}}"""

  private def metaLine(schema: String, partitions: Seq[String] = Nil): String =
    val json = JSONString(schema).toJSON
    s"""{"metaData":{"id":"t","format":{"provider":"parquet","options":{}},"schemaString":${json},"partitionColumns":[${partitions
        .map(p => s"\"${p}\"")
        .mkString(",")}],"configuration":{}}}"""

  private def addLine(path: String, size: Long, partitions: String = "{}"): String =
    s"""{"add":{"path":"${path}","partitionValues":${partitions},"size":${size},"modificationTime":0,"dataChange":true}}"""

  private def points(ids: Int*): Array[Byte] = Parquet.writeBytes(
    ids.map(i => DeltaPoint(i, i * 1.5))
  )

  test("read the live files of the latest version") {
    val ds = Parquet.dataset(fixture("partitioned"))
    ds.partitionColumns.map(_.name) shouldBe IndexedSeq("dt", "country")
    // The rewrite for `DELETE WHERE id = 2` replaced a file, which is still on disk
    ds.files.size shouldBe 3
    events(ds) shouldBe
      Seq(
        DeltaEvent(1, "n1", Some(LocalDate.of(2026, 11, 19)), "JP"),
        DeltaEvent(3, "n3", Some(LocalDate.of(2026, 11, 19)), "JP"),
        DeltaEvent(4, "n4", Some(LocalDate.of(2026, 11, 20)), "US"),
        DeltaEvent(5, "n5", Some(LocalDate.of(2026, 11, 20)), "US"),
        DeltaEvent(6, "n6", None, "JP")
      )
  }

  test("read the raw files with withTableFormat(Files)") {
    val ds = Parquet.dataset(
      fixture("partitioned"),
      ParquetDatasetOptions.default.withTableFormat(ParquetTableFormat.Files)
    )
    // The removed file is read too, so ids 1 and 3 show up twice
    try
      ds.readRows().map(_("id")).toSeq.sortBy(_.asInstanceOf[Long]) shouldBe
        Seq(1L, 1L, 2L, 3L, 3L, 4L, 5L, 6L)
    finally ds.close()
  }

  test("prune files by partition values") {
    val ds = Parquet.dataset(fixture("partitioned"))
    try
      val opts = ParquetReadOptions.default.withFilter(col("country") === "US")
      ds.selectFiles(opts).size shouldBe 1
      ds.readRows(opts).map(_("id")).toSeq.sortBy(_.asInstanceOf[Long]) shouldBe Seq(4L, 5L)
    finally
      ds.close()
  }

  test("time travel with withVersion") {
    val v0 = Parquet.dataset(fixture("partitioned"), ParquetDatasetOptions.default.withVersion(0))
    events(v0).map(_.id) shouldBe Seq(1L, 2L, 3L)
    val v2 = Parquet.dataset(fixture("partitioned"), ParquetDatasetOptions.default.withVersion(2))
    events(v2).map(_.id) shouldBe Seq(1L, 2L, 3L, 4L, 5L, 6L)
    val e = intercept[ParquetException] {
      Parquet.dataset(fixture("partitioned"), ParquetDatasetOptions.default.withVersion(9))
    }
    e.getMessage shouldContain "the readable versions are 0 to 3"
  }

  test("replay commits after a checkpoint") {
    val ds = Parquet.dataset(fixture("checkpoint"))
    try ds.readAs[DeltaPoint]().toSeq shouldBe Seq(DeltaPoint(10, 0.0))
    finally ds.close()
    // Version 3: the checkpoint at version 2 and commit 3
    val v3 = Parquet.dataset(fixture("checkpoint"), ParquetDatasetOptions.default.withVersion(3))
    try v3.readAs[DeltaPoint]().toSeq.sortBy(_.id).map(_.id) shouldBe Seq(0, 1, 2, 3)
    finally v3.close()
  }

  test("read versions from a checkpoint after log cleanup removed older commits") {
    val files = fixtureFiles("checkpoint").filterNot((p, _) => p == commit(0) || p == commit(1))
    val root  = tableDir(files*).path
    val v3    = Parquet.dataset(root, ParquetDatasetOptions.default.withVersion(3))
    try v3.readAs[DeltaPoint]().toSeq.map(_.id).sorted shouldBe Seq(0, 1, 2, 3)
    finally v3.close()
    val e = intercept[ParquetException] {
      Parquet.dataset(root, ParquetDatasetOptions.default.withVersion(1))
    }
    e.getMessage shouldContain "readable versions are 2 to 4"
  }

  test("an empty table has the schema from the log") {
    val ds = Parquet.dataset(fixture("empty"))
    try
      ds.files shouldBe IndexedSeq.empty
      ds.schema.fields.map(_.name) shouldBe IndexedSeq("id", "tags", "attrs", "pt")
      ds.readRows().toSeq shouldBe Seq.empty
    finally
      ds.close()
  }

  test("use a complete multi-part checkpoint and skip an incomplete one") {
    val meta                         = CpRow(None, None, Some(CpMetaData("t", pointSchema, Nil)))
    val prot                         = CpRow(None, Some(CpProtocol(1, 2)), None)
    def add(name: String, size: Int) = CpRow(Some(CpAdd(name, Map.empty, size.toLong)), None, None)
    val a                            = points(1)
    val b                            = points(2)
    val c                            = points(3)
    val root                         =
      tableDir(
        "a.parquet" -> a,
        "b.parquet" -> b,
        "c.parquet" -> c,
        commit(0)   ->
          utf8(
            Seq(protocolLine(1), metaLine(pointSchema), addLine("a.parquet", a.length)).mkString(
              "\n"
            )
          ),
        commit(1) -> utf8(addLine("b.parquet", b.length)),
        commit(2) -> utf8(addLine("c.parquet", c.length)),
        // Version 1 in two parts
        f"_delta_log/${1}%020d.checkpoint.${1}%010d.${2}%010d.parquet" ->
          Parquet.writeBytes(Seq(meta, prot, add("a.parquet", a.length))),
        f"_delta_log/${1}%020d.checkpoint.${2}%010d.${2}%010d.parquet" ->
          Parquet.writeBytes(Seq(add("b.parquet", b.length))),
        // Version 2, with a part missing
        f"_delta_log/${2}%020d.checkpoint.${1}%010d.${2}%010d.parquet" ->
          Parquet.writeBytes(Seq(meta, prot))
      ).path
    val plan = DeltaLog.plan(s"${root}/", ParquetDataset.listLocal(IOPath.parse(root)), None)
    plan.checkpoint.size shouldBe 2
    plan.commits.map(_.segments.last) shouldBe Seq(f"${2}%020d.json")
    val ds = Parquet.dataset(root)
    try ds.readAs[DeltaPoint]().toSeq.map(_.id).sorted shouldBe Seq(1, 2, 3)
    finally ds.close()
  }

  test("refuse reader features that change what files mean") {
    val a                       = points(1)
    def table(protocol: String) =
      tableDir(
        "a.parquet" -> a,
        commit(0)   ->
          utf8(Seq(protocol, metaLine(pointSchema), addLine("a.parquet", a.length)).mkString("\n"))
      ).path
    val dv = intercept[ParquetException] {
      Parquet.dataset(table(protocolLine(3, Seq("deletionVectors", "timestampNtz"))))
    }
    dv.getMessage shouldContain "doesn't support: deletionVectors"
    val cm = intercept[ParquetException](Parquet.dataset(table(protocolLine(2))))
    cm.getMessage shouldContain "columnMapping"
    // Features that don't change reads are fine
    val ok = Parquet.dataset(table(protocolLine(3, Seq("timestampNtz", "vacuumProtocolCheck"))))
    try ok.readAs[DeltaPoint]().toSeq shouldBe Seq(DeltaPoint(1, 1.5))
    finally ok.close()
  }

  test("the newest protocol at the target version decides") {
    val a    = points(1)
    val root =
      tableDir(
        "a.parquet" -> a,
        commit(0)   ->
          utf8(
            Seq(protocolLine(1), metaLine(pointSchema), addLine("a.parquet", a.length)).mkString(
              "\n"
            )
          ),
        commit(1) -> utf8(protocolLine(3, Seq("deletionVectors")))
      ).path
    intercept[ParquetException](Parquet.dataset(root))
    val v0 = Parquet.dataset(root, ParquetDatasetOptions.default.withVersion(0))
    try v0.readAs[DeltaPoint]().toSeq shouldBe Seq(DeltaPoint(1, 1.5))
    finally v0.close()
  }

  test("partition values: empty strings are null, absolute paths, and unsupported types") {
    val schema =
      """{"type":"struct","fields":[{"name":"id","type":"integer","nullable":true,"metadata":{}},{"name":"v","type":"double","nullable":true,"metadata":{}},{"name":"flag","type":"boolean","nullable":true,"metadata":{}},{"name":"n","type":"long","nullable":true,"metadata":{}}]}"""
    val a   = points(1)
    val b   = points(2)
    val dir = tableDir("x/a%20b.parquet" -> a, "b.parquet" -> b)
    // file:///C:/... on Windows, file:///tmp/... elsewhere
    val absB = s"file:///${dir.posixPath.stripPrefix("/")}/b.parquet"
    FileSystem.writeBytes(
      dir.resolve(commit(0)),
      utf8(
        Seq(
          protocolLine(1),
          metaLine(schema, Seq("flag", "n")),
          // %25 decodes to %, the file name on disk
          addLine("x/a%2520b.parquet", a.length, """{"flag":"true","n":""}"""),
          addLine(absB, b.length, """{"flag":"false","n":"7"}""")
        ).mkString("\n")
      )
    )
    val e = intercept[ParquetException](Parquet.dataset(dir.path))
    e.getMessage shouldContain "withPartitionType(\"flag\", ParquetType.STRING)"
    val ds = Parquet.dataset(
      dir.path,
      ParquetDatasetOptions.default.withPartitionType("flag", ParquetType.STRING)
    )
    try
      ds.files.map(_.path) shouldBe
        IndexedSeq(dir.resolve("b.parquet").path, dir.resolve("x/a%20b.parquet").path)
      ds.readRows()
        .toSeq
        .sortBy(_("id").asInstanceOf[Int])
        .map(r => (r("id"), r("flag"), r("n"))) shouldBe Seq((1, "true", null), (2, "false", 7L))
    finally
      ds.close()
  }

  test("Delta-only options need a Delta root") {
    val plain = tableDir("a.parquet" -> points(1)).path
    intercept[ParquetException] {
      Parquet.dataset(
        plain,
        ParquetDatasetOptions.default.withTableFormat(ParquetTableFormat.Delta)
      )
    }.getMessage shouldContain "is not a Delta table"
    intercept[IllegalArgumentException] {
      Parquet.dataset(plain, ParquetDatasetOptions.default.withVersion(0))
    }
    intercept[IllegalArgumentException] {
      Parquet.dataset(
        s"${fixture("partitioned")}/dt=*",
        ParquetDatasetOptions.default.withVersion(0)
      )
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

  test("read a Delta table in an object store asynchronously") {
    storeWith("partitioned").flatMap { store =>
      Parquet
        .datasetAsync(store, bucketRoot)
        .flatMap { ds =>
          ds.files.size shouldBe 3
          ds.files.head.path shouldMatch {
            case p: String if p.startsWith("s3://lake/events/dt=") =>
          }
          ds.readAllAsync[DeltaEvent]().map(_.map(_.id).sorted)
        }
        .map(_ shouldBe Seq(1L, 3L, 4L, 5L, 6L))
    }
  }

  test("list only the log with withTableFormat(Delta)") {
    storeWith("checkpoint").flatMap { store =>
      Parquet
        .datasetAsync(
          store,
          bucketRoot,
          ParquetDatasetOptions.default.withTableFormat(ParquetTableFormat.Delta).withVersion(3)
        )
        .flatMap(_.readAllAsync[DeltaPoint]())
        .map(_.map(_.id).sorted shouldBe Seq(0, 1, 2, 3))
    }
  }

end DeltaTableTest
