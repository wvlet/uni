package wvlet.uni.cloud.parquet

import wvlet.uni.io.FileSystem
import wvlet.uni.io.FileSystemInit
import wvlet.uni.io.IOPath
import wvlet.uni.cloud.parquet.ParquetFilter.*
import wvlet.uni.test.UniTest
import wvlet.uni.weaver.Weaver

import java.time.Instant
import java.time.LocalDate

case class FilterRow(id: Int, name: Option[String], score: Double, day: LocalDate, ts: Instant)
    derives Weaver

case class FilterCity(city: Option[String], zip: Int) derives Weaver
case class FilterPerson(id: Int, address: Option[FilterCity], tags: Seq[String]) derives Weaver
case class FilterPrice(id: Int, price: BigDecimal) derives Weaver
case class FilterV1(id: Long, x: Int) derives Weaver
case class FilterV2(id: Long, x: Double, note: Option[String]) derives Weaver

class RowFilterTest extends UniTest:
  FileSystemInit.init()

  private val base = Instant.parse("2026-11-21T00:00:00Z")

  private val rows = (0 until 3000).map { i =>
    FilterRow(
      id = i,
      name = Option.when(i % 3 != 0)(s"n${i % 10}"),
      score =
        if i % 100 == 7 then
          Double.NaN
        else if i == 50 then
          -0.0
        else
          (i % 50).toDouble
      ,
      day = LocalDate.of(2026, 1, 1).plusDays(i % 30),
      ts = base.plusMillis(i.toLong)
    )
  }

  // Several row groups, so statistics skip some and rows are filtered in others
  private val bytes = Parquet.writeBytes(
    rows,
    ParquetWriteOptions.default.withRowGroupSize(16 * 1024)
  )

  private def withFile[A](body: ParquetFile => A): A =
    val f = Parquet.open(bytes)
    try body(f)
    finally f.close()

  private def ids(f: ParquetFilter, opts: ParquetReadOptions = ParquetReadOptions.default) =
    withFile(_.readAs[FilterRow](opts.withFilter(f)).map(_.id).toSeq)

  private def expected(p: FilterRow => Boolean): Seq[Int] = rows.filter(p).map(_.id)

  test("return only the matching rows") {
    withFile(_.numRowGroups > 1) shouldBe true
    ids(col("id") >= 2990) shouldBe (2990 until 3000)
    ids(col("id") === 5L) shouldBe Seq(5)
    ids(col("id") > 10 && col("id") <= 12) shouldBe Seq(11, 12)
    ids(col("id") < 2 || col("id") > 2997) shouldBe Seq(0, 1, 2998, 2999)
    ids(col("name") === "n4") shouldBe expected(_.name.contains("n4"))
  }

  test("treat comparisons with null as unknown") {
    ids(col("name") <= "n1") shouldBe expected(_.name.exists(_ <= "n1"))
    ids(col("name").isNull) shouldBe expected(_.name.isEmpty)
    ids(col("name").isNotNull && col("id") < 6) shouldBe Seq(1, 2, 4, 5)
    // Unknown OR true is true
    ids(col("name") === "nope" || col("id") < 4) shouldBe Seq(0, 1, 2, 3)
    // Unknown AND true is unknown, so the row is dropped
    ids(col("name") > "" && col("id") < 4) shouldBe Seq(1, 2)
  }

  test("compare floating point values as IEEE 754") {
    ids(col("score") > 48.0) shouldBe expected(r => r.score > 48.0)
    ids(col("score") >= 0) shouldBe expected(r => !r.score.isNaN)
    // -0.0 equals 0.0
    ids(col("score") === 0.0) shouldBe expected(_.score == 0.0)
    ids(col("score") === -0.0).contains(50) shouldBe true
    intercept[IllegalArgumentException](ids(col("score") > Double.NaN))
    intercept[IllegalArgumentException](ids(col("score") > (1L << 60)))
  }

  test("convert literals without loss") {
    ids(col("id") === "7") shouldBe Seq(7)
    ids(col("id") === 7.toShort) shouldBe Seq(7)
    ids(col("day") === LocalDate.of(2026, 1, 3)) shouldBe
      expected(_.day == LocalDate.of(2026, 1, 3))
    ids(col("day") === "2026-01-03") shouldBe expected(_.day == LocalDate.of(2026, 1, 3))
    val e = intercept[IllegalArgumentException](ids(col("id") > 1.5))
    e.getMessage shouldContain "id"
    intercept[IllegalArgumentException](ids(col("day") === "Jan 3"))
    intercept[IllegalArgumentException](ids(col("name") === 3))
    intercept[IllegalArgumentException](ids(col("name") === null)).getMessage shouldContain "isNull"
    intercept[IllegalArgumentException](ids(col("nope") === 1)).getMessage shouldContain "nope"
  }

  test("compare timestamps exactly") {
    val t = base.plusMillis(5)
    ids(col("ts") === t) shouldBe Seq(5)
    // Just above row 5's value, so row 5 is less and row 6 is greater
    val above = t.plusNanos(1)
    ids(col("ts") < above && col("id") > 2) shouldBe Seq(3, 4, 5)
    ids(col("ts") > above && col("id") < 8) shouldBe Seq(6, 7)
    ids(col("ts") === above) shouldBe Seq.empty
    ids(col("ts") < Instant.MIN) shouldBe Seq.empty
    ids(col("ts") < Instant.MAX).size shouldBe rows.size
  }

  test("keep a row group when a sub-unit Instant lies above its min") {
    withFile { f =>
      val first = f.rowGroups.head.numRows.toInt
      // The literal is above row 0's value by less than the column's unit
      val opts = ParquetReadOptions.default.withRowGroupFilter(col("ts") < base.plusNanos(1))
      f.selectRowGroups(opts) shouldBe IndexedSeq(0)
      (first > 1) shouldBe true
    }
  }

  test("read the filter's columns without returning them") {
    withFile { f =>
      val out =
        f.readRows(ParquetReadOptions.default.withColumns("id").withFilter(col("name") === "n2"))
          .toSeq
      out.head.keys.toSeq shouldBe Seq("id")
      out.map(_("id")) shouldBe expected(_.name.contains("n2"))
    }
  }

  test("filter every read path") {
    val opts = ParquetReadOptions.default.withFilter(col("id") >= 1000 && col("id") < 1003)
    withFile { file =>
      file.readRows(opts).map(_("id")).toSeq shouldBe Seq(1000, 1001, 1002)
      file.readJson(opts).map(_.get("id").get.toJSON).toSeq shouldBe Seq("1000", "1001", "1002")
      val batches = file.readBatches(opts.withBatchSize(2)).toSeq
      batches.forall(_.numRows > 0) shouldBe true
      batches.map(_.numRows).sum shouldBe 3
      // readRowGroup always returns its batch, even with no rows left
      file.readRowGroup(0, ParquetReadOptions.default.withFilter(col("id") < 0)).numRows shouldBe 0
    }
  }

  test("filter asynchronously") {
    val opts = ParquetReadOptions.default.withFilter(col("id") >= 2997)
    Parquet
      .openAsync(ByteSource.ofBytes(bytes))
      .flatMap { f =>
        f.readAllAsync[FilterRow](opts)
          .flatMap { out =>
            out.map(_.id) shouldBe Seq(2997, 2998, 2999)
            f.readAllRowsAsync(opts.withColumns("name"))
          }
          .flatMap { out =>
            out shouldBe Seq(Map("name" -> null), Map("name" -> "n8"), Map("name" -> "n9"))
            f.readRowsAsync(opts).lastOption.toRx
          }
          .flatMap { last =>
            last.map(_("id")) shouldBe Some(2999)
            f.readRowGroupAsync(0, ParquetReadOptions.default.withFilter(col("id") === 1))
          }
          .map { b =>
            b.numRows shouldBe 1
          }
      }
  }

  test("report a bad filter from async reads") {
    Parquet
      .openAsync(ByteSource.ofBytes(bytes))
      .flatMap(f => f.readBatchesAsync(ParquetReadOptions.default.withFilter(col("nope") === 1)))
      .map(_ => false)
      .recover { case e: IllegalArgumentException =>
        true
      }
      .map(_ shouldBe true)
  }

  private val people = Seq(
    FilterPerson(1, Some(FilterCity(Some("Tokyo"), 100)), Seq("a")),
    FilterPerson(2, Some(FilterCity(None, 200)), Seq.empty),
    FilterPerson(3, None, Seq("b", "c")),
    FilterPerson(4, Some(FilterCity(Some("Osaka"), 300)), Seq("d"))
  )

  private def personIds(f: ParquetFilter): Seq[Int] =
    val file = Parquet.open(Parquet.writeBytes(people))
    try file.readAs[FilterPerson](ParquetReadOptions.default.withFilter(f)).map(_.id).toSeq
    finally file.close()

  test("filter nested columns by dotted paths") {
    personIds(col("address.city") === "Tokyo") shouldBe Seq(1)
    // A null struct makes its fields null
    personIds(col("address.city").isNull) shouldBe Seq(2, 3)
    personIds(col("address.zip") >= 200) shouldBe Seq(2, 4)
    personIds(col("address").isNull) shouldBe Seq(3)
    // The matching rows keep their lists
    val file = Parquet.open(Parquet.writeBytes(people))
    try
      file
        .readAs[FilterPerson](ParquetReadOptions.default.withFilter(col("id") >= 3))
        .toSeq shouldBe people.drop(2)
    finally file.close()
    intercept[IllegalArgumentException](personIds(col("tags") === "a")).getMessage shouldContain
      "list"
    intercept[IllegalArgumentException](personIds(col("address") === "x")).getMessage shouldContain
      "address.city"
  }

  test("reject types filters can't compare") {
    val file = Parquet.open(Parquet.writeBytes(Seq(FilterPrice(1, BigDecimal("1.5")))))
    try
      val e = intercept[IllegalArgumentException](
        file.readRows(ParquetReadOptions.default.withFilter(col("price") > 1)).toSeq
      )
      e.getMessage shouldContain "withRowGroupFilter"
      // withRowGroupFilter ignores what it can't check
      file.readRows(ParquetReadOptions.default.withRowGroupFilter(col("price") > 1)).size shouldBe 1
    finally
      file.close()
  }

  test("keep the old RowGroupFilter name working") {
    @annotation.nowarn("cat=deprecation")
    val f: RowGroupFilter = RowGroupFilter.col("id") === 1
    ids(f) shouldBe Seq(1)
  }

  // ---- Datasets ----

  private var dirs: List[IOPath] = Nil

  override protected def afterAll: Unit = dirs.foreach(FileSystem.deleteRecursively)

  private def datasetDir(files: (String, Array[Byte])*): IOPath =
    val root = FileSystem.createTempDirectory("uni-parquet-filter")
    dirs = root :: dirs
    files.foreach { (rel, b) =>
      val path = root.resolve(rel)
      mkdirs(path.parent.get)
      FileSystem.writeBytes(path, b)
    }
    root

  private def mkdirs(p: IOPath): Unit =
    if !FileSystem.exists(p) then
      p.parent.foreach(mkdirs)
      FileSystem.createDirectory(p)

  private def evolving = datasetDir(
    "dt=2026-11-19/part-0.parquet" -> Parquet.writeBytes((0 until 4).map(i => FilterV1(i, i * 10))),
    "dt=2026-11-20/part-0.parquet" ->
      Parquet.writeBytes(
        (4 until 8).map(i => FilterV2(i, i * 10 + 0.5, Option.when(i % 2 == 0)(s"note${i}")))
      )
  )

  test("filter dataset rows after schema evolution and widening") {
    if FileSystem.isBrowser then
      skip("Datasets list local directories, which browsers don't have")
    val ds = Parquet.dataset(evolving.path)
    try
      def dsIds(f: ParquetFilter, opts: ParquetReadOptions = ParquetReadOptions.default) =
        ds.readRows(opts.withFilter(f)).map(_("id")).toSeq
      // INT32 x in the first file is widened to DOUBLE before the comparison
      dsIds(col("x") > 25.0) shouldBe Seq(3L, 4L, 5L, 6L, 7L)
      dsIds(col("x") === 60.5) shouldBe Seq(6L)
      // The first file lacks `note`, so it reads as null there
      dsIds(col("note").isNull) shouldBe Seq(0L, 1L, 2L, 3L, 5L, 7L)
      dsIds(col("note") === "note4") shouldBe Seq(4L)
      // Partitions and file columns in one filter
      dsIds(col("dt") === "2026-11-20" || col("x") < 15) shouldBe Seq(0L, 1L, 4L, 5L, 6L, 7L)
      dsIds(col("dt") === "2026-11-19" && col("x") >= 20) shouldBe Seq(2L, 3L)
      // Filter-only columns are not returned
      val out =
        ds.readRows(
            ParquetReadOptions.default.withColumns("id").withFilter(col("note") === "note6")
          )
          .toSeq
      out shouldBe Seq(Map("id" -> 6L))
      intercept[IllegalArgumentException](dsIds(col("x") === "abc"))
      // A dotted name in withColumns is still an unknown column
      intercept[IllegalArgumentException](
        ds.readRows(ParquetReadOptions.default.withColumns("id.x"))
      )
    finally
      ds.close()
  }

  test("filter dataset rows asynchronously") {
    if FileSystem.isBrowser then
      skip("Datasets list local directories, which browsers don't have")
    val opts = ParquetReadOptions.default.withFilter(col("note").isNotNull || col("x") === 10)
    Parquet
      .datasetAsync(evolving.path)
      .flatMap { ds =>
        ds.readAllRowsAsync(opts.withColumns("id"))
          .map { out =>
            out.map(_("id")) shouldBe Seq(1L, 4L, 6L)
          }
          .tapOn { case _ =>
            ds.close()
          }
      }
  }

end RowFilterTest
