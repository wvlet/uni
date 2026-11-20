package wvlet.uni.parquet

import wvlet.uni.msgpack.impl.PureScalaBufferPacker
import wvlet.uni.msgpack.spi.MessagePack
import wvlet.uni.msgpack.spi.Packer
import wvlet.uni.msgpack.spi.Value
import wvlet.uni.parquet.writer.FileWriter
import wvlet.uni.surface.Surface
import wvlet.uni.weaver.Weaver
import wvlet.uni.weaver.WeaverConfig

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

  // One packer for every row, so its buffer is not regrown per row. The shredder reads each row
  // in the packer's buffer, without copying it out.
  private val packer = PureScalaBufferPacker()
  private val config = WeaverConfig()

  def write(row: A): Unit =
    packer.clear
    weaver.pack(packer, row, config)
    core.write(packer.writtenArray, packer.writtenSize)

  def writeAll(rows: IterableOnce[A]): Unit = rows.iterator.foreach(write)

  /**
    * Stops writing without finishing the file. Writing to an object store upload stream, this
    * discards the upload, so no partial object appears.
    */
  def abort(): Unit = core.abort()

  override def close(): Unit = core.close()

object ParquetWriter:
  private[parquet] def create[A](
      surface: Surface,
      openOutput: () => java.io.OutputStream,
      options: ParquetWriteOptions,
      weaver: Weaver[A]
  ): ParquetWriter[A] =
    val schema = ParquetSchema.fromSurface(surface, options)
    ParquetWriter(FileWriter(schema, options, openOutput), weaver)

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

  // Reused for every non-JSON record; the shredder reads the record in the packer's buffer, as in
  // ParquetWriter.write
  private val packer = PureScalaBufferPacker()

  def write(record: Any): Unit =
    record match
      case json: String =>
        core.write(RecordBytes.fromJson(json))
      case other =>
        packer.clear
        RecordBytes.pack(packer, other)
        core.write(packer.writtenArray, packer.writtenSize)

  def writeAll(records: IterableOnce[Any]): Unit = records.iterator.foreach(write)

  /** Stops writing without finishing the file; see [[ParquetWriter.abort]] */
  def abort(): Unit = core.abort()

  override def close(): Unit = core.close()

/** Converts records into MessagePack bytes for the shredder */
private[parquet] object RecordBytes:

  def fromJson(json: String): Array[Byte] =
    try
      MessagePack.fromJSON(json)
    catch
      case e: ParquetException =>
        throw e
      case e: Exception =>
        throw ParquetException(s"A record string is not valid JSON: ${e.getMessage}", e)

  def pack(p: Packer, v: Any): Unit =
    v match
      case null | None =>
        p.packNil
      case Some(x) =>
        pack(p, x)
      case v: Value =>
        p.packValue(v)
      case b: Boolean =>
        p.packBoolean(b)
      case i: Int =>
        p.packLong(i)
      case l: Long =>
        p.packLong(l)
      case s: Short =>
        p.packLong(s)
      case b: Byte =>
        p.packLong(b)
      case f: Float =>
        p.packDouble(f)
      case d: Double =>
        p.packDouble(d)
      case s: String =>
        p.packString(s)
      case c: Char =>
        p.packString(c.toString)
      case b: BigInt =>
        p.packBigInteger(b.bigInteger)
      case b: java.math.BigInteger =>
        p.packBigInteger(b)
      case d: BigDecimal =>
        p.packString(d.toString)
      case d: java.math.BigDecimal =>
        p.packString(d.toString)
      case t: Instant =>
        p.packTimestamp(t)
      case d: LocalDate =>
        p.packString(d.toString)
      case u: java.util.UUID =>
        p.packString(u.toString)
      case b: Array[Byte] =>
        p.packBinaryHeader(b.length)
        p.writePayload(b)
      case m: Map[?, ?] =>
        p.packMapHeader(m.size)
        m.foreach { (k, x) =>
          pack(p, k)
          pack(p, x)
        }
      case a: Array[?] =>
        p.packArrayHeader(a.length)
        a.foreach(pack(p, _))
      case s: Iterable[?] =>
        p.packArrayHeader(s.size)
        s.foreach(pack(p, _))
      case e: scala.reflect.Enum =>
        p.packString(e.toString)
      case x: Product =>
        p.packMapHeader(x.productArity)
        var i = 0
        while i < x.productArity do
          p.packString(x.productElementName(i))
          pack(p, x.productElement(i))
          i += 1
      case other =>
        throw ParquetException(s"Cannot write a value of ${other.getClass.getName} to Parquet")

end RecordBytes
