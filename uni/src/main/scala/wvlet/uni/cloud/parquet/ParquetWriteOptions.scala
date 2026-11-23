package wvlet.uni.cloud.parquet

import wvlet.uni.cloud.parquet.format.CompressionCodec

/**
  * Options for writing a Parquet file.
  *
  * @param compression
  *   the page codec: SNAPPY (default), UNCOMPRESSED, LZ4_RAW, ZSTD, or GZIP (not in browsers)
  * @param compressionLevel
  *   the codec's compression level, or None for its default. Only ZSTD takes one: 1 (fastest) to 22
  *   (smallest), 3 by default
  * @param rowGroupSize
  *   a row group is written once its encoded data reaches this many bytes. The writer holds one row
  *   group in memory.
  * @param pageSize
  *   a data page is closed once its encoded data reaches this many bytes
  * @param dictionaryEnabled
  *   dictionary-encode columns (except booleans), falling back to PLAIN when the dictionary grows
  *   past `dictionaryPageSize`, or when it is no smaller than PLAIN after a row group's first
  *   20,000 values of the column
  * @param dictionaryPageSize
  *   the largest dictionary, in bytes of PLAIN-encoded values, before a column falls back to PLAIN
  *   for the rest of its row group
  * @param decimalPrecision
  *   the precision of DECIMAL columns derived from `BigDecimal` fields
  * @param decimalScale
  *   the scale of DECIMAL columns derived from `BigDecimal` fields
  * @param decimalColumns
  *   precision and scale for individual `BigDecimal` fields, keyed by the field path (`price`, or
  *   `order.price` for a field of a nested case class)
  * @param keyValueMetadata
  *   key-value pairs stored in the footer
  * @param parallelism
  *   threads that encode and compress columns at once: the columns of each column batch the batch
  *   writers take, and every row group's last pages when it closes. On the JVM the default is the
  *   available processors, up to 8; Scala.js and Scala Native write on one thread. 1 writes on the
  *   calling thread only. The output is the same for every setting.
  */
case class ParquetWriteOptions(
    compression: CompressionCodec = CompressionCodec.SNAPPY,
    compressionLevel: Option[Int] = None,
    rowGroupSize: Long = 128L * 1024 * 1024,
    pageSize: Int = 1024 * 1024,
    dictionaryEnabled: Boolean = true,
    dictionaryPageSize: Int = 1024 * 1024,
    decimalPrecision: Int = 38,
    decimalScale: Int = 18,
    decimalColumns: Map[String, (Int, Int)] = Map.empty,
    keyValueMetadata: Map[String, String] = Map.empty,
    parallelism: Int = WriteParallelism.default
):
  def withCompression(codec: CompressionCodec): ParquetWriteOptions = copy(compression = codec)
  def noCompression(): ParquetWriteOptions = copy(compression = CompressionCodec.UNCOMPRESSED)

  /** Sets the codec and its compression level, as in `withCompression(CompressionCodec.ZSTD, 9)` */
  def withCompression(codec: CompressionCodec, level: Int): ParquetWriteOptions = copy(
    compression = codec,
    compressionLevel = Some(level)
  )

  /**
    * Sets the compression level of the codec; only ZSTD takes one (1 to 22). Writing fails when the
    * codec does not.
    */
  def withCompressionLevel(level: Int): ParquetWriteOptions = copy(compressionLevel = Some(level))

  def noCompressionLevel(): ParquetWriteOptions = copy(compressionLevel = None)

  def withRowGroupSize(bytes: Long): ParquetWriteOptions =
    require(bytes > 0, s"rowGroupSize must be positive: ${bytes}")
    copy(rowGroupSize = bytes)

  def withPageSize(bytes: Int): ParquetWriteOptions =
    require(bytes > 0, s"pageSize must be positive: ${bytes}")
    copy(pageSize = bytes)

  def withDictionaryEnabled(enabled: Boolean): ParquetWriteOptions = copy(dictionaryEnabled =
    enabled
  )

  def noDictionary(): ParquetWriteOptions = copy(dictionaryEnabled = false)

  def withDictionaryPageSize(bytes: Int): ParquetWriteOptions =
    require(bytes > 0, s"dictionaryPageSize must be positive: ${bytes}")
    copy(dictionaryPageSize = bytes)

  /** Sets the precision and scale of every DECIMAL column derived from a `BigDecimal` field */
  def withDecimal(precision: Int, scale: Int): ParquetWriteOptions =
    ParquetType.decimal(precision, scale) // validates the arguments
    copy(decimalPrecision = precision, decimalScale = scale)

  /** Sets the precision and scale of one `BigDecimal` field, by its path (`order.price`) */
  def withDecimal(field: String, precision: Int, scale: Int): ParquetWriteOptions =
    ParquetType.decimal(precision, scale)
    copy(decimalColumns = decimalColumns + (field -> (precision, scale)))

  def withKeyValueMetadata(metadata: Map[String, String]): ParquetWriteOptions = copy(
    keyValueMetadata = metadata
  )

  def noKeyValueMetadata(): ParquetWriteOptions = copy(keyValueMetadata = Map.empty)

  /** Sets how many threads encode and compress columns at once; 1 for the calling thread only */
  def withParallelism(threads: Int): ParquetWriteOptions =
    require(threads >= 1, s"parallelism must be at least 1: ${threads}")
    copy(parallelism = threads)

end ParquetWriteOptions

object ParquetWriteOptions:
  val default: ParquetWriteOptions = ParquetWriteOptions()
