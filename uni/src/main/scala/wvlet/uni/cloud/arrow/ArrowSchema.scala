package wvlet.uni.cloud.arrow

import wvlet.uni.cloud.parquet.ParquetField
import wvlet.uni.cloud.parquet.ParquetSchema
import wvlet.uni.cloud.parquet.column.Assembly
import wvlet.uni.cloud.parquet.format.LogicalType
import wvlet.uni.cloud.parquet.format.PhysicalType
import wvlet.uni.cloud.parquet.format.Repetition
import wvlet.uni.cloud.parquet.format.TimeUnit

/** A time unit of Arrow TIME, TIMESTAMP, and DURATION types */
enum ArrowTimeUnit:
  case SECOND,
    MILLISECOND,
    MICROSECOND,
    NANOSECOND

/** The Arrow types [[ArrowWriter]] writes */
enum ArrowType:
  /** A column with no values: every row is null, and no buffers are written */
  case Null
  case Bool
  case Int(bitWidth: scala.Int, isSigned: Boolean)

  /** IEEE 754 floats of 16, 32, or 64 bits */
  case FloatingPoint(bitWidth: scala.Int)
  case Utf8
  case Binary
  case FixedSizeBinary(byteWidth: scala.Int)

  /** Days since the epoch, in 32 bits */
  case Date32

  /** A time of day: 32 bits for SECOND and MILLISECOND, 64 bits for MICROSECOND and NANOSECOND */
  case Time(unit: ArrowTimeUnit)

  /**
    * Time since the epoch. With a time zone, values are UTC instants shown in that zone; without
    * one, they are wall-clock times with no zone.
    */
  case Timestamp(unit: ArrowTimeUnit, timezone: Option[String])

  /** Months, days, and nanoseconds, each counted separately */
  case IntervalMonthDayNano

  /** A two's complement unscaled value of 128 or 256 bits */
  case Decimal(precision: scala.Int, scale: scala.Int, bitWidth: scala.Int)
  case List
  case Struct

  /** A list of `entries` structs, each with a non-null `key` and a `value` */
  case Map(keysSorted: Boolean)

  override def toString: String =
    this match
      case Null =>
        "null"
      case Bool =>
        "bool"
      case Int(w, signed) =>
        s"${
            if signed then
              ""
            else
              "u"
          }int${w}"
      case FloatingPoint(w) =>
        s"float${w}"
      case Utf8 =>
        "utf8"
      case Binary =>
        "binary"
      case FixedSizeBinary(w) =>
        s"fixed_size_binary(${w})"
      case Date32 =>
        "date32"
      case Time(u) =>
        val w =
          if u == ArrowTimeUnit.SECOND || u == ArrowTimeUnit.MILLISECOND then
            32
          else
            64
        s"time${w}(${u.toString.toLowerCase})"
      case Timestamp(u, tz) =>
        s"timestamp(${u.toString.toLowerCase}${tz.map(z => s", ${z}").getOrElse("")})"
      case IntervalMonthDayNano =>
        "interval(month_day_nano)"
      case Decimal(p, s, w) =>
        s"decimal${w}(${p}, ${s})"
      case List =>
        "list"
      case Struct =>
        "struct"
      case Map(_) =>
        "map"

end ArrowType

/**
  * A field of an [[ArrowSchema]]. Lists have one child (the element), structs one per member, and
  * maps one non-null `entries` struct of `key` and `value`. `metadata` holds the field's custom
  * metadata, such as `ARROW:extension:name` for extension types.
  */
case class ArrowField(
    name: String,
    tpe: ArrowType,
    nullable: Boolean,
    children: IndexedSeq[ArrowField] = IndexedSeq.empty,
    metadata: Map[String, String] = Map.empty
):
  override def toString: String =
    val sb = StringBuilder()
    show(sb, 0)
    sb.result()

  private[arrow] def show(sb: StringBuilder, indent: Int): Unit =
    sb.append("  " * indent)
    sb.append(s"${name}: ${tpe}")
    if !nullable then
      sb.append(" not null")
    metadata.get("ARROW:extension:name").foreach(e => sb.append(s" <${e}>"))
    children.foreach { c =>
      sb.append('\n')
      c.show(sb, indent + 1)
    }

/** The schema of an Arrow IPC stream or file */
case class ArrowSchema(fields: IndexedSeq[ArrowField], metadata: Map[String, String] = Map.empty):
  def field(name: String): Option[ArrowField] = fields.find(_.name == name)

  override def toString: String =
    val sb = StringBuilder("schema {")
    fields.foreach { f =>
      sb.append('\n')
      f.show(sb, 1)
    }
    sb.append("\n}")
    sb.result()

object ArrowSchema:
  /**
    * The Arrow schema [[ArrowWriter]] writes for a Parquet schema.
    *
    * | Parquet column                                   | Arrow type                                 |
    * |:-------------------------------------------------|:-------------------------------------------|
    * | BOOLEAN                                          | bool                                       |
    * | INT32, INT64, and INT(8/16/32/64, signed or not) | int8 to int64, uint8 to uint64             |
    * | FLOAT, DOUBLE, FLOAT16                           | float32, float64, float16                  |
    * | STRING, ENUM, JSON                               | utf8                                       |
    * | BYTE_ARRAY, BSON, GEOMETRY, GEOGRAPHY            | binary (WKB for the geo types)             |
    * | FIXED_LEN_BYTE_ARRAY(n)                          | fixed_size_binary(n)                       |
    * | UUID                                             | fixed_size_binary(16) as `arrow.uuid`      |
    * | DATE                                             | date32                                     |
    * | TIME(MILLIS), TIME(MICROS/NANOS)                 | time32(ms), time64(us/ns)                  |
    * | TIMESTAMP(unit), UTC-adjusted                    | timestamp(unit, "UTC")                     |
    * | TIMESTAMP(unit), not UTC-adjusted                | timestamp(unit) with no time zone          |
    * | INT96 (legacy timestamps)                        | timestamp(ns, "UTC")                       |
    * | INTERVAL                                         | interval(month_day_nano)                   |
    * | DECIMAL(p, s)                                    | decimal128(p, s), or decimal256 above p 38 |
    * | UNKNOWN                                          | null                                       |
    * | LIST, repeated fields                            | list                                       |
    * | MAP                                              | map                                        |
    * | other groups                                     | struct                                     |
    *
    * OPTIONAL fields are nullable. VARIANT groups and logical types this mapping does not know
    * throw [[ArrowException]] naming the column.
    *
    * @param binaryAsString
    *   write BYTE_ARRAY columns without a logical type as utf8, for files from writers that store
    *   strings unannotated, as `ParquetReadOptions.withBinaryAsString` does for reads
    */
  def fromParquet(schema: ParquetSchema, binaryAsString: Boolean = false): ArrowSchema =
    ArrowSchema(schema.fields.map(f => ArrowPlan.of(f, binaryAsString).arrow))

end ArrowSchema

/** The error for a schema or batch the Arrow writer cannot write */
class ArrowException(message: String, cause: Throwable = null)
    extends RuntimeException(message, cause)

/**
  * How a Parquet field's vectors become Arrow buffers: the Arrow field, plus the conversion for a
  * leaf, or the child plans of a list, map, or struct.
  */
private[arrow] sealed abstract class ArrowPlan(val parquet: ParquetField, val arrow: ArrowField)

private[arrow] object ArrowPlan:
  import ArrowType as T

  /** How leaf values convert */
  enum Leaf:
    case Null
    case Bool
    case Float
    case Double

    /** IntColumn values written in `bytes` bytes, checked to fit `bits` and the sign */
    case FromInt(bytes: scala.Int, signed: Boolean)

    /** LongColumn values written as is */
    case FromLong

    /** BinaryColumn values with int32 offsets */
    case VarBinary

    /** BinaryColumn values of `width` bytes each, copied as is */
    case Fixed(width: scala.Int)

    /** INT96 (nanoseconds of day, Julian day) to int64 nanoseconds since the epoch */
    case Int96

    /** Parquet INTERVAL (months, days, millis as uint32) to month_day_nano */
    case Interval

    /** Unscaled decimals from IntColumn, LongColumn, or big-endian binary, into `bytes` */
    case DecimalFromInt(bytes: scala.Int)
    case DecimalFromLong(bytes: scala.Int)
    case DecimalFromBinary(bytes: scala.Int)

  end Leaf

  final class LeafPlan(p: ParquetField.Primitive, a: ArrowField, val leaf: Leaf)
      extends ArrowPlan(p, a)

  /** A list (a ListColumn) whose element vector `element` describes */
  final class ListPlan(f: ParquetField, a: ArrowField, val element: ArrowPlan)
      extends ArrowPlan(f, a)

  final class MapPlan(
      g: ParquetField.Group,
      a: ArrowField,
      val key: ArrowPlan,
      val value: ArrowPlan
  ) extends ArrowPlan(g, a)

  final class StructPlan(g: ParquetField.Group, a: ArrowField, val children: IndexedSeq[ArrowPlan])
      extends ArrowPlan(g, a)

  private def fail(f: ParquetField, msg: String): Nothing =
    throw ArrowException(s"Cannot write column ${f.pathString} as Arrow: ${msg}")

  def of(f: ParquetField, binaryAsString: Boolean): ArrowPlan =
    val nullable = f.repetition == Repetition.OPTIONAL
    f match
      case p: ParquetField.Primitive if p.isRepeated =>
        // An unannotated repeated leaf: a list of non-null values
        val elem = leaf(p, p.name, nullable = false, binaryAsString)
        ListPlan(p, ArrowField(p.name, T.List, nullable = false, IndexedSeq(elem.arrow)), elem)
      case p: ParquetField.Primitive =>
        leaf(p, p.name, nullable, binaryAsString)
      case g: ParquetField.Group if g.isRepeated =>
        // An unannotated repeated group: a list of non-null structs
        val elem = struct(g, nullable = false, binaryAsString)
        ListPlan(g, ArrowField(g.name, T.List, nullable = false, IndexedSeq(elem.arrow)), elem)
      case g: ParquetField.Group if g.logicalType.contains(LogicalType.Variant) =>
        fail(g, "VARIANT is not supported")
      case g: ParquetField.Group if Assembly.isList(g) =>
        val r    = g.children(0)
        val elem =
          if Assembly.isThreeLevelList(g, r) then
            val e = r.asInstanceOf[ParquetField.Group].children(0)
            of(e, binaryAsString)
          else
            // Legacy 2-level lists: the repeated field is the (non-null) element
            r match
              case rp: ParquetField.Primitive =>
                leaf(rp, rp.name, nullable = false, binaryAsString)
              case rg: ParquetField.Group =>
                struct(rg, nullable = false, binaryAsString)
        ListPlan(g, ArrowField(g.name, T.List, nullable, IndexedSeq(elem.arrow)), elem)
      case g: ParquetField.Group if Assembly.isMap(g) =>
        val kv       = g.children(0).asInstanceOf[ParquetField.Group]
        val key      = of(kv.children(0), binaryAsString)
        val value    = of(kv.children(1), binaryAsString)
        val keyField = key.arrow.copy(name = "key", nullable = false)
        val entries  = ArrowField(
          "entries",
          T.Struct,
          nullable = false,
          IndexedSeq(keyField, value.arrow.copy(name = "value"))
        )
        MapPlan(g, ArrowField(g.name, T.Map(false), nullable, IndexedSeq(entries)), key, value)
      case g: ParquetField.Group =>
        struct(g, nullable, binaryAsString)
    end match

  end of

  private def struct(
      g: ParquetField.Group,
      nullable: Boolean,
      binaryAsString: Boolean
  ): StructPlan =
    val children = g.children.map(of(_, binaryAsString))
    StructPlan(g, ArrowField(g.name, T.Struct, nullable, children.map(_.arrow)), children)

  private def unit(u: TimeUnit): ArrowTimeUnit =
    u match
      case TimeUnit.MILLIS =>
        ArrowTimeUnit.MILLISECOND
      case TimeUnit.MICROS =>
        ArrowTimeUnit.MICROSECOND
      case TimeUnit.NANOS =>
        ArrowTimeUnit.NANOSECOND

  private def leaf(
      p: ParquetField.Primitive,
      name: String,
      nullable: Boolean,
      binaryAsString: Boolean
  ): LeafPlan =
    import PhysicalType.*
    def plan(tpe: ArrowType, l: Leaf, metadata: Map[String, String] = Map.empty): LeafPlan =
      LeafPlan(p, ArrowField(name, tpe, nullable, IndexedSeq.empty, metadata), l)
    def physicalMismatch(lt: LogicalType): Nothing = fail(
      p,
      s"${lt} on a ${p.physicalType} column is not supported"
    )
    p.logicalType match
      case None =>
        p.physicalType match
          case BOOLEAN =>
            plan(T.Bool, Leaf.Bool)
          case INT32 =>
            plan(T.Int(32, true), Leaf.FromInt(4, true))
          case INT64 =>
            plan(T.Int(64, true), Leaf.FromLong)
          case FLOAT =>
            plan(T.FloatingPoint(32), Leaf.Float)
          case DOUBLE =>
            plan(T.FloatingPoint(64), Leaf.Double)
          case BYTE_ARRAY =>
            val tpe =
              if binaryAsString then
                T.Utf8
              else
                T.Binary
            plan(tpe, Leaf.VarBinary)
          case FIXED_LEN_BYTE_ARRAY =>
            plan(T.FixedSizeBinary(p.typeLength), Leaf.Fixed(p.typeLength))
          case INT96 =>
            plan(T.Timestamp(ArrowTimeUnit.NANOSECOND, Some("UTC")), Leaf.Int96)
      case Some(lt) =>
        lt match
          case LogicalType.String | LogicalType.Enum | LogicalType.Json =>
            if p.physicalType != BYTE_ARRAY then
              physicalMismatch(lt)
            plan(T.Utf8, Leaf.VarBinary)
          case LogicalType.Bson | LogicalType.Geometry(_) | LogicalType.Geography(_, _) =>
            if p.physicalType != BYTE_ARRAY then
              physicalMismatch(lt)
            plan(T.Binary, Leaf.VarBinary)
          case LogicalType.Integer(bits, signed) =>
            p.physicalType match
              case INT32 if bits <= 32 =>
                plan(T.Int(bits, signed), Leaf.FromInt(bits / 8, signed))
              case INT64 if bits == 64 =>
                plan(T.Int(64, signed), Leaf.FromLong)
              case _ =>
                physicalMismatch(lt)
          case LogicalType.Date =>
            if p.physicalType != INT32 then
              physicalMismatch(lt)
            plan(T.Date32, Leaf.FromInt(4, true))
          case LogicalType.Time(_, u) =>
            p.physicalType match
              case INT32 if u == TimeUnit.MILLIS =>
                plan(T.Time(ArrowTimeUnit.MILLISECOND), Leaf.FromInt(4, true))
              case INT64 if u != TimeUnit.MILLIS =>
                plan(T.Time(unit(u)), Leaf.FromLong)
              case _ =>
                physicalMismatch(lt)
          case LogicalType.Timestamp(utc, u) =>
            if p.physicalType != INT64 then
              physicalMismatch(lt)
            val tz =
              if utc then
                Some("UTC")
              else
                None
            plan(T.Timestamp(unit(u), tz), Leaf.FromLong)
          case LogicalType.Decimal(scale, precision) =>
            if precision < 1 || precision > 76 then
              fail(p, s"DECIMAL precision ${precision} is over decimal256's 76")
            val bits =
              if precision <= 38 then
                128
              else
                256
            val tpe   = T.Decimal(precision, scale, bits)
            val bytes = bits / 8
            p.physicalType match
              case INT32 =>
                plan(tpe, Leaf.DecimalFromInt(bytes))
              case INT64 =>
                plan(tpe, Leaf.DecimalFromLong(bytes))
              case BYTE_ARRAY | FIXED_LEN_BYTE_ARRAY =>
                plan(tpe, Leaf.DecimalFromBinary(bytes))
              case _ =>
                physicalMismatch(lt)
          case LogicalType.Uuid =>
            if p.physicalType != FIXED_LEN_BYTE_ARRAY || p.typeLength != 16 then
              physicalMismatch(lt)
            plan(
              T.FixedSizeBinary(16),
              Leaf.Fixed(16),
              Map("ARROW:extension:name" -> "arrow.uuid", "ARROW:extension:metadata" -> "")
            )
          case LogicalType.Float16 =>
            if p.physicalType != FIXED_LEN_BYTE_ARRAY || p.typeLength != 2 then
              physicalMismatch(lt)
            plan(T.FloatingPoint(16), Leaf.Fixed(2))
          case LogicalType.Interval =>
            if p.physicalType != FIXED_LEN_BYTE_ARRAY || p.typeLength != 12 then
              physicalMismatch(lt)
            plan(T.IntervalMonthDayNano, Leaf.Interval)
          case LogicalType.Unknown =>
            // The null type has no validity bitmap, so it is always nullable
            LeafPlan(p, ArrowField(name, T.Null, nullable = true), Leaf.Null)
          case other =>
            fail(p, s"logical type ${other} is not supported")
    end match

  end leaf

end ArrowPlan
