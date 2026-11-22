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

/**
  * parquet-java writers that do the same work as [[UniWriters]]: the example Group writer, fed
  * Groups built from the same case classes, with the schema Uni derives for them (camelCase
  * names, dates as strings). Each returns (rows, file size).
  */
object ParquetJavaWriters:
  val lineItemSchema: MessageType = MessageTypeParser.parseMessageType("""message LineItem {
      |  required int64 lOrderkey;
      |  required int64 lPartkey;
      |  required int64 lSuppkey;
      |  required int32 lLinenumber;
      |  required double lQuantity;
      |  required double lExtendedprice;
      |  required double lDiscount;
      |  required double lTax;
      |  required binary lReturnflag (STRING);
      |  required binary lLinestatus (STRING);
      |  required binary lShipdate (STRING);
      |  required binary lCommitdate (STRING);
      |  required binary lReceiptdate (STRING);
      |  required binary lShipinstruct (STRING);
      |  required binary lShipmode (STRING);
      |  required binary lComment (STRING);
      |}""".stripMargin)

  val orderSchema: MessageType = MessageTypeParser.parseMessageType("""message Order {
      |  required int64 oOrderkey;
      |  required int64 oCustkey;
      |  required binary oOrderstatus (STRING);
      |  required double oTotalprice;
      |  required binary oOrderdate (STRING);
      |  required binary oOrderpriority (STRING);
      |  required binary oComment (STRING);
      |  required group lines (LIST) {
      |    repeated group list {
      |      required group element {
      |        required int64 lPartkey;
      |        required int64 lSuppkey;
      |        required int32 lLinenumber;
      |        required double lQuantity;
      |        required double lExtendedprice;
      |        required double lDiscount;
      |        required binary lShipmode (STRING);
      |        required binary lComment (STRING);
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

  private def codecOf(codec: String): CompressionCodecName =
    codec match
      case "uncompressed" =>
        CompressionCodecName.UNCOMPRESSED
      case "snappy" =>
        CompressionCodecName.SNAPPY
      case other =>
        throw IllegalArgumentException(s"Unsupported codec ${other}")

  private def writer(path: String, schema: MessageType, codec: String) =
    val p = Path.of(path)
    Files.createDirectories(p.getParent)
    ExampleParquetWriter
      .builder(LocalOutputFile(p))
      .withType(schema)
      .withCompressionCodec(codecOf(codec))
      .withWriteMode(ParquetFileWriter.Mode.OVERWRITE)
      .build()

  def lineItems(rows: Seq[LineItem], path: String, codec: String): (Long, Long) =
    val f = SimpleGroupFactory(lineItemSchema)
    val w = writer(path, lineItemSchema, codec)
    var n = 0L
    try rows.foreach { r =>
        val g = f.newGroup()
        g.add(0, r.lOrderkey)
        g.add(1, r.lPartkey)
        g.add(2, r.lSuppkey)
        g.add(3, r.lLinenumber)
        g.add(4, r.lQuantity)
        g.add(5, r.lExtendedprice)
        g.add(6, r.lDiscount)
        g.add(7, r.lTax)
        g.add(8, r.lReturnflag)
        g.add(9, r.lLinestatus)
        g.add(10, r.lShipdate)
        g.add(11, r.lCommitdate)
        g.add(12, r.lReceiptdate)
        g.add(13, r.lShipinstruct)
        g.add(14, r.lShipmode)
        g.add(15, r.lComment)
        w.write(g)
        n += 1
      }
    finally w.close()
    (n, Files.size(Path.of(path)))

  def orders(rows: Seq[Order], path: String, codec: String): (Long, Long) =
    val f = SimpleGroupFactory(orderSchema)
    val w = writer(path, orderSchema, codec)
    var n = 0L
    try rows.foreach { o =>
        val g = f.newGroup()
        g.add(0, o.oOrderkey)
        g.add(1, o.oCustkey)
        g.add(2, o.oOrderstatus)
        g.add(3, o.oTotalprice)
        g.add(4, o.oOrderdate)
        g.add(5, o.oOrderpriority)
        g.add(6, o.oComment)
        val list = g.addGroup(7)
        o.lines
          .foreach { l =>
            val e = list.addGroup(0).addGroup(0)
            e.add(0, l.lPartkey)
            e.add(1, l.lSuppkey)
            e.add(2, l.lLinenumber)
            e.add(3, l.lQuantity)
            e.add(4, l.lExtendedprice)
            e.add(5, l.lDiscount)
            e.add(6, l.lShipmode)
            e.add(7, l.lComment)
          }
        o.attrs
          .foreach { m =>
            val mg = g.addGroup(8)
            m.foreach { (k, v) =>
              val kv = mg.addGroup(0)
              kv.add(0, k)
              v.foreach(x => kv.add(1, x))
            }
          }
        w.write(g)
        n += 1
      }
    finally w.close()
    end try
    (n, Files.size(Path.of(path)))

  end orders

end ParquetJavaWriters
