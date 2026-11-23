package wvlet.uni.cloud.parquet

/**
  * What [[Parquet.compactIcebergTable]] rewrites. Data files are grouped by partition spec and
  * partition value. In a group, every file that deletes apply to is rewritten with them applied,
  * and small files are merged when the group has enough of them. The new files are cut at the
  * target size.
  *
  * @param targetFileSize
  *   the size of the files to write; when unset, the table's `write.target-file-size-bytes`, or 512
  *   MiB
  * @param minFileSize
  *   files below this size are small; when unset, 75% of the target size
  * @param minInputFiles
  *   a group without deletes is merged only when it has at least this many small files
  * @param fileMerging
  *   whether small files without deletes are merged; when false, only files with deletes are
  *   rewritten
  * @param minDeleteRatio
  *   a file is rewritten for its deletes only when the rows they delete reach this fraction of its
  *   rows (0 rewrites every file with deletes). Partition-scoped and equality deletes count in
  *   full, as iceberg-java counts them.
  * @param filter
  *   compacts only the files that the filter may match, by partition values and column statistics;
  *   those files are rewritten whole, rows the filter doesn't match included
  * @param writeOptions
  *   the Parquet options of the new files (codec, row group size)
  */
case class IcebergCompactionOptions(
    targetFileSize: Option[Long] = None,
    minFileSize: Option[Long] = None,
    minInputFiles: Int = 5,
    fileMerging: Boolean = true,
    minDeleteRatio: Double = 0.0,
    filter: Option[ParquetFilter] = None,
    writeOptions: ParquetWriteOptions = ParquetWriteOptions.default
):
  def withTargetFileSize(bytes: Long): IcebergCompactionOptions =
    require(bytes > 0, s"targetFileSize must be positive: ${bytes}")
    copy(targetFileSize = Some(bytes))

  def noTargetFileSize(): IcebergCompactionOptions = copy(targetFileSize = None)

  def withMinFileSize(bytes: Long): IcebergCompactionOptions =
    require(bytes >= 0, s"minFileSize must not be negative: ${bytes}")
    copy(minFileSize = Some(bytes))

  def noMinFileSize(): IcebergCompactionOptions = copy(minFileSize = None)

  def withMinInputFiles(files: Int): IcebergCompactionOptions =
    require(files >= 1, s"minInputFiles must be at least 1: ${files}")
    copy(minInputFiles = files)

  def withFileMerging(enabled: Boolean): IcebergCompactionOptions = copy(fileMerging = enabled)

  /** Rewrites only files with deletes, without merging small files */
  def noFileMerging(): IcebergCompactionOptions = copy(fileMerging = false)

  def withMinDeleteRatio(ratio: Double): IcebergCompactionOptions =
    require(ratio >= 0 && ratio <= 1, s"minDeleteRatio must be in [0, 1]: ${ratio}")
    copy(minDeleteRatio = ratio)

  def withFilter(filter: ParquetFilter): IcebergCompactionOptions = copy(filter = Some(filter))

  def noFilter(): IcebergCompactionOptions = copy(filter = None)

  def withWriteOptions(options: ParquetWriteOptions): IcebergCompactionOptions = copy(writeOptions =
    options
  )

end IcebergCompactionOptions

object IcebergCompactionOptions:
  val default: IcebergCompactionOptions = IcebergCompactionOptions()

/**
  * What [[Parquet.compactIcebergTable]] committed; all zeros when there was nothing to compact
  *
  * @param removedDataFiles
  *   the data files rewritten and removed
  * @param addedDataFiles
  *   the data files written
  * @param removedDeleteFiles
  *   the delete files removed: deletion vectors, position deletes, and equality deletes that no
  *   remaining data file needs
  * @param rows
  *   the rows written to the new files
  */
case class IcebergCompaction(
    removedDataFiles: Int,
    addedDataFiles: Int,
    removedDeleteFiles: Int,
    rows: Long
):
  def isEmpty: Boolean = removedDataFiles == 0 && removedDeleteFiles == 0

object IcebergCompaction:
  val empty: IcebergCompaction = IcebergCompaction(0, 0, 0, 0L)
