package wvlet.uni.parquet

import wvlet.uni.io.FileSystem
import wvlet.uni.io.FileSystemInit
import wvlet.uni.io.IOPath
import wvlet.uni.io.ListOptions
import wvlet.uni.test.UniTest

/** Merge-on-read row updates: new data files plus deletes of the old rows, in one snapshot */
class IcebergRowUpdateTest extends UniTest with IcebergTableFixtures:
  FileSystemInit.init()

  private def rows(root: String): Seq[Map[String, Any]] =
    val ds = Parquet.dataset(root)
    try ds.readRows().toSeq.sortBy(_("id").asInstanceOf[Long])
    finally ds.close()

  private def items(root: String): Seq[(Long, String)] = rows(root).map(r =>
    r("id").asInstanceOf[Long] -> r("item").toString
  )

  private def dataFiles(root: String): Seq[String] =
    FileSystem
      .list(IOPath.parse(s"${root}/data"), ListOptions(recursive = true))
      .map(_.path)
      .filter(p => p.endsWith(".parquet") && !p.endsWith("-deletes.parquet"))
      .sorted

  /** The row IDs of the rows a filter matches, as an update reads them */
  private def rowIds(root: String, filter: ParquetFilter): Map[Long, Any] =
    val ds  = Parquet.dataset(root)
    val out = Map.newBuilder[Long, Any]
    try
      ds.foreachMatchingRows(filter) { m =>
        m.rows.zip(m.rowIds).foreach((r, id) => out += r("id").asInstanceOf[Long] -> id)
      }
    finally ds.close()
    out.result()

  private def upper(row: Map[String, Any]): Map[String, Any] = row.updated(
    "item",
    row("item").toString.toUpperCase
  )

  test("update rows of a version 2 table") {
    val root = create()
    Parquet.updateIcebergRows(root, col("id") === 2L || col("id") === 4L)(upper) shouldBe 2L
    items(root) shouldBe
      Seq(1L -> "apple", 2L -> "BANANA", 3L -> "cherry", 4L -> "DURIAN", 5L -> "elderberry")
    summary(root, "operation") shouldBe Some("overwrite")
    summary(root, "added-records") shouldBe Some("2")
    summary(root, "added-position-deletes") shouldBe Some("2")
    summary(root, "added-position-delete-files") shouldBe Some("2")
    summary(root, "total-records") shouldBe Some("7")
    // The other columns, including nulls and the partition column, are kept
    rows(root).find(_("id") == 2L).get shouldBe
      Map("id" -> 2L, "item" -> "BANANA", "dt" -> d1, "country" -> null)
    // One commit holds both the new rows and the deletes
    newestVersion(root) shouldBe 2
  }

  test("update rows of a version 3 table with deletion vectors") {
    val root = create(v3)
    Parquet.updateIcebergRows(root, col("dt") === d2)(upper) shouldBe 3L
    items(root).map(_._2) shouldBe Seq("apple", "banana", "CHERRY", "DURIAN", "ELDERBERRY")
    summary(root, "operation") shouldBe Some("overwrite")
    summary(root, "added-dvs") shouldBe Some("1")
    // A second update of the same rows merges the vector of the new file
    Parquet.updateIcebergRows(root, col("id") === 3L)(_.updated("item", "cherry2")) shouldBe 1L
    items(root).find(_._1 == 3L) shouldBe Some(3L -> "cherry2")
    rows(root).size shouldBe 5
  }

  test("update nothing when no row matches") {
    val root   = create()
    val before = dataFiles(root)
    Parquet.updateIcebergRows(root, col("id") === 99L)(upper) shouldBe 0L
    newestVersion(root) shouldBe 1
    dataFiles(root) shouldBe before
  }

  test("an updated row keeps its row ID") {
    val root   = create(v3)
    val filter = col("id") === 2L || col("id") === 4L
    val before = rowIds(root, filter)
    before
      .values
      .foreach(id =>
        id shouldMatch { case _: Long =>
        }
      )
    before.values.toSet.size shouldBe 2
    Parquet.updateIcebergRows(root, filter)(upper) shouldBe 2L
    // The new files store the IDs, and a null last-updated sequence number to inherit the commit's
    val added = dataFiles(root)
      .flatMap(p => Parquet.readRows(FileSystem.readBytes(IOPath.parse(p))))
      .filter(_.contains("_row_id"))
    added.map(r => r("id") -> r("_row_id")).toMap shouldBe before
    added.foreach(_("_last_updated_sequence_number") shouldBe null)
    // The metadata columns stay hidden from reads, and the IDs survive another update
    rows(root).foreach(_.keySet shouldBe Set("id", "item", "dt", "country"))
    rowIds(root, filter) shouldBe before
    Parquet.updateIcebergRows(root, col("id") === 2L)(_.updated("item", "b")) shouldBe 1L
    rowIds(root, filter) shouldBe before
    // Rows that weren't updated keep the IDs they inherit
    rowIds(root, col("id") === 1L).values.head shouldMatch { case _: Long =>
    }
  }

  test("a table upgraded to version 3 without a commit gives updated rows fresh IDs") {
    val root = create()
    Parquet.alterIcebergTable(root, IcebergTableChange.SetProperty("format-version", "3"))
    rowIds(root, col("id") === 1L).values.toSeq shouldBe Seq(null)
    Parquet.updateIcebergRows(root, col("id") === 1L)(upper) shouldBe 1L
    items(root).head shouldBe (1L -> "APPLE")
    // The commit assigned IDs, so the updated row has one now
    rowIds(root, col("id") === 1L).values.head shouldMatch { case _: Long =>
    }
  }

  test("move rows to another partition") {
    val root = create(v3)
    Parquet.updateIcebergRows(root, col("id") === 1L)(_.updated("dt", d2)) shouldBe 1L
    val ds = Parquet.dataset(root)
    try
      ds.readRows(ParquetReadOptions.default.withFilter(col("dt") === d2))
        .map(_("id").asInstanceOf[Long])
        .toSeq
        .sorted shouldBe Seq(1L, 3L, 4L, 5L)
    finally ds.close()
    dataFiles(root).exists(_.contains("dt=2026-11-21")) shouldBe true
  }

  test("a row that doesn't fit the schema fails and leaves no files") {
    val root   = create(v3)
    val before = dataFiles(root)
    intercept[Exception](
      Parquet.updateIcebergRows(root, col("id") === 1L)(_.updated("id", "not a number"))
    )
    newestVersion(root) shouldBe 1
    dataFiles(root) shouldBe before
    items(root).head shouldBe (1L -> "apple")
  }

  test("an update that loses the race reads the rows again") {
    val root   = create(v3)
    var calls  = 0
    val before = dataFiles(root)
    Parquet.updateIcebergRows(root, col("dt") === d2) { row =>
      calls += 1
      if calls == 1 then
        // Another writer deletes a row of the same file while the update runs
        Parquet.deleteIcebergRows(root, col("id") === 4L) shouldBe 1L
      upper(row)
    } shouldBe 2L
    // Three rows on the first attempt, two on the retry
    calls shouldBe 5
    items(root) shouldBe Seq(1L -> "apple", 2L -> "banana", 3L -> "CHERRY", 5L -> "ELDERBERRY")
    // The first attempt's data file was deleted; only the retry's was added
    dataFiles(root).diff(before).size shouldBe 1
  }

  test("refuse a root that isn't an Iceberg table") {
    val root = newRoot()
    Parquet.writeDataset(root, sales, ParquetDatasetWriteOptions.default)
    intercept[ParquetException](Parquet.updateIcebergRows(root, col("id") === 1L)(upper))
      .getMessage shouldContain "is not an Iceberg table"
  }

  // ---- Tables other engines wrote ----

  private def names(root: String): Seq[(Long, String)] = rows(root).map(r =>
    r("id").asInstanceOf[Long] -> r("name").toString
  )

  private val duckdbUpdated = (0L until 10L).map(i =>
    i -> (
      if i % 3 == 0 then
        s"N${i}"
      else
        s"n${i}"
    )
  )

  test("update on top of DuckDB's update of a version 3 table, keeping its row IDs") {
    val root = hadoopCopy("v3_update")
    names(root) shouldBe duckdbUpdated
    // DuckDB stored the IDs of its updated rows; the others inherit theirs
    val all = rowIds(root, col("id") >= 0L)
    all shouldBe (0L until 10L).map(i => i -> i).toMap
    Parquet.updateIcebergRows(root, col("id") === 3L || col("id") === 4L)(
      _.updated("name", "u")
    ) shouldBe 2L
    names(root).filter(_._2 == "u").map(_._1) shouldBe Seq(3L, 4L)
    rowIds(root, col("id") >= 0L) shouldBe all
  }

  test("update on top of DuckDB's update of a version 2 table") {
    val root = hadoopCopy("v2_update")
    names(root) shouldBe duckdbUpdated
    Parquet.updateIcebergRows(root, col("id") === 3L || col("id") === 4L)(
      _.updated("name", "u")
    ) shouldBe 2L
    names(root).filter(_._2 == "u").map(_._1) shouldBe Seq(3L, 4L)
    rows(root).size shouldBe 10
  }

  test("update on top of DuckDB's deletion vectors") {
    val root = hadoopCopy("v3_deletes")
    ids(root) shouldBe Seq(1L, 2L, 4L, 5L, 7L, 8L)
    Parquet.updateIcebergRows(root, col("id") === 4L || col("id") === 8L)(
      _.updated("id", 40L)
    ) shouldBe 2L
    ids(root) shouldBe Seq(1L, 2L, 5L, 7L, 40L, 40L)
    val refs = liveDeletes(root)
      .filter(_("file_format").toString.equalsIgnoreCase("puffin"))
      .map(_("referenced_data_file"))
    refs.distinct.size shouldBe refs.size
  }

  test("update on top of DuckDB's position-delete files") {
    val root     = hadoopCopy("v2_deletes")
    val expected = (0L until 20L).filterNot(i => i % 4 == 1 || i == 12)
    Parquet.updateIcebergRows(root, col("id") < 4L)(r =>
      r.updated("id", r("id").asInstanceOf[Long] + 100)
    ) shouldBe 3L
    ids(root) shouldBe (expected.filter(_ >= 4L) ++ expected.filter(_ < 4L).map(_ + 100)).sorted
  }

  test("rows that equality deletes removed aren't updated") {
    val root = hadoopCopy("equality_deletes")
    ids(root) shouldBe Seq(2L, 4L, 5L, 6L)
    Parquet.updateIcebergRows(root, col("id") <= 4L)(r =>
      r.updated("id", r("id").asInstanceOf[Long] * 10)
    ) shouldBe 2L
    ids(root) shouldBe Seq(5L, 6L, 20L, 40L)
  }

end IcebergRowUpdateTest
