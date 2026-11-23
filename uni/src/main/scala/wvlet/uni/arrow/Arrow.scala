package wvlet.uni.arrow

import wvlet.uni.parquet.ColumnBatch
import wvlet.uni.parquet.ParquetSchema

import java.io.ByteArrayOutputStream
import java.io.OutputStream

/** Body compression of Arrow record batches */
enum ArrowCompression:
  case NONE,
    LZ4_FRAME,
    ZSTD

/**
  * Options of [[Arrow]] writers.
  *
  * @param compression
  *   record batch body compression; NONE by default, since many readers decode uncompressed batches
  *   without copying
  * @param compressionLevel
  *   the ZSTD level (1 to 22); the codec default when not set
  * @param binaryAsString
  *   write BYTE_ARRAY columns without a logical type as utf8
  * @param metadata
  *   custom metadata of the schema
  */
case class ArrowWriteOptions(
    compression: ArrowCompression = ArrowCompression.NONE,
    compressionLevel: Option[Int] = None,
    binaryAsString: Boolean = false,
    metadata: Map[String, String] = Map.empty
):
  def withCompression(c: ArrowCompression): ArrowWriteOptions = copy(compression = c)

  /** Sets the codec and its level, as in `withCompression(ArrowCompression.ZSTD, 9)` */
  def withCompression(c: ArrowCompression, level: Int): ArrowWriteOptions = copy(
    compression = c,
    compressionLevel = Some(level)
  )

  def noCompression(): ArrowWriteOptions = copy(compression = ArrowCompression.NONE)

  /** Sets the ZSTD level (1 to 22); only ZSTD takes one */
  def withCompressionLevel(level: Int): ArrowWriteOptions     = copy(compressionLevel = Some(level))
  def noCompressionLevel(): ArrowWriteOptions                 = copy(compressionLevel = None)
  def withBinaryAsString(b: Boolean): ArrowWriteOptions       = copy(binaryAsString = b)
  def withMetadata(m: Map[String, String]): ArrowWriteOptions = copy(metadata = m)
  def noMetadata(): ArrowWriteOptions                         = copy(metadata = Map.empty)

  private[arrow] def validate(): Unit = compressionLevel.foreach { level =>
    if compression != ArrowCompression.ZSTD then
      throw IllegalArgumentException(s"${compression} takes no compression level")
    if level < 1 || level > 22 then
      throw IllegalArgumentException(s"ZSTD level must be 1 to 22: ${level}")
  }

end ArrowWriteOptions

object ArrowWriteOptions:
  val default: ArrowWriteOptions = ArrowWriteOptions()

/**
  * Writes [[ColumnBatch]]es as Apache Arrow IPC, for delivering results to Arrow readers such as
  * pyarrow, pandas, polars, Arrow JS, and ADBC clients. Pure Scala on every platform.
  *
  * {{{
  * val file   = Parquet.open(path)
  * val writer = Arrow.newStreamWriter(out, file.schema)
  * file.readBatches().foreach(writer.write)
  * writer.close()
  * }}}
  *
  * The stream format suits HTTP responses and sockets (media type [[StreamMediaType]]); the file
  * format adds a footer so readers can seek to any batch. See [[ArrowSchema.fromParquet]] for the
  * type mapping.
  */
object Arrow:
  /** The media type of the Arrow IPC stream format */
  val StreamMediaType: String = "application/vnd.apache.arrow.stream"

  /** The media type of the Arrow IPC file format */
  val FileMediaType: String = "application/vnd.apache.arrow.file"

  /** A writer of the stream format. Throws [[ArrowException]] for a schema it cannot map. */
  def newStreamWriter(
      out: OutputStream,
      schema: ParquetSchema,
      options: ArrowWriteOptions = ArrowWriteOptions.default
  ): ArrowWriter = create(out, schema, options, fileFormat = false)

  /** A writer of the file format (`.arrow` files) */
  def newFileWriter(
      out: OutputStream,
      schema: ParquetSchema,
      options: ArrowWriteOptions = ArrowWriteOptions.default
  ): ArrowWriter = create(out, schema, options, fileFormat = true)

  /** The batches as one stream in memory */
  def toStreamBytes(
      schema: ParquetSchema,
      batches: IterableOnce[ColumnBatch],
      options: ArrowWriteOptions = ArrowWriteOptions.default
  ): Array[Byte] = toBytes(schema, batches, options, fileFormat = false)

  /** The batches as one file in memory */
  def toFileBytes(
      schema: ParquetSchema,
      batches: IterableOnce[ColumnBatch],
      options: ArrowWriteOptions = ArrowWriteOptions.default
  ): Array[Byte] = toBytes(schema, batches, options, fileFormat = true)

  private def toBytes(
      schema: ParquetSchema,
      batches: IterableOnce[ColumnBatch],
      options: ArrowWriteOptions,
      fileFormat: Boolean
  ): Array[Byte] =
    val out = ByteArrayOutputStream()
    val w   = create(out, schema, options, fileFormat)
    w.writeAll(batches)
    w.close()
    out.toByteArray

  private def create(
      out: OutputStream,
      schema: ParquetSchema,
      options: ArrowWriteOptions,
      fileFormat: Boolean
  ): ArrowWriter =
    options.validate()
    ArrowWriter(out, schema, options, fileFormat)

end Arrow
