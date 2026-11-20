package wvlet.uni.parquet

import wvlet.uni.io.FileSystemInit
import wvlet.uni.parquet.ColumnVector.*
import wvlet.uni.parquet.ParquetType.*
import wvlet.uni.parquet.format.CompressionCodec
import wvlet.uni.test.UniTest

import java.time.Instant
import java.time.LocalDate

class BatchWriterTest extends UniTest:
  FileSystemInit.init()

  private def writeBytes(
      schema: ParquetSchema,
      options: ParquetWriteOptions = ParquetWriteOptions.default
  )(body: ParquetBatchWriter => Unit): Array[Byte] =
    val out = java.io.ByteArrayOutputStream()
    val w   = Parquet.newBatchWriter(out, schema, options)
    try body(w)
    finally w.close()
    out.toByteArray

  /** Values compared by content: byte arrays as sequences, NaN as a string */
  private def norm(v: Any): Any =
    v match
      case b: Array[Byte] =>
        b.toSeq
      case d: Double if d.isNaN =>
        "NaN"
      case f: Float if f.isNaN =>
        "NaN"
      case m: Map[?, ?] =>
        m.toSeq.map((k, x) => norm(k) -> norm(x))
      case s: Iterable[?] =>
        s.map(norm).toSeq
      case other =>
        other

  private def readAll(bytes: Array[Byte]): Seq[Any] = Parquet.readRows(bytes).map(norm)

  /** Copies a file batch by batch, as the docs show */
  private def copy(bytes: Array[Byte], options: ParquetWriteOptions): Array[Byte] =
    val in = Parquet.open(bytes)
    try writeBytes(in.schema, options)(_.writeAll(in.readBatches()))
    finally in.close()

  private val flatSchema = ParquetSchema
    .builder("flat")
    .required("id", INT64)
    .required("n", INT32)
    .optional("name", STRING)
    .optional("price", DOUBLE)
    .optional("ratio", FLOAT)
    .required("ok", BOOLEAN)
    .optional("day", DATE)
    .optional("at", TIMESTAMP_MICROS)
    .optional("uid", UUID)
    .build()

  private def f(name: String): ParquetField = flatSchema.field(name).get

  private def utf8(values: Seq[String]): (Array[Int], Array[Byte]) =
    val bytes   = values.map(_.getBytes("UTF-8"))
    val offsets = bytes.scanLeft(0)(_ + _.length).toArray
    (offsets, bytes.flatten.toArray)

  private def flatBatch(from: Int, n: Int): ColumnBatch =
    val ids         = (from until from + n).map(_.toLong).toArray
    val nulls       = Array.tabulate(n)(i => (from + i) % 3 == 0)
    val (off, data) = utf8((0 until n).map(i => s"name-${from + i}"))
    val uids        = Array.tabulate(n * 16)(i => ((from * 16 + i) % 251).toByte)
    ColumnBatch(
      n,
      IndexedSeq(
        LongColumn(f("id"), ids, null),
        IntColumn(f("n"), ids.map(_.toInt * 2), null),
        BinaryColumn(f("name"), off, data, nulls),
        DoubleColumn(f("price"), ids.map(_ * 1.5), nulls),
        FloatColumn(f("ratio"), ids.map(_.toFloat / 4), null),
        BooleanColumn(f("ok"), ids.map(_ % 2 == 0), null),
        IntColumn(f("day"), ids.map(_.toInt + 19000), null),
        LongColumn(f("at"), ids.map(_ * 1000000L), nulls),
        BinaryColumn(f("uid"), Array.tabulate(n + 1)(_ * 16), uids, null)
      )
    )

  test("write flat columns and read them back") {
    val bytes =
      writeBytes(flatSchema)(w =>
        w.write(flatBatch(0, 10))
        w.write(flatBatch(10, 5))
        w.numRows shouldBe 15
      )
    val rows = Parquet.readRows(bytes)
    rows.size shouldBe 15
    rows.map(_("id")) shouldBe (0 until 15).map(_.toLong)
    rows(1)("name") shouldBe "name-1"
    rows(3)("name") shouldBe null
    rows(4)("price") shouldBe 6.0
    rows(5)("ratio") shouldBe 1.25f
    rows(6)("ok") shouldBe true
    rows(7)("day") shouldBe LocalDate.ofEpochDay(19007)
    rows(8)("at") shouldBe Instant.ofEpochSecond(8)
    rows(9)("at") shouldBe null
    rows(0)("uid") shouldMatch { case _: java.util.UUID =>
    }
  }

  test("match columns by name, in any order") {
    val b     = flatBatch(0, 4)
    val bytes = writeBytes(flatSchema)(_.write(ColumnBatch(4, b.columns.reverse)))
    Parquet.readRows(bytes).map(_("n")) shouldBe Seq(0, 2, 4, 6)
  }

  test("write a batch read from a file back unchanged") {
    val file = Parquet.writeBytes(
      (0 until 300).map { i =>
        BatchRow(
          i.toLong,
          s"name-${i % 7}",
          Option.when(i % 3 == 0)(s"note ${i}"),
          (0 until i % 4).map(j => s"t${j}"),
          (0 until i % 5).map(j => BatchLine(s"sku-${i}-${j}", j)),
          (0 until i % 3).map(j => s"k${j}" -> (i * j)).toMap
        )
      }
    )
    for codec <- Seq(CompressionCodec.UNCOMPRESSED, CompressionCodec.SNAPPY) do
      val copied = copy(file, ParquetWriteOptions.default.withCompression(codec))
      readAll(copied) shouldBe readAll(file)
      Parquet.readAs[BatchRow](copied) shouldBe Parquet.readAs[BatchRow](file)
  }

  test("close row groups at the row group size, inside batches") {
    val options = ParquetWriteOptions.default.withRowGroupSize(4 * 1024).withPageSize(512)
    // One large batch and many small ones give the same row groups, within a few rows
    def groups(batches: Seq[ColumnBatch]): Seq[Long] =
      val bytes = writeBytes(flatSchema, options)(_.writeAll(batches))
      val in    = Parquet.open(bytes)
      try
        in.readRows().map(_("id")).toSeq shouldBe (0 until 2000).map(_.toLong)
        in.rowGroups.map(_.numRows)
      finally
        in.close()
    val one   = groups(Seq(flatBatch(0, 2000)))
    val small = groups((0 until 2000 by 7).map(i => flatBatch(i, math.min(7, 2000 - i))))
    (one.size > 3) shouldBe true
    debug(s"row groups: ${one} / ${small}")
    (math.abs(one.size - small.size) <= 1) shouldBe true
    one.sum shouldBe 2000
    small.sum shouldBe 2000
  }

  test("write nested vectors built by hand") {
    val schema = ParquetSchema
      .builder("nested")
      .required("id", INT32)
      .optional("tags", ListOf(STRING, optionalElements = true))
      .optional("attrs", MapOf(STRING, INT64, optionalValues = true))
      .optional(
        "point",
        Struct(Seq(ParquetColumn.required("x", DOUBLE), ParquetColumn.optional("label", STRING)))
      )
      .build()
    val tags  = schema.field("tags").get.asInstanceOf[ParquetField.Group]
    val elem  = tags.children(0).asInstanceOf[ParquetField.Group].children(0)
    val attrs = schema.field("attrs").get.asInstanceOf[ParquetField.Group]
    val kv    = attrs.children(0).asInstanceOf[ParquetField.Group]
    val point = schema.field("point").get.asInstanceOf[ParquetField.Group]

    val (tagOff, tagData) = utf8(Seq("a", "", "c"))
    val (keyOff, keyData) = utf8(Seq("k1", "k2"))
    val (lblOff, lblData) = utf8(Seq("p0", "", ""))
    val batch             = ColumnBatch(
      3,
      IndexedSeq(
        IntColumn(schema.field("id").get, Array(1, 2, 3), null),
        // Row 0: [a, null, c]; row 1: []; row 2: null
        ListColumn(
          tags,
          Array(0, 3, 3, 3),
          BinaryColumn(elem, tagOff, tagData, Array(false, true, false)),
          Array(false, false, true)
        ),
        // Row 0: {}; row 1: {k1: 10, k2: null}; row 2: null
        MapColumn(
          attrs,
          Array(0, 0, 2, 2),
          BinaryColumn(kv.children(0), keyOff, keyData, null),
          LongColumn(kv.children(1), Array(10L, 0L), Array(false, true)),
          Array(false, false, true)
        ),
        // Row 0: (1.0, p0); row 1: null; row 2: (3.0, null). The required x is null under the null
        // struct, as the reader leaves it.
        StructColumn(
          point,
          3,
          IndexedSeq(
            DoubleColumn(point.children(0), Array(1.0, 0.0, 3.0), Array(false, true, false)),
            BinaryColumn(point.children(1), lblOff, lblData, Array(false, true, true))
          ),
          Array(false, true, false)
        )
      )
    )
    val bytes = writeBytes(schema)(_.write(batch))
    val rows  = Parquet.readRows(bytes)
    rows.map(_("tags")) shouldBe Seq(Seq("a", null, "c"), Seq.empty, null)
    rows.map(_("attrs")) shouldBe Seq(Map.empty, Map("k1" -> 10L, "k2" -> null), null)
    rows.map(_("point")) shouldBe
      Seq(Map("x" -> 1.0, "label" -> "p0"), null, Map("x" -> 3.0, "label" -> null))
    // The batch read back writes the same file content again
    readAll(copy(bytes, ParquetWriteOptions.default)) shouldBe readAll(bytes)
  }

  test("reject batches that do not fit, and stay usable") {
    val out = java.io.ByteArrayOutputStream()
    val w   = Parquet.newBatchWriter(out, flatSchema)
    val ok  = flatBatch(0, 4)
    def replace(name: String, v: ColumnVector): ColumnBatch = ColumnBatch(
      4,
      ok.columns
        .map(c =>
          if c.name == name then
            v
          else
            c
        )
    )
    def rejected(b: ColumnBatch, message: String): Unit =
      val e = intercept[ParquetException](w.write(b))
      e.getMessage shouldContain message

    rejected(ColumnBatch(4, ok.columns.tail), "no column id")
    rejected(ColumnBatch(4, ok.columns :+ ok.columns.head), "2 times")
    rejected(replace("n", LongColumn(f("n"), new Array[Long](4), null)), "expected a IntColumn")
    rejected(replace("n", IntColumn(f("n"), new Array[Int](3), null)), "3 values")
    rejected(
      replace("id", LongColumn(f("id"), new Array[Long](4), Array(false, true, false, false))),
      "row 1 is null in a required field"
    )
    rejected(
      replace("name", BinaryColumn(f("name"), Array(0, 2, 1, 3, 4), new Array[Byte](4), null)),
      "is less than"
    )
    rejected(
      replace("uid", BinaryColumn(f("uid"), Array(0, 16, 32, 48, 63), new Array[Byte](63), null)),
      "has 15 bytes"
    )
    rejected(ColumnBatch(5, ok.columns), "5 are expected")
    w.numRows shouldBe 0
    w.write(ok)
    w.close()
    Parquet.readRows(out.toByteArray).map(_("id")) shouldBe Seq(0L, 1L, 2L, 3L)
  }

  test("reject extra columns") {
    val w = Parquet.newBatchWriter(java.io.ByteArrayOutputStream(), flatSchema.select("id"))
    val e = intercept[ParquetException](w.write(flatBatch(0, 2)))
    e.getMessage shouldContain "not in the schema"
    w.abort()
  }

  test("reject INT96 columns before creating the file") {
    var opened = false
    val in     = Parquet.open(TestFiles.bytes("apache/int96_from_spark.parquet"))
    try
      intercept[ParquetException] {
        ParquetBatchWriter.create(
          in.schema,
          () =>
            opened = true
            java.io.ByteArrayOutputStream()
          ,
          ParquetWriteOptions.default
        )
      }
      opened shouldBe false
    finally
      in.close()
  }

  test("project columns with select") {
    val file = writeBytes(flatSchema)(_.write(flatBatch(0, 6)))
    val in   = Parquet.open(file)
    try
      val schema = in.schema.select("name", "id")
      schema.fields.map(_.name) shouldBe Seq("name", "id")
      schema.columns.map(_.columnIndex) shouldBe Seq(0, 1)
      val bytes =
        writeBytes(schema)(w => in.readBatches().foreach(b => w.write(b.select("name", "id"))))
      val rows = Parquet.readRows(bytes)
      rows.map(_.keys.toSeq).distinct shouldBe Seq(Seq("name", "id"))
      rows.map(_("id")) shouldBe (0 until 6).map(_.toLong)
    finally
      in.close()
    intercept[NoSuchElementException](flatSchema.select("missing"))
    intercept[IllegalArgumentException](flatSchema.select("id", "id"))
  }

  // Files from other writers, including the legacy 2-level list layouts, copied batch by batch
  private val apacheFiles = Seq(
    "nested_lists.snappy.parquet",
    "nested_maps.snappy.parquet",
    "nullable.impala.parquet",
    "nonnullable.impala.parquet",
    "repeated_no_annotation.parquet",
    "repeated_primitive_no_list.parquet",
    "old_list_structure.parquet",
    "list_columns.parquet",
    "null_list.parquet",
    "incorrect_map_schema.parquet",
    "delta_byte_array.parquet",
    "byte_stream_split_extended.gzip.parquet",
    "fixed_length_decimal.parquet",
    "byte_array_decimal.parquet",
    "float16_nonzeros_and_nans.parquet",
    "nested_structs.rust.parquet",
    "nulls.snappy.parquet",
    "map_no_value.parquet"
  )

  for name <- apacheFiles do
    test(s"copy ${name}") {
      val file = TestFiles.bytes(s"apache/${name}")
      readAll(copy(file, ParquetWriteOptions.default)) shouldBe readAll(file)
    }

end BatchWriterTest
