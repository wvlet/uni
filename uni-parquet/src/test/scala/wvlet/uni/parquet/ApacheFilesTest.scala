package wvlet.uni.parquet

import wvlet.uni.io.FileSystemInit
import wvlet.uni.test.UniTest
import wvlet.uni.weaver.Weaver

import java.time.Instant

case class AllTypesPlain(
    id: Int,
    boolCol: Boolean,
    tinyintCol: Int,
    bigintCol: Long,
    floatCol: Float,
    doubleCol: Double,
    dateStringCol: String,
    stringCol: String,
    timestampCol: Instant
) derives Weaver

/**
  * Files from apache/parquet-testing, written by parquet-java, Impala, Spark, pyarrow, and others
  */
class ApacheFilesTest extends UniTest:
  FileSystemInit.init()

  private def open(name: String): ParquetFile = Parquet.open(TestFiles.bytes(s"apache/${name}"))

  private def rows(name: String): IndexedSeq[Map[String, Any]] =
    val f = open(name)
    try f.readRows().toIndexedSeq
    finally f.close()

  // Row counts from DuckDB
  private val counts = Seq(
    "alltypes_plain.parquet"                    -> 8,
    "alltypes_plain.snappy.parquet"             -> 2,
    "alltypes_dictionary.parquet"               -> 2,
    "nested_lists.snappy.parquet"               -> 3,
    "nested_maps.snappy.parquet"                -> 6,
    "nullable.impala.parquet"                   -> 7,
    "nonnullable.impala.parquet"                -> 1,
    "repeated_no_annotation.parquet"            -> 6,
    "repeated_primitive_no_list.parquet"        -> 4,
    "old_list_structure.parquet"                -> 1,
    "list_columns.parquet"                      -> 3,
    "null_list.parquet"                         -> 1,
    "incorrect_map_schema.parquet"              -> 1,
    "delta_binary_packed.parquet"               -> 200,
    "delta_byte_array.parquet"                  -> 1000,
    "delta_length_byte_array.parquet"           -> 1000,
    "delta_encoding_optional_column.parquet"    -> 100,
    "byte_stream_split.zstd.parquet"            -> 300,
    "byte_stream_split_extended.gzip.parquet"   -> 200,
    "datapage_v2.snappy.parquet"                -> 5,
    "datapage_v2_empty_datapage.snappy.parquet" -> 1,
    "hadoop_lz4_compressed.parquet"             -> 4,
    "non_hadoop_lz4_compressed.parquet"         -> 4,
    "lz4_raw_compressed.parquet"                -> 4,
    "rle_boolean_encoding.parquet"              -> 68,
    "int32_decimal.parquet"                     -> 24,
    "int64_decimal.parquet"                     -> 24,
    "fixed_length_decimal.parquet"              -> 24,
    "fixed_length_decimal_legacy.parquet"       -> 24,
    "byte_array_decimal.parquet"                -> 24,
    "int96_from_spark.parquet"                  -> 6,
    "float16_nonzeros_and_nans.parquet"         -> 8,
    "concatenated_gzip_members.parquet"         -> 513,
    "dict-page-offset-zero.parquet"             -> 39,
    "unknown-logical-type.parquet"              -> 3,
    "nested_structs.rust.parquet"               -> 1,
    "page_v2_empty_compressed.parquet"          -> 10,
    "empty_data_pages_v1.parquet"               -> 3,
    "nulls.snappy.parquet"                      -> 8,
    "single_nan.parquet"                        -> 1,
    "int32_with_null_pages.parquet"             -> 1000,
    "column_chunk_key_value_metadata.parquet"   -> 0,
    "map_no_value.parquet"                      -> 3
  )

  for (name, n) <- counts do
    test(s"read ${name}") {
      rows(name).size shouldBe n
    }

  test("read Impala's alltypes_plain into case classes") {
    val f = open("alltypes_plain.parquet")
    try
      val all = f.readAs[AllTypesPlain](ParquetReadOptions.default.withBinaryAsString(true)).toSeq
      all.map(_.id) shouldBe Seq(4, 5, 6, 7, 2, 3, 0, 1)
      all(1) shouldBe
        AllTypesPlain(
          5,
          false,
          1,
          10L,
          1.1f,
          10.1,
          "03/01/09",
          "1",
          Instant.parse("2009-03-01T00:01:00Z")
        )
    finally
      f.close()
  }

  test("read three-level nested lists") {
    rows("nested_lists.snappy.parquet")(0)("a") shouldBe
      Seq(Seq(Seq("a", "b"), Seq("c")), Seq(null, Seq("d")))
  }

  test("read nested maps") {
    val r = rows("nested_maps.snappy.parquet")
    r(0)("a") shouldBe Map("a" -> Map(1 -> true, 2 -> false))
    r(2)("a") shouldBe Map("c" -> null)
  }

  test("read Impala's nested and nullable columns") {
    val r = rows("nullable.impala.parquet")
    r(0)("int_array") shouldBe Seq(1, 2, 3)
    r(1)("int_array") shouldBe Seq(null, 1, 2, null, 3, null)
    r(1)("int_array_Array") shouldBe Seq(Seq(null, 1, 2, null), Seq(3, null, 4), Seq.empty, null)
    r(1)("int_map") shouldBe Map("k1" -> 2, "k2" -> null)
    r(2)("int_array") shouldBe Seq.empty
    r(2)("int_Map_Array") shouldBe Seq(null, null)
  }

  test("read legacy list layouts") {
    rows("old_list_structure.parquet")(0)("a") shouldBe Seq(Seq(1, 2), Seq(3, 4))
    rows("null_list.parquet")(0)("emptylist") shouldBe Seq.empty
    rows("list_columns.parquet")(2)("utf8_list") shouldBe Seq("efg", null, "hij", "xyz")
    rows("repeated_no_annotation.parquet")(2)("phoneNumbers") shouldBe Map("phone" -> Seq.empty)
  }

  test("read a map without the MAP_KEY_VALUE annotation") {
    rows("incorrect_map_schema.parquet")(0)("my_map") shouldBe
      Map("parent" -> "another", "name" -> "report")
  }

  test("read decimals stored as INT32, INT64, fixed and variable length binary") {
    for name <- Seq(
        "int32_decimal.parquet",
        "int64_decimal.parquet",
        "fixed_length_decimal.parquet",
        "fixed_length_decimal_legacy.parquet",
        "byte_array_decimal.parquet"
      )
    do
      rows(name).map(_("value")) shouldBe (1 to 24).map(i => BigDecimal(i).setScale(2))
  }

  test("read Spark's INT96 timestamps") {
    rows("int96_from_spark.parquet").take(2).map(_("a")) shouldBe
      Seq(Instant.parse("2024-01-01T20:34:56.123456Z"), Instant.parse("2024-01-01T01:00:00Z"))
  }

  test("read FLOAT16 values") {
    rows("float16_nonzeros_and_nans.parquet").take(3).map(_("x")) shouldBe Seq(null, 1.0f, -2.0f)
  }

  test("reject a malformed dictionary page") {
    intercept[ParquetException] {
      rows("nation.dict-malformed.parquet")
    }
  }

  test("reject encrypted files with a clear error") {
    for name <- Seq(
        "encrypt_columns_and_footer.parquet.encrypted",
        "encrypt_columns_plaintext_footer.parquet.encrypted"
      )
    do
      val e = intercept[ParquetException] {
        rows(name)
      }
      e.getMessage shouldContain "encrypted"
  }

end ApacheFilesTest
