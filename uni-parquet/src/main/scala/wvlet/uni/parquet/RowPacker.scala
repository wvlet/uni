package wvlet.uni.parquet

import wvlet.uni.msgpack.spi.Packer
import wvlet.uni.parquet.ColumnVector.*
import wvlet.uni.parquet.format.LogicalType
import wvlet.uni.parquet.format.PhysicalType
import wvlet.uni.parquet.format.TimeUnit

import java.time.LocalDate

/**
  * Writes rows of a column batch as MessagePack maps (column name to value), so Weaver can decode
  * them into case classes. Weaver matches names loosely, so a `user_id` column fills a `userId`
  * field.
  *
  * Value mapping: timestamps become MessagePack timestamps (Instant), DATE and TIME become ISO-8601
  * strings, DECIMAL, UUID, and unsigned 64-bit integers become strings, and lists, maps, and
  * structs become arrays and maps.
  */
private[parquet] final class RowPacker(binaryAsString: Boolean):

  def packRow(p: Packer, batch: ColumnBatch, row: Int): Unit =
    p.packMapHeader(batch.columns.size)
    batch
      .columns
      .foreach { c =>
        p.packString(c.name)
        pack(p, c, row)
      }

  def pack(p: Packer, c: ColumnVector, i: Int): Unit =
    if c.isNull(i) then
      p.packNil
    else
      c match
        case b: BooleanColumn =>
          p.packBoolean(b.values(i))
        case v: IntColumn =>
          packInt(p, v.values(i), v.field.logicalType)
        case v: LongColumn =>
          packLong(p, v.values(i), v.field.logicalType)
        case v: FloatColumn =>
          p.packFloat(v.values(i))
        case v: DoubleColumn =>
          p.packDouble(v.values(i))
        case v: BinaryColumn =>
          packBinary(p, v, i)
        case l: ListColumn =>
          val from = l.offsets(i)
          val to   = l.offsets(i + 1)
          p.packArrayHeader(to - from)
          var j = from
          while j < to do
            pack(p, l.elements, j)
            j += 1
        case m: MapColumn =>
          val from = m.offsets(i)
          val to   = m.offsets(i + 1)
          p.packMapHeader(to - from)
          var j = from
          while j < to do
            pack(p, m.keys, j)
            pack(p, m.values, j)
            j += 1
        case s: StructColumn =>
          p.packMapHeader(s.children.size)
          s.children
            .foreach { child =>
              p.packString(child.name)
              pack(p, child, i)
            }

  private def packInt(p: Packer, v: Int, lt: Option[LogicalType]): Unit =
    lt match
      case Some(LogicalType.Date) =>
        p.packString(LocalDate.ofEpochDay(v.toLong).toString)
      case Some(LogicalType.Time(_, _)) =>
        p.packString(LogicalValues.localTime(v.toLong * 1000000L).toString)
      case Some(LogicalType.Decimal(scale, _)) =>
        p.packString(BigDecimal(BigInt(v), scale).toString)
      case Some(LogicalType.Integer(32, false)) =>
        p.packLong(v.toLong & 0xffffffffL)
      case Some(LogicalType.Integer(16, false)) =>
        p.packInt(v & 0xffff)
      case Some(LogicalType.Integer(8, false)) =>
        p.packInt(v & 0xff)
      case _ =>
        p.packInt(v)

  private def packLong(p: Packer, v: Long, lt: Option[LogicalType]): Unit =
    lt match
      case Some(LogicalType.Timestamp(_, unit)) =>
        p.packTimestamp(LogicalValues.timestamp(v, unit))
      case Some(LogicalType.Time(_, unit)) =>
        val nanos =
          unit match
            case TimeUnit.MILLIS =>
              v * 1000000L
            case TimeUnit.MICROS =>
              v * 1000L
            case TimeUnit.NANOS =>
              v
        p.packString(LogicalValues.localTime(nanos).toString)
      case Some(LogicalType.Decimal(scale, _)) =>
        p.packString(BigDecimal(BigInt(v), scale).toString)
      case Some(LogicalType.Integer(64, false)) if v < 0 =>
        p.packString((BigInt(v) + (BigInt(1) << 64)).toString)
      case _ =>
        p.packLong(v)

  private def packBinary(p: Packer, c: BinaryColumn, i: Int): Unit =
    val off      = c.offsets(i)
    val len      = c.length(i)
    val physical =
      c.field match
        case f: ParquetField.Primitive =>
          f.physicalType
        case _ =>
          PhysicalType.BYTE_ARRAY
    c.field.logicalType match
      case Some(LogicalType.String | LogicalType.Enum | LogicalType.Json) =>
        p.packRawStringHeader(len)
        p.writePayload(c.data, off, len)
      case Some(LogicalType.Decimal(_, _)) | Some(LogicalType.Uuid) | Some(LogicalType.Float16) =>
        LogicalValues.fromBinary(c.data, off, len, c.field) match
          case f: Float =>
            p.packFloat(f)
          case _: Array[Byte] =>
            // A UUID or FLOAT16 of the wrong length: pass the bytes through
            p.packBinaryHeader(len)
            p.writePayload(c.data, off, len)
          case other =>
            p.packString(other.toString)
      case _ if physical == PhysicalType.INT96 && len == 12 =>
        p.packTimestamp(LogicalValues.int96ToInstant(c.data, off))
      case _ if binaryAsString =>
        p.packRawStringHeader(len)
        p.writePayload(c.data, off, len)
      case _ =>
        p.packBinaryHeader(len)
        p.writePayload(c.data, off, len)

  end packBinary

end RowPacker
