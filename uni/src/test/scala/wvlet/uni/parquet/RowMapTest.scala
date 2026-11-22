package wvlet.uni.parquet

import wvlet.uni.io.FileSystemInit
import wvlet.uni.json.JSON
import wvlet.uni.json.JSON.*
import wvlet.uni.test.UniTest

import java.time.Instant
import java.time.LocalDate

class RowMapTest extends UniTest:
  FileSystemInit.init()

  private val duckdb      = "duckdb/all_snappy_V1.parquet"
  private val columnNames = Seq(
    "id",
    "i32",
    "name",
    "d",
    "f",
    "b",
    "dt",
    "ts",
    "dec",
    "bigdec",
    "cat",
    "tags",
    "s",
    "m",
    "nested",
    "uuid"
  )

  test("read rows as maps in schema column order") {
    val rows = Parquet.readRows(TestFiles.bytes(duckdb))
    rows.size shouldBe 3000
    rows.foreach(_.keys.toSeq shouldBe columnNames)
    val r = rows(43)
    r("id") shouldBe 43L
    r("i32") shouldBe 29
    r("name") shouldBe "name-43"
    r("f") shouldBe 10.75f
    r("dt") shouldBe LocalDate.of(2020, 1, 1).plusDays(43)
    r("ts") shouldBe Instant.parse("2020-01-01T00:00:00Z").plusSeconds(43)
    r("dec") shouldBe BigDecimal("53.75")
    r("tags") shouldBe Seq("t0", "t1", "t2")
    r("s") shouldBe Map("x" -> 43, "y" -> "y43")
    r("s") shouldMatch { case s: Map[?, ?] =>
      s.keys.toSeq shouldBe Seq("x", "y")
    }
    r("m") shouldBe Map("k0" -> 0)
    r("uuid") shouldBe java.util.UUID.fromString("00000000-0000-0000-0000-000000000043")
    rows(0)("name") shouldBe null
  }

  test("read rows from a local file") {
    val rows = Parquet.readRows(
      TestFiles.path(duckdb).path,
      ParquetReadOptions.default.withColumns("id", "cat")
    )
    rows.size shouldBe 3000
    rows(4) shouldBe Map("id" -> 4L, "cat" -> "b")
  }

  test("match the rows of a batch") {
    val f = Parquet.open(TestFiles.bytes(duckdb))
    try
      val batch = f.readRowGroup(1)
      batch.row(0) shouldBe f.readRows().drop(2048).next()
      batch.rows.size shouldBe 952
    finally
      f.close()
  }

  test("read rows asynchronously") {
    Parquet
      .openAsync(ByteSource.ofBytes(TestFiles.bytes(duckdb)))
      .flatMap(_.readAllRowsAsync(ParquetReadOptions.default.withColumns("id", "ts")))
      .map { rows =>
        rows.size shouldBe 3000
        rows(2500) shouldBe
          Map("id" -> 2500L, "ts" -> Instant.parse("2020-01-01T00:00:00Z").plusSeconds(2500))
      }
  }

  test("emit rows asynchronously") {
    Parquet
      .openAsync(ByteSource.ofBytes(TestFiles.bytes(duckdb)))
      .flatMap(_.readRowsAsync(ParquetReadOptions.default.withColumns("id")))
      .lastOption
      .map { last =>
        // Both row groups are emitted, in order
        last shouldBe Map("id" -> 2999L)
      }
  }

  test("decode unannotated binary columns as strings with withBinaryAsString") {
    val bytes = TestFiles.bytes("apache/alltypes_plain.parquet")
    val raw   = Parquet.readRows(bytes)
    raw(1)("string_col") shouldMatch { case _: Array[Byte] =>
    }
    val rows = Parquet.readRows(bytes, ParquetReadOptions.default.withBinaryAsString(true))
    rows(1)("string_col") shouldBe "1"
    rows(1)("date_string_col") shouldBe "03/01/09"
    rows(1)("timestamp_col") shouldBe Instant.parse("2009-03-01T00:01:00Z")
    val f = Parquet.open(bytes)
    try
      f.readRowGroup(0, ParquetReadOptions.default.withBinaryAsString(true))
        .column("string_col")
        .getValue(1) shouldBe "1"
    finally f.close()
  }

  test("read rows as JSON") {
    val f = Parquet.open(TestFiles.bytes(duckdb))
    try
      val json = f.readJson().drop(43).next()
      json.toJSON shouldBe
        """{"id":43,"i32":29,"name":"name-43","d":21.5,"f":10.75,"b":false,"dt":"2020-02-13","ts":"2020-01-01T00:00:43Z","dec":"53.75","bigdec":"64.500","cat":"b","tags":["t0","t1","t2"],"s":{"x":43,"y":"y43"},"m":{"k0":0},"nested":[[0,43]],"uuid":"00000000-0000-0000-0000-000000000043"}"""
      // JSON lines parse back
      JSON.parse(json.toJSON) shouldBe json
    finally
      f.close()
  }

  test("read binary values as base64 in JSON") {
    val json = Parquet.readJson(TestFiles.bytes("apache/alltypes_plain.parquet"))
    json.head.get("string_col") shouldBe Some(JSONString("MA=="))
    // Map keys render as their JSON values would
    ParquetJson.toJSON(Map(Array[Byte](1, 2) -> 1, BigDecimal("1E+3") -> 2)).toJSON shouldBe
      """{"AQI=":1,"1000":2}"""
  }

  test("summarize column statistics from the footer") {
    val f = Parquet.open(TestFiles.bytes(duckdb))
    try
      val stats = f.columnStatistics
      stats.keys.toSeq.take(4) shouldBe Seq("id", "i32", "name", "d")
      val id = stats("id")
      id.numValues shouldBe 3000L
      id.numNulls shouldBe Some(0L)
      id.min shouldBe Some(0L)
      id.max shouldBe Some(2999L)
      (id.compressedSize > 0) shouldBe true
      (id.uncompressedSize > 0) shouldBe true
      stats("name").numNulls shouldBe Some(429L)
      stats("ts").min shouldBe Some(Instant.parse("2020-01-01T00:00:00Z"))
      stats("ts").max shouldBe Some(Instant.parse("2020-01-01T00:00:00Z").plusSeconds(2999))
      stats("dt").min shouldBe Some(LocalDate.of(2020, 1, 1))
      stats("cat").min shouldBe Some("a")
      stats("cat").max shouldBe Some("c")
      // DECIMAL has no order that statistics can rely on here
      stats("dec").min shouldBe None
      stats.contains("tags.list.element") shouldBe true
    finally
      f.close()
  }

end RowMapTest

class IsoDateTest extends wvlet.uni.test.UniTest:
  test("format epoch days as LocalDate does") {
    val days =
      (-800000 to 3000000 by 997) ++ Seq(-719528, -719162, 0, 1, 59, 60, 365, 10957, 2932896)
    days.foreach { d =>
      VectorUnpacker.isoDate(d) shouldBe java.time.LocalDate.ofEpochDay(d.toLong).toString
    }
  }

case class NarrowInt(v: Int) derives wvlet.uni.weaver.Weaver

class ReadOverflowTest extends wvlet.uni.test.UniTest:
  test("reject a value that does not fit an Int field") {
    val schema = ParquetSchema.builder().required("v", ParquetType.INT64).build()
    val out    = java.io.ByteArrayOutputStream()
    val w      = Parquet.newRecordWriter(out, schema)
    w.write(Map("v" -> (1L << 40)))
    w.close()
    val f = Parquet.open(out.toByteArray)
    try intercept[Exception](f.readAs[NarrowInt]().toSeq)
    finally f.close()
  }
