package wvlet.uni.cloud.parquet

/**
  * How [[Parquet.dataset]] finds files and reads partition folders. Per-read settings (columns,
  * filters, batch size) stay in [[ParquetReadOptions]].
  *
  * @param partitionTypes
  *   the type of each `key=value` partition folder key. Keys not listed are STRING, so a type never
  *   changes with the data. Supported: [[ParquetType.STRING]], [[ParquetType.INT32]],
  *   [[ParquetType.INT64]], and [[ParquetType.DATE]] (ISO dates such as `2026-11-20`).
  * @param fileExtension
  *   keep only files ending with this extension, such as `.parquet`. By default every file is kept
  *   except those under a folder or with a name starting with `_` or `.`, so files that Hive or
  *   Trino write without an extension are read too.
  * @param prefetch
  *   how many file footers a read fetches at a time (sync reads on the JVM, and async reads
  *   everywhere)
  * @param readCommonMetadata
  *   read `_common_metadata` at the root, which [[Parquet.writeDataset]] writes, for the partition
  *   types and for the schema of a dataset without files. Types set with [[withPartitionType]] win.
  * @param tableFormat
  *   how a root is read: [[ParquetTableFormat.Auto]] reads a root with a `_delta_log/` folder as a
  *   Delta Lake table, a root with a `metadata/` folder with `.metadata.json` files as an Apache
  *   Iceberg table, and any other root as Parquet files
  * @param version
  *   the Delta table version to read, instead of the latest
  * @param snapshotId
  *   the Iceberg snapshot to read, instead of the table's current snapshot
  */
case class ParquetDatasetOptions(
    partitionTypes: Map[String, ParquetType.Primitive] = Map.empty,
    fileExtension: Option[String] = None,
    prefetch: Int = 4,
    readCommonMetadata: Boolean = true,
    tableFormat: ParquetTableFormat = ParquetTableFormat.Auto,
    version: Option[Long] = None,
    snapshotId: Option[Long] = None
):
  def withPartitionType(key: String, tpe: ParquetType.Primitive): ParquetDatasetOptions =
    require(
      ParquetDatasetOptions.PartitionTypes.contains(tpe),
      s"Unsupported partition type for ${key}: ${tpe}. Use STRING, INT32, INT64, or DATE"
    )
    copy(partitionTypes = partitionTypes + (key -> tpe))

  def noPartitionType(key: String): ParquetDatasetOptions = copy(partitionTypes =
    partitionTypes - key
  )

  def withFileExtension(ext: String): ParquetDatasetOptions = copy(fileExtension = Some(ext))
  def noFileExtension(): ParquetDatasetOptions              = copy(fileExtension = None)

  def withReadCommonMetadata(enabled: Boolean): ParquetDatasetOptions = copy(readCommonMetadata =
    enabled
  )

  def withTableFormat(format: ParquetTableFormat): ParquetDatasetOptions = copy(tableFormat =
    format
  )

  /** Reads this version of a Delta table (time travel) instead of the latest */
  def withVersion(version: Long): ParquetDatasetOptions =
    require(version >= 0, s"version must not be negative: ${version}")
    copy(version = Some(version))

  def noVersion(): ParquetDatasetOptions = copy(version = None)

  /** Reads this snapshot of an Iceberg table (time travel) instead of the current one */
  def withSnapshotId(id: Long): ParquetDatasetOptions = copy(snapshotId = Some(id))

  def noSnapshotId(): ParquetDatasetOptions = copy(snapshotId = None)

  def withPrefetch(files: Int): ParquetDatasetOptions =
    require(files > 0, s"prefetch must be positive: ${files}")
    copy(prefetch = files)

end ParquetDatasetOptions

/** How [[Parquet.dataset]] reads a root */
enum ParquetTableFormat:
  /**
    * A Delta Lake table when the root has a `_delta_log/` folder, an Iceberg table when it has a
    * `metadata/` folder with `.metadata.json` files (or is such a file), Parquet files otherwise
    */
  case Auto

  /** Every Parquet file under the root, even when it is a table */
  case Files

  /** A Delta Lake table: only the files of the chosen version, as its `_delta_log/` records */
  case Delta

  /**
    * An Apache Iceberg table: only the data files of the chosen snapshot, as its `metadata/`
    * records
    */
  case Iceberg

object ParquetDatasetOptions:
  val default: ParquetDatasetOptions = ParquetDatasetOptions()

  private[parquet] val PartitionTypes: Set[ParquetType.Primitive] = Set(
    ParquetType.STRING,
    ParquetType.INT32,
    ParquetType.INT64,
    ParquetType.DATE
  )
