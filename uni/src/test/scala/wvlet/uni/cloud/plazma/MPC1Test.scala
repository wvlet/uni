package wvlet.uni.cloud.plazma

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
