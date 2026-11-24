package wvlet.uni.cloud.parquet

import wvlet.uni.cloud.parquet.ColumnVector.*
import wvlet.uni.cloud.parquet.column.ColumnChunkCursor
import wvlet.uni.io.FileSystemInit
import wvlet.uni.test.UniTest
import wvlet.uni.weaver.Weaver

case class SlotInner(a: Option[Int], b: Option[Double]) derives Weaver

case class SlotRequiredInner(x: Int, y: Long) derives Weaver

case class SlotRow(
    id: Long,
    req: Int,
    noNulls: Option[Long],
    // Nulls a few rows apart: bit-packed definition levels
    scattered: Option[Int],
    // Long runs of nulls and values: RLE definition levels
    runs: Option[Double],
    allNull: Option[Float],
    flag: Option[Boolean],
    // Few distinct values: dictionary-encoded
    small: Option[Long],
    price: Option[BigDecimal],
    // A struct over optional leaves (2-level definition levels)
    inner: Option[SlotInner],
    // An optional struct over required leaves: the leaves' 1-bit levels are the struct's nulls
    reqInner: Option[SlotRequiredInner]
) derives Weaver

/**
  * Flat nullable columns of fixed-size values decode each page straight into their row slots. The
  * vectors must be the same as the general path gives: values, null flags (null when a batch has no
  * nulls), default values in null rows, and errors on corrupt data.
  */
class SlottedDecodeTest extends UniTest:
  FileSystemInit.init()

  private val rows: Seq[SlotRow] = (0 until 5000).map { i =>
    SlotRow(
      id = i.toLong,
      req = i * 7,
      noNulls = Some(i * 11L),
      scattered = Option.when(i % 13 != 4 && i % 29 != 0)(i - 2500),
      runs = Option.when((i / 300) % 2 == 0)(i * 0.5),
      allNull = None,
      flag = Option.when(i % 3 != 1)(i % 7 < 3),
      small = Option.when(i % 5 != 2)((i % 4).toLong),
      price = Option.when(i % 9 != 0)(BigDecimal(i % 1000) / BigDecimal(100)),
      inner =
        Option.when(i % 11 != 0)(
          SlotInner(Option.when(i % 3 != 0)(i), Option.when(i % 4 != 0)(i / 8.0))
        ),
      reqInner = Option.when(i % 6 != 5)(SlotRequiredInner(i, -i.toLong))
    )
  }

  private def write(options: ParquetWriteOptions): Array[Byte] = Parquet.writeBytes(
    rows,
    options.withPageSize(1024).withRowGroupSize(48 * 1024).withDecimal("price", 7, 2)
  )

  private val files: Seq[(String, Array[Byte])] = Seq(
    "dictionary pages" -> write(ParquetWriteOptions.default.withDictionaryPageSize(2048)),
    "PLAIN pages"      -> write(ParquetWriteOptions.default.noDictionary()),
    "uncompressed"     -> write(ParquetWriteOptions.default.noCompression())
  )

  private val readOptions: Seq[(String, ParquetReadOptions)] = Seq(
    "whole row groups"          -> ParquetReadOptions.default,
    "1 row"                     -> ParquetReadOptions.default.withBatchSize(1),
    "37 rows"                   -> ParquetReadOptions.default.withBatchSize(37),
    "1000 rows"                 -> ParquetReadOptions.default.withBatchSize(1000),
    "decimals as Long"          -> ParquetReadOptions.default.withInt32DecimalAsLong(true),
    "decimals as Long, 37 rows" ->
      ParquetReadOptions.default.withInt32DecimalAsLong(true).withBatchSize(37)
  )

  /** A comparable picture of a vector: its class, values, and null flags */
  private def describe(v: ColumnVector): Any =
    val nulls = Option(v.nullFlags).map(_.toSeq)
    v match
      case c: BooleanColumn =>
        ("boolean", c.values.toSeq, nulls)
      case c: IntColumn =>
        ("int", c.values.toSeq, nulls)
      case c: LongColumn =>
        ("long", c.values.toSeq, nulls)
      case c: FloatColumn =>
        ("float", c.values.toSeq.map(java.lang.Float.floatToRawIntBits), nulls)
      case c: DoubleColumn =>
        ("double", c.values.toSeq.map(java.lang.Double.doubleToRawLongBits), nulls)
      case c: BinaryColumn =>
        ("binary", c.offsets.toSeq, (0 until c.size).map(i => plain(c.getValue(i))), nulls)
      case c: StructColumn =>
        ("struct", c.size, c.children.map(describe), nulls)
      case c: ListColumn =>
        ("list", c.offsets.toSeq, describe(c.elements), nulls)
      case c: MapColumn =>
        ("map", c.offsets.toSeq, describe(c.keys), describe(c.values), nulls)

  private def plain(v: Any): Any =
    v match
      case b: Array[Byte] =>
        b.toSeq
      case other =>
        other

  /** The batches' vectors, or the error, with slotted decoding on or off */
  private def read(bytes: Array[Byte], options: ParquetReadOptions, slotting: Boolean): Any =
    synchronized {
      ColumnChunkCursor.slotting = slotting
      try
        val f = Parquet.open(bytes)
        try f.readBatches(options).map(b => (b.numRows, b.columns.map(describe))).toList
        finally f.close()
      catch
        case e: Exception =>
          (e.getClass.getName, e.getMessage)
      finally
        ColumnChunkCursor.slotting = true
    }

  private def checkSame(bytes: Array[Byte], options: ParquetReadOptions): Unit =
    read(bytes, options, slotting = true) shouldBe read(bytes, options, slotting = false)

  test("write files with several row groups and many pages per column") {
    files.foreach { (_, bytes) =>
      val f = Parquet.open(bytes)
      try (f.numRowGroups > 1) shouldBe true
      finally f.close()
    }
  }

  for
    (fileName, bytes) <- files
    (optName, opts)   <- readOptions
  do
    test(s"read ${fileName} in batches of ${optName} as the general path does") {
      checkSame(bytes, opts)
    }

  test("read the rows back") {
    files.foreach { (_, bytes) =>
      val f = Parquet.open(bytes)
      try f.readAs[SlotRow]().toSeq shouldBe rows
      finally f.close()
    }
  }

  test("flag nulls only in batches that have them") {
    val f = Parquet.open(files.head._2)
    try f
        .readBatches(ParquetReadOptions.default.withBatchSize(100))
        .foreach { b =>
          b.column("noNulls").nullFlags shouldBe null
          b.column("allNull") shouldMatch { case c: FloatColumn =>
            c.nullFlags.toSeq shouldBe Seq.fill(b.numRows)(true)
            c.values.toSeq shouldBe Seq.fill(b.numRows)(0f)
          }
          // Rows 300 to 599 have no `runs` values, so a batch of them flags every row
          b.column("runs") shouldMatch { case c: DoubleColumn =>
            (0 until b.numRows).foreach { i =>
              if c.isNull(i) then
                c.values(i) shouldBe 0.0
            }
          }
        }
    finally f.close()
  }

  private val fixtures = Seq(
    "apache/alltypes_dictionary.parquet",
    "apache/alltypes_plain.parquet",
    "apache/alltypes_plain.snappy.parquet",
    "apache/byte_stream_split.zstd.parquet",
    "apache/byte_stream_split_extended.gzip.parquet",
    "apache/datapage_v2.snappy.parquet",
    "apache/datapage_v2_empty_datapage.snappy.parquet",
    "apache/delta_binary_packed.parquet",
    "apache/delta_encoding_optional_column.parquet",
    "apache/empty_data_pages_v1.parquet",
    "apache/int32_decimal.parquet",
    "apache/int32_with_null_pages.parquet",
    "apache/int64_decimal.parquet",
    "apache/nested_structs.rust.parquet",
    "apache/nonnullable.impala.parquet",
    "apache/nullable.impala.parquet",
    "apache/nulls.snappy.parquet",
    "apache/page_v2_empty_compressed.parquet",
    "apache/rle_boolean_encoding.parquet",
    "apache/single_nan.parquet",
    "duckdb/all_snappy_V1.parquet",
    "duckdb/all_snappy_V2.parquet",
    "duckdb/all_uncompressed_V1.parquet",
    "duckdb/all_uncompressed_V2.parquet"
  )

  test("read Apache and DuckDB files as the general path does") {
    for
      name <- fixtures
      opts <- Seq(ParquetReadOptions.default, ParquetReadOptions.default.withBatchSize(3))
    do
      checkSame(TestFiles.bytes(name), opts)
  }

  test("fail on corrupt pages as the general path does") {
    val bytes  = files.head._2
    val random = scala.util.Random(42)
    (0 until 300).foreach { _ =>
      val corrupt = bytes.clone()
      // Inside the data, away from the footer, so the file still opens
      val at = 4 + random.nextInt(bytes.length / 2)
      corrupt(at) = (corrupt(at) ^ (1 << random.nextInt(8))).toByte
      checkSame(corrupt, ParquetReadOptions.default.withBatchSize(500))
    }
  }

end SlottedDecodeTest
