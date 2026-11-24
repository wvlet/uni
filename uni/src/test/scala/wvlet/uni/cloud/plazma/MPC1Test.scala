package wvlet.uni.cloud.plazma

import wvlet.uni.cloud.parquet.ParquetField
import wvlet.uni.io.FileSystem
import wvlet.uni.io.FileSystemInit
import wvlet.uni.io.IOPath
import wvlet.uni.msgpack.impl.PureScalaBufferPacker
import wvlet.uni.msgpack.spi.Value
import wvlet.uni.msgpack.spi.Value.*
import wvlet.uni.test.UniTest

import scala.collection.immutable.ListMap

object MPC1TestFiles:
  private val roots = Seq(
    "uni/src/test/resources/plazma",
    "src/test/resources/plazma",
    "../src/test/resources/plazma",
    "../../uni/src/test/resources/plazma"
  )

  def bytes(name: String): Array[Byte] =
    val path = roots
      .map(r => IOPath.parse(s"${r}/${name}"))
      .find(FileSystem.exists)
      .getOrElse(
        throw IllegalArgumentException(
          s"Test file ${name} not found from ${FileSystem.currentDirectory}"
        )
      )
    FileSystem.readBytes(path)

class MPC1Test extends UniTest:
  FileSystemInit.init()

  private def canWriteGzip: Boolean = !FileSystem.isBrowser

  private def row(kv: (String, Value)*): MapValue = MapValue(
    ListMap.from(kv.map((k, v) => (StringValue(k): Value) -> v))
  )

  private def block(f: PureScalaBufferPacker => Unit): Array[Byte] =
    val pk = PureScalaBufferPacker()
    f(pk)
    pk.toByteArray

  test("typed reads match the values of every lineitem column") {
    val file  = MPC1.open(MPC1TestFiles.bytes("lineitem.mpc1"))
    val types = file.columnNames.map(n => n -> file.columnType(n)).toMap
    types("time") shouldBe MPC1ColumnType.LONG
    types("l_orderkey") shouldBe MPC1ColumnType.LONG
    types("l_extendedprice") shouldBe MPC1ColumnType.DOUBLE
    types("l_comment") shouldBe MPC1ColumnType.STRING
    types("l_dummy") shouldBe MPC1ColumnType.NIL
    for name <- file.columnNames do
      val expected = file.readColumn(name).toIndexedSeq
      val vector   =
        types(name) match
          case MPC1ColumnType.LONG | MPC1ColumnType.NIL =>
            file.readLongs(name)
          case MPC1ColumnType.DOUBLE =>
            file.readDoubles(name)
          case MPC1ColumnType.BOOLEAN =>
            file.readBooleans(name)
          case MPC1ColumnType.STRING =>
            file.readStrings(name)
          case MPC1ColumnType.BINARY =>
            file.readBinaries(name)
          case MPC1ColumnType.ANY =>
            fail(s"column ${name} has mixed types")
      vector.name shouldBe name
      vector.size shouldBe 1876
      for i <- expected.indices do
        val actual = vector.getValue(i)
        expected(i) match
          case NilValue =>
            vector.isNull(i) shouldBe true
          case LongValue(x) if types(name) == MPC1ColumnType.DOUBLE =>
            actual shouldBe x.toDouble
          case LongValue(x) =>
            actual shouldBe x
          case DoubleValue(x) =>
            actual shouldBe x
          case BooleanValue(x) =>
            actual shouldBe x
          case StringValue(x) =>
            actual shouldBe x
          case BinaryValue(x) =>
            actual shouldMatch { case a: Array[Byte] =>
              a.toSeq shouldBe x.toSeq
            }
          case other =>
            fail(s"unexpected value ${other} in column ${name}")
    end for
  }

  test("probe column types from format bytes") {
    def typeOf(f: PureScalaBufferPacker => Unit) = MPC1Decoder.columnType(block(f))
    typeOf(_ => ()) shouldBe MPC1ColumnType.NIL
    typeOf(_.packNil.packNil) shouldBe MPC1ColumnType.NIL
    typeOf(_.packLong(1).packNil.packLong(Long.MaxValue)) shouldBe MPC1ColumnType.LONG
    typeOf(_.packDouble(1.5).packNil) shouldBe MPC1ColumnType.DOUBLE
    typeOf(_.packLong(1).packFloat(2.5f)) shouldBe MPC1ColumnType.DOUBLE
    typeOf(_.packBoolean(true).packNil) shouldBe MPC1ColumnType.BOOLEAN
    typeOf(_.packString("a").packNil) shouldBe MPC1ColumnType.STRING
    typeOf(_.packBinaryHeader(1).writePayload(Array[Byte](1))) shouldBe MPC1ColumnType.BINARY
    typeOf(_.packString("a").packBinaryHeader(0)) shouldBe MPC1ColumnType.BINARY
    typeOf(_.packLong(1).packString("a")) shouldBe MPC1ColumnType.ANY
    typeOf(_.packBoolean(true).packLong(1)) shouldBe MPC1ColumnType.ANY
    typeOf(_.packLong(1).packArrayHeader(1).packLong(2)) shouldBe MPC1ColumnType.ANY
  }

  test("decode nullable typed columns") {
    val longs = MPC1Decoder.decodeLongs("a", block(_.packLong(-1).packNil.packLong(1L << 40)))
    longs.values.toSeq shouldBe Seq(-1L, 0L, 1L << 40)
    longs.nullFlags.toSeq shouldBe Seq(false, true, false)

    val doubles = MPC1Decoder.decodeDoubles("a", block(_.packDouble(1.5).packLong(2).packNil))
    doubles.values.toSeq shouldBe Seq(1.5, 2.0, 0.0)
    doubles.nullFlags.toSeq shouldBe Seq(false, false, true)

    val bools = MPC1Decoder.decodeBooleans(
      "a",
      block(_.packBoolean(true).packNil.packBoolean(false))
    )
    bools.values.toSeq shouldBe Seq(true, false, false)
    bools.nullFlags.toSeq shouldBe Seq(false, true, false)

    val long = "x" * 300
    val strs = MPC1Decoder.decodeStrings("a", block(_.packString("ab").packNil.packString(long)))
    strs.size shouldBe 3
    strs.getString(0) shouldBe "ab"
    strs.isNull(1) shouldBe true
    strs.getString(2) shouldBe long
    strs.getValue(2) shouldBe long

    val bins = MPC1Decoder.decodeBinaries(
      "a",
      block(_.packBinaryHeader(2).writePayload(Array[Byte](1, 2)).packString("c"))
    )
    bins.getBytes(0).toSeq shouldBe Seq[Byte](1, 2)
    bins.getBytes(1).toSeq shouldBe "c".getBytes("UTF-8").toSeq
    bins.nullFlags shouldBe null
  }

  test("no null flags without nils, and all nulls for a nil-only column") {
    val many = MPC1Decoder.decodeLongs(
      "a",
      block(pk => (0 until 5000).foreach(i => pk.packLong(i)))
    )
    many.size shouldBe 5000
    many.values(4999) shouldBe 4999L
    many.nullFlags shouldBe null

    val nils = MPC1Decoder.decodeStrings("a", block(pk => (0 until 3000).foreach(_ => pk.packNil)))
    nils.size shouldBe 3000
    (0 until 3000).forall(nils.isNull) shouldBe true
  }

  test("fail on a value of another type") {
    val e = intercept[MPC1Exception] {
      MPC1Decoder.decodeLongs("a", block(_.packLong(1).packString("x")))
    }
    e.getMessage shouldContain "Column a, row 1"
    e.getMessage shouldContain "string"
    intercept[MPC1Exception] {
      MPC1Decoder.decodeStrings("a", block(_.packBinaryHeader(0)))
    }
    intercept[MPC1Exception] {
      MPC1Decoder.decodeBooleans("a", block(_.packLong(1)))
    }
    intercept[MPC1Exception] {
      MPC1Decoder.decodeDoubles("a", block(_.packBoolean(true)))
    }
    // uint64 over Long.MaxValue
    val overflow = Array[Byte](0xcf.toByte) ++ Array.fill[Byte](8)(0xff.toByte)
    intercept[MPC1Exception] {
      MPC1Decoder.decodeLongs("a", overflow)
    }.getMessage shouldContain "row 0"
  }

  test("typed reads by column name") {
    val writer    = MPC1.newWriter(MPC1WriteOptions().noCompression())
    writer.addRow("id"    -> LongValue(1), "name" -> StringValue("a"))
    writer.addRow("score" -> DoubleValue(0.5))
    val file      = MPC1.open(writer.toByteArray)
    file.columnType("id") shouldBe MPC1ColumnType.LONG
    file.readLongs("id").getValue(1) shouldBe null
    file.readStrings("name").getString(0) shouldBe "a"
    file.readDoubles("score").getValue(1) shouldBe 0.5
    file.readDoubles("score").field shouldMatch { case p: ParquetField.Primitive =>
      p.columnIndex shouldBe file.columnNames.indexOf("score")
    }
    intercept[IllegalArgumentException] {
      file.readLongs("no_such_column")
    }
  }

  test("encode and decode block sizes") {
    val sizes = Seq(1L, 127L, 128L, 300L, 16384L, 1L << 35, Long.MaxValue)
    val pk    = PureScalaBufferPacker()
    MPC1Format.encodeIndex(sizes, pk)
    MPC1Format.decodeIndex(pk.toByteArray, 0, pk.writtenSize) shouldBe sizes
  }

  test("a zero size ends the block-size list") {
    val bytes = Array[Byte](0x81.toByte, 0x80.toByte, 0x82.toByte)
    MPC1Format.decodeIndex(bytes, 0, bytes.length) shouldBe Seq(1L)
  }

  test("read a file written by the Java writer") {
    val file = MPC1.open(MPC1TestFiles.bytes("lineitem.mpc1"))
    file.compression shouldBe MPC1Compression.GZIP
    file.columnNames shouldContain "time"
    file.columnNames shouldContain "l_orderkey"
    file.countRows() shouldBe 1876L
    val times = file.readColumn("time").toSeq
    times.size shouldBe 1876
    times.distinct shouldBe Seq(LongValue(908636400L))

    val rows = file.readRows().toSeq
    rows.size shouldBe 1876
    // l_dummy holds only nils, which row maps leave out
    file.readColumn("l_dummy").forall(_ == NilValue) shouldBe true
    rows.head.size shouldBe file.columns.size - 1
    rows.head.get(StringValue("time")) shouldBe Some(LongValue(908636400L))
  }

  test("project columns") {
    val file = MPC1.open(MPC1TestFiles.bytes("lineitem.mpc1"))
    val rows = file.readRows(Seq("l_orderkey", "time", "no_such_column")).toSeq
    rows.size shouldBe 1876
    rows.foreach { r =>
      r.entries.keys.map(_.toUnquotedString).toSet shouldBe Set("l_orderkey", "time")
    }
    // The order of file columns, not of the request
    rows.head.entries.keys.map(_.toUnquotedString).toSeq shouldBe
      file.columnNames.filter(Set("l_orderkey", "time"))
  }

  test("rewrite the Java file and read the same rows") {
    if !canWriteGzip then
      pending("gzip writes are not supported in browsers")
    val original = MPC1.open(MPC1TestFiles.bytes("lineitem.mpc1"))
    val writer   = MPC1.newWriter()
    original.readRows().foreach(writer.add)
    val copy = MPC1.open(writer.toByteArray)
    // Rows carry no nil values, so the all-nil l_dummy column is not rewritten
    copy.columnNames.toSet shouldBe original.columnNames.toSet - "l_dummy"
    copy.readRows().toSeq.map(_.entries.toMap) shouldBe
      original.readRows().toSeq.map(_.entries.toMap)
  }

  for compression <- MPC1Compression.values do
    test(s"round trip rows with ${compression.code}") {
      if compression == MPC1Compression.GZIP && !canWriteGzip then
        pending("gzip writes are not supported in browsers")
      val writer = MPC1.newWriter(MPC1WriteOptions().withCompression(compression))
      writer.add(row("a" -> LongValue(1), "b" -> StringValue("x")))
      writer.add(row("b" -> NilValue, "c" -> DoubleValue(1.5)))
      writer.add(row())
      writer.add(row("a" -> ArrayValue(IndexedSeq(LongValue(1), LongValue(2)))))
      writer.rowCount shouldBe 4L

      val file = MPC1.open(writer.toByteArray)
      file.compression shouldBe compression
      file.columnNames.toSet shouldBe Set("a", "b", "c")
      file.countRows() shouldBe 4L
      file.readRows().toSeq.map(_.entries.toMap) shouldBe
        Seq(
          row("a" -> LongValue(1), "b" -> StringValue("x")),
          row("c" -> DoubleValue(1.5)),
          row(),
          row("a" -> ArrayValue(IndexedSeq(LongValue(1), LongValue(2))))
        ).map(_.entries.toMap)
      file.readColumn("b").toSeq shouldBe Seq(StringValue("x"), NilValue, NilValue, NilValue)
    }

  test("read a gzip name block in an uncompressed file") {
    if !canWriteGzip then
      pending("gzip writes are not supported in browsers")
    val names = PureScalaBufferPacker()
    names.packString("a")
    val nameBlock = wvlet.uni.io.Gzip.compress(names.toByteArray)
    val column    = PureScalaBufferPacker()
    column.packLong(1).packNil
    val block  = column.toByteArray
    val header = MPC1Format.encodeHeader(
      MPC1Compression.NONE,
      ArrayValue(IndexedSeq.empty),
      Seq(nameBlock.length.toLong, block.length.toLong)
    )
    val file = MPC1.open(header ++ nameBlock ++ block)
    file.columnNames shouldBe Seq("a")
    file.readColumn("a").toSeq shouldBe Seq(LongValue(1), NilValue)
  }

  test("keep the first non-nil value of a repeated column") {
    val writer = MPC1.newWriter(MPC1WriteOptions().noCompression())
    writer.add(
      Seq[(Value, Value)](
        StringValue("a") -> NilValue,
        StringValue("a") -> LongValue(1),
        StringValue("a") -> LongValue(2)
      )
    )
    MPC1.open(writer.toByteArray).readColumn("a").toSeq shouldBe Seq(LongValue(1))
  }

  test("sparse and dense columns across many rows") {
    val writer = MPC1.newWriter(MPC1WriteOptions().noCompression())
    val n      = 1000
    for i <- 0 until n do
      val kv = Seq.newBuilder[(String, Value)]
      kv += "id" -> LongValue(i)
      // Turns dense after a few hundred rows
      if i >= 200 then
        kv += "late" -> LongValue(i)
      // Stays sparse
      if i            % 50 == 0 then
        kv += "rare" -> StringValue(s"r${i}")
      // One column per row: a very wide, sparse table
      kv += s"c${i % 300}" -> LongValue(i)
      writer.addRow(kv.result()*)

    val file = MPC1.open(writer.toByteArray)
    file.columns.size shouldBe 303
    file.countRows() shouldBe n.toLong
    val late = file.readColumn("late").toIndexedSeq
    late.take(200).forall(_ == NilValue) shouldBe true
    late.drop(200) shouldBe (200 until n).map(i => LongValue(i))
    val rare = file.readColumn("rare").toIndexedSeq
    rare
      .zipWithIndex
      .foreach { (v, i) =>
        if i % 50 == 0 then
          v shouldBe StringValue(s"r${i}")
        else
          v shouldBe NilValue
      }
    file
      .readColumn("c7")
      .toIndexedSeq
      .zipWithIndex
      .foreach { (v, i) =>
        if i % 300 == 7 then
          v shouldBe LongValue(i)
        else
          v shouldBe NilValue
      }
  }

  test("write metadata") {
    val meta   = MapValue(Map(StringValue("k") -> StringValue("v")))
    val writer = MPC1.newWriter(MPC1WriteOptions().noCompression().withMetadata(meta))
    writer.addRow("a" -> LongValue(1))
    MPC1.open(writer.toByteArray).metadata shouldBe meta
    val noMeta = MPC1.newWriter(MPC1WriteOptions().noCompression())
    MPC1.open(noMeta.toByteArray).metadata shouldBe ArrayValue(IndexedSeq.empty)
  }

  test("a file without columns has no rows") {
    val file = MPC1.open(MPC1.newWriter(MPC1WriteOptions().noCompression()).toByteArray)
    file.columns shouldBe IndexedSeq.empty
    file.countRows() shouldBe 0L
    file.readRows().hasNext shouldBe false
  }

  test("a header larger than the first read") {
    val writer = MPC1.newWriter(MPC1WriteOptions().noCompression())
    val names  = (0 until 500).map(i => s"column_with_a_long_name_${i}")
    writer.addRow(names.map(n => n -> StringValue(n))*)
    val file = MPC1.open(writer.toByteArray)
    file.columnNames.toSet shouldBe names.toSet
    file.readRows().toSeq.head.size shouldBe 500
  }

  test("reject files that are not MPC1") {
    intercept[MPC1Exception] {
      MPC1.open("PAR1 not an mpc1 file".getBytes)
    }
    val bytes = MPC1.newWriter(MPC1WriteOptions().noCompression()).toByteArray
    intercept[MPC1Exception] {
      MPC1.open(bytes.take(6))
    }
  }

  test("reject non-string column names") {
    val writer = MPC1.newWriter()
    intercept[IllegalArgumentException] {
      writer.add(Seq[(Value, Value)](LongValue(1) -> LongValue(1)))
    }
  }

  test("a rejected row stores no values") {
    val writer = MPC1.newWriter(MPC1WriteOptions().noCompression())
    intercept[IllegalArgumentException] {
      writer.add(
        Seq[(Value, Value)](StringValue("a") -> LongValue(1), LongValue(7) -> LongValue(2))
      )
    }
    writer.addRow("a" -> LongValue(9))
    MPC1.open(writer.toByteArray).readColumn("a").toSeq shouldBe Seq(LongValue(9))
  }

  test("reject block sizes over 63 bits") {
    val bytes = Array.fill[Byte](9)(0x7f) :+ 0xff.toByte
    intercept[MPC1Exception] {
      MPC1Format.decodeIndex(bytes, 0, bytes.length)
    }
  }

  test("accept no rows after writing") {
    val writer = MPC1.newWriter(MPC1WriteOptions().noCompression())
    writer.toByteArray
    intercept[IllegalStateException] {
      writer.addRow("a" -> LongValue(1))
    }
  }

end MPC1Test
