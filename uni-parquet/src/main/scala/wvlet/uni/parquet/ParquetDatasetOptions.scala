package wvlet.uni.parquet

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
  *   how many file footers the async reads fetch at a time
  */
case class ParquetDatasetOptions(
    partitionTypes: Map[String, ParquetType.Primitive] = Map.empty,
    fileExtension: Option[String] = None,
    prefetch: Int = 4
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

  def withPrefetch(files: Int): ParquetDatasetOptions =
    require(files > 0, s"prefetch must be positive: ${files}")
    copy(prefetch = files)

object ParquetDatasetOptions:
  val default: ParquetDatasetOptions = ParquetDatasetOptions()

  private[parquet] val PartitionTypes: Set[ParquetType.Primitive] = Set(
    ParquetType.STRING,
    ParquetType.INT32,
    ParquetType.INT64,
    ParquetType.DATE
  )
