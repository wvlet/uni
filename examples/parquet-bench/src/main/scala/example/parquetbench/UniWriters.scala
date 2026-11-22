package example.parquetbench

import wvlet.uni.io.FileSystem
import wvlet.uni.parquet.ColumnBatch
import wvlet.uni.parquet.ColumnVector
import wvlet.uni.parquet.ColumnVector.*
import wvlet.uni.parquet.Parquet
import wvlet.uni.parquet.ParquetSchema
import wvlet.uni.parquet.ParquetWriteOptions
import wvlet.uni.parquet.format.CompressionCodec

/** The rows of the benchmark files, read once and kept in memory for the write cases */
object BenchData:
  private var cache = Map.empty[String, Seq[Any]]

  private def load[A](key: String, read: => Seq[A]): Seq[A] =
    cache.get(key) match
      case Some(rows) =>
        rows.asInstanceOf[Seq[A]]
      case None =>
        // Keep one data set in memory at a time
        cache = Map.empty
        val rows = read
        cache = Map(key -> rows)
        rows

  def clear(): Unit = cache = Map.empty

  def lineItems(dir: String): Seq[LineItem] = load(
    "lineitem",
    Parquet.readAs[LineItem](s"${dir}/lineitem.uncompressed.parquet").toIndexedSeq
  )

  def orders(dir: String): Seq[Order] = load(
    "orders",
    Parquet.readAs[Order](s"${dir}/orders.uncompressed.parquet").toIndexedSeq
  )

/** A table as column batches, for the batch writer */
case class BenchBatches(schema: ParquetSchema, batches: Seq[ColumnBatch]):
  /**
    * The same batches rebuilt as an application builds them, without the dictionary entries the
    * reader attaches to binary vectors, so the writer looks up every value
    */
  lazy val rebuilt: BenchBatches = BenchBatches(
    schema,
    batches.map(b => ColumnBatch(b.numRows, b.columns.map(BenchBatches.rebuild)))
  )

object BenchBatches:
  private var cache = Option.empty[(String, BenchBatches)]

  /**
    * The case classes written by uni and read back as batches, so the batch writer writes the same
    * schema (camelCase names, dates as strings) and values as the case-class writer. One table is
    * kept at a time, and its rows are dropped once the batches are built.
    */
  private def load(key: String, bytes: => Array[Byte]): BenchBatches =
    cache match
      case Some((k, b)) if k == key =>
        b
      case _ =>
        cache = None
        val f = Parquet.open(bytes)
        val b =
          try BenchBatches(f.schema, f.readBatches().toIndexedSeq)
          finally f.close()
        BenchData.clear()
        cache = Some(key -> b)
        b

  private def nullsOf(v: ColumnVector): Array[Boolean] =
    val n = Array.tabulate(v.size)(v.isNull)
    if n.contains(true) then
      n
    else
      null

  def rebuild(v: ColumnVector): ColumnVector =
    v match
      case b: BinaryColumn =>
        BinaryColumn(b.field, b.offsets, b.data, nullsOf(b), b.binaryAsString)
      case l: ListColumn =>
        ListColumn(l.field, l.offsets, rebuild(l.elements), nullsOf(l))
      case m: MapColumn =>
        MapColumn(m.field, m.offsets, rebuild(m.keys), rebuild(m.values), nullsOf(m))
      case s: StructColumn =>
        StructColumn(s.field, s.size, s.children.map(rebuild), nullsOf(s))
      case other =>
        other

  def lineItems(dir: String): BenchBatches = load(
    "lineitem",
    Parquet.writeBytes[LineItem](BenchData.lineItems(dir))
  )

  def orders(dir: String): BenchBatches = load(
    "orders",
    Parquet.writeBytes[Order](BenchData.orders(dir))
  )

end BenchBatches

/** Uni writers. Each returns (rows, file size). */
object UniWriters:
  def options(codec: String): ParquetWriteOptions =
    codec match
      case "uncompressed" =>
        ParquetWriteOptions.default.noCompression()
      case "snappy" =>
        ParquetWriteOptions.default.withCompression(CompressionCodec.SNAPPY)
      case "zstd" =>
        ParquetWriteOptions.default.withCompression(CompressionCodec.ZSTD)
      case other =>
        throw IllegalArgumentException(s"Unsupported codec ${other}")

  private def prepare(path: String): Unit =
    FileSystem.createDirectoryIfNotExists(path.substring(0, path.lastIndexOf('/')))
    FileSystem.deleteIfExists(path)

  def lineItems(rows: Seq[LineItem], path: String, codec: String): (Long, Long) =
    prepare(path)
    val w = Parquet.newWriter[LineItem](path, options(codec))
    try w.writeAll(rows)
    finally w.close()
    (w.numRows, FileSystem.info(path).size)

  def orders(rows: Seq[Order], path: String, codec: String): (Long, Long) =
    prepare(path)
    val w = Parquet.newWriter[Order](path, options(codec))
    try w.writeAll(rows)
    finally w.close()
    (w.numRows, FileSystem.info(path).size)

  def batches(data: BenchBatches, path: String, codec: String): (Long, Long) =
    prepare(path)
    val w = Parquet.newBatchWriter(path, data.schema, options(codec))
    try w.writeAll(data.batches)
    finally w.close()
    (w.numRows, FileSystem.info(path).size)

end UniWriters
