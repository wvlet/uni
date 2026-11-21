package wvlet.uni.parquet

import wvlet.uni.weaver.Weaver

import scala.collection.immutable.VectorMap
import scala.util.control.NonFatal

/** One partition key and its type, as recorded in `_common_metadata` */
private[parquet] case class PartitionTypeEntry(name: String, `type`: String) derives Weaver

/**
  * What `_common_metadata` records about a dataset: the files' columns and the partition keys with
  * their types, in folder order
  */
private[parquet] case class DatasetMetadata(
    fields: IndexedSeq[ParquetField],
    partitionTypes: VectorMap[String, ParquetType.Primitive]
)

/**
  * The root files a dataset writer leaves: `_SUCCESS`, and `_common_metadata`, Parquet's summary
  * file name. It is a Parquet file with no row groups, whose key-value metadata lists the partition
  * types. Partition columns stay out of its schema, as they are out of the data files.
  */
private[parquet] object DatasetMetadata:
  val SuccessMarker  = "_SUCCESS"
  val CommonMetadata = "_common_metadata"

  /** The key-value metadata key for the partition types, a JSON array of name and type */
  val PartitionTypesKey = "wvlet.uni.parquet.partition.types"

  private val typeNames: Map[String, ParquetType.Primitive] = Map(
    "STRING" -> ParquetType.STRING,
    "INT32"  -> ParquetType.INT32,
    "INT64"  -> ParquetType.INT64,
    "DATE"   -> ParquetType.DATE
  )

  private val namesOfTypes: Map[ParquetType.Primitive, String] = typeNames.map(_.swap)

  def typeName(t: ParquetType.Primitive): String = namesOfTypes.getOrElse(t, t.toString)

  private val entriesWeaver = summon[Weaver[Seq[PartitionTypeEntry]]]

  /** The bytes of `_common_metadata` for files with `fileSchema` */
  def toBytes(
      fileSchema: ParquetSchema,
      partitionTypes: Seq[(String, ParquetType.Primitive)],
      options: ParquetWriteOptions
  ): Array[Byte] =
    val json = entriesWeaver.toJson(
      partitionTypes.map((k, t) => PartitionTypeEntry(k, namesOfTypes(t)))
    )
    val out = java.io.ByteArrayOutputStream()
    val w   = Parquet.newRecordWriter(
      out,
      fileSchema,
      options.withKeyValueMetadata(options.keyValueMetadata + (PartitionTypesKey -> json))
    )
    w.close()
    out.toByteArray

  /** Reads `_common_metadata`; a file Uni didn't write, without partition types, gives None */
  def parse(bytes: Array[Byte], path: String): Option[DatasetMetadata] =
    val file =
      try
        Parquet.open(bytes)
      catch
        case NonFatal(e) =>
          throw ParquetException(s"Failed to read ${path}: ${e.getMessage}", e)
    try file
        .keyValueMetadata
        .get(PartitionTypesKey)
        .map { json =>
          val entries =
            try
              entriesWeaver.fromJson(json)
            catch
              case NonFatal(e) =>
                throw ParquetException(
                  s"${path} has unreadable partition types (${json}): ${e.getMessage}",
                  e
                )
          val types = entries.map { e =>
            e.name ->
              typeNames.getOrElse(
                e.`type`,
                throw ParquetException(
                  s"${path} gives partition ${e.name} the unsupported type ${e.`type`}"
                )
              )
          }
          DatasetMetadata(file.schema.fields, VectorMap.from(types))
        }
    finally file.close()

  end parse

end DatasetMetadata
