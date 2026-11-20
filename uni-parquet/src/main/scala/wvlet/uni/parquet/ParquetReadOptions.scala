package wvlet.uni.parquet

/**
  * Options for reading a Parquet file.
  *
  * @param columns
  *   top-level fields to read, or None for all of them
  * @param binaryAsString
  *   decode BYTE_ARRAY columns without a logical type as UTF-8 strings when reading rows. Older
  *   writers (for example Hive and Impala) store strings without the STRING annotation.
  * @param rowGroupFilter
  *   skip row groups whose statistics show that no row matches. Rows of the row groups that are
  *   read are not filtered.
  * @param maxMergeGap
  *   column chunks closer than this many bytes are fetched in one read, which saves round trips
  *   over HTTP
  */
case class ParquetReadOptions(
    columns: Option[Seq[String]] = None,
    binaryAsString: Boolean = false,
    rowGroupFilter: Option[RowGroupFilter] = None,
    maxMergeGap: Int = 1024 * 1024
):
  def withColumns(names: String*): ParquetReadOptions           = copy(columns = Some(names))
  def noColumns(): ParquetReadOptions                           = copy(columns = None)
  def withBinaryAsString(enabled: Boolean): ParquetReadOptions  = copy(binaryAsString = enabled)
  def withRowGroupFilter(f: RowGroupFilter): ParquetReadOptions = copy(rowGroupFilter = Some(f))
  def noRowGroupFilter(): ParquetReadOptions                    = copy(rowGroupFilter = None)
  def withMaxMergeGap(bytes: Int): ParquetReadOptions           = copy(maxMergeGap = bytes)

object ParquetReadOptions:
  val default: ParquetReadOptions = ParquetReadOptions()
