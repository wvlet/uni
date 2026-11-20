package wvlet.uni.parquet

import wvlet.uni.parquet.format.*
import wvlet.uni.parquet.io.ByteReader

import java.time.Instant
import java.time.LocalDate

/**
  * A predicate checked against row group statistics. Row groups whose min, max, and null counts
  * show that no row can match are skipped; rows inside the row groups that are read are returned
  * unchanged, so filter them again in your code.
  *
  * Only top-level, non-repeated columns are checked. A column without statistics, or with a type
  * the check does not understand (for example DECIMAL), never causes a row group to be skipped.
  *
  * {{{
  * import wvlet.uni.parquet.RowGroupFilter.*
  * val f = col("ts") >= Instant.parse("2026-01-01T00:00:00Z") && col("country") === "JP"
  * }}}
  */
enum RowGroupFilter:
  case Compare(column: String, op: RowGroupFilter.Op, value: Any)
  case IsNull(column: String)
  case IsNotNull(column: String)
  case And(left: RowGroupFilter, right: RowGroupFilter)
  case Or(left: RowGroupFilter, right: RowGroupFilter)

  def &&(other: RowGroupFilter): RowGroupFilter = And(this, other)
  def ||(other: RowGroupFilter): RowGroupFilter = Or(this, other)

object RowGroupFilter:
  enum Op:
    case Eq,
      Lt,
      LtEq,
      Gt,
      GtEq

  /** Builds comparisons: `col("x") > 10` */
  final class Column(name: String):
    def ===(v: Any): RowGroupFilter = Compare(name, Op.Eq, v)
    def <(v: Any): RowGroupFilter   = Compare(name, Op.Lt, v)
    def <=(v: Any): RowGroupFilter  = Compare(name, Op.LtEq, v)
    def >(v: Any): RowGroupFilter   = Compare(name, Op.Gt, v)
    def >=(v: Any): RowGroupFilter  = Compare(name, Op.GtEq, v)
    def isNull: RowGroupFilter      = IsNull(name)
    def isNotNull: RowGroupFilter   = IsNotNull(name)

  def col(name: String): Column = Column(name)

  /** False only when the statistics prove that no row in the row group matches */
  private[parquet] def mayMatch(
      f: RowGroupFilter,
      schema: ParquetSchema,
      rg: RowGroupMeta
  ): Boolean =
    f match
      case And(a, b) =>
        mayMatch(a, schema, rg) && mayMatch(b, schema, rg)
      case Or(a, b) =>
        mayMatch(a, schema, rg) || mayMatch(b, schema, rg)
      case IsNull(c) =>
        stats(c, schema, rg).forall { case (_, s) =>
          !s.nullCount.contains(0L)
        }
      case IsNotNull(c) =>
        stats(c, schema, rg).forall { case (_, s) =>
          !s.nullCount.contains(rg.numRows)
        }
      case Compare(c, op, v) =>
        stats(c, schema, rg).forall { case (p, s) =>
          compareMayMatch(p, s, op, v, rg.numRows)
        }

  private def stats(
      column: String,
      schema: ParquetSchema,
      rg: RowGroupMeta
  ): Option[(ParquetField.Primitive, Statistics)] =
    schema.field(column) match
      case Some(p: ParquetField.Primitive) if !p.isRepeated && p.columnIndex < rg.columns.size =>
        rg.columns(p.columnIndex).statistics.map(p -> _)
      case _ =>
        None

  private def compareMayMatch(
      p: ParquetField.Primitive,
      s: Statistics,
      op: Op,
      v: Any,
      numRows: Long
  ): Boolean =
    if s.nullCount.contains(numRows) then
      // Every value is null, so no comparison matches
      false
    else
      (s.min, s.max, ordering(p, s)) match
        case (Some(minB), Some(maxB), Some(ord)) =>
          ord.key(v) match
            case Some(target) =>
              val min = ord.decode(minB)
              val max = ord.decode(maxB)
              if min.isEmpty || max.isEmpty then
                true
              else
                val lo = ord.cmp(target, min.get)
                val hi = ord.cmp(target, max.get)
                op match
                  case Op.Eq =>
                    lo >= 0 && hi <= 0
                  case Op.Lt =>
                    ord.cmp(min.get, target) < 0
                  case Op.LtEq =>
                    ord.cmp(min.get, target) <= 0
                  case Op.Gt =>
                    ord.cmp(max.get, target) > 0
                  case Op.GtEq =>
                    ord.cmp(max.get, target) >= 0
            case None =>
              true
        case _ =>
          true

  /** How to decode statistics of a column and convert filter values to the same domain */
  private trait Ordering:
    def decode(b: Array[Byte]): Option[Any]
    def key(v: Any): Option[Any]
    def cmp(a: Any, b: Any): Int

  private object LongOrdering:
    def apply(decodeF: Array[Byte] => Option[Long], keyF: PartialFunction[Any, Long]): Ordering =
      new Ordering:
        def decode(b: Array[Byte]): Option[Any] = decodeF(b)
        def key(v: Any): Option[Any]            = keyF.lift(v)
        def cmp(a: Any, b: Any): Int            = java
          .lang
          .Long
          .compare(a.asInstanceOf[Long], b.asInstanceOf[Long])

  private val integral: PartialFunction[Any, Long] = {
    case i: Int =>
      i.toLong
    case l: Long =>
      l
    case s: Short =>
      s.toLong
    case b: Byte =>
      b.toLong
  }

  private def int32(b: Array[Byte]): Option[Long] =
    if b.length == 4 then
      Some(ByteReader.intLE(b, 0).toLong)
    else
      None

  private def int64(b: Array[Byte]): Option[Long] =
    if b.length == 8 then
      Some(ByteReader.longLE(b, 0))
    else
      None

  private def ordering(p: ParquetField.Primitive, s: Statistics): Option[Ordering] =
    (p.physicalType, p.logicalType) match
      case (PhysicalType.INT32, None | Some(LogicalType.Integer(_, true))) =>
        Some(LongOrdering(int32, integral))
      case (PhysicalType.INT32, Some(LogicalType.Date)) =>
        Some(
          LongOrdering(
            int32,
            { case d: LocalDate =>
              d.toEpochDay
            }
          )
        )
      case (PhysicalType.INT64, None | Some(LogicalType.Integer(_, true))) =>
        Some(LongOrdering(int64, integral))
      case (PhysicalType.INT64, Some(LogicalType.Timestamp(_, unit))) =>
        Some(
          LongOrdering(
            int64,
            { case t: Instant =>
              unit match
                case TimeUnit.MILLIS =>
                  t.toEpochMilli
                case TimeUnit.MICROS =>
                  Math.addExact(Math.multiplyExact(t.getEpochSecond, 1000000L), t.getNano / 1000L)
                case TimeUnit.NANOS =>
                  Math.addExact(Math.multiplyExact(t.getEpochSecond, 1000000000L), t.getNano.toLong)
            }
          )
        )
      case (PhysicalType.FLOAT | PhysicalType.DOUBLE, None) =>
        val isFloat = p.physicalType == PhysicalType.FLOAT
        Some(
          new Ordering:
            def decode(b: Array[Byte]): Option[Any] =
              val d =
                if isFloat && b.length == 4 then
                  Some(java.lang.Float.intBitsToFloat(ByteReader.intLE(b, 0)).toDouble)
                else if !isFloat && b.length == 8 then
                  Some(java.lang.Double.longBitsToDouble(ByteReader.longLE(b, 0)))
                else
                  None
              // NaN statistics say nothing about the order
              d.filterNot(_.isNaN)
            def key(v: Any): Option[Any] =
              v match
                case d: Double =>
                  Some(d).filterNot(_.isNaN)
                case f: Float =>
                  Some(f.toDouble).filterNot(_.isNaN)
                case x if integral.isDefinedAt(x) =>
                  Some(integral(x).toDouble)
                case _ =>
                  None
            def cmp(a: Any, b: Any): Int = java
              .lang
              .Double
              .compare(a.asInstanceOf[Double], b.asInstanceOf[Double])
        )
      case (PhysicalType.BYTE_ARRAY, Some(LogicalType.String | LogicalType.Enum))
          if !s.isLegacyMinMax =>
        Some(
          new Ordering:
            def decode(b: Array[Byte]): Option[Any] = Some(b)
            def key(v: Any): Option[Any]            =
              v match
                case str: String =>
                  Some(str.getBytes("UTF-8"))
                case _ =>
                  None
            def cmp(a: Any, b: Any): Int =
              // Unsigned lexicographic order, which matches UTF-8 code point order
              val x = a.asInstanceOf[Array[Byte]]
              val y = b.asInstanceOf[Array[Byte]]
              val n = math.min(x.length, y.length)
              var i = 0
              var r = 0
              while r == 0 && i < n do
                r = Integer.compare(x(i) & 0xff, y(i) & 0xff)
                i += 1
              if r != 0 then
                r
              else
                Integer.compare(x.length, y.length)
        )
      case (PhysicalType.BOOLEAN, None) =>
        Some(
          new Ordering:
            def decode(b: Array[Byte]): Option[Any] =
              if b.length == 1 then
                Some((b(0) & 1).toLong)
              else
                None
            def key(v: Any): Option[Any] =
              v match
                case x: Boolean =>
                  Some(
                    if x then
                      1L
                    else
                      0L
                  )
                case _ =>
                  None
            def cmp(a: Any, b: Any): Int = java
              .lang
              .Long
              .compare(a.asInstanceOf[Long], b.asInstanceOf[Long])
        )
      case _ =>
        None

end RowGroupFilter
