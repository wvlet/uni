package wvlet.uni.parquet

import scala.collection.immutable.VectorMap

/** What a dataset write does when the root already holds data files */
enum ParquetWriteMode:
  /** Fail before writing anything when the root holds a file [[Parquet.dataset]] would read */
  case ErrorIfExists

  /** Add new files next to the existing ones, which must use the same partition keys */
  case Append

  /**
    * Replace the dataset: once every new file is written, delete the data files that were under the
    * root before the write started
    */
  case Overwrite

  /**
    * Replace only the partitions this write touched, like Spark's dynamic partition overwrite:
    * other partitions are kept
    */
  case OverwritePartitions

/**
  * A column to sort dataset rows by, for [[ParquetDatasetWriteOptions.withSortBy]]
  *
  * Values are ordered as Parquet statistics order them, so row group min/max follow the sort:
  * signed for integers, dates, timestamps, and decimals; unsigned for unsigned integers; encoded
  * (UTF-8) bytes, unsigned, for strings and binary; false before true; NaN after every other
  * number.
  *
  * @param column
  *   a top-level primitive column that is not a partition column
  * @param descending
  *   largest first
  * @param nullsFirst
  *   nulls before every value. The default puts them last, as DuckDB does, in either direction.
  */
case class ParquetSortKey(column: String, descending: Boolean = false, nullsFirst: Boolean = false):
  def withDescending(enabled: Boolean): ParquetSortKey = copy(descending = enabled)
  def withNullsFirst(enabled: Boolean): ParquetSortKey = copy(nullsFirst = enabled)

object ParquetSortKey:
  def asc(column: String): ParquetSortKey  = ParquetSortKey(column)
  def desc(column: String): ParquetSortKey = ParquetSortKey(column, descending = true)

/**
  * How [[Parquet.writeDataset]] and the dataset writers lay out files. Per-file settings (codec,
  * row group size, dictionary) are in [[writeOptions]].
  *
  * @param partitionBy
  *   the columns to split folders by, outermost first: `dt` and `country` write
  *   `dt=2026-11-20/country=JP/part-....parquet`. The columns are left out of the files. Each must
  *   be a top-level STRING, INT32, INT64, or DATE column.
  * @param mode
  *   what to do when the root already holds data files; see [[ParquetWriteMode]]
  * @param maxRowsPerFile
  *   start a new file in the same partition after this many rows
  * @param maxFileSize
  *   start a new file in the same partition once a file reaches about this many bytes. The check
  *   runs between rows, so a file can run over by up to one row (by up to 1024 rows for column
  *   batches).
  * @param maxOpenFiles
  *   how many files may be open at once. At the limit, the file that went longest without a row is
  *   finished, and that partition's next row starts a new file.
  * @param maxBufferedBytes
  *   the memory budget for rows buffered across open files. Over it, the largest buffered row group
  *   is written out early; its file stays open.
  * @param writeOptions
  *   settings for each file, as for [[Parquet.newWriter]]
  * @param writeSuccessMarker
  *   write an empty `_SUCCESS` file at the root once every file is written, as Spark and Hadoop do
  * @param writeCommonMetadata
  *   write `_common_metadata` at the root: a Parquet file without rows that holds the files' schema
  *   and the partition column types, so [[Parquet.dataset]] reads typed partitions without
  *   `withPartitionType`
  * @param sortBy
  *   sort each partition's rows by these columns before writing, like Spark's
  *   `sortWithinPartitions` or DuckDB's `ORDER BY` before `COPY`. A partition's rows are held in
  *   memory and written as one sorted run when the writer closes, so its files cover consecutive
  *   key ranges. When the held rows exceed [[maxBufferedBytes]], the largest partition is written
  *   early as a separate run: each file stays sorted, but runs overlap. Every row group records
  *   Parquet's `sorting_columns`. [[maxOpenFiles]] has no effect, as a run writes one file at a
  *   time.
  * @param tableFormat
  *   `Auto` (default) writes plain files, or appends to the Iceberg table the root already holds
  *   (and fails on a Delta table, which Uni doesn't write yet). `Iceberg` creates an Apache Iceberg
  *   table (a Hadoop table: `metadata/vN.metadata.json`) with [[ParquetWriteMode.ErrorIfExists]],
  *   or adds a snapshot to one with [[ParquetWriteMode.Append]]; the data files go under `data/`
  *   and keep the partition columns. `Files` always writes plain files.
  */
case class ParquetDatasetWriteOptions(
    partitionBy: Seq[String] = Seq.empty,
    mode: ParquetWriteMode = ParquetWriteMode.ErrorIfExists,
    maxRowsPerFile: Option[Long] = None,
    maxFileSize: Option[Long] = None,
    maxOpenFiles: Int = 32,
    maxBufferedBytes: Long = 256L * 1024 * 1024,
    writeOptions: ParquetWriteOptions = ParquetWriteOptions.default,
    writeSuccessMarker: Boolean = true,
    writeCommonMetadata: Boolean = true,
    sortBy: Seq[ParquetSortKey] = Seq.empty,
    tableFormat: ParquetTableFormat = ParquetTableFormat.Auto
):
  def withPartitionBy(columns: String*): ParquetDatasetWriteOptions =
    require(columns.distinct.size == columns.size, s"Duplicate partition columns: ${columns}")
    columns.foreach { c =>
      require(c.nonEmpty, "A partition column name is empty")
      require(
        !c.startsWith("_") && !c.startsWith("."),
        s"Partition column ${c} starts with _ or ., so Parquet.dataset would skip its folders"
      )
    }
    copy(partitionBy = columns.toSeq)

  def noPartitionBy(): ParquetDatasetWriteOptions = copy(partitionBy = Seq.empty)

  def withMode(mode: ParquetWriteMode): ParquetDatasetWriteOptions = copy(mode = mode)

  def withMaxRowsPerFile(rows: Long): ParquetDatasetWriteOptions =
    require(rows > 0, s"maxRowsPerFile must be positive: ${rows}")
    copy(maxRowsPerFile = Some(rows))

  def noMaxRowsPerFile(): ParquetDatasetWriteOptions = copy(maxRowsPerFile = None)

  def withMaxFileSize(bytes: Long): ParquetDatasetWriteOptions =
    require(bytes > 0, s"maxFileSize must be positive: ${bytes}")
    copy(maxFileSize = Some(bytes))

  def noMaxFileSize(): ParquetDatasetWriteOptions = copy(maxFileSize = None)

  def withMaxOpenFiles(files: Int): ParquetDatasetWriteOptions =
    require(files > 0, s"maxOpenFiles must be positive: ${files}")
    copy(maxOpenFiles = files)

  def withMaxBufferedBytes(bytes: Long): ParquetDatasetWriteOptions =
    require(bytes > 0, s"maxBufferedBytes must be positive: ${bytes}")
    copy(maxBufferedBytes = bytes)

  def withWriteOptions(options: ParquetWriteOptions): ParquetDatasetWriteOptions = copy(
    writeOptions = options
  )

  def withWriteSuccessMarker(enabled: Boolean): ParquetDatasetWriteOptions = copy(
    writeSuccessMarker = enabled
  )

  def withWriteCommonMetadata(enabled: Boolean): ParquetDatasetWriteOptions = copy(
    writeCommonMetadata = enabled
  )

  /** Sort each partition's rows by these columns, ascending with nulls last */
  def withSortBy(columns: String*): ParquetDatasetWriteOptions = withSortKeys(
    columns.map(ParquetSortKey(_))
  )

  /** Sort each partition's rows by these keys, most significant first */
  def withSortBy(key: ParquetSortKey, more: ParquetSortKey*): ParquetDatasetWriteOptions =
    withSortKeys(key +: more)

  private def withSortKeys(keys: Seq[ParquetSortKey]): ParquetDatasetWriteOptions =
    val names = keys.map(_.column)
    require(names.distinct.size == names.size, s"Duplicate sort columns: ${names.mkString(", ")}")
    keys.foreach(k => require(k.column.nonEmpty, "A sort column name is empty"))
    copy(sortBy = keys)

  def noSortBy(): ParquetDatasetWriteOptions = copy(sortBy = Seq.empty)

  def withTableFormat(format: ParquetTableFormat): ParquetDatasetWriteOptions = copy(tableFormat =
    format
  )

end ParquetDatasetWriteOptions

object ParquetDatasetWriteOptions:
  val default: ParquetDatasetWriteOptions = ParquetDatasetWriteOptions()

/**
  * A file a dataset write produced
  *
  * @param path
  *   a local path, or an object URI
  * @param partitions
  *   the partition values of the file's folders, null for a null or empty value
  */
case class WrittenFile(path: String, partitions: VectorMap[String, Any], numRows: Long, size: Long)
