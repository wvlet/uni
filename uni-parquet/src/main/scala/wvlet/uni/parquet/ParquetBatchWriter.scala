package wvlet.uni.parquet

import wvlet.uni.parquet.writer.BatchShredder
import wvlet.uni.parquet.writer.FileWriter

/**
  * Writes [[ColumnBatch]]es to a Parquet file, column by column, with no row objects in between.
  * Create one with [[Parquet.newBatchWriter]], call `write` for each batch, and `close()` to write
  * the footer; the file is not readable until then.
  *
  * A batch takes the vector types the reader returns, so a batch read from one file can be written
  * to another unchanged. Its columns are matched to the schema's top-level fields by name, in any
  * order. Values are physical, as when reading:
  *
  * | Parquet column                                        | Vector                                     |
  * |:------------------------------------------------------|:-------------------------------------------|
  * | BOOLEAN                                               | `BooleanColumn`                            |
  * | INT32, INT(8/16/32), DATE (days), TIME(MILLIS)        | `IntColumn`                                |
  * | INT64, INT(64), TIMESTAMP (in its unit), TIME(MICROS) | `LongColumn`                               |
  * | FLOAT, DOUBLE                                         | `FloatColumn`, `DoubleColumn`              |
  * | DECIMAL                                               | the vector of its physical type (unscaled) |
  * | BYTE_ARRAY: STRING, ENUM, JSON (UTF-8), plain bytes   | `BinaryColumn`                             |
  * | FIXED_LEN_BYTE_ARRAY, UUID                            | `BinaryColumn`, each value its full length |
  * | LIST, or a repeated field without LIST                | `ListColumn`                               |
  * | MAP                                                   | `MapColumn`                                |
  * | another group                                         | `StructColumn`, children by name           |
  *
  * Build vectors with their constructors and the schema's fields:
  *
  * {{{
  * val schema = ParquetSchema.builder().required("id", ParquetType.INT64).build()
  * val ids    = ColumnVector.LongColumn(schema.field("id").get, Array(1L, 2L), null)
  * w.write(ColumnBatch(2, IndexedSeq(ids)))
  * }}}
  *
  * A batch that does not fit (a missing or extra column, a vector of the wrong type or size,
  * malformed offsets, or a null in a required field) throws [[ParquetException]] before any of it
  * is written, and the writer stays usable. Row groups close at the row group size, inside a batch
  * if needed, so batch sizes do not show in the file. After an I/O error the writer is closed and
  * the file has no footer.
  */
class ParquetBatchWriter private[parquet] (core: FileWriter, shredder: BatchShredder)
    extends AutoCloseable:
  def schema: ParquetSchema = core.schema

  /** The number of rows written so far */
  def numRows: Long = core.numRows

  def write(batch: ColumnBatch): Unit =
    val vectors = shredder.check(batch)
    try
      core.writeColumns(batch.numRows)((writers, from, to) =>
        shredder.write(vectors, writers, from, to)
      )
    finally shredder.release()

  def writeAll(batches: IterableOnce[ColumnBatch]): Unit = batches.iterator.foreach(write)

  /** Stops writing without finishing the file; see [[ParquetWriter.abort]] */
  def abort(): Unit = core.abort()

  override def close(): Unit = core.close()

object ParquetBatchWriter:
  private[parquet] def create(
      schema: ParquetSchema,
      openOutput: () => java.io.OutputStream,
      options: ParquetWriteOptions
  ): ParquetBatchWriter =
    // Check the schema before the file is created
    val shredder = BatchShredder(schema)
    ParquetBatchWriter(FileWriter(schema, options, openOutput), shredder)
