package wvlet.uni.parquet.format

import wvlet.uni.parquet.ParquetException
import wvlet.uni.parquet.thrift.TStruct

/** One entry of the flattened schema in the footer (depth-first order) */
case class SchemaElement(
    name: String,
    physicalType: Option[PhysicalType],
    typeLength: Option[Int],
    repetition: Option[Repetition],
    numChildren: Option[Int],
    logicalType: Option[LogicalType],
    fieldId: Option[Int]
)

/** Column statistics. Min and max are in the column's PLAIN encoding. */
case class Statistics(
    min: Option[Array[Byte]],
    max: Option[Array[Byte]],
    nullCount: Option[Long],
    distinctCount: Option[Long],
    isMinExact: Option[Boolean] = None,
    isMaxExact: Option[Boolean] = None,
    /**
      * True when min and max come from the deprecated fields, which older writers filled using
      * signed byte comparison. They are reliable for numbers but not for strings.
      */
    isLegacyMinMax: Boolean = false
)

case class ColumnChunkMeta(
    path: Seq[String],
    physicalType: PhysicalType,
    encodings: Seq[Encoding],
    codec: CompressionCodec,
    numValues: Long,
    totalUncompressedSize: Long,
    totalCompressedSize: Long,
    dataPageOffset: Long,
    dictionaryPageOffset: Option[Long],
    statistics: Option[Statistics],
    offsetIndexOffset: Option[Long],
    offsetIndexLength: Option[Int],
    columnIndexOffset: Option[Long],
    columnIndexLength: Option[Int],
    bloomFilterOffset: Option[Long],
    bloomFilterLength: Option[Int],
    filePath: Option[String],
    isEncrypted: Boolean
):
  /** Where the chunk's first page starts: the dictionary page if there is one */
  def startOffset: Long =
    dictionaryPageOffset match
      // Some writers set dictionary_page_offset to 0 when there is no dictionary
      case Some(d) if d > 0 && d < dataPageOffset =>
        d
      case _ =>
        dataPageOffset

/**
  * A column the rows of a row group are sorted by (Parquet's `SortingColumn`)
  *
  * @param columnIdx
  *   the leaf column's index in the file schema, as in [[ParquetSchema.columns]]
  */
case class SortingColumn(columnIdx: Int, descending: Boolean, nullsFirst: Boolean)

/**
  * @param sortingColumns
  *   the columns the rows are sorted by, most significant first; empty when the writer did not
  *   record a sort order
  */
case class RowGroupMeta(
    columns: IndexedSeq[ColumnChunkMeta],
    totalByteSize: Long,
    numRows: Long,
    ordinal: Option[Int],
    sortingColumns: IndexedSeq[SortingColumn] = IndexedSeq.empty
)

case class FileMetaData(
    version: Int,
    schema: IndexedSeq[SchemaElement],
    numRows: Long,
    rowGroups: IndexedSeq[RowGroupMeta],
    keyValueMetadata: Map[String, String],
    createdBy: Option[String]
)

private[parquet] case class DataPageHeader(
    numValues: Int,
    encoding: Encoding,
    definitionLevelEncoding: Encoding,
    repetitionLevelEncoding: Encoding
)

private[parquet] case class DataPageHeaderV2(
    numValues: Int,
    numNulls: Int,
    numRows: Int,
    encoding: Encoding,
    definitionLevelsByteLength: Int,
    repetitionLevelsByteLength: Int,
    isCompressed: Boolean
)

private[parquet] case class DictionaryPageHeader(numValues: Int, encoding: Encoding)

private[parquet] case class PageHeader(
    pageType: PageType,
    uncompressedSize: Int,
    compressedSize: Int,
    crc: Option[Int],
    dataPage: Option[DataPageHeader],
    dataPageV2: Option[DataPageHeaderV2],
    dictionaryPage: Option[DictionaryPageHeader]
)

/** Converts decoded Thrift structs into metadata classes */
private[parquet] object MetadataDecoder:

  def fileMetaData(s: TStruct): FileMetaData =
    if s.has(8) then
      // encryption_algorithm: a plaintext footer with encrypted columns
      throw ParquetException.unsupported("encrypted Parquet files")
    FileMetaData(
      version = s.int(1, "version"),
      schema = s.structList(2).map(schemaElement),
      numRows = s.long(3, "num_rows"),
      rowGroups = s.structList(4).map(rowGroup),
      keyValueMetadata =
        s.structList(5)
          .map { kv =>
            kv.string(1, "key") -> kv.stringOpt(2).getOrElse("")
          }
          .toMap,
      createdBy = s.stringOpt(6)
    )

  private def schemaElement(s: TStruct): SchemaElement =
    val scale     = s.intOpt(7).getOrElse(0)
    val precision = s.intOpt(8).getOrElse(0)
    val logical   = s
      .structOpt(10)
      .flatMap(logicalType)
      .orElse(s.intOpt(6).flatMap(LogicalType.fromConvertedType(_, scale, precision)))
    SchemaElement(
      name = s.string(4, "name"),
      physicalType = s.intOpt(1).map(PhysicalType.of),
      typeLength = s.intOpt(2),
      repetition = s.intOpt(3).map(Repetition.of),
      numChildren = s.intOpt(5),
      logicalType = logical,
      fieldId = s.intOpt(9)
    )

  private def timeUnit(s: TStruct): TimeUnit =
    s.unionField.map(_._1) match
      case Some(1) =>
        TimeUnit.MILLIS
      case Some(2) =>
        TimeUnit.MICROS
      case Some(3) =>
        TimeUnit.NANOS
      case other =>
        throw ParquetException.corrupt(s"unknown time unit ${other}")

  private def logicalType(s: TStruct): Option[LogicalType] = s
    .unionField
    .map {
      case (1, _) =>
        LogicalType.String
      case (2, _) =>
        LogicalType.Map
      case (3, _) =>
        LogicalType.List
      case (4, _) =>
        LogicalType.Enum
      case (5, d: TStruct) =>
        LogicalType.Decimal(d.int(1, "scale"), d.int(2, "precision"))
      case (6, _) =>
        LogicalType.Date
      case (7, t: TStruct) =>
        LogicalType.Time(t.bool(1, true), timeUnit(t.struct(2, "unit")))
      case (8, t: TStruct) =>
        LogicalType.Timestamp(t.bool(1, true), timeUnit(t.struct(2, "unit")))
      case (10, i: TStruct) =>
        LogicalType.Integer(i.int(1, "bitWidth"), i.bool(2, true))
      case (11, _) =>
        LogicalType.Unknown
      case (12, _) =>
        LogicalType.Json
      case (13, _) =>
        LogicalType.Bson
      case (14, _) =>
        LogicalType.Uuid
      case (15, _) =>
        LogicalType.Float16
      case (id, _) =>
        LogicalType.Other(id)
    }

  private def rowGroup(s: TStruct): RowGroupMeta = RowGroupMeta(
    columns = s.structList(1).map(columnChunk),
    totalByteSize = s.longOpt(2).getOrElse(0L),
    numRows = s.long(3, "num_rows"),
    ordinal = s.intOpt(7),
    sortingColumns = s
      .structList(4)
      .map { c =>
        SortingColumn(c.int(1, "column_idx"), c.bool(2, false), c.bool(3, false))
      }
  )

  private def columnChunk(s: TStruct): ColumnChunkMeta =
    val encrypted = s.has(8) || s.has(9)
    val m         =
      s.structOpt(3) match
        case Some(m) =>
          m
        case None if encrypted =>
          throw ParquetException.unsupported("encrypted Parquet columns")
        case None =>
          throw ParquetException.corrupt("column chunk has no metadata")
    ColumnChunkMeta(
      path = m
        .list(3)
        .collect { case b: Array[Byte] =>
          String(b, "UTF-8")
        },
      physicalType = PhysicalType.of(m.int(1, "type")),
      encodings = m
        .list(2)
        .collect { case i: Int =>
          i
        }
        .flatMap(id => Encoding.values.find(_.id == id)),
      codec = CompressionCodec.of(m.int(4, "codec")),
      numValues = m.long(5, "num_values"),
      totalUncompressedSize = m.long(6, "total_uncompressed_size"),
      totalCompressedSize = m.long(7, "total_compressed_size"),
      dataPageOffset = m.long(9, "data_page_offset"),
      dictionaryPageOffset = m.longOpt(11),
      statistics = m.structOpt(12).map(statistics),
      offsetIndexOffset = s.longOpt(4),
      offsetIndexLength = s.intOpt(5),
      columnIndexOffset = s.longOpt(6),
      columnIndexLength = s.intOpt(7),
      bloomFilterOffset = m.longOpt(14),
      bloomFilterLength = m.intOpt(15),
      filePath = s.stringOpt(1),
      isEncrypted = encrypted
    )

  end columnChunk

  def statistics(s: TStruct): Statistics =
    // min_value/max_value (5, 6) follow the column's sort order. The deprecated min/max (2, 1) used
    // signed byte comparison, so prefer the new fields.
    Statistics(
      min = s.binaryOpt(6).orElse(s.binaryOpt(2)),
      max = s.binaryOpt(5).orElse(s.binaryOpt(1)),
      nullCount = s.longOpt(3),
      distinctCount = s.longOpt(4),
      isMinExact = s
        .get(8)
        .collect { case b: Boolean =>
          b
        },
      isMaxExact = s
        .get(7)
        .collect { case b: Boolean =>
          b
        },
      isLegacyMinMax = !s.has(5) && !s.has(6)
    )

  def pageHeader(s: TStruct): PageHeader = PageHeader(
    pageType = PageType.of(s.int(1, "type")),
    uncompressedSize = s.int(2, "uncompressed_page_size"),
    compressedSize = s.int(3, "compressed_page_size"),
    crc = s.intOpt(4),
    dataPage = s
      .structOpt(5)
      .map { d =>
        DataPageHeader(
          numValues = d.int(1, "num_values"),
          encoding = Encoding.of(d.int(2, "encoding")),
          definitionLevelEncoding = Encoding.of(d.int(3, "definition_level_encoding")),
          repetitionLevelEncoding = Encoding.of(d.int(4, "repetition_level_encoding"))
        )
      },
    dataPageV2 = s
      .structOpt(8)
      .map { d =>
        DataPageHeaderV2(
          numValues = d.int(1, "num_values"),
          numNulls = d.int(2, "num_nulls"),
          numRows = d.int(3, "num_rows"),
          encoding = Encoding.of(d.int(4, "encoding")),
          definitionLevelsByteLength = d.int(5, "definition_levels_byte_length"),
          repetitionLevelsByteLength = d.int(6, "repetition_levels_byte_length"),
          isCompressed = d.bool(7, true)
        )
      },
    dictionaryPage = s
      .structOpt(7)
      .map { d =>
        DictionaryPageHeader(d.int(1, "num_values"), Encoding.of(d.intOpt(2).getOrElse(0)))
      }
  )

end MetadataDecoder
