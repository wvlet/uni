package wvlet.uni.parquet

import wvlet.uni.io.FileSystem
import wvlet.uni.io.FileSystemInit
import wvlet.uni.parquet.ColumnVector.*
import wvlet.uni.parquet.RowGroupFilter.*
import wvlet.uni.parquet.format.CompressionCodec
import wvlet.uni.parquet.format.Encoding
import wvlet.uni.test.UniTest
import wvlet.uni.weaver.Weaver

import java.time.Instant
import java.util.UUID

enum Color derives Weaver:
  case Red,
    Green,
    Blue

case class Address(city: String, zip: Option[String]) derives Weaver

case class Item(name: String, qty: Int, price: BigDecimal) derives Weaver

case class Order(
    id: Long,
    i32: Int,
    i16: Short,
    i8: Byte,
    f: Float,
    d: Double,
    flag: Boolean,
    name: String,
    note: Option[String],
    ts: Instant,
    uuid: UUID,
    amount: BigDecimal,
    color: Color,
    tags: Seq[String],
    scores: Seq[Option[Int]],
    attrs: Map[String, Int],
    optAttrs: Option[Map[String, Option[String]]],
    address: Address,
    billing: Option[Address],
    items: Seq[Item],
    matrix: Option[Seq[Seq[Long]]]
) derives Weaver

object Order:
  private val base = Instant.parse("2026-01-01T00:00:00Z")

  def sample(i: Int): Order = Order(
    id = i.toLong,
    i32 = i * 3 - 100,
    i16 = (i % 300).toShort,
    i8 = (i  % 100 - 50).toByte,
    f = i / 4.0f,
    d = i * 0.5,
    flag = i % 3 == 0,
    name = s"name-${i % 17}",
    note =
      if i % 4 == 0 then
        None
      else
        Some(s"note ${i}")
    ,
    // Microsecond precision: TIMESTAMP(MICROS) drops nanoseconds
    ts = base.plusSeconds(i.toLong).plusNanos(i * 1000L),
    uuid = UUID(i.toLong, -i.toLong),
    amount = BigDecimal(i) * BigDecimal("1.25") - BigDecimal(50),
    color = Color.fromOrdinal(i % 3),
    tags = (0 until i   % 4).map(x => s"t${x}"),
    scores = (0 until i % 3).map(x =>
      if x == 1 then
        None
      else
        Some(x * i)
    ),
    attrs = (0 until i % 3).map(x => s"k${x}" -> x).toMap,
    optAttrs =
      if i % 5 == 0 then
        None
      else
        Some(Map("a" -> Some(s"v${i}"), "b" -> None))
    ,
    address = Address(s"city${i % 5}", Option.when(i % 2 == 0)(s"${10000 + i}")),
    billing = Option.when(i % 3 != 0)(Address("billing", None)),
    items = (0 until i % 3).map(x => Item(s"item${x}", x + 1, BigDecimal(x) + BigDecimal("0.5"))),
    matrix =
      if i % 6 == 0 then
        None
      else
        Some(Seq(Seq(i.toLong, i + 1L), Seq.empty, Seq(-1L)))
  )

end Order

case class Blob(id: Int, data: Array[Byte], opt: Option[Array[Byte]]) derives Weaver

case class Price(id: Int, price: BigDecimal, small: BigDecimal) derives Weaver

case class Simple(id: Long, name: Option[String]) derives Weaver

case class Tagged(id: Int, tags: Seq[String], note: Option[String]) derives Weaver

case class Recursive(id: Int, children: Seq[Recursive]) derives Weaver

class WriterTest extends UniTest:
  FileSystemInit.init()

  private val orders = (0 until 300).map(Order.sample)

  private def roundTrip[A: Weaver](
      bytes: Array[Byte],
      options: ParquetReadOptions = ParquetReadOptions.default
  ): Seq[A] =
    val f = Parquet.open(bytes)
    try f.readAs[A](options).toSeq
    finally f.close()

  test("derive a schema from a case class") {
    val schema = ParquetSchema.of[Simple]
    schema.toString shouldBe """message schema {
        |  required int64 id
        |  optional byte_array name (String)
        |}""".stripMargin
  }

  test("derive nested lists, maps, and groups") {
    val schema = ParquetSchema.of[Order]
    val s      = schema.toString
    s shouldContain "required group tags (List) {"
    s shouldContain "repeated group list {"
    s shouldContain "required byte_array element (String)"
    s shouldContain "optional int32 element"
    s shouldContain "required group attrs (Map) {"
    s shouldContain "repeated group key_value {"
    s shouldContain "required byte_array key (String)"
    s shouldContain "optional group billing {"
    s shouldContain "required int64 ts (Timestamp(true,MICROS))"
    s shouldContain "required fixed_len_byte_array(16) uuid (Uuid)"
    s shouldContain "required fixed_len_byte_array(16) amount (Decimal(18,38))"
    s shouldContain "required int32 i16 (Integer(16,true))"
  }

  test("reject recursive types") {
    intercept[ParquetException] {
      ParquetSchema.of[Recursive]
    }
  }

  test("write and read back every type") {
    val bytes = Parquet.writeBytes(orders)
    roundTrip[Order](bytes) shouldBe orders
  }

  for codec <- Seq(
      CompressionCodec.UNCOMPRESSED,
      CompressionCodec.SNAPPY,
      CompressionCodec.LZ4_RAW,
      CompressionCodec.GZIP
    )
  do
    test(s"write with ${codec}") {
      if codec == CompressionCodec.GZIP && FileSystem.isBrowser then
        skip("GZIP needs the platform zlib")
      val opts  = ParquetWriteOptions.default.withCompression(codec)
      val bytes = Parquet.writeBytes(orders, opts)
      val f     = Parquet.open(bytes)
      f.rowGroups.head.columns.head.codec shouldBe codec
      f.close()
      roundTrip[Order](bytes) shouldBe orders
    }

  test("write without dictionary encoding") {
    val bytes = Parquet.writeBytes(orders, ParquetWriteOptions.default.noDictionary())
    val f     = Parquet.open(bytes)
    f.rowGroups.head.columns.forall(!_.encodings.contains(Encoding.RLE_DICTIONARY)) shouldBe true
    f.close()
    roundTrip[Order](bytes) shouldBe orders
  }

  test("fall back from dictionary to PLAIN when the dictionary grows too large") {
    val rows  = (0 until 5000).map(i => Simple(i % 50, Some(s"distinct-value-${i}")))
    val opts  = ParquetWriteOptions.default.withDictionaryPageSize(4096).withPageSize(1024)
    val bytes = Parquet.writeBytes(rows, opts)
    val f     = Parquet.open(bytes)
    val cols  = f.rowGroups.head.columns
    // id stays dictionary-encoded; name falls back
    cols(0).encodings shouldContain Encoding.RLE_DICTIONARY
    cols(1).encodings shouldContain Encoding.RLE_DICTIONARY
    cols(1).encodings shouldContain Encoding.PLAIN
    f.close()
    roundTrip[Simple](bytes) shouldBe rows
    // Batches that end inside a page, and a dictionary run followed by PLAIN values
    roundTrip[Simple](bytes, ParquetReadOptions.default.withBatchSize(7)) shouldBe rows
  }

  test("read dictionary strings in nested columns and small batches") {
    val rows = (0 until 2000).map(i =>
      Tagged(i, Seq.tabulate(i % 4)(j => s"tag-${(i + j) % 5}"), Option.when(i % 3 != 0)("x"))
    )
    val bytes = Parquet.writeBytes(rows, ParquetWriteOptions.default.withPageSize(512))
    roundTrip[Tagged](bytes) shouldBe rows
    roundTrip[Tagged](bytes, ParquetReadOptions.default.withBatchSize(13)) shouldBe rows
  }

  test("split row groups and pages") {
    val rows  = (0 until 20000).map(i => Simple(i, Option.when(i % 10 != 0)(s"n${i % 100}")))
    val opts  = ParquetWriteOptions.default.withRowGroupSize(32 * 1024).withPageSize(2048)
    val bytes = Parquet.writeBytes(rows, opts)
    val f     = Parquet.open(bytes)
    (f.numRowGroups > 1) shouldBe true
    f.numRows shouldBe 20000L
    f.rowGroups.map(_.numRows).sum shouldBe 20000L
    f.close()
    roundTrip[Simple](bytes) shouldBe rows
  }

  test("write statistics that RowGroupFilter uses") {
    val rows  = (0 until 20000).map(i => Simple(i, Some(f"n${i}%05d")))
    val opts  = ParquetWriteOptions.default.withRowGroupSize(32 * 1024)
    val bytes = Parquet.writeBytes(rows, opts)
    val f     = Parquet.open(bytes)
    val stats = f.rowGroups.head.columns.head.statistics.get
    stats.nullCount shouldBe Some(0L)
    stats.isLegacyMinMax shouldBe false
    val n      = f.numRowGroups
    val picked = f.selectRowGroups(
      ParquetReadOptions.default.withRowGroupFilter(col("id") >= 19990L)
    )
    picked shouldBe IndexedSeq(n - 1)
    f.selectRowGroups(ParquetReadOptions.default.withRowGroupFilter(col("name") === "n00005"))
      .shouldBe(IndexedSeq(0))
    f.close()
  }

  test("count nulls in statistics") {
    val rows  = (0 until 100).map(i => Simple(i, Option.when(i % 4 != 0)("x")))
    val bytes = Parquet.writeBytes(rows)
    val f     = Parquet.open(bytes)
    f.rowGroups.head.columns(1).statistics.get.nullCount shouldBe Some(25L)
    f.close()
  }

  test("write binary columns") {
    val rows = (0 until 50).map(i =>
      Blob(i, Array.tabulate(i % 7)(_.toByte), Option.when(i % 2 == 0)(Array[Byte](-1, 0, 1)))
    )
    val bytes = Parquet.writeBytes(rows)
    val back  = roundTrip[Blob](bytes)
    back.size shouldBe rows.size
    back
      .zip(rows)
      .foreach { (a, b) =>
        a.id shouldBe b.id
        a.data.toSeq shouldBe b.data.toSeq
        a.opt.map(_.toSeq) shouldBe b.opt.map(_.toSeq)
      }
  }

  test("set the precision and scale of decimals") {
    val rows = Seq(
      Price(1, BigDecimal("12.34"), BigDecimal("1.5")),
      Price(2, BigDecimal("-99999999.99"), BigDecimal("0"))
    )
    val opts  = ParquetWriteOptions.default.withDecimal(10, 2).withDecimal("small", 3, 1)
    val bytes = Parquet.writeBytes(rows, opts)
    val f     = Parquet.open(bytes)
    f.schema.toString shouldContain "required int64 price (Decimal(2,10))"
    f.schema.toString shouldContain "required int32 small (Decimal(1,3))"
    f.close()
    roundTrip[Price](bytes) shouldBe rows
  }

  test("reject decimals that do not fit") {
    val opts = ParquetWriteOptions.default.withDecimal(4, 2)
    val e    = intercept[ParquetException] {
      Parquet.writeBytes(Seq(Price(1, BigDecimal("123.45"), BigDecimal(0))), opts)
    }
    e.getMessage shouldContain "price"
    intercept[ParquetException] {
      Parquet.writeBytes(Seq(Price(1, BigDecimal("1.234"), BigDecimal(0))), opts)
    }
  }

  test("an empty file reads back with no rows") {
    val bytes = Parquet.writeBytes(Seq.empty[Simple])
    val f     = Parquet.open(bytes)
    f.numRows shouldBe 0L
    f.numRowGroups shouldBe 0
    f.close()
  }

  test("an invalid writer creates no file") {
    if FileSystem.isBrowser then
      skip("no local files in browsers")
    val dir  = FileSystem.createTempDirectory("uni-parquet")
    val path = dir / "invalid.parquet"
    try
      intercept[IllegalArgumentException] {
        Parquet.newRecordWriter(path, ParquetSchema.builder().build())
      }
      intercept[ParquetException] {
        Parquet.newWriter[Recursive](path)
      }
      FileSystem.exists(path) shouldBe false
    finally
      FileSystem.delete(dir)
  }

  test("write a local file") {
    if FileSystem.isBrowser then
      skip("no local files in browsers")
    val dir  = FileSystem.createTempDirectory("uni-parquet")
    val path = dir / "orders.parquet"
    try
      Parquet.write(path.toString, orders)
      Parquet.readAs[Order](path.toString) shouldBe orders
      val w = Parquet.newWriter[Simple](path)
      try
        w.write(Simple(1, Some("a")))
        w.write(Simple(2, None))
        w.numRows shouldBe 2L
      finally
        w.close()
      Parquet.readAs[Simple](path.toString) shouldBe Seq(Simple(1, Some("a")), Simple(2, None))
    finally
      FileSystem.delete(path)
      FileSystem.delete(dir)
  }

  // Record writer

  private val recordSchema = ParquetSchema
    .builder("record")
    .required("id", ParquetType.INT64)
    .optional("name", ParquetType.STRING)
    .optional("tags", ParquetType.list(ParquetType.STRING))
    .optional("ts", ParquetType.TIMESTAMP_MILLIS)
    .optional("price", ParquetType.decimal(9, 2))
    .optional("day", ParquetType.DATE)
    .optional("uuid", ParquetType.UUID)
    .optional(
      "attrs",
      ParquetType.map(ParquetType.STRING, ParquetType.INT32, optionalValues = true)
    )
    .optional(
      "addr",
      ParquetType.struct(
        ParquetColumn.required("city", ParquetType.STRING),
        ParquetColumn.optional("zip", ParquetType.STRING)
      )
    )
    .build()

  private def writeRecords(records: Seq[Any]): Array[Byte] =
    val out = java.io.ByteArrayOutputStream()
    val w   = Parquet.newRecordWriter(out, recordSchema)
    try w.writeAll(records)
    finally w.close()
    out.toByteArray

  private def rows(bytes: Array[Byte]): Seq[Map[String, Any]] =
    val f = Parquet.open(bytes)
    try f.readBatches().flatMap(b => (0 until b.numRows).map(b.row)).toSeq
    finally f.close()

  test("write records from maps, JSON, and value sequences") {
    val ts    = Instant.parse("2026-02-03T04:05:06.789Z")
    val bytes = writeRecords(
      Seq(
        Map(
          "id"    -> 1,
          "name"  -> "aina",
          "tags"  -> Seq("a", "b"),
          "ts"    -> ts,
          "price" -> BigDecimal("12.50"),
          "day"   -> java.time.LocalDate.of(2026, 1, 31),
          "uuid"  -> UUID(1L, 2L),
          "attrs" -> Map("x" -> 1, "y" -> None),
          "addr"  -> Address("tokyo", Some("100"))
        ),
        """{"id": 2, "name": "leo", "tags": [], "ts": "2026-02-03T04:05:06.789Z", "price": "1.5", "day": "2026-01-31", "addr": {"city": "kyoto"}}""",
        Seq(3, "aina", null, null, null, null, null, null, null),
        """[4, null, ["z"], 1770091506789, 3, null, "00000000-0000-0001-0000-000000000002", {"k": 7}, null]"""
      )
    )
    val r = rows(bytes)
    r.size shouldBe 4
    r(0)("id") shouldBe 1L
    r(0)("name") shouldBe "aina"
    r(0)("tags") shouldBe Seq("a", "b")
    r(0)("ts") shouldBe ts
    r(0)("price") shouldBe BigDecimal("12.50")
    r(0)("day") shouldBe java.time.LocalDate.of(2026, 1, 31)
    r(0)("uuid") shouldBe UUID(1L, 2L)
    r(0)("attrs") shouldBe Map("x" -> 1, "y" -> null)
    r(0)("addr") shouldBe Map("city" -> "tokyo", "zip" -> "100")
    r(1)("id") shouldBe 2L
    r(1)("tags") shouldBe Seq.empty
    r(1)("ts") shouldBe ts
    r(1)("price") shouldBe BigDecimal("1.50")
    r(1)("addr") shouldBe Map("city" -> "kyoto", "zip" -> null)
    r(2)("id") shouldBe 3L
    r(2)("name") shouldBe "aina"
    r(2)("tags") shouldBe null
    r(3)("tags") shouldBe Seq("z")
    r(3)("ts") shouldBe Instant.ofEpochMilli(1770091506789L)
    r(3)("price") shouldBe BigDecimal("3.00")
    r(3)("uuid") shouldBe UUID(1L, 2L)
    r(3)("attrs") shouldBe Map("k" -> 7)
  }

  test("match record keys loosely") {
    val schema = ParquetSchema.builder().required("userName", ParquetType.STRING).build()
    val out    = java.io.ByteArrayOutputStream()
    val w      = Parquet.newRecordWriter(out, schema)
    w.write(Map("user_name" -> "aina"))
    w.close()
    rows(out.toByteArray) shouldBe Seq(Map("userName" -> "aina"))
  }

  test("match record keys in any order, exact names before loose ones") {
    val schema = ParquetSchema
      .builder()
      .required("id", ParquetType.INT64)
      .optional("userName", ParquetType.STRING)
      .optional(
        "addr",
        ParquetType.struct(
          ParquetColumn.required("city", ParquetType.STRING),
          ParquetColumn.optional("zip", ParquetType.STRING)
        )
      )
      .build()
    val out = java.io.ByteArrayOutputStream()
    val w   = Parquet.newRecordWriter(out, schema)
    w.write("""{"addr": {"zip": "1", "city": "kyoto"}, "user_name": "loose", "id": 1}""")
    w.write("""{"id": 2, "user_name": "loose", "userName": "exact", "extra": [1, {"a": 2}]}""")
    // When a key appears twice, the first one counts
    w.write("""{"id": 3, "id": 4}""")
    w.write(Seq(5, "aina", Map("city" -> "tokyo")))
    w.close()
    rows(out.toByteArray) shouldBe
      Seq(
        Map("id" -> 1L, "userName" -> "loose", "addr" -> Map("city" -> "kyoto", "zip" -> "1")),
        Map("id" -> 2L, "userName" -> "exact", "addr" -> null),
        Map("id" -> 3L, "userName" -> null, "addr"    -> null),
        Map("id" -> 5L, "userName" -> "aina", "addr"  -> Map("city" -> "tokyo", "zip" -> null))
      )
  }

  test("reject a bad row and keep writing") {
    val out = java.io.ByteArrayOutputStream()
    val w   = Parquet.newRecordWriter(out, recordSchema)
    val e1  = intercept[ParquetException] {
      w.write(Map("name" -> "no id"))
    }
    e1.getMessage shouldContain "id"
    val e2 = intercept[ParquetException] {
      w.write(Map("id" -> 1, "tags" -> Seq("ok"), "price" -> "abc"))
    }
    e2.getMessage shouldContain "price"
    intercept[ParquetException] {
      w.write("""{"id": "#"}""")
    }
    intercept[ParquetException] {
      w.write("{not json")
    }
    // 1e30 is a whole number but beyond Long; it must not saturate to Long.MaxValue
    intercept[ParquetException] {
      w.write("""{"id": 1e30}""")
    }
    intercept[ParquetException] {
      w.write(Seq(1, 2))
    }
    w.write(Map("id" -> 10, "tags" -> Seq("kept")))
    w.numRows shouldBe 1L
    w.close()
    val r = rows(out.toByteArray)
    r.size shouldBe 1
    r.head("id") shouldBe 10L
    r.head("tags") shouldBe Seq("kept")
  }

  test("reject writes after close") {
    val w = Parquet.newRecordWriter(java.io.ByteArrayOutputStream(), recordSchema)
    w.close()
    intercept[ParquetException] {
      w.write(Map("id" -> 1))
    }
  }

  test("an I/O error closes the writer without a footer") {
    var fail = false
    val sink = java.io.ByteArrayOutputStream()
    val out  =
      new java.io.OutputStream:
        override def write(b: Int): Unit                             = write(Array(b.toByte), 0, 1)
        override def write(b: Array[Byte], off: Int, len: Int): Unit =
          if fail then
            throw java.io.IOException("disk full")
          sink.write(b, off, len)
    val w = Parquet.newWriter[Simple](out)
    w.write(Simple(1, None))
    fail = true
    intercept[java.io.IOException] {
      w.close()
    }
    intercept[ParquetException] {
      w.write(Simple(2, None))
    }
    intercept[ParquetException] {
      Parquet.open(sink.toByteArray)
    }
  }

  test("reject GZIP in browsers only") {
    if FileSystem.isBrowser then
      intercept[ParquetException] {
        Parquet.writeBytes(
          Seq(Simple(1, None)),
          ParquetWriteOptions.default.withCompression(CompressionCodec.GZIP)
        )
      }
    intercept[ParquetException] {
      Parquet.writeBytes(
        Seq(Simple(1, None)),
        ParquetWriteOptions.default.withCompression(CompressionCodec.ZSTD)
      )
    }
  }

end WriterTest
