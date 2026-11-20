package wvlet.uni.parquet

import wvlet.uni.parquet.ColumnVector.*
import wvlet.uni.io.FileSystemInit
import wvlet.uni.test.UniTest
import wvlet.uni.weaver.Weaver

import java.time.Instant
import java.time.LocalDate

case class S(x: Int, y: Option[String]) derives Weaver

case class AllTypes(
    id: Long,
    i32: Int,
    name: Option[String],
    d: Double,
    f: Float,
    b: Boolean,
    dt: String,
    ts: Instant,
    dec: BigDecimal,
    bigdec: BigDecimal,
    cat: String,
    tags: Option[Seq[String]],
    s: Option[S],
    m: Option[Map[String, Int]],
    nested: Option[Seq[Seq[Long]]],
    uuid: String
) derives Weaver

object AllTypes:
  private val baseDate = LocalDate.of(2020, 1, 1)
  private val baseTs   = Instant.parse("2020-01-01T00:00:00Z")

  /** The row the generator script writes for row number i */
  def expected(i: Int): AllTypes = AllTypes(
    id = i.toLong,
    i32 = i * 3 - 100,
    name =
      if i % 7 == 0 then
        None
      else
        Some(s"name-${i}")
    ,
    d = i * 0.5,
    f = (i / 4.0).toFloat,
    b = i % 3 == 0,
    dt = baseDate.plusDays(i.toLong).toString,
    ts = baseTs.plusSeconds(i.toLong),
    dec = BigDecimal(i) * BigDecimal("1.25"),
    bigdec = BigDecimal(i) * BigDecimal("1.5"),
    cat = Seq("a", "b", "c")(i % 3),
    tags =
      if i % 5 == 0 then
        None
      else
        Some((0 until i % 4).map(x => s"t${x}"))
    ,
    s =
      if i % 11 == 0 then
        None
      else
        Some(
          S(
            i,
            if i % 2 == 0 then
              None
            else
              Some(s"y${i}")
          )
        )
    ,
    m =
      if i % 13 == 0 then
        None
      else
        Some((0 until i % 3).map(x => s"k${x}" -> x * i).toMap)
    ,
    nested =
      if i % 4 == 0 then
        None
      else
        Some((0 until i % 3).map(x => Seq(x.toLong, (x + i).toLong)))
    ,
    uuid = f"00000000-0000-0000-0000-${i}%012d"
  )

end AllTypes

class DuckDBFilesTest extends UniTest:
  FileSystemInit.init()
  private val numRows = 3000

  private val supported = Seq("uncompressed", "snappy", "gzip", "lz4_raw", "zstd", "brotli")

  for
    codec   <- supported
    version <- Seq("V1", "V2")
  do
    val name = s"duckdb/all_${codec}_${version}.parquet"
    test(s"read ${name} into case classes") {
      val f = Parquet.open(TestFiles.bytes(name))
      try
        f.numRows shouldBe numRows.toLong
        // DuckDB rounds row groups up to its 2048-row vectors
        f.rowGroups.map(_.numRows) shouldBe Seq(2048L, 952L)
        val rows = f.readAs[AllTypes]().toIndexedSeq
        rows.size shouldBe numRows
        for i <- 0 until numRows do
          rows(i) shouldBe AllTypes.expected(i)
      finally
        f.close()
    }

  test("read columns as vectors") {
    val f = Parquet.open(TestFiles.bytes("duckdb/all_snappy_V1.parquet"))
    try
      val batch = f.readRowGroup(1, ParquetReadOptions.default.withColumns("id", "name", "tags"))
      // Row group 1 starts at row 2048
      batch.numRows shouldBe 952
      batch.columnNames shouldBe Seq("id", "name", "tags")
      batch.column("id") shouldMatch { case ids: LongColumn =>
        ids.getLong(0) shouldBe 2048L
        ids.getLong(951) shouldBe 2999L
      }
      batch.column("name") shouldMatch { case names: BinaryColumn =>
        // 2051 % 7 == 0
        names.isNull(3) shouldBe true
        names.getString(0) shouldBe "name-2048"
      }
      batch.column("tags") shouldMatch { case tags: ListColumn =>
        // Row 2050: null (2050 % 5 == 0); row 2051: three tags
        tags.isNull(2) shouldBe true
        tags.getValue(3) shouldBe Seq("t0", "t1", "t2")
      }
    finally
      f.close()
  }

  test("convert logical types in getValue") {
    val f = Parquet.open(TestFiles.bytes("duckdb/all_snappy_V1.parquet"))
    try
      val row = f.readRowGroup(0).row(5)
      row("dt") shouldBe LocalDate.of(2020, 1, 6)
      row("ts") shouldBe Instant.parse("2020-01-01T00:00:05Z")
      row("dec") shouldBe BigDecimal("6.25")
      row("uuid") shouldBe java.util.UUID.fromString("00000000-0000-0000-0000-000000000005")
      row("m") shouldBe Map("k0" -> 0, "k1" -> 5)
      row("s") shouldBe Map("x" -> 5, "y" -> "y5")
    finally
      f.close()
  }

  test("show the schema") {
    val f = Parquet.open(TestFiles.bytes("duckdb/all_snappy_V1.parquet"))
    try
      val s = f.schema.toString
      s shouldContain "optional int64 id"
      s shouldContain "optional group tags (List)"
      f.schema.columns.size shouldBe 18
      f.schema.isFlat shouldBe false
    finally
      f.close()
  }

  test("read an empty file") {
    val f = Parquet.open(TestFiles.bytes("duckdb/empty.parquet"))
    try
      f.numRows shouldBe 0L
      f.readAs[AllTypes]().toSeq shouldBe Seq.empty
    finally
      f.close()
  }

  test("read a local file") {
    Parquet.readAs[AllTypes](TestFiles.path("duckdb/all_gzip_V2.parquet").path).size shouldBe
      numRows
  }

  test("read asynchronously") {
    Parquet
      .openAsync(ByteSource.ofBytes(TestFiles.bytes("duckdb/all_snappy_V2.parquet")))
      .flatMap(_.readAllAsync[AllTypes]())
      .map { rows =>
        rows.size shouldBe numRows
        rows(42) shouldBe AllTypes.expected(42)
      }
  }

end DuckDBFilesTest
