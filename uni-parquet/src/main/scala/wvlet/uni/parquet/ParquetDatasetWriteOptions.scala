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
    writeCommonMetadata: Boolean = true
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
