package wvlet.uni.arrow

import wvlet.uni.arrow.ArrowTestReader.*
import wvlet.uni.io.FileSystemInit
import wvlet.uni.parquet.ColumnBatch
import wvlet.uni.parquet.ColumnVector.*
import wvlet.uni.parquet.Parquet
import wvlet.uni.parquet.ParquetColumn
import wvlet.uni.parquet.ParquetField
import wvlet.uni.parquet.ParquetReadOptions
import wvlet.uni.parquet.ParquetSchema
import wvlet.uni.parquet.ParquetType
import wvlet.uni.parquet.ParquetType.*
import wvlet.uni.parquet.format.LogicalType
import wvlet.uni.parquet.format.PhysicalType
import wvlet.uni.parquet.format.TimeUnit
import wvlet.uni.test.UniTest
import wvlet.uni.weaver.Weaver

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

case class ArrowItem(sku: String, qty: Int) derives Weaver

case class ArrowRow(
    id: Long,
    i32: Int,
    small: Short,
    tiny: Byte,
    name: Option[String],
    d: Double,
    f: Float,
    b: Boolean,
    ts: Instant,
    day: LocalDate,
    t: LocalTime,
    price: BigDecimal,
    uid: UUID,
    tags: Seq[String],
    items: Seq[ArrowItem],
    attrs: Map[String, Int],
    note: Option[ArrowItem],
    bin: Array[Byte]
) derives Weaver

/** Sample data that ArrowWriterTest checks and WriteArrowSamples writes for pyarrow */
object ArrowSamples:
  val rows: IndexedSeq[ArrowRow] = (0 until 300).map { i =>
    ArrowRow(
      id = i.toLong * 1000000007L,
      i32 = i - 150,
      small = (i * 100 - 15000).toShort,
      tiny = (i % 256 - 128).toByte,
      name = Option.when(i % 3 != 0)(s"name-${i % 17}"),
      d = i * 0.5,
      f = i.toFloat / 4,
      b = i % 2 == 0,
      ts = Instant.ofEpochSecond(1700000000L + i, i * 1000L),
      day = LocalDate.of(2026, 1, 1).plusDays(i),
      t = LocalTime.of(i % 24, i % 60, i % 60),
      price = BigDecimal(i) / 8 - 10,
      uid = UUID(i.toLong, -i.toLong),
      tags = (0 until i  % 4).map(j => s"t${j}"),
      items = (0 until i % 3).map(j => ArrowItem(s"sku-${i}-${j}", j)),
      attrs = (0 until i % 3).map(j => s"k${j}" -> (i * j)).toMap,
      note = Option.when(i % 5 == 0)(ArrowItem(s"n${i}", -i)),
      bin = Array.tabulate(i % 5)(_.toByte)
    )
  }

  val parquet = Parquet.open(Parquet.writeBytes(rows))

  def batches(): Seq[ColumnBatch] =
    parquet.readBatches(ParquetReadOptions.default.withBatchSize(128)).toSeq

  private def prim(pt: PhysicalType, lt: LogicalType, len: Int = 0): ParquetType.Primitive =
    ParquetType.Primitive(pt, Some(lt), len)

  /** The DECIMAL(40, 0) value of the types batch */
  val big: BigInt = -(BigInt(10).pow(39) + 7)

  /** Integers, decimals, and legacy types, in one two-row batch */
  val (typesSchema, typesBatch) =
    val schema =
      ParquetSchema
        .builder()
        .optional("u8", prim(PhysicalType.INT32, LogicalType.Integer(8, false)))
        .required("i16", INT16)
        .required("u32", prim(PhysicalType.INT32, LogicalType.Integer(32, false)))
        .required("u64", prim(PhysicalType.INT64, LogicalType.Integer(64, false)))
        .optional("dec9", decimal(9, 2))
        .required("dec18", decimal(18, 4))
        .optional("dec40", decimal(40, 0))
        .required("decvar", prim(PhysicalType.BYTE_ARRAY, LogicalType.Decimal(2, 20)))
        .required("f16", prim(PhysicalType.FIXED_LEN_BYTE_ARRAY, LogicalType.Float16, 2))
        .required("ival", prim(PhysicalType.FIXED_LEN_BYTE_ARRAY, LogicalType.Interval, 12))
        // Made INT96 below: schema builders write no INT96 columns
        .optional("legacy", fixed(12))
        .optional("unknown", prim(PhysicalType.INT32, LogicalType.Unknown))
        .required(
          "local_ts",
          prim(PhysicalType.INT64, LogicalType.Timestamp(false, TimeUnit.NANOS))
        )
        .required("tm", TIME_MILLIS)
        .required("js", JSON)
        .required("raw", BINARY)
        .build() match
        case s =>
          ParquetSchema(
            s.root
              .copy(children =
                s.root
                  .children
                  .map {
                    case p: ParquetField.Primitive if p.name == "legacy" =>
                      p.copy(physicalType = PhysicalType.INT96, typeLength = 0)
                    case other =>
                      other
                  }
              )
          )
    def f(name: String) = schema.field(name).get
    def bin(name: String, values: Seq[Array[Byte]], nulls: Array[Boolean] = null) =
      val offsets = values.scanLeft(0)(_ + _.length).toArray
      BinaryColumn(f(name), offsets, values.flatten.toArray, nulls)
    def be(v: BigInt, len: Int): Array[Byte] =
      val b = v.toByteArray
      Array.fill[Byte](len - b.length)(
        if v < 0 then
          -1
        else
          0
      ) ++ b
    // 2026-01-02T03:04:05.000000006Z as INT96: nanoseconds of the day, then the Julian day
    val julian = LocalDate.of(2026, 1, 2).toEpochDay + 2440588L
    val nod    = (3 * 3600 + 4 * 60 + 5) * 1000000000L + 6
    val int96  = java.nio.ByteBuffer.allocate(12).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    int96.putLong(nod).putInt(julian.toInt)
    val ival = java.nio.ByteBuffer.allocate(12).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    ival.putInt(14).putInt(3).putInt(1500)
    val batch = ColumnBatch(
      2,
      IndexedSeq(
        IntColumn(f("u8"), Array(255, 0), Array(false, true)),
        IntColumn(f("i16"), Array(-32768, 32767), null),
        IntColumn(f("u32"), Array(-1, 7), null),
        LongColumn(f("u64"), Array(-1L, 0L), null),
        IntColumn(f("dec9"), Array(-12345, 0), Array(false, true)),
        LongColumn(f("dec18"), Array(-999999999999999999L, 99L), null),
        bin("dec40", Seq(be(big, 17), Array.emptyByteArray), Array(false, true)),
        // A redundant sign byte, and a short value
        bin("decvar", Seq(be(BigInt(-5), 18), Array(1.toByte))),
        bin("f16", Seq(Array(0x00.toByte, 0x3e.toByte), Array(0x00.toByte, 0xc0.toByte))),
        bin("ival", Seq(ival.array(), ival.array())),
        bin("legacy", Seq(int96.array(), Array.emptyByteArray), Array(false, true)),
        IntColumn(f("unknown"), Array(0, 0), Array(true, true)),
        LongColumn(f("local_ts"), Array(1L, -1L), null),
        IntColumn(f("tm"), Array(1000, 86399999), null),
        bin("js", Seq("{\"a\":1}".getBytes, "[]".getBytes)),
        bin("raw", Seq(Array(1.toByte), Array.emptyByteArray))
      )
    )
    (schema, batch)

  end val

end ArrowSamples

class ArrowWriterTest extends UniTest:
  FileSystemInit.init()
  import ArrowSamples.*

  private def hex(b: Array[Byte]): Hex = Hex.of(b, 0, b.length)

  private def uuidHex(u: UUID): Hex =
    val bb = java.nio.ByteBuffer.allocate(16)
    bb.putLong(u.getMostSignificantBits)
    bb.putLong(u.getLeastSignificantBits)
    hex(bb.array())

  /** The row as the test reader decodes it */
  private def expected(r: ArrowRow): Map[String, Any] = Map(
    "id"    -> BigInt(r.id),
    "i32"   -> BigInt(r.i32),
    "small" -> BigInt(r.small.toInt),
    "tiny"  -> BigInt(r.tiny.toInt),
    "name"  -> r.name.orNull,
    "d"     -> r.d,
    "f"     -> r.f,
    "b"     -> r.b,
    "ts"    -> BigInt(r.ts.getEpochSecond * 1000000L + r.ts.getNano / 1000),
    "day"   -> BigInt(r.day.toEpochDay),
    "t"     -> BigInt(r.t.toNanoOfDay / 1000),
    "price" -> (r.price * BigDecimal(10).pow(18)).toBigInt,
    "uid"   -> uuidHex(r.uid),
    "tags"  -> r.tags.toIndexedSeq,
    "items" -> r.items.map(it => Map("sku" -> it.sku, "qty" -> BigInt(it.qty))).toIndexedSeq,
    "attrs" -> r.attrs.map((k, v) => k -> BigInt(v)),
    "note"  -> r.note.map(it => Map("sku" -> it.sku, "qty" -> BigInt(it.qty))).orNull,
    "bin"   -> hex(r.bin)
  )

  test("map a Parquet schema to Arrow types") {
    val s = ArrowSchema.fromParquet(parquet.schema)
    s.field("id").get.tpe shouldBe ArrowType.Int(64, true)
    s.field("small").get.tpe shouldBe ArrowType.Int(16, true)
    s.field("tiny").get.tpe shouldBe ArrowType.Int(8, true)
    s.field("name").get.tpe shouldBe ArrowType.Utf8
    s.field("name").get.nullable shouldBe true
    s.field("id").get.nullable shouldBe false
    s.field("ts").get.tpe shouldBe ArrowType.Timestamp(ArrowTimeUnit.MICROSECOND, Some("UTC"))
    s.field("day").get.tpe shouldBe ArrowType.Date32
    s.field("t").get.tpe shouldBe ArrowType.Time(ArrowTimeUnit.MICROSECOND)
    s.field("price").get.tpe shouldBe ArrowType.Decimal(38, 18, 128)
    s.field("uid").get.tpe shouldBe ArrowType.FixedSizeBinary(16)
    s.field("uid").get.metadata.get("ARROW:extension:name") shouldBe Some("arrow.uuid")
    s.field("tags").get.tpe shouldBe ArrowType.List
    s.field("attrs").get.tpe shouldBe ArrowType.Map(false)
    s.field("attrs").get.children.map(_.name) shouldBe Seq("entries")
    s.field("attrs").get.children(0).children.map(_.name) shouldBe Seq("key", "value")
    s.field("note").get.tpe shouldBe ArrowType.Struct
    s.toString shouldContain "ts: timestamp(microsecond, UTC) not null"
  }

  test("write a stream that reads back row for row") {
    val bytes = Arrow.toStreamBytes(parquet.schema, batches())
    val r     = readStream(bytes)
    r.sawEndOfStream shouldBe true
    r.batches.map(_.length) shouldBe Seq(128, 128, 44)
    r.field("ts").params("timezone") shouldBe Some("UTC")
    r.field("uid").metadata("ARROW:extension:name") shouldBe "arrow.uuid"
    r.field("name").nullable shouldBe true
    r.rows.size shouldBe rows.size
    r.rows
      .zip(rows)
      .foreach { (a, e) =>
        a shouldBe expected(e)
      }
  }

  test("write a file whose footer points to every batch") {
    val bytes = Arrow.toFileBytes(parquet.schema, batches())
    val r     = readFile(bytes)
    r.batches.size shouldBe 3
    r.rows shouldBe readStream(Arrow.toStreamBytes(parquet.schema, batches())).rows
  }

  for c <- Seq(ArrowCompression.ZSTD, ArrowCompression.LZ4_FRAME) do
    test(s"compress record batches with ${c}") {
      val plain      = Arrow.toStreamBytes(parquet.schema, batches())
      val compressed = Arrow.toStreamBytes(
        parquet.schema,
        batches(),
        ArrowWriteOptions.default.withCompression(c)
      )
      (compressed.length < plain.length) shouldBe true
      readStream(compressed).rows shouldBe readStream(plain).rows
      readFile(
        Arrow.toFileBytes(parquet.schema, batches(), ArrowWriteOptions.default.withCompression(c))
      ).rows shouldBe readStream(plain).rows
    }

  test("store buffers that do not shrink uncompressed") {
    val schema = ParquetSchema.builder().required("x", INT64).build()
    val rnd    = scala.util.Random(42)
    val batch  = ColumnBatch(
      100,
      IndexedSeq(LongColumn(schema.fields(0), Array.fill(100)(rnd.nextLong()), null))
    )
    val bytes = Arrow.toStreamBytes(
      schema,
      Seq(batch),
      ArrowWriteOptions.default.withCompression(ArrowCompression.LZ4_FRAME)
    )
    readStream(bytes).rows.map(_("x")) shouldBe
      batch.columns(0).asInstanceOf[LongColumn].values.toSeq.map(BigInt(_))
  }

  test("write a stream with a schema and no batches") {
    val r = readStream(Arrow.toStreamBytes(parquet.schema, Seq.empty))
    r.batches.isEmpty shouldBe true
    r.sawEndOfStream shouldBe true
    r.fields.map(_.name) shouldBe parquet.schema.fields.map(_.name)
    readFile(Arrow.toFileBytes(parquet.schema, Seq.empty)).batches.isEmpty shouldBe true
  }

  test("write schema metadata") {
    val bytes = Arrow.toStreamBytes(
      parquet.schema,
      Seq.empty,
      ArrowWriteOptions.default.withMetadata(Map("source" -> "uni"))
    )
    readStream(bytes).metadata shouldBe Map("source" -> "uni")
  }

  test("convert integers, decimals, and legacy types") {
    val schema = typesSchema
    val batch  = typesBatch
    val r      = readStream(Arrow.toStreamBytes(schema, Seq(batch)))
    r.field("u8").params shouldBe Map("bitWidth" -> 8, "isSigned" -> false)
    r.field("dec40").params("bitWidth") shouldBe 256
    r.field("legacy").params("unit") shouldBe 3
    r.field("legacy").params("timezone") shouldBe Some("UTC")
    r.field("local_ts").params("timezone") shouldBe None
    r.field("unknown").typeName shouldBe "Null"
    r.field("ival").params("unit") shouldBe 2
    r.field("tm").params shouldBe Map("unit" -> 1, "bitWidth" -> 32)
    val Seq(r0, r1) = r.rows
    r0("u8") shouldBe BigInt(255)
    r1("u8") shouldBe null
    r0("i16") shouldBe BigInt(-32768)
    r0("u32") shouldBe BigInt(4294967295L)
    r0("u64") shouldBe (BigInt(1) << 64) - 1
    r0("dec9") shouldBe BigInt(-12345)
    r0("dec18") shouldBe BigInt(-999999999999999999L)
    r0("dec40") shouldBe big
    r1("dec40") shouldBe null
    r0("decvar") shouldBe BigInt(-5)
    r1("decvar") shouldBe BigInt(1)
    r0("f16") shouldBe 1.5f
    r1("f16") shouldBe -2.0f
    r0("ival") shouldBe MonthDayNano(14, 3, 1500000000L)
    r0("legacy") shouldBe
      BigInt(Instant.parse("2026-01-02T03:04:05.000000006Z").getEpochSecond) * 1000000000L + 6
    r0("unknown") shouldBe null
    r0("local_ts") shouldBe BigInt(1)
    r1("tm") shouldBe BigInt(86399999)
    r0("js") shouldBe "{\"a\":1}"
    r0("raw") shouldBe Hex("01")
    r1("raw") shouldBe Hex("")
  }

  test("write unannotated binaries as strings when asked") {
    val schema = ParquetSchema.builder().required("s", BINARY).build()
    val batch  = ColumnBatch(
      1,
      IndexedSeq(BinaryColumn(schema.fields(0), Array(0, 2), "hi".getBytes, null))
    )
    val opts = ArrowWriteOptions.default.withBinaryAsString(true)
    readStream(Arrow.toStreamBytes(schema, Seq(batch), opts)).rows shouldBe Seq(Map("s" -> "hi"))
  }

  test("reject a VARIANT column before writing anything") {
    val schema = ParquetSchema.builder().required("v", BINARY).build()
    // A VARIANT group: metadata and value binaries
    val variant = ParquetSchema.fromColumns(
      "schema",
      Seq(
        ParquetColumn.optional(
          "v",
          ParquetType.struct(
            ParquetColumn.required("metadata", BINARY),
            ParquetColumn.required("value", BINARY)
          )
        )
      )
    )
    val g      = variant.fields(0).asInstanceOf[ParquetField.Group]
    val tagged = ParquetSchema(
      variant.root.copy(children = IndexedSeq(g.copy(logicalType = Some(LogicalType.Variant))))
    )
    val out = java.io.ByteArrayOutputStream()
    val e   = intercept[ArrowException] {
      Arrow.newStreamWriter(out, tagged)
    }
    e.getMessage shouldContain "VARIANT"
    e.getMessage shouldContain "v"
    out.size() shouldBe 0
    // A plain binary column is fine
    Arrow.newStreamWriter(out, schema).close()
  }

  test("reject a batch that does not fit, keeping the earlier batches whole") {
    val schema = ParquetSchema.builder().required("x", INT32).optional("y", INT8).build()
    val ok     = ColumnBatch(
      1,
      IndexedSeq(
        IntColumn(schema.fields(0), Array(1), null),
        IntColumn(schema.fields(1), Array(2), null)
      )
    )
    val out = java.io.ByteArrayOutputStream()
    val w   = Arrow.newStreamWriter(out, schema)
    w.write(ok)
    val size = out.size()
    // A null in a required column
    val e1 = intercept[ArrowException] {
      w.write(
        ColumnBatch(
          1,
          IndexedSeq(
            IntColumn(schema.fields(0), Array(1), Array(true)),
            IntColumn(schema.fields(1), Array(2), null)
          )
        )
      )
    }
    e1.getMessage shouldContain "x"
    // A value over int8
    val e2 = intercept[ArrowException] {
      w.write(
        ColumnBatch(
          1,
          IndexedSeq(
            IntColumn(schema.fields(0), Array(1), null),
            IntColumn(schema.fields(1), Array(300), null)
          )
        )
      )
    }
    e2.getMessage shouldContain "300"
    out.size() shouldBe size
    w.abort()
    val r = readStream(out.toByteArray)
    r.sawEndOfStream shouldBe false
    r.rows shouldBe Seq(Map("x" -> BigInt(1), "y" -> BigInt(2)))
  }

  test("copy fixed-width rows one by one when skipped rows are uneven") {
    val schema = ParquetSchema
      .builder()
      .optional("s", ParquetType.struct(ParquetColumn.required("f", fixed(2))))
      .build()
    val g = schema.fields(0).asInstanceOf[ParquetField.Group]
    // Row 0 is a null struct whose child has 0 bytes; row 2's child has 4, so the total is 2 * 3
    val child = BinaryColumn(g.children(0), Array(0, 0, 2, 6), Array[Byte](1, 2, 9, 9, 9, 9), null)
    val batch = ColumnBatch(
      3,
      IndexedSeq(StructColumn(g, 3, IndexedSeq(child), Array(true, false, true)))
    )
    readStream(Arrow.toStreamBytes(schema, Seq(batch))).rows.map(_("s")) shouldBe
      Seq(null, Map("f" -> Hex("0102")), null)
  }

  test("reject an INT96 value that is not 12 bytes") {
    val base   = ParquetSchema.builder().required("t", fixed(12)).build()
    val schema = ParquetSchema(
      base
        .root
        .copy(children =
          IndexedSeq(
            base
              .fields(0)
              .asInstanceOf[ParquetField.Primitive]
              .copy(physicalType = PhysicalType.INT96)
          )
        )
    )
    val batch = ColumnBatch(
      1,
      IndexedSeq(BinaryColumn(schema.fields(0), Array(0, 8), new Array[Byte](8), null))
    )
    val e = intercept[ArrowException] {
      Arrow.toStreamBytes(schema, Seq(batch))
    }
    e.getMessage shouldContain "12"
  }

  test("fail writes after close") {
    val schema = ParquetSchema.builder().required("x", INT32).build()
    val w      = Arrow.newStreamWriter(java.io.ByteArrayOutputStream(), schema)
    w.close()
    intercept[IllegalStateException] {
      w.write(ColumnBatch(0, IndexedSeq(IntColumn(schema.fields(0), Array.emptyIntArray, null))))
    }
  }

  test("reject a compression level for codecs without one") {
    intercept[IllegalArgumentException] {
      Arrow.newStreamWriter(
        java.io.ByteArrayOutputStream(),
        parquet.schema,
        ArrowWriteOptions.default.withCompression(ArrowCompression.LZ4_FRAME, 3)
      )
    }
  }

end ArrowWriterTest
