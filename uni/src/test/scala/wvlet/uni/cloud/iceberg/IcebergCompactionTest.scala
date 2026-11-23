package wvlet.uni.cloud.iceberg

import wvlet.uni.cloud.parquet.*

import wvlet.uni.io.FileSystem
import wvlet.uni.io.FileSystemInit
import wvlet.uni.io.IOPath
import wvlet.uni.json.JSON
import wvlet.uni.json.JSON.*
import wvlet.uni.test.UniTest
import wvlet.uni.weaver.Weaver

import java.nio.charset.StandardCharsets

case class RespecRow(id: Long, country: Option[String]) derives Weaver

/** Compacting Iceberg tables: rewriting data files with their deletes and merging small files */
class IcebergCompactionTest extends UniTest:
  FileSystemInit.init()

  private var dirs: List[IOPath] = Nil

  override protected def afterAll: Unit = dirs.foreach(FileSystem.deleteRecursively)

  private def newRoot(): String =
    val root = FileSystem.createTempDirectory("uni-iceberg-compact")
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

  private val v3 = Map(IcebergWrite.FormatVersionProperty -> "3")

  private def options(properties: Map[String, String]) = ParquetDatasetWriteOptions
    .default
    .withTableFormat(ParquetTableFormat.Iceberg)
    .withPartitionBy("dt")
    .withTableProperties(properties)

  private def create(properties: Map[String, String] = Map.empty): String =
    val root = newRoot()
    Parquet.writeDataset(root, sales, options(properties))
    root

  private def append(root: String, rows: Seq[IcebergSale]): Unit = Parquet.writeDataset(
    root,
    rows,
    options(Map.empty).withMode(ParquetWriteMode.Append)
  )

  private def col(name: String) = ParquetFilter.col(name)

  private def ids(root: String): Seq[Long] =
    val ds = Parquet.dataset(root)
    try ds.readRows().map(_("id").asInstanceOf[Long]).toSeq.sorted
    finally ds.close()

  private def rows(root: String): Seq[Map[String, Any]] =
    val ds = Parquet.dataset(root)
    try ds.readRows().toSeq.sortBy(_("id").toString)
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

  private def currentSnapshot(root: String): JSONObject =
    val m  = metadata(root, newestVersion(root))
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
    currentSnapshot(root)
      .get("summary")
      .collect { case o: JSONObject =>
        o.get(key)
          .collect { case JSONString(v) =>
            v
          }
      }
      .flatten

  private def live(root: String): IcebergWrite.LiveFiles =
    val target = DatasetTarget.Local(IOPath.parse(root))
    val layout = DatasetWriterCore.icebergLayout(target)
    val t      = DatasetWriterCore.runStepsSync(target, IcebergWrite.load(layout)).get
    DatasetWriterCore.runStepsSync(target, IcebergWrite.liveFiles(layout, t))

  /** Each row's `_row_id` and `_last_updated_sequence_number`, by its `id` */
  private def lineage(root: String): Map[Long, (Long, Long)] =
    val files = live(root).data.map(f => f.key -> f).toMap
    val ds    = Parquet.dataset(root)
    try
      ds.files
        .flatMap { file =>
          val f   = files(file.path)
          val out = Seq.newBuilder[(Long, (Long, Long))]
          ds.foreachLiveBatch(
            file,
            Some(ParquetDataset.Inherited(f.firstRowId, Some(f.sequence)))
          ) { (b, lineage) =>
            val l        = lineage.get
            val idColumn = b.column("id")
            (0 until b.numRows).foreach { r =>
              l.rowIdNulls(r) shouldBe false
              out += idColumn.getValue(r).asInstanceOf[Long] -> (l.rowIds(r), l.lastUpdated(r))
            }
          }
          out.result()
        }
        .toMap
    finally ds.close()

  test("compact a version 2 table with position deletes") {
    val root = create()
    Iceberg.deleteRows(root, col("id") === 2L || col("id") === 4L) shouldBe 2L
    val before = rows(root)
    live(root).deletes.size shouldBe 2
    val c = Iceberg.compactTable(root)
    c shouldBe IcebergCompaction(2, 2, 2, 3L)
    rows(root) shouldBe before
    live(root).deletes shouldBe Seq.empty
    live(root).data.map(_.records).sum shouldBe 3L
    summary(root, "operation") shouldBe Some("replace")
    summary(root, "total-records") shouldBe Some("3")
    summary(root, "total-delete-files") shouldBe Some("0")
    summary(root, "total-data-files") shouldBe Some("2")
    // Nothing is left to compact
    Iceberg.compactTable(root) shouldBe IcebergCompaction.empty
  }

  test("compact a version 3 table and keep row lineage") {
    val root = create(v3)
    Iceberg.deleteRows(root, col("id") === 3L) shouldBe 1L
    val ids1 = lineage(root)
    ids1.keySet shouldBe Set(1L, 2L, 4L, 5L)
    Iceberg.compactTable(root).removedDeleteFiles shouldBe 1
    lineage(root) shouldBe ids1
    // The lineage columns are metadata, not table columns
    val ds = Parquet.dataset(root)
    try ds.schema.fields.map(_.name) shouldBe Seq("id", "item", "dt", "country")
    finally ds.close()
    // A second round reads the stored values back
    Iceberg.deleteRows(root, col("id") === 4L) shouldBe 1L
    Iceberg.compactTable(root).removedDataFiles shouldBe 1
    lineage(root) shouldBe ids1 - 4L
    ids(root) shouldBe Seq(1L, 2L, 5L)
  }

  test("merge small files of a partition") {
    val root = create()
    (6 to 10).foreach(i => append(root, Seq(IcebergSale(i, s"item${i}", d1, Some("JP")))))
    live(root).data.size shouldBe 7
    // Without deletes, a partition needs minInputFiles small files
    Iceberg.compactTable(root, IcebergCompactionOptions.default.noFileMerging()) shouldBe
      IcebergCompaction.empty
    val before = rows(root)
    val c      = Iceberg.compactTable(root)
    c.removedDataFiles shouldBe 6
    c.addedDataFiles shouldBe 1
    rows(root) shouldBe before
    live(root).data.size shouldBe 2
  }

  test("cut new files at the target size") {
    val root = newRoot()
    val many = (1 to 2000).map(i => IcebergSale(i, s"item-${i}-${"x" * 50}", d1, Some("JP")))
    Parquet.writeDataset(root, many, options(Map.empty))
    Iceberg.deleteRows(root, col("id") === 1L) shouldBe 1L
    val c = Iceberg.compactTable(
      root,
      IcebergCompactionOptions
        .default
        .withTargetFileSize(16 * 1024)
        .withWriteOptions(ParquetWriteOptions.default.withRowGroupSize(8 * 1024))
    )
    (c.addedDataFiles > 1) shouldBe true
    ids(root) shouldBe (2L to 2000L)
  }

  test("rewrite only files past the delete ratio") {
    val root = create()
    Iceberg.deleteRows(root, col("id") === 3L) shouldBe 1L
    // One of the three rows of its file: below a ratio of 0.5
    Iceberg.compactTable(
      root,
      IcebergCompactionOptions.default.withMinDeleteRatio(0.5)
    ) shouldBe IcebergCompaction.empty
    Iceberg
      .compactTable(root, IcebergCompactionOptions.default.withMinDeleteRatio(0.3))
      .removedDataFiles shouldBe 1
    ids(root) shouldBe Seq(1L, 2L, 4L, 5L)
  }

  test("compact only the files a filter may match") {
    val root = create()
    Iceberg.deleteRows(root, col("id") === 1L || col("id") === 3L) shouldBe 2L
    val c = Iceberg.compactTable(
      root,
      IcebergCompactionOptions.default.withFilter(col("dt") === d1)
    )
    c shouldBe IcebergCompaction(1, 1, 1, 1L)
    live(root).deletes.size shouldBe 1
    ids(root) shouldBe Seq(2L, 4L, 5L)
  }

  test("remove deletes of files a copy-on-write rewrite replaced") {
    val root = create(v3)
    Iceberg.deleteRows(root, col("id") === 1L) shouldBe 1L
    // Rewrite the d1 file by hand, which leaves its deletion vector behind
    val ds   = Parquet.dataset(root)
    val file =
      try ds.files.find(_.partitions.get("dt").contains(d1)).get
      finally ds.close()
    Parquet.writeDataset(
      root,
      Seq(IcebergSale(2, "banana", d1, None)),
      options(Map.empty).withMode(ParquetWriteMode.Overwrite).withReplacedFiles(Seq(file.path))
    )
    live(root).deletes.size shouldBe 1
    val c = Iceberg.compactTable(root, IcebergCompactionOptions.default.noFileMerging())
    c shouldBe IcebergCompaction(0, 0, 1, 0L)
    live(root).deletes shouldBe Seq.empty
    ids(root) shouldBe Seq(2L, 3L, 4L, 5L)
  }

  test("a compaction rebases on a commit that touched nothing it rewrote") {
    val root   = create()
    val target = DatasetTarget.Local(IOPath.parse(root))
    val layout = DatasetWriterCore.icebergLayout(target)
    Iceberg.deleteRows(root, col("id") === 3L) shouldBe 1L
    val (plan, files, ds) = rewrite(root, target, layout)
    try
      // Another writer appends, and deletes a row of a file the compaction keeps
      append(root, Seq(IcebergSale(6, "fig", d1, Some("JP"))))
      Iceberg.deleteRows(root, col("id") === 1L) shouldBe 1L
      DatasetWriterCore.runStepsSync(
        target,
        IcebergWrite.compactionCommit(layout, plan, files).run()
      )
      ids(root) shouldBe Seq(2L, 4L, 5L, 6L)
      // The other writer's delete stays live
      live(root).deletes.size shouldBe 1
    finally
      ds.close()
  }

  test("a compaction plans again when a newer delete hits a rewritten file") {
    val root   = create(v3)
    val target = DatasetTarget.Local(IOPath.parse(root))
    val layout = DatasetWriterCore.icebergLayout(target)
    Iceberg.deleteRows(root, col("id") === 3L) shouldBe 1L
    val (plan, files, ds) = rewrite(root, target, layout)
    try
      Iceberg.deleteRows(root, col("id") === 4L) shouldBe 1L
      intercept[IcebergWrite.LostRace] {
        DatasetWriterCore.runStepsSync(
          target,
          IcebergWrite.compactionCommit(layout, plan, files).run()
        )
      }
      ids(root) shouldBe Seq(1L, 2L, 5L)
      Iceberg.compactTable(root).removedDataFiles shouldBe 1
      ids(root) shouldBe Seq(1L, 2L, 5L)
      live(root).deletes shouldBe Seq.empty
    finally
      ds.close()
  }

  /** Plans a compaction and writes its files, without committing */
  private def rewrite(
      root: String,
      target: DatasetTarget,
      layout: IcebergWrite.Layout
  ): (IcebergWrite.CompactionPlan, IndexedSeq[IcebergWrite.CompactedFile], ParquetDataset) =
    val t    = DatasetWriterCore.runStepsSync(target, IcebergWrite.load(layout)).get
    val l    = DatasetWriterCore.runStepsSync(target, IcebergWrite.liveFiles(layout, t))
    val plan = IcebergWrite.planCompaction(l, IcebergCompactionOptions.default, None)
    val ds   = Parquet.dataset(
      root,
      ParquetDatasetOptions.default.withSnapshotId(IcebergWrite.currentSnapshotId(t).get)
    )
    val files = Iceberg.rewriteGroups(
      target,
      layout,
      plan,
      ds,
      IcebergCompactionOptions.default,
      scala.collection.mutable.ArrayBuffer.empty
    )
    (plan, files, ds)

  test("refuse a root that isn't an Iceberg table") {
    val root = newRoot()
    Parquet.writeDataset(root, sales, ParquetDatasetWriteOptions.default)
    intercept[ParquetException](Iceberg.compactTable(root)).getMessage shouldContain
      "is not an Iceberg table"
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

  test("compact DuckDB's deletion vectors and keep row lineage") {
    val root   = hadoopCopy("v3_deletes")
    val before = lineage(root)
    ids(root) shouldBe Seq(1L, 2L, 4L, 5L, 7L, 8L)
    Iceberg.compactTable(root).removedDeleteFiles > 0 shouldBe true
    ids(root) shouldBe Seq(1L, 2L, 4L, 5L, 7L, 8L)
    live(root).deletes shouldBe Seq.empty
    lineage(root) shouldBe before
  }

  test("compact DuckDB's position-delete files") {
    val root     = hadoopCopy("v2_deletes")
    val expected = (0L until 20L).filterNot(i => i % 4 == 1 || i == 12)
    Iceberg.compactTable(root).removedDeleteFiles > 0 shouldBe true
    ids(root) shouldBe expected
    live(root).deletes shouldBe Seq.empty
  }

  test("remove equality deletes once no older file is left in their scope") {
    val root   = hadoopCopy("equality_deletes")
    val before = live(root).deletes.count(_.content == 2)
    before > 0 shouldBe true
    Iceberg.compactTable(root)
    ids(root) shouldBe Seq(2L, 4L, 5L, 6L)
    // Every file the deletes applied to was rewritten
    live(root).deletes shouldBe Seq.empty
    summary(root, "removed-equality-delete-files") shouldBe Some(before.toString)
  }

  test("compact a table with two partition specs, keeping each file's spec") {
    val root   = hadoopCopy("respec")
    val before = rows(root)
    // A row in each spec, so both specs get files with deletes
    Parquet.writeDataset(
      root,
      Seq(RespecRow(100L, Some("US"))),
      ParquetDatasetWriteOptions
        .default
        .withTableFormat(ParquetTableFormat.Iceberg)
        .withMode(ParquetWriteMode.Append)
    )
    Iceberg.deleteRows(root, col("id") === 100L) shouldBe 1L
    val specs = live(root).data.map(_.specId).toSet
    specs.size shouldBe 2
    Iceberg.compactTable(root, IcebergCompactionOptions.default.withMinInputFiles(1))
    rows(root) shouldBe before
    live(root).deletes shouldBe Seq.empty
    live(root).data.map(_.specId).toSet.subsetOf(specs) shouldBe true
  }

end IcebergCompactionTest
