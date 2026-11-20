package wvlet.uni.parquet

import wvlet.uni.parquet.format.CompressionCodec

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
  *   past `dictionaryPageSize`
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
    keyValueMetadata: Map[String, String] = Map.empty
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

end ParquetWriteOptions

object ParquetWriteOptions:
  val default: ParquetWriteOptions = ParquetWriteOptions()
