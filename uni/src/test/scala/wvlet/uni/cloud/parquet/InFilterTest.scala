package wvlet.uni.cloud.parquet

import wvlet.uni.io.FileSystem
import wvlet.uni.io.FileSystemInit
import wvlet.uni.io.IOPath
import wvlet.uni.cloud.parquet.ParquetFilter.*
import wvlet.uni.test.UniTest
import wvlet.uni.weaver.Weaver

import java.time.LocalDate

case class InRow(id: Long, store: Option[Int], day: LocalDate, name: String, small: Short)
    derives Weaver

case class InPart(id: Long) derives Weaver

class InFilterTest extends UniTest:
  FileSystemInit.init()

  private val rows = (0 until 3000).map { i =>
    InRow(
      id = i.toLong,
      store = Option.when(i % 7 != 0)(i % 50),
      day = LocalDate.of(2026, 1, 1).plusDays(i % 40),
      name = s"n${i % 10}",
      small = (i % 100).toShort
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
    withFile(_.readAs[InRow](opts.withFilter(f)).map(_.id).toSeq)

  private def expected(p: InRow => Boolean): Seq[Long] = rows.filter(p).map(_.id)

  test("keep the rows whose value is in the set") {
    withFile(_.numRowGroups > 2) shouldBe true
    ids(col("id").isIn(5L, 2999L, 1234L)) shouldBe Seq(5L, 1234L, 2999L)
    ids(col("id").isIn(Seq(-1L, 3000L))) shouldBe Seq.empty
    ids(col("small").isIn(3L, 97L)) shouldBe expected(r => r.small == 3 || r.small == 97)
    val stores = Set(3L, 4L, 10L, 49L)
    ids(col("store").isIn(stores)) shouldBe expected(_.store.exists(s => stores(s.toLong)))
  }

  test("drop null values, as SQL IN does") {
    ids(col("store").isIn((0L until 50L).toSeq)) shouldBe expected(_.store.isDefined)
    // Unknown OR true is true; unknown AND true is unknown
    ids(col("store").isIn(1L) || col("id") === 0L) shouldBe
      expected(r => r.id == 0 || r.store.contains(1))
    ids(col("store").isIn((0L until 50L).toSeq) && col("id") < 8L) shouldBe
      Seq(1L, 2L, 3L, 4L, 5L, 6L)
  }

  test("test runs of one value, as fact tables store an order's rows, with nulls among them") {
    // Runs of 1 to 20 rows share a key, 0 included; some rows in a run are null
    val rnd  = scala.util.Random(3)
    val runs =
      Iterator
        .continually((rnd.nextInt(6).toLong, 1 + rnd.nextInt(20)))
        .flatMap((k, len) => Iterator.fill(len)(k))
        .take(5000)
        .toIndexedSeq
    val clustered = runs
      .zipWithIndex
      .map((k, i) =>
        InRow(
          id = i.toLong,
          store = Option.when(i % 9 != 4)(k.toInt),
          day = LocalDate.of(2026, 1, 1),
          name = s"n${k}",
          small = (i % 100).toShort
        )
      )
    val f = Parquet.open(
      Parquet.writeBytes(clustered, ParquetWriteOptions.default.withRowGroupSize(16 * 1024))
    )
    try
      for set <- Seq(Seq(0L), Seq(1L, 4L), Seq(0L, 2L, 3L, 5L)) do
        // A second conjunct, so the other columns are read for the selected rows only
        val got = f.readAs[InRow](
          ParquetReadOptions.default.withFilter(col("store").isIn(set) && col("small") < 90L)
        )
        got.toSeq shouldBe
          clustered.filter(r => r.store.exists(s => set.contains(s.toLong)) && r.small < 90)
    finally f.close()
  }

  test("keep no rows for an empty set") {
    ids(col("id").isIn()) shouldBe Seq.empty
    withFile(
      _.selectRowGroups(ParquetReadOptions.default.withRowGroupFilter(col("id").isIn()))
    ) shouldBe IndexedSeq.empty
  }

  test("test DATE columns by epoch days") {
    val days = Seq(LocalDate.of(2026, 1, 3), LocalDate.of(2026, 2, 9))
    ids(col("day").isIn(days.map(_.toEpochDay))) shouldBe expected(r => days.contains(r.day))
  }

  test("combine with other conjuncts in any order") {
    val f1   = col("store").isIn(3L, 4L) && col("name") === "n3"
    val f2   = col("name") === "n3" && col("store").isIn(3L, 4L)
    val want = expected(r => r.name == "n3" && r.store.exists(s => s == 3 || s == 4))
    ids(f1) shouldBe want
    ids(f2) shouldBe want
    // Filter-only columns are read but not returned
    withFile { f =>
      val out = f.readRows(ParquetReadOptions.default.withColumns("name").withFilter(f1)).toSeq
      out.size shouldBe want.size
      out.head.keys.toSeq shouldBe Seq("name")
    }
  }

  test("skip row groups whose statistics miss every value") {
    withFile { f =>
      val n    = f.rowGroups.head.numRows
      val opts = ParquetReadOptions.default
      f.selectRowGroups(opts.withRowGroupFilter(col("id").isIn(0L, 1L))) shouldBe IndexedSeq(0)
      // The values straddle the first row group's range without falling inside it
      f.selectRowGroups(opts.withRowGroupFilter(col("id").isIn(-5L, 99999L))) shouldBe
        IndexedSeq.empty
      f.selectRowGroups(opts.withRowGroupFilter(col("id").isIn(n))) shouldBe IndexedSeq(1)
    }
  }

  test("accept a caller's own set") {
    // Accepts even ids in [100, 120], like a join build's bitmap over its keys
    object Evens extends IntegerSet:
      def contains(v: Long): Boolean = v >= 100 && v <= 120 && v % 2 == 0
      def min: Long                  = 100
      def max: Long                  = 120
    ids(col("id").isIn(Evens)) shouldBe (100L to 120L by 2L)
    withFile(
      _.selectRowGroups(ParquetReadOptions.default.withRowGroupFilter(col("id").isIn(Evens)))
    ) shouldBe IndexedSeq(0)
  }

  test("filter asynchronously") {
    Parquet
      .openAsync(ByteSource.ofBytes(bytes))
      .flatMap(f =>
        f.readAllAsync[InRow](ParquetReadOptions.default.withFilter(col("id").isIn(7L, 2998L)))
      )
      .map(_.map(_.id) shouldBe Seq(7L, 2998L))
  }

  test("reject columns that aren't integers or dates") {
    val e = intercept[IllegalArgumentException](ids(col("name").isIn(1L)))
    e.getMessage shouldContain "isIn"
    e.getMessage shouldContain "name"
    intercept[IllegalArgumentException](ids(col("nope").isIn(1L))).getMessage shouldContain "nope"
  }

  test("build exact sets of any shape") {
    def check(values: Seq[Long], probes: Seq[Long]): Unit =
      val s = IntegerSet.of(values)
      probes.foreach(p => s.contains(p) shouldBe values.contains(p))
      if values.nonEmpty then
        s.min shouldBe values.min
        s.max shouldBe values.max
    // Dense (bitmap), sparse (hash table, with 0 kept aside), and extreme values
    check((0L until 200L by 3L), (-5L until 210L))
    val sparse = Seq(0L, -1L, 7L, 1L << 40, -(1L << 50), 123456789L)
    check(sparse, sparse ++ sparse.map(_ + 1) ++ Seq(2L, 8L))
    val extreme = Seq(Long.MinValue, Long.MaxValue, 0L)
    check(extreme, extreme ++ Seq(Long.MinValue + 1, Long.MaxValue - 1, 1L))
    check(Seq.empty, Seq(0L, 1L))
    check(Seq(5L, 5L, 5L), Seq(4L, 5L, 6L))
    val s = IntegerSet.of(Seq(10L, 20L, 30L))
    s.mayIntersect(11, 19) shouldBe false
    s.mayIntersect(11, 20) shouldBe true
    s.mayIntersect(31, 40) shouldBe false
    s.mayIntersect(0, 10) shouldBe true
  }

  // ---- Datasets ----

  private var dirs: List[IOPath] = Nil

  override protected def afterAll: Unit = dirs.foreach(FileSystem.deleteRecursively)

  test("test partition values") {
    if FileSystem.isBrowser then
      skip("Datasets list local directories, which browsers don't have")
    val root = FileSystem.createTempDirectory("uni-parquet-in")
    dirs = root :: dirs
    (1 to 3).foreach { p =>
      val dir = root.resolve(s"p=${p}")
      FileSystem.createDirectory(dir)
      FileSystem.writeBytes(
        dir.resolve("part-0.parquet"),
        Parquet.writeBytes((0 until 3).map(i => InPart(p * 10L + i)))
      )
    }
    val f  = ParquetReadOptions.default.withFilter(col("p").isIn(1L, 3L))
    val ds = Parquet.dataset(
      root.path,
      ParquetDatasetOptions.default.withPartitionType("p", ParquetType.INT64)
    )
    try
      ds.readRows(f).map(_("id")).toSeq.sortBy(_.asInstanceOf[Long]) shouldBe
        Seq(10L, 11L, 12L, 30L, 31L, 32L)
    finally ds.close()
    // Partition values read as strings by default, which isIn can't test
    val strings = Parquet.dataset(root.path)
    try
      intercept[IllegalArgumentException](strings.readRows(f).toSeq).getMessage shouldContain "isIn"
    finally strings.close()
  }

end InFilterTest
