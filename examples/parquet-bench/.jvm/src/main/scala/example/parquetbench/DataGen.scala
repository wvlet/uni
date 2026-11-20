package example.parquetbench

import org.apache.parquet.example.data.Group
import org.apache.parquet.example.data.simple.SimpleGroupFactory
import org.apache.parquet.hadoop.ParquetFileWriter
import org.apache.parquet.hadoop.example.ExampleParquetWriter
import org.apache.parquet.hadoop.metadata.CompressionCodecName
import org.apache.parquet.io.LocalOutputFile
import org.apache.parquet.schema.MessageType
import org.apache.parquet.schema.MessageTypeParser

import java.nio.file.Files
import java.nio.file.Path
import scala.util.Random

/**
  * Writes the benchmark files with parquet-java: a flat TPC-H lineitem-like table and a nested
  * orders table (each order with a LIST of line STRUCTs and a MAP of attributes), each in
  * uncompressed, snappy, and zstd variants. The data is deterministic (a fixed seed).
  *
  * Usage: DataGen [out dir] [lineitem rows]
  */
object DataGen:
  val lineItemSchema: MessageType = MessageTypeParser.parseMessageType("""message lineitem {
      |  required int64 l_orderkey;
      |  required int64 l_partkey;
      |  required int64 l_suppkey;
      |  required int32 l_linenumber;
      |  required double l_quantity;
      |  required double l_extendedprice;
      |  required double l_discount;
      |  required double l_tax;
      |  required binary l_returnflag (STRING);
      |  required binary l_linestatus (STRING);
      |  required int32 l_shipdate (DATE);
      |  required int32 l_commitdate (DATE);
      |  required int32 l_receiptdate (DATE);
      |  required binary l_shipinstruct (STRING);
      |  required binary l_shipmode (STRING);
      |  required binary l_comment (STRING);
      |}""".stripMargin)

  val orderSchema: MessageType = MessageTypeParser.parseMessageType("""message orders {
      |  required int64 o_orderkey;
      |  required int64 o_custkey;
      |  required binary o_orderstatus (STRING);
      |  required double o_totalprice;
      |  required int32 o_orderdate (DATE);
      |  required binary o_orderpriority (STRING);
      |  required binary o_comment (STRING);
      |  required group lines (LIST) {
      |    repeated group list {
      |      required group element {
      |        required int64 l_partkey;
      |        required int64 l_suppkey;
      |        required int32 l_linenumber;
      |        required double l_quantity;
      |        required double l_extendedprice;
      |        required double l_discount;
      |        required binary l_shipmode (STRING);
      |        required binary l_comment (STRING);
      |      }
      |    }
      |  }
      |  optional group attrs (MAP) {
      |    repeated group key_value {
      |      required binary key (STRING);
      |      optional binary value (STRING);
      |    }
      |  }
      |}""".stripMargin)

  val codecs: Seq[(String, CompressionCodecName)] = Seq(
    "uncompressed" -> CompressionCodecName.UNCOMPRESSED,
    "snappy"       -> CompressionCodecName.SNAPPY,
    "zstd"         -> CompressionCodecName.ZSTD
  )

  private val shipInstructs = IndexedSeq(
    "DELIVER IN PERSON",
    "COLLECT COD",
    "NONE",
    "TAKE BACK RETURN"
  )

  private val shipModes  = IndexedSeq("REG AIR", "AIR", "RAIL", "SHIP", "TRUCK", "MAIL", "FOB")
  private val priorities = IndexedSeq("1-URGENT", "2-HIGH", "3-MEDIUM", "4-NOT SPECIFIED", "5-LOW")
  private val attrKeys   = IndexedSeq("channel", "region", "campaign", "device", "segment")
  // TPC-H comments are random words from a grammar; a word list gives the same mix of mostly
  // distinct short strings
  private val words = IndexedSeq(
    "furiously",
    "carefully",
    "quickly",
    "slyly",
    "blithely",
    "ironic",
    "final",
    "regular",
    "express",
    "pending",
    "bold",
    "special",
    "unusual",
    "even",
    "silent",
    "packages",
    "deposits",
    "requests",
    "accounts",
    "theodolites",
    "instructions",
    "foxes",
    "pinto",
    "beans",
    "ideas",
    "dependencies",
    "excuses",
    "platelets",
    "asymptotes",
    "courts",
    "dolphins",
    "sleep",
    "wake",
    "are",
    "haggle",
    "nag",
    "use",
    "boost",
    "affix",
    "detect",
    "integrate",
    "cajole",
    "among",
    "across",
    "above",
    "according",
    "to",
    "the",
    "after",
    "along"
  )

  // 1992-01-01 in days since the epoch
  private val startDate = 8035

  private def comment(r: Random, minLen: Int, maxLen: Int): String =
    val target = minLen + r.nextInt(maxLen - minLen + 1)
    val sb     = StringBuilder()
    while sb.length < target do
      if sb.nonEmpty then
        sb.append(' ')
      sb.append(words(r.nextInt(words.size)))
    sb.substring(0, target)

  private def fillLine(g: Group, r: Random, orderKey: Long, line: Int, flat: Boolean): Unit =
    val quantity = (1 + r.nextInt(50)).toDouble
    val price    = quantity * (900 + r.nextInt(100000) / 100.0)
    if flat then
      g.append("l_orderkey", orderKey)
    g.append("l_partkey", 1L + r.nextInt(200000))
    g.append("l_suppkey", 1L + r.nextInt(10000))
    g.append("l_linenumber", line)
    g.append("l_quantity", quantity)
    g.append("l_extendedprice", price)
    g.append("l_discount", r.nextInt(11) / 100.0)
    if flat then
      g.append("l_tax", r.nextInt(9) / 100.0)
      g.append("l_returnflag", Seq("R", "A", "N")(r.nextInt(3)))
      g.append("l_linestatus", Seq("O", "F")(r.nextInt(2)))
      val ship = startDate + r.nextInt(2400)
      g.append("l_shipdate", ship)
      g.append("l_commitdate", ship + r.nextInt(60) - 30)
      g.append("l_receiptdate", ship + 1 + r.nextInt(30))
      g.append("l_shipinstruct", shipInstructs(r.nextInt(shipInstructs.size)))
    g.append("l_shipmode", shipModes(r.nextInt(shipModes.size)))
    g.append("l_comment", comment(r, 10, 43))

  private def writer(path: Path, schema: MessageType, codec: CompressionCodecName) =
    ExampleParquetWriter
      .builder(LocalOutputFile(path))
      .withType(schema)
      .withCompressionCodec(codec)
      .withWriteMode(ParquetFileWriter.Mode.OVERWRITE)
      .build()

  def writeLineItems(path: Path, rows: Int, codec: CompressionCodecName): Unit =
    val r = Random(42)
    val f = SimpleGroupFactory(lineItemSchema)
    val w = writer(path, lineItemSchema, codec)
    try
      var orderKey = 1L
      var line     = 1
      var i        = 0
      while i < rows do
        val g = f.newGroup()
        fillLine(g, r, orderKey, line, flat = true)
        w.write(g)
        // 1 to 7 lines per order, as in TPC-H
        if line >= 1 + r.nextInt(7) then
          orderKey += 1
          line = 1
        else
          line += 1
        i += 1
    finally
      w.close()

  /** Writes orders until they hold about `lines` lines in total */
  def writeOrders(path: Path, lines: Int, codec: CompressionCodecName): Unit =
    val r = Random(7)
    val f = SimpleGroupFactory(orderSchema)
    val w = writer(path, orderSchema, codec)
    try
      var written  = 0
      var orderKey = 1L
      while written < lines do
        val g = f.newGroup()
        g.append("o_orderkey", orderKey)
        g.append("o_custkey", 1L + r.nextInt(150000))
        g.append("o_orderstatus", Seq("O", "F", "P")(r.nextInt(3)))
        g.append("o_totalprice", 1000 + r.nextInt(50000000) / 100.0)
        g.append("o_orderdate", startDate + r.nextInt(2400))
        g.append("o_orderpriority", priorities(r.nextInt(priorities.size)))
        g.append("o_comment", comment(r, 19, 78))
        val list = g.addGroup("lines")
        val n    = 1 + r.nextInt(7)
        (1 to n).foreach { line =>
          fillLine(list.addGroup("list").addGroup("element"), r, orderKey, line, flat = false)
        }
        // A third of the orders have no attributes (a null map)
        if r.nextInt(3) != 0 then
          val m = g.addGroup("attrs")
          attrKeys
            .filter(_ => r.nextBoolean())
            .foreach { k =>
              val kv = m.addGroup("key_value")
              kv.append("key", k)
              if r.nextInt(5) != 0 then
                kv.append("value", s"${k}-${r.nextInt(20)}")
            }
        w.write(g)
        written += n
        orderKey += 1
    finally
      w.close()
    end try

  end writeOrders

  def main(args: Array[String]): Unit =
    val dir  = Path.of(args.headOption.getOrElse("examples/parquet-bench/out/data"))
    val rows = args.lift(1).map(_.toInt).getOrElse(1_000_000)
    Files.createDirectories(dir)
    codecs.foreach { case (name, codec) =>
      writeLineItems(dir.resolve(s"lineitem.${name}.parquet"), rows, codec)
      writeOrders(dir.resolve(s"orders.${name}.parquet"), rows, codec)
      println(s"Wrote ${dir}/lineitem.${name}.parquet and orders.${name}.parquet")
    }

end DataGen
