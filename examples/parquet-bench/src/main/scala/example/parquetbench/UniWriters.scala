package example.parquetbench

import wvlet.uni.io.FileSystem
import wvlet.uni.parquet.ColumnBatch
import wvlet.uni.parquet.Parquet
import wvlet.uni.parquet.ParquetSchema
import wvlet.uni.parquet.ParquetWriteOptions
import wvlet.uni.parquet.format.CompressionCodec

/** The rows of the benchmark files, read once and kept in memory for the write cases */
object BenchData:
  private var cache = Map.empty[String, Seq[Any]]

  private[parquetbench] def load[A](key: String, read: => Seq[A]): Seq[A] =
    cache.get(key) match
      case Some(rows) =>
        rows.asInstanceOf[Seq[A]]
      case None =>
        // Keep one data set in memory at a time
        cache = Map.empty
        val rows = read
        cache = Map(key -> rows)
        rows

  def lineItems(dir: String): Seq[LineItem] = load(
    "lineitem",
    Parquet.readAs[LineItem](s"${dir}/lineitem.uncompressed.parquet").toIndexedSeq
  )

  def orders(dir: String): Seq[Order] = load(
    "orders",
    Parquet.readAs[Order](s"${dir}/orders.uncompressed.parquet").toIndexedSeq
  )

/** A table as column batches, for the batch writer */
case class BenchBatches(schema: ParquetSchema, batches: Seq[ColumnBatch])

object BenchBatches:
  /**
    * The case classes written by uni and read back as batches, so the batch writer writes the
    * same schema (camelCase names, dates as strings) and values as the case-class writer
    */
  def of[A](rows: Seq[A])(using weaver: wvlet.uni.weaver.Weaver[A]): Seq[BenchBatches] =
    val f = Parquet.open(Parquet.writeBytes(rows))
    try Seq(BenchBatches(f.schema, f.readBatches().toIndexedSeq))
    finally f.close()

object BenchTables:
  def lineItemBatches(dir: String): BenchBatches = BenchData
    .load("lineitem-batches", BenchBatches.of(BenchData.lineItems(dir)))
    .head

  def orderBatches(dir: String): BenchBatches = BenchData
    .load("orders-batches", BenchBatches.of(BenchData.orders(dir)))
    .head

/** uni-parquet writers. Each returns (rows, file size). */
object UniWriters:
  def options(codec: String): ParquetWriteOptions =
    codec match
      case "uncompressed" =>
        ParquetWriteOptions.default.noCompression()
      case "snappy" =>
        ParquetWriteOptions.default.withCompression(CompressionCodec.SNAPPY)
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
