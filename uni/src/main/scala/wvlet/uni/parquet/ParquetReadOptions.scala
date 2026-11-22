package wvlet.uni.parquet

/**
  * Options for reading a Parquet file.
  *
  * @param columns
  *   top-level fields to read, or None for all of them
  * @param binaryAsString
  *   decode BYTE_ARRAY columns without a logical type as UTF-8 strings, in case classes, row maps,
  *   JSON, and [[ColumnVector.getValue]]. Older writers (for example Hive and Impala) store strings
  *   without the STRING annotation.
  * @param rowGroupFilter
  *   skip row groups whose statistics show that no row matches. Rows of the row groups that are
  *   read are not filtered. Columns and literals the check doesn't understand are ignored.
  * @param maxMergeGap
  *   column chunks closer than this many bytes are fetched in one read, which saves round trips
  *   over HTTP
  * @param batchSize
  *   the most rows in one batch of [[ParquetFile.readBatches]], or None for whole row groups. A row
  *   group is also split when a column's values in it pass 1 GiB, so a batch stays below the 2 GiB
  *   limit of an array. With a [[filter]], batches can be smaller, since rows that don't match are
  *   removed.
  * @param filter
  *   return only the rows that match. Files and row groups that can't match are skipped as with
  *   [[rowGroupFilter]]. A filter that can't be evaluated exactly (an unknown or list column, an
  *   unsupported type such as DECIMAL, or a literal that can't be converted without loss) fails
  *   when the read starts.
  * @param int32DecimalAsLong
  *   read top-level DECIMAL columns stored as INT32 into [[ColumnVector.LongColumn]]s of unscaled
  *   values, widened while the decoded values are copied, for consumers that keep decimals as Long
  *   values
  */
case class ParquetReadOptions(
    columns: Option[Seq[String]] = None,
    binaryAsString: Boolean = false,
    rowGroupFilter: Option[ParquetFilter] = None,
    maxMergeGap: Int = 1024 * 1024,
    batchSize: Option[Int] = None,
    filter: Option[ParquetFilter] = None,
    int32DecimalAsLong: Boolean = false
):
  def withColumns(names: String*): ParquetReadOptions          = copy(columns = Some(names))
  def noColumns(): ParquetReadOptions                          = copy(columns = None)
  def withBinaryAsString(enabled: Boolean): ParquetReadOptions = copy(binaryAsString = enabled)
  def withFilter(f: ParquetFilter): ParquetReadOptions         = copy(filter = Some(f))
  def noFilter(): ParquetReadOptions                           = copy(filter = None)
  def withRowGroupFilter(f: ParquetFilter): ParquetReadOptions = copy(rowGroupFilter = Some(f))
  def noRowGroupFilter(): ParquetReadOptions                   = copy(rowGroupFilter = None)
  def withMaxMergeGap(bytes: Int): ParquetReadOptions          = copy(maxMergeGap = bytes)

  /**
    * Read top-level DECIMAL columns stored as INT32 into LongColumns (unscaled values), so that a
    * consumer keeping decimals as Long values doesn't widen them in another pass
    */
  def withInt32DecimalAsLong(enabled: Boolean): ParquetReadOptions = copy(int32DecimalAsLong =
    enabled
  )

  def withBatchSize(rows: Int): ParquetReadOptions =
    require(rows > 0, s"batch size must be positive: ${rows}")
    copy(batchSize = Some(rows))

  def noBatchSize(): ParquetReadOptions = copy(batchSize = None)

  /** Both filters, for skipping files and row groups */
  private[parquet] def pruneFilter: Option[ParquetFilter] = ParquetFilter.both(
    rowGroupFilter,
    filter
  )

end ParquetReadOptions

object ParquetReadOptions:
  val default: ParquetReadOptions = ParquetReadOptions()
