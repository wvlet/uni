package wvlet.uni.parquet

import wvlet.uni.parquet.format.LogicalType
import wvlet.uni.test.UniTest
import wvlet.uni.weaver.Weaver

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

case class VariantRow(id: Long, v: Any) derives Weaver

/**
  * Reads VARIANT, GEOMETRY, and GEOGRAPHY columns of plain Parquet files. DuckDB 2.0 wrote the
  * fixtures with `parquet/duckdb/make_variant_geometry.sql`.
  */
class VariantGeometryTest extends UniTest:

  private def rows(name: String, opts: ParquetReadOptions = ParquetReadOptions.default) =
    val f = Parquet.open(TestFiles.bytes(s"duckdb/${name}"))
    try f
        .readRows(opts)
        .map(r => r("id").asInstanceOf[Number].longValue() -> plain(r("v")))
        .toSeq
        .sortBy(_._1)
    finally f.close()

  // Byte arrays compare by identity, so compare their contents
  private def plain(v: Any): Any =
    v match
      case b: Array[Byte] =>
        b.toSeq
      case m: Map[?, ?] =>
        m.map((k, x) => k -> plain(x))
      case s: Seq[?] =>
        s.map(plain)
      case other =>
        other

  test("read every variant primitive type, and objects and arrays") {
    val f = Parquet.open(TestFiles.bytes("duckdb/variant_types.parquet"))
    try f.schema.field("v").flatMap(_.logicalType) shouldBe Some(LogicalType.Variant)
    finally f.close()
    rows("variant_types.parquet").map(_._2) shouldBe
      Seq(
        null,
        true,
        42,
        4242,
        424242,
        42424242424L,
        1.5,
        2.5f,
        BigDecimal("12.34"),
        BigDecimal("1234567890.12"),
        BigDecimal("123456789012345678901234.5678"),
        LocalDate.of(2026, 11, 22),
        Instant.parse("2026-11-22T01:02:03.123456Z"),
        Instant.parse("2026-11-22T01:02:03.123456Z"),
        Seq(0xaa.toByte, 0xbb.toByte),
        "a short string",
        "long " * 20,
        LocalTime.of(1, 2, 3, 123456000),
        Instant.parse("2026-11-22T01:02:03.123456789Z"),
        java.util.UUID.fromString("12345678-1234-5678-1234-567812345678"),
        Map("a" -> 1, "b" -> Seq(1, 2, 3), "c" -> Map("d" -> "x")),
        Seq(1, "two", null, Seq(BigDecimal("3.5"))),
        Map("a" -> 2, "b" -> 1),
        false
      )
  }

  test("read a shredded object with fields kept in the binary value") {
    rows("variant_object.parquet") shouldBe
      Seq(
        0L -> Map("x" -> 0L, "y" -> "n0"),
        1L -> Map("x" -> 1L, "y" -> "n1"),
        2L -> Map("x" -> 2L, "y" -> "n2", "z" -> true),
        3L -> null,
        4L -> "not an object",
        5L -> Map("x" -> "str", "y" -> "n5")
      )
  }

  test("read a shredded array") {
    rows("variant_array.parquet") shouldBe
      Seq(0L -> Seq(0L, 1L), 1L -> Seq(1L, 2L), 2L -> "scalar", 3L -> Seq(3L, 4L))
  }

  test("keep variant strings and binaries with binaryAsString") {
    val opts = ParquetReadOptions.default.withBinaryAsString(true)
    rows("variant_types.parquet", opts).map(_._2)(14) shouldBe Seq(0xaa.toByte, 0xbb.toByte)
    rows("variant_object.parquet", opts).head shouldBe (0L -> Map("x" -> 0L, "y" -> "n0"))
  }

  test("decode variants into case classes and JSON") {
    val f = Parquet.open(TestFiles.bytes("duckdb/variant_array.parquet"))
    try
      f.readAs[VariantRow]().toSeq.sortBy(_.id).map(r => r.id -> r.v) shouldBe
        Seq(0L -> Seq(0L, 1L), 1L -> Seq(1L, 2L), 2L -> "scalar", 3L -> Seq(3L, 4L))
    finally f.close()
    val g = Parquet.open(TestFiles.bytes("duckdb/variant_object.parquet"))
    try g.readJson().map(_.toJSON).toSeq(2) shouldBe """{"id":2,"v":{"x":2,"y":"n2","z":true}}"""
    finally g.close()
  }

  test("merge differently shredded variants across the files of a dataset") {
    val ds = Parquet.dataset(
      Seq(
        TestFiles.path("duckdb/variant_object.parquet").path,
        TestFiles.path("duckdb/variant_array.parquet").path
      )
    )
    try
      ds.schema.field("v").flatMap(_.logicalType) shouldBe Some(LogicalType.Variant)
      ds.readRows().map(r => plain(r("v"))).toSeq.filter(_ != null).size shouldBe 9
    finally
      ds.close()
  }

  // WKB for POINT(1 2): little-endian, type 1, then x and y
  private val point12 =
    Seq(1, 1, 0, 0, 0).map(_.toByte) ++ longLE(java.lang.Double.doubleToLongBits(1.0)) ++
      longLE(java.lang.Double.doubleToLongBits(2.0))

  private def longLE(v: Long): Seq[Byte] = (0 until 8).map(k => (v >>> (8 * k)).toByte)

  test("read geometry columns as WKB") {
    val f = Parquet.open(TestFiles.bytes("duckdb/geometry.parquet"))
    try
      f.schema.field("g").flatMap(_.logicalType) shouldMatch { case Some(LogicalType.Geometry(_)) =>
      }
      f.schema.field("h").flatMap(_.logicalType) shouldMatch {
        case Some(LogicalType.Geometry(Some(crs))) if crs.contains("CRS84") =>
      }
      for opts <- Seq(
          ParquetReadOptions.default,
          ParquetReadOptions.default.withBinaryAsString(true)
        )
      do
        val rs = f.readRows(opts).toSeq
        plain(rs(0)("g")) shouldBe point12
        rs(1)("g") shouldBe null
    finally
      f.close()
  }

end VariantGeometryTest
