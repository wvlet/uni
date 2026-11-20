package wvlet.uni.parquet.format

import wvlet.uni.parquet.ParquetException

/** Parquet physical types: how values are stored on disk */
enum PhysicalType(val id: Int):
  case BOOLEAN              extends PhysicalType(0)
  case INT32                extends PhysicalType(1)
  case INT64                extends PhysicalType(2)
  case INT96                extends PhysicalType(3)
  case FLOAT                extends PhysicalType(4)
  case DOUBLE               extends PhysicalType(5)
  case BYTE_ARRAY           extends PhysicalType(6)
  case FIXED_LEN_BYTE_ARRAY extends PhysicalType(7)

object PhysicalType:
  def of(id: Int): PhysicalType = values
    .find(_.id == id)
    .getOrElse(throw ParquetException.corrupt(s"unknown physical type ${id}"))

enum Repetition(val id: Int):
  case REQUIRED extends Repetition(0)
  case OPTIONAL extends Repetition(1)
  case REPEATED extends Repetition(2)

object Repetition:
  def of(id: Int): Repetition = values
    .find(_.id == id)
    .getOrElse(throw ParquetException.corrupt(s"unknown repetition type ${id}"))

/** Compression codecs. LZO and the Hadoop-framed LZ4 are listed so errors can name them. */
enum CompressionCodec(val id: Int):
  case UNCOMPRESSED extends CompressionCodec(0)
  case SNAPPY       extends CompressionCodec(1)
  case GZIP         extends CompressionCodec(2)
  case LZO          extends CompressionCodec(3)
  case BROTLI       extends CompressionCodec(4)
  case LZ4          extends CompressionCodec(5)
  case ZSTD         extends CompressionCodec(6)
  case LZ4_RAW      extends CompressionCodec(7)

object CompressionCodec:
  def of(id: Int): CompressionCodec = values
    .find(_.id == id)
    .getOrElse(throw ParquetException.unsupported(s"compression codec ${id}"))

enum Encoding(val id: Int):
  case PLAIN                   extends Encoding(0)
  case PLAIN_DICTIONARY        extends Encoding(2)
  case RLE                     extends Encoding(3)
  case BIT_PACKED              extends Encoding(4)
  case DELTA_BINARY_PACKED     extends Encoding(5)
  case DELTA_LENGTH_BYTE_ARRAY extends Encoding(6)
  case DELTA_BYTE_ARRAY        extends Encoding(7)
  case RLE_DICTIONARY          extends Encoding(8)
  case BYTE_STREAM_SPLIT       extends Encoding(9)

object Encoding:
  def of(id: Int): Encoding = values
    .find(_.id == id)
    .getOrElse(throw ParquetException.unsupported(s"encoding ${id}"))

enum PageType(val id: Int):
  case DATA_PAGE       extends PageType(0)
  case INDEX_PAGE      extends PageType(1)
  case DICTIONARY_PAGE extends PageType(2)
  case DATA_PAGE_V2    extends PageType(3)

object PageType:
  def of(id: Int): PageType = values
    .find(_.id == id)
    .getOrElse(throw ParquetException.corrupt(s"unknown page type ${id}"))

enum TimeUnit:
  case MILLIS,
    MICROS,
    NANOS

/**
  * Logical types annotate physical types with their meaning. Files from older writers carry only a
  * converted type, which [[LogicalType.fromConvertedType]] maps to the same cases.
  */
enum LogicalType:
  case String
  case Map
  case List
  case Enum
  case Decimal(scale: Int, precision: Int)
  case Date
  case Time(isAdjustedToUTC: Boolean, unit: TimeUnit)
  case Timestamp(isAdjustedToUTC: Boolean, unit: TimeUnit)
  case Integer(bitWidth: Int, isSigned: Boolean)
  case Unknown
  case Json
  case Bson
  case Uuid
  case Float16
  case Interval
  case MapKeyValue

  /** A logical type this reader does not interpret (for example VARIANT or GEOMETRY) */
  case Other(id: Int)

object LogicalType:
  private[parquet] def fromConvertedType(id: Int, scale: Int, precision: Int): Option[LogicalType] =
    id match
      case 0 =>
        Some(String)
      case 1 =>
        Some(Map)
      case 2 =>
        Some(MapKeyValue)
      case 3 =>
        Some(List)
      case 4 =>
        Some(Enum)
      case 5 =>
        Some(Decimal(scale, precision))
      case 6 =>
        Some(Date)
      case 7 =>
        Some(Time(true, TimeUnit.MILLIS))
      case 8 =>
        Some(Time(true, TimeUnit.MICROS))
      case 9 =>
        Some(Timestamp(true, TimeUnit.MILLIS))
      case 10 =>
        Some(Timestamp(true, TimeUnit.MICROS))
      case 11 =>
        Some(Integer(8, false))
      case 12 =>
        Some(Integer(16, false))
      case 13 =>
        Some(Integer(32, false))
      case 14 =>
        Some(Integer(64, false))
      case 15 =>
        Some(Integer(8, true))
      case 16 =>
        Some(Integer(16, true))
      case 17 =>
        Some(Integer(32, true))
      case 18 =>
        Some(Integer(64, true))
      case 19 =>
        Some(Json)
      case 20 =>
        Some(Bson)
      case 21 =>
        Some(Interval)
      case _ =>
        None

end LogicalType
