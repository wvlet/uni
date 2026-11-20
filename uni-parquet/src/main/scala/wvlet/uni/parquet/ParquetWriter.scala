package wvlet.uni.parquet

import wvlet.uni.msgpack.spi.MessagePack
import wvlet.uni.msgpack.spi.Value
import wvlet.uni.msgpack.spi.Value.*
import wvlet.uni.parquet.writer.FileWriter
import wvlet.uni.surface.Surface
import wvlet.uni.weaver.Weaver

import java.time.Instant
import java.time.LocalDate

/**
  * Writes case classes of type `A` to a Parquet file. Create one with [[Parquet.newWriter]], call
  * `write` for each row, and `close()` to write the footer; the file is not readable until then.
  *
  * A row that does not fit the schema throws [[ParquetException]] and is not written; the writer
  * stays usable. After an I/O error the writer is closed and the file has no footer.
  */
class ParquetWriter[A] private[parquet] (core: FileWriter, weaver: Weaver[A]) extends AutoCloseable:
  def schema: ParquetSchema = core.schema

  /** The number of rows written so far */
  def numRows: Long = core.numRows

  def write(row: A): Unit = core.write(RecordValues.fromMsgPack(weaver.toMsgPack(row)))

  def writeAll(rows: IterableOnce[A]): Unit = rows.iterator.foreach(write)

  override def close(): Unit = core.close()

object ParquetWriter:
  private[parquet] def create[A](
      surface: Surface,
      out: java.io.OutputStream,
      options: ParquetWriteOptions,
      weaver: Weaver[A]
  ): ParquetWriter[A] =
    val schema = ParquetSchema.fromSurface(surface, options)
    ParquetWriter(FileWriter(schema, options, out), weaver)

/**
  * Writes records with an explicit schema. Create one with [[Parquet.newRecordWriter]]. `write`
  * accepts:
  *
  *   - a `Map[String, Any]` of column names to values: `Map("id" -> 1, "name" -> "aina")`
  *   - a JSON object string: `"""{"id": 1, "name": "aina"}"""`
  *   - a `Seq` of values in schema order: `Seq(1, "aina")`
  *   - a JSON array string: `"""[1, "aina"]"""`
  *
  * Values may be numbers, strings, booleans, `Instant`, `LocalDate`, `BigDecimal`, `UUID`,
  * `Array[Byte]`, `Option`, collections, maps, and case classes (for struct columns). Missing
  * columns are null; keys without a column are ignored.
  */
class ParquetRecordWriter private[parquet] (core: FileWriter) extends AutoCloseable:
  def schema: ParquetSchema = core.schema
  def numRows: Long         = core.numRows

  def write(record: Any): Unit = core.write(RecordValues.of(record))

  def writeAll(records: IterableOnce[Any]): Unit = records.iterator.foreach(write)

  override def close(): Unit = core.close()

/** Converts records into MessagePack values for the shredder */
private[parquet] object RecordValues:

  def fromMsgPack(msgpack: Array[Byte]): Value = MessagePack.newUnpacker(msgpack).unpackValue

  def of(record: Any): Value =
    record match
      case json: String =>
        try
          fromMsgPack(MessagePack.fromJSON(json))
        catch
          case e: ParquetException =>
            throw e
          case e: Exception =>
            throw ParquetException(s"A record string is not valid JSON: ${e.getMessage}", e)
      case other =>
        toValue(other)

  def toValue(v: Any): Value =
    v match
      case null | None =>
        NilValue
      case Some(x) =>
        toValue(x)
      case v: Value =>
        v
      case b: Boolean =>
        BooleanValue(b)
      case i: Int =>
        LongValue(i)
      case l: Long =>
        LongValue(l)
      case s: Short =>
        LongValue(s)
      case b: Byte =>
        LongValue(b)
      case f: Float =>
        DoubleValue(f)
      case d: Double =>
        DoubleValue(d)
      case s: String =>
        StringValue(s)
      case c: Char =>
        StringValue(c.toString)
      case b: BigInt =>
        BigIntegerValue(b.bigInteger)
      case b: java.math.BigInteger =>
        BigIntegerValue(b)
      case d: BigDecimal =>
        StringValue(d.toString)
      case d: java.math.BigDecimal =>
        StringValue(d.toString)
      case t: Instant =>
        TimestampValue(t)
      case d: LocalDate =>
        StringValue(d.toString)
      case u: java.util.UUID =>
        StringValue(u.toString)
      case b: Array[Byte] =>
        BinaryValue(b)
      case m: Map[?, ?] =>
        MapValue(m.map((k, x) => toValue(k) -> toValue(x)))
      case a: Array[?] =>
        ArrayValue(a.toIndexedSeq.map(toValue))
      case s: Iterable[?] =>
        ArrayValue(s.toIndexedSeq.map(toValue))
      case e: scala.reflect.Enum =>
        StringValue(e.toString)
      case p: Product =>
        MapValue(
          p.productElementNames
            .zip(p.productIterator)
            .map((k, x) => StringValue(k) -> toValue(x))
            .toMap
        )
      case other =>
        throw ParquetException(s"Cannot write a value of ${other.getClass.getName} to Parquet")

end RecordValues
