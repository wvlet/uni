package wvlet.uni.parquet.writer

import wvlet.uni.parquet.ParquetException
import wvlet.uni.parquet.ParquetSchema
import wvlet.uni.parquet.ParquetWriteOptions
import wvlet.uni.parquet.SchemaElements
import wvlet.uni.parquet.codec.Compressors
import wvlet.uni.parquet.format.*
import wvlet.uni.parquet.io.ByteBuf

import scala.collection.mutable
import scala.util.control.NonFatal

/**
  * Writes rows to a Parquet file: `PAR1`, the row groups, then the footer. One row group is
  * buffered in memory (as compressed pages) and written once it reaches the row group size.
  *
  * A row that does not fit the schema is rejected before anything is buffered, and the writer stays
  * usable. An I/O error closes the output without a footer, so the partial file never reads as a
  * valid Parquet file.
  *
  * `openOutput` is called after the schema and options are checked, so an invalid writer creates no
  * file.
  */
private[parquet] final class FileWriter(
    val schema: ParquetSchema,
    options: ParquetWriteOptions,
    openOutput: () => java.io.OutputStream
) extends AutoCloseable:
  import FileWriter.*

  if schema.columns.isEmpty then
    throw IllegalArgumentException("A Parquet schema needs at least one column")

  Compressors.check(options.compression)

  private val writers   = schema.columns.map(ColumnWriter(_, options))
  private val shredder  = Shredder(schema)
  private val output    = CountingOutput(openOutput())
  private val rowGroups = mutable.ArrayBuffer.empty[RowGroupMeta]
  private var groupRows = 0L
  private var totalRows = 0L
  private var closed    = false
  private var failure   = Option.empty[Throwable]

  io(output.write(Magic))

  def numRows: Long = totalRows

  /** Writes a row given as MessagePack bytes; see [[Shredder]] */
  def write(row: Array[Byte]): Unit = write(row, row.length)

  /** Writes a row given as the MessagePack bytes `row[0, len)` */
  def write(row: Array[Byte], len: Int): Unit =
    ensureOpen()
    shredder.shred(row, len)
    io {
      shredder.commit(writers)
      groupRows += 1
      totalRows += 1
      if bufferedSize >= options.rowGroupSize then
        flushRowGroup()
    }

  private def bufferedSize: Long =
    var s = 0L
    writers.foreach(w => s += w.bufferedSize)
    s

  private def ensureOpen(): Unit =
    failure.foreach(e =>
      throw ParquetException(s"The Parquet writer failed earlier: ${e.getMessage}", e)
    )
    if closed then
      throw ParquetException("The Parquet writer is closed")

  private def io[A](body: => A): A =
    try
      body
    catch
      case NonFatal(e) =>
        failure = Some(e)
        closed = true
        try
          output.abort()
        catch
          case NonFatal(_) =>
        throw e

  private def flushRowGroup(): Unit =
    val chunks = writers.map(_.writeChunk(output))
    rowGroups +=
      RowGroupMeta(
        columns = chunks,
        totalByteSize = chunks.map(_.totalUncompressedSize).sum,
        numRows = groupRows,
        ordinal = Some(rowGroups.size)
      )
    groupRows = 0

  /** Discards the file: an object store upload is aborted, so no partial file appears */
  def abort(): Unit =
    if !closed then
      closed = true
      output.abort()

  /** Writes the remaining rows and the footer, then closes the output */
  override def close(): Unit =
    if !closed then
      io {
        if groupRows > 0 then
          flushRowGroup()
        val footer = MetadataEncoder.fileMetaData(
          FileMetaData(
            version = 2,
            schema = SchemaElements.of(schema),
            numRows = totalRows,
            rowGroups = rowGroups.toIndexedSeq,
            keyValueMetadata = options.keyValueMetadata,
            createdBy = Some(CreatedBy)
          )
        )
        output.write(footer)
        val tail = ByteBuf(8)
        tail.writeIntLE(footer.length)
        tail.write(Magic)
        output.write(tail.toByteArray)
        output.flush()
        closed = true
        output.close()
      }

end FileWriter

private[parquet] object FileWriter:
  private val Magic     = "PAR1".getBytes("US-ASCII")
  private val CreatedBy = "uni-parquet"
