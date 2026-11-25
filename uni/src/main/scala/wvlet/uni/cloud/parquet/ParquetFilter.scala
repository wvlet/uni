package wvlet.uni.cloud.parquet

import wvlet.uni.io.codec.Bytes
import wvlet.uni.cloud.parquet.ColumnVector.*
import wvlet.uni.cloud.parquet.format.*
import wvlet.uni.cloud.parquet.io.ByteReader

import java.time.Instant
import java.time.LocalDate

/**
  * A predicate on columns, used two ways:
  *
  *   - [[ParquetReadOptions.withFilter]] returns exactly the rows that match. It also skips files
  *     by partition values and row groups by statistics.
  *   - [[ParquetReadOptions.withRowGroupFilter]] only skips row groups (and dataset files) that
  *     can't match. Rows inside the row groups that are read come back unchanged.
  *
  * Comparisons follow SQL: a comparison with a null value is unknown, and a row is kept only when
  * the whole predicate is true. Floating-point values follow IEEE 754, so NaN matches no
  * comparison, and -0.0 equals 0.0. A dotted name such as `address.city` names a field inside
  * structs; a top-level column with that exact name wins.
  *
  * {{{
  * import wvlet.uni.cloud.parquet.ParquetFilter.*
  * val f = col("ts") >= Instant.parse("2026-01-01T00:00:00Z") && col("country") === "JP"
  * // Set membership on an integer or DATE column, such as a join's build keys
  * val g = col("store_id").isIn(3L, 4L, 10L)
  * }}}
  */
enum ParquetFilter:
  case Compare(column: String, op: ParquetFilter.Op, value: Any)
  case IsNull(column: String)
  case IsNotNull(column: String)

  /**
    * The rows whose value of an integer or DATE column (as epoch days) the set accepts. A null
    * value is unknown, so its row isn't kept.
    */
  case In(column: String, values: ParquetFilter.IntegerSet)
  case And(left: ParquetFilter, right: ParquetFilter)
  case Or(left: ParquetFilter, right: ParquetFilter)

  def &&(other: ParquetFilter): ParquetFilter = And(this, other)
  def ||(other: ParquetFilter): ParquetFilter = Or(this, other)

  /** The column names this filter refers to, in order of appearance */
  def columnNames: Seq[String] =
    this match
      case Compare(c, _, _) =>
        Seq(c)
      case IsNull(c) =>
        Seq(c)
      case IsNotNull(c) =>
        Seq(c)
      case In(c, _) =>
        Seq(c)
      case And(a, b) =>
        (a.columnNames ++ b.columnNames).distinct
      case Or(a, b) =>
        (a.columnNames ++ b.columnNames).distinct

end ParquetFilter

object ParquetFilter:
  enum Op:
    case Eq,
      Lt,
      LtEq,
      Gt,
      GtEq

  /** Builds comparisons: `col("x") > 10` */
  final class Column(name: String):
    def ===(v: Any): ParquetFilter = Compare(name, Op.Eq, v)
    def <(v: Any): ParquetFilter   = Compare(name, Op.Lt, v)
    def <=(v: Any): ParquetFilter  = Compare(name, Op.LtEq, v)
    def >(v: Any): ParquetFilter   = Compare(name, Op.Gt, v)
    def >=(v: Any): ParquetFilter  = Compare(name, Op.GtEq, v)
    def isNull: ParquetFilter      = IsNull(name)
    def isNotNull: ParquetFilter   = IsNotNull(name)

    /** The value is one of these integers (epoch days for a DATE column) */
    def isIn(values: Long*): ParquetFilter = In(name, IntegerSet.of(values))

    /** The value is one of these integers (epoch days for a DATE column) */
    def isIn(values: Iterable[Long]): ParquetFilter = In(name, IntegerSet.of(values))

    /** The set accepts the value, such as a join's bitmap or Bloom filter over its build keys */
    def isIn(set: IntegerSet): ParquetFilter = In(name, set)

  def col(name: String): Column = Column(name)

  /**
    * A set of Long values for [[ParquetFilter.In]], tested against an INT32, INT64, or DATE (epoch
    * days) column. Implement it to hand over a predicate without copying it, such as a hash join's
    * bitmap or Bloom filter over its build keys. A set may accept values it doesn't hold (a Bloom
    * filter's false positives); a filter then keeps the rows the set accepts.
    *
    * Scans call `contains` from several threads at once, so it must not change state.
    */
  trait IntegerSet:
    /** Whether the set accepts v. Called once per row, so keep it cheap. */
    def contains(v: Long): Boolean

    /** No accepted value is below this. Long.MaxValue with max = Long.MinValue for an empty set. */
    def min: Long

    /** No accepted value is above this */
    def max: Long

    /**
      * False only when no value in [lo, hi] is accepted, which skips a row group whose statistics
      * lie in that range. The default checks that [lo, hi] overlaps [min, max].
      */
    def mayIntersect(lo: Long, hi: Long): Boolean = lo <= max && hi >= min

  object IntegerSet:
    /** An exact set of these values: a bitmap when they are dense, a hash table otherwise */
    def of(values: Iterable[Long]): IntegerSet = SortedIntegerSet(values.toArray.sorted.distinct)

    /** Values spanning up to this many bits per value use a bitmap */
    private[parquet] final val BitmapBitsPerValue = 64L

    /** Spans up to this many values always use a bitmap (8 KB) */
    private[parquet] final val SmallBitmapSpan = 64L * 1024

  /** An exact set over sorted distinct values */
  private[parquet] final class SortedIntegerSet(sorted: Array[Long]) extends IntegerSet:
    val min: Long =
      if sorted.isEmpty then
        Long.MaxValue
      else
        sorted(0)

    val max: Long =
      if sorted.isEmpty then
        Long.MinValue
      else
        sorted(sorted.length - 1)

    // A bitmap over [min, max] when it is small enough; a linear-probing hash table otherwise
    private val bitmap: Array[Long] =
      if sorted.isEmpty then
        null
      else
        // Negative when the span overflows a Long; such sets use the hash table
        val span = max - min
        if span >= 0 &&
          span < math.max(IntegerSet.SmallBitmapSpan, IntegerSet.BitmapBitsPerValue * sorted.length)
        then
          val b = new Array[Long]((span >>> 6).toInt + 1)
          var i = 0
          while i < sorted.length do
            val d = sorted(i) - min
            b((d >>> 6).toInt) |= 1L << d
            i += 1
          b
        else
          null

    private val (table, mask, hasZero) =
      if bitmap != null || sorted.isEmpty then
        (null: Array[Long], 0, false)
      else
        // 0 marks an empty slot, so the value 0 is kept aside
        val cap = Integer.highestOneBit(math.max(4, sorted.length * 2) * 2 - 1)
        val t   = new Array[Long](cap)
        var z   = false
        var i   = 0
        while i < sorted.length do
          val v = sorted(i)
          if v == 0L then
            z = true
          else
            var h = SortedIntegerSet.slot(v, cap - 1)
            while t(h) != 0L do
              h = (h + 1) & (cap - 1)
            t(h) = v
          i += 1
        (t, cap - 1, z)

    def contains(v: Long): Boolean =
      if bitmap != null then
        v >= min && v <= max && {
          val d = v - min
          (bitmap((d >>> 6).toInt) & (1L << d)) != 0
        }
      else if table == null then
        false
      else if v == 0L then
        hasZero
      else
        var h     = SortedIntegerSet.slot(v, mask)
        var found = false
        var e     = table(h)
        while !found && e != 0L do
          if e == v then
            found = true
          else
            h = (h + 1) & mask
            e = table(h)
        found

    override def mayIntersect(lo: Long, hi: Long): Boolean =
      // The first value at or above lo, if any, must be at most hi
      var l = 0
      var r = sorted.length
      while l < r do
        val m = (l + r) >>> 1
        if sorted(m) < lo then
          l = m + 1
        else
          r = m
      l < sorted.length && sorted(l) <= hi

    override def toString: String =
      if sorted.length <= 8 then
        sorted.mkString("{", ", ", "}")
      else
        s"{${sorted.take(8).mkString(", ")}, ... (${sorted.length} values)}"

  end SortedIntegerSet

  private[parquet] object SortedIntegerSet:
    inline def slot(v: Long, mask: Int): Int =
      val h = v * 0x9e3779b97f4a7c15L
      (h ^ (h >>> 32)).toInt & mask

  private[parquet] def both(a: Option[ParquetFilter], b: Option[ParquetFilter]) =
    (a, b) match
      case (Some(x), Some(y)) =>
        Some(And(x, y))
      case _ =>
        a.orElse(b)

  // ---- Columns ----

  /**
    * The fields from the top-level column down to the one a filter names: an exact top-level name
    * first, then a dotted path through groups
    */
  private[parquet] def resolve(schema: ParquetSchema, name: String): Option[Seq[ParquetField]] =
    schema.field(name) match
      case Some(f) =>
        Some(Seq(f))
      case None if name.contains('.') =>
        val segments = name.split('.').toSeq
        schema
          .field(segments.head)
          .flatMap { top =>
            segments
              .tail
              .foldLeft(Option(Seq(top))) { (acc, seg) =>
                acc.flatMap { chain =>
                  chain.last match
                    case g: ParquetField.Group =>
                      g.field(seg).map(chain :+ _)
                    case _ =>
                      None
                }
              }
          }
      case None =>
        None

  /** The leaf column a filter names, or why a row filter can't use it */
  private[parquet] def leaf(
      schema: ParquetSchema,
      name: String
  ): Either[String, (Seq[ParquetField], ParquetField.Primitive)] =
    resolve(schema, name) match
      case None =>
        Left(s"No column named ${name}. Columns: ${schema.fields.map(_.name).mkString(", ")}")
      case Some(chain) if chain.exists(_.isRepeated) =>
        Left(s"Column ${name} is a list or inside one, which filters don't support")
      case Some(chain) =>
        chain.last match
          case p: ParquetField.Primitive =>
            Right((chain, p))
          case g: ParquetField.Group =>
            g.logicalType match
              case Some(LogicalType.List | LogicalType.Map) =>
                Left(s"Column ${name} is a list or map, which filters don't support")
              case _ =>
                Left(
                  s"Column ${name} is a group. Name one of its fields, such as ${name}.${g
                      .children
                      .headOption
                      .map(_.name)
                      .getOrElse("x")}"
                )

  // ---- Statistics ----

  /** False only when the statistics prove that no row in the row group matches */
  private[parquet] def mayMatch(
      f: ParquetFilter,
      schema: ParquetSchema,
      rg: RowGroupMeta,
      binaryAsString: Boolean = false
  ): Boolean =
    f match
      case And(a, b) =>
        mayMatch(a, schema, rg, binaryAsString) && mayMatch(b, schema, rg, binaryAsString)
      case Or(a, b) =>
        mayMatch(a, schema, rg, binaryAsString) || mayMatch(b, schema, rg, binaryAsString)
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
          compareMayMatch(p, s, op, v, rg.numRows, binaryAsString)
        }
      case In(c, set) =>
        stats(c, schema, rg).forall { case (p, s) =>
          inMayMatch(p, s, set, rg.numRows)
        }

  private def stats(
      column: String,
      schema: ParquetSchema,
      rg: RowGroupMeta
  ): Option[(ParquetField.Primitive, Statistics)] =
    leaf(schema, column) match
      case Right((_, p)) if p.columnIndex < rg.columns.size =>
        rg.columns(p.columnIndex).statistics.map(p -> _)
      case _ =>
        None

  private def compareMayMatch(
      p: ParquetField.Primitive,
      s: Statistics,
      op: Op,
      v: Any,
      numRows: Long,
      binaryAsString: Boolean
  ): Boolean =
    if s.nullCount.contains(numRows) then
      // Every value is null, so no comparison matches
      false
    else
      val bounds =
        for
          ord <- order(p, binaryAsString)
          if ord.trusts(s)
          key  <- ord.key(v).toOption
          minB <- s.min
          maxB <- s.max
          min  <- ord.decode(minB)
          max  <- ord.decode(maxB)
        yield (ord, key, min, max)
      bounds match
        case Some((ord, key, min, max)) =>
          op match
            case Op.Eq =>
              ord.cmpKey(min, key) <= 0 && ord.cmpKey(max, key) >= 0
            case Op.Lt =>
              ord.cmpKey(min, key) < 0
            case Op.LtEq =>
              ord.cmpKey(min, key) <= 0
            case Op.Gt =>
              ord.cmpKey(max, key) > 0
            case Op.GtEq =>
              ord.cmpKey(max, key) >= 0
        case None =>
          true

  private def inMayMatch(
      p: ParquetField.Primitive,
      s: Statistics,
      set: IntegerSet,
      numRows: Long
  ): Boolean =
    if s.nullCount.contains(numRows) then
      false
    else
      membershipOrder(p) match
        case Right(ord) if ord.trusts(s) =>
          (
            for
              minB <- s.min
              maxB <- s.max
              min  <- ord.decode(minB)
              max  <- ord.decode(maxB)
            yield set.mayIntersect(min.asInstanceOf[Long], max.asInstanceOf[Long])
          ).getOrElse(true)
        case _ =>
          true

  /**
    * The order of a column [[ParquetFilter.In]] can test: INT32 and INT64 integers (unsigned up to
    * 32 bits) and DATE, whose values are all Longs in signed order
    */
  private[parquet] def membershipOrder(p: ParquetField.Primitive): Either[String, IntOrder] =
    order(p, binaryAsString = false) match
      case Some(o: IntOrder) if o.physical != PhysicalType.BOOLEAN && !o.isUnsigned64 =>
        Right(o)
      case _ =>
        Left(
          s"isIn tests INT32, INT64, and DATE columns, and ${p.name} is ${describe(
              p
            )}. Combine === with || instead"
        )

  // ---- Literals and column orders ----

  /** A filter literal converted to a column's domain */
  private[parquet] enum Key:
    /** Integers, DATE (epoch days), and BOOLEAN (0 or 1) */
    case Num(v: Long)

    /**
      * An Instant in a TIMESTAMP column's unit: `floor`, plus `rest` = 1 when the Instant lies just
      * above it, -1 just below it (only when it is beyond the column's range), and 0 when it is
      * exact
      */
    case Time(floor: Long, rest: Int)

    case Real(v: Double)
    case Bytes(v: Array[Byte])

    /** An INT96 timestamp */
    case Moment(v: Instant)

  /**
    * How a column's values compare with each other and with filter literals. Statistics decode to
    * the same domain the literal keys use.
    */
  private[parquet] sealed abstract class ColumnOrder:
    /** The literal in this column's domain, or why it can't be converted without loss */
    def key(v: Any): Either[String, Key]

    /** A PLAIN-encoded statistics value in this column's domain */
    def decode(b: Array[Byte]): Option[Any]

    /** Compares two decoded statistics values */
    def cmp(a: Any, b: Any): Int

    /** Compares a decoded statistics value with a literal key */
    def cmpKey(a: Any, k: Key): Int

    /** Whether min and max were written in the order this column needs */
    def trusts(s: Statistics): Boolean = true

  /** INT32/INT64 integers (signed or unsigned), DATE, and BOOLEAN, compared as Long */
  private[parquet] final class IntOrder(
      val physical: PhysicalType,
      val unsignedBits: Int,
      val isDate: Boolean
  ) extends ColumnOrder:
    def isUnsigned64: Boolean = unsignedBits == 64

    /** An INT32 value as this column's Long */
    inline def ofInt(v: Int): Long =
      if unsignedBits == 32 then
        v.toLong & 0xffffffffL
      else
        v.toLong

    inline def compareLong(a: Long, b: Long): Int =
      if isUnsigned64 then
        java.lang.Long.compareUnsigned(a, b)
      else
        java.lang.Long.compare(a, b)

    def key(v: Any): Either[String, Key] =
      if physical == PhysicalType.BOOLEAN then
        v match
          case b: Boolean =>
            Right(
              Key.Num(
                if b then
                  1L
                else
                  0L
              )
            )
          case _ =>
            Left("a Boolean")
      else if isDate then
        v match
          case d: LocalDate =>
            Right(Key.Num(d.toEpochDay))
          case s: String =>
            try
              Right(Key.Num(LocalDate.parse(s).toEpochDay))
            catch
              case _: java.time.format.DateTimeParseException =>
                Left("a LocalDate or an ISO date string such as 2026-11-20")
          case _ =>
            Left("a LocalDate or an ISO date string such as 2026-11-20")
      else
        val expected =
          if unsignedBits > 0 then
            "a non-negative integer"
          else
            "an integer (Int, Long, Short, Byte, or a string of digits)"
        val asLong: Option[Long] =
          v match
            case i: Int =>
              Some(i.toLong)
            case l: Long =>
              Some(l)
            case s: Short =>
              Some(s.toLong)
            case b: Byte =>
              Some(b.toLong)
            case s: String =>
              s.toLongOption
            case b: BigInt if isUnsigned64 && b >= 0 && b.bitLength <= 64 =>
              Some(b.toLong)
            case b: BigInt if b.isValidLong =>
              Some(b.toLong)
            case _ =>
              None
        asLong match
          case Some(l) if isUnsigned64 && l < 0 && !v.isInstanceOf[BigInt] =>
            Left(expected)
          case Some(_) if isUnsigned64 && v.isInstanceOf[BigInt] && v.asInstanceOf[BigInt] < 0 =>
            Left(expected)
          case Some(l) =>
            Right(Key.Num(l))
          case None =>
            Left(expected)

    end key

    def decode(b: Array[Byte]): Option[Any] =
      physical match
        case PhysicalType.BOOLEAN if b.length == 1 =>
          Some((b(0) & 1).toLong)
        case PhysicalType.INT32 if b.length == 4 =>
          Some(ofInt(Bytes.intLE(b, 0)))
        case PhysicalType.INT64 if b.length == 8 =>
          Some(Bytes.longLE(b, 0))
        case _ =>
          None

    def cmp(a: Any, b: Any): Int = compareLong(a.asInstanceOf[Long], b.asInstanceOf[Long])

    def cmpKey(a: Any, k: Key): Int =
      k match
        case Key.Num(v) =>
          compareLong(a.asInstanceOf[Long], v)
        case _ =>
          throw IllegalStateException(s"Unexpected key ${k}")

    // Old writers ordered unsigned values as signed in the deprecated min/max fields
    override def trusts(s: Statistics): Boolean = unsignedBits == 0 || !s.isLegacyMinMax

  end IntOrder

  /** INT64 TIMESTAMP in any unit, compared exactly with an Instant */
  private[parquet] final class TimeOrder(val unit: TimeUnit) extends ColumnOrder:
    def key(v: Any): Either[String, Key] =
      v match
        case t: Instant =>
          Right(timeKey(t))
        case _ =>
          Left("an Instant")

    private def timeKey(t: Instant): Key =
      val perSec =
        unit match
          case TimeUnit.MILLIS =>
            1000L
          case TimeUnit.MICROS =>
            1000000L
          case TimeUnit.NANOS =>
            1000000000L
      val nanosPerUnit = 1000000000L / perSec
      try
        val floor = Math.addExact(
          Math.multiplyExact(t.getEpochSecond, perSec),
          t.getNano / nanosPerUnit
        )
        Key.Time(
          floor,
          if t.getNano % nanosPerUnit != 0 then
            1
          else
            0
        )
      catch
        case _: ArithmeticException =>
          // Beyond the unit's range: above or below every value
          if t.getEpochSecond > 0 then
            Key.Time(Long.MaxValue, 1)
          else
            Key.Time(Long.MinValue, -1)

    def decode(b: Array[Byte]): Option[Any] =
      if b.length == 8 then
        Some(Bytes.longLE(b, 0))
      else
        None

    def cmp(a: Any, b: Any): Int = java
      .lang
      .Long
      .compare(a.asInstanceOf[Long], b.asInstanceOf[Long])

    def cmpKey(a: Any, k: Key): Int =
      k match
        case Key.Time(floor, rest) =>
          compareTime(a.asInstanceOf[Long], floor, rest)
        case _ =>
          throw IllegalStateException(s"Unexpected key ${k}")

  end TimeOrder

  inline def compareTime(v: Long, floor: Long, rest: Int): Int =
    if v != floor then
      java.lang.Long.compare(v, floor)
    else
      -rest

  /** INT96 timestamps (written by Spark, Hive, and Impala). Their statistics are not trusted. */
  private[parquet] object Int96Order extends ColumnOrder:
    def key(v: Any): Either[String, Key] =
      v match
        case t: Instant =>
          Right(Key.Moment(t))
        case _ =>
          Left("an Instant")

    def decode(b: Array[Byte]): Option[Any] = None
    def cmp(a: Any, b: Any): Int            = 0
    def cmpKey(a: Any, k: Key): Int         = 0
    override def trusts(s: Statistics)      = false

  private final val MaxExactDouble = 1L << 53

  /** FLOAT and DOUBLE, compared as Double */
  private[parquet] final class RealOrder(isFloat: Boolean) extends ColumnOrder:
    def key(v: Any): Either[String, Key] =
      val d: Option[Double] =
        v match
          case d: Double =>
            Some(d)
          case f: Float =>
            Some(f.toDouble)
          case i: Int =>
            Some(i.toDouble)
          case s: Short =>
            Some(s.toDouble)
          case b: Byte =>
            Some(b.toDouble)
          case l: Long if l >= -MaxExactDouble && l <= MaxExactDouble =>
            Some(l.toDouble)
          case _ =>
            None
      d match
        case Some(x) if x.isNaN =>
          Left("a number other than NaN, which never matches a comparison")
        case Some(x) =>
          Right(Key.Real(x + 0.0))
        case None =>
          Left("a Double, a Float, or an integer within ±2^53")

    def decode(b: Array[Byte]): Option[Any] =
      val d =
        if isFloat && b.length == 4 then
          Some(java.lang.Float.intBitsToFloat(Bytes.intLE(b, 0)).toDouble)
        else if !isFloat && b.length == 8 then
          Some(java.lang.Double.longBitsToDouble(Bytes.longLE(b, 0)))
        else
          None
      // NaN statistics say nothing about the order; + 0.0 turns -0.0 into 0.0, since the two
      // compare equal in data
      d.filterNot(_.isNaN).map(_ + 0.0)

    def cmp(a: Any, b: Any): Int = java
      .lang
      .Double
      .compare(a.asInstanceOf[Double], b.asInstanceOf[Double])

    def cmpKey(a: Any, k: Key): Int =
      k match
        case Key.Real(v) =>
          java.lang.Double.compare(a.asInstanceOf[Double], v)
        case _ =>
          throw IllegalStateException(s"Unexpected key ${k}")

  end RealOrder

  /** STRING, ENUM, and untyped binary read as strings, compared as unsigned UTF-8 bytes */
  private[parquet] object BytesOrder extends ColumnOrder:
    def key(v: Any): Either[String, Key] =
      v match
        case s: String =>
          Right(Key.Bytes(s.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
        case _ =>
          Left("a String")

    def decode(b: Array[Byte]): Option[Any] = Some(b)

    def cmp(a: Any, b: Any): Int =
      val x = a.asInstanceOf[Array[Byte]]
      val y = b.asInstanceOf[Array[Byte]]
      compareBytes(x, 0, x.length, y)

    def cmpKey(a: Any, k: Key): Int =
      k match
        case Key.Bytes(v) =>
          val x = a.asInstanceOf[Array[Byte]]
          compareBytes(x, 0, x.length, v)
        case _ =>
          throw IllegalStateException(s"Unexpected key ${k}")

    // Old writers ordered the deprecated min/max as signed bytes
    override def trusts(s: Statistics): Boolean = !s.isLegacyMinMax

  /** Unsigned lexicographic order, which matches UTF-8 code point order */
  private[parquet] def compareBytes(x: Array[Byte], off: Int, len: Int, y: Array[Byte]): Int =
    val n = math.min(len, y.length)
    var i = 0
    var r = 0
    while r == 0 && i < n do
      r = Integer.compare(x(off + i) & 0xff, y(i) & 0xff)
      i += 1
    if r != 0 then
      r
    else
      Integer.compare(len, y.length)

  /** Strings in UTF-8 byte order (code point order), as string columns compare */
  private[parquet] def compareStrings(a: String, b: String): Int =
    val x = a.getBytes(java.nio.charset.StandardCharsets.UTF_8)
    compareBytes(x, 0, x.length, b.getBytes(java.nio.charset.StandardCharsets.UTF_8))

  /** How a column compares, or None for types filters don't understand (such as DECIMAL) */
  private[parquet] def order(
      p: ParquetField.Primitive,
      binaryAsString: Boolean
  ): Option[ColumnOrder] =
    (p.physicalType, p.logicalType) match
      case (PhysicalType.BOOLEAN, None) =>
        Some(IntOrder(PhysicalType.BOOLEAN, 0, isDate = false))
      case (PhysicalType.INT32, None | Some(LogicalType.Integer(_, true))) =>
        Some(IntOrder(PhysicalType.INT32, 0, isDate = false))
      case (PhysicalType.INT32, Some(LogicalType.Integer(bits, false))) =>
        Some(IntOrder(PhysicalType.INT32, bits, isDate = false))
      case (PhysicalType.INT32, Some(LogicalType.Date)) =>
        Some(IntOrder(PhysicalType.INT32, 0, isDate = true))
      case (PhysicalType.INT64, None | Some(LogicalType.Integer(_, true))) =>
        Some(IntOrder(PhysicalType.INT64, 0, isDate = false))
      case (PhysicalType.INT64, Some(LogicalType.Integer(64, false))) =>
        Some(IntOrder(PhysicalType.INT64, 64, isDate = false))
      case (PhysicalType.INT64, Some(LogicalType.Timestamp(_, unit))) =>
        Some(TimeOrder(unit))
      case (PhysicalType.INT96, None) =>
        Some(Int96Order)
      case (PhysicalType.FLOAT, None) =>
        Some(RealOrder(isFloat = true))
      case (PhysicalType.DOUBLE, None) =>
        Some(RealOrder(isFloat = false))
      case (PhysicalType.BYTE_ARRAY, Some(LogicalType.String | LogicalType.Enum)) =>
        Some(BytesOrder)
      case (PhysicalType.BYTE_ARRAY, None) if binaryAsString =>
        Some(BytesOrder)
      case _ =>
        None

  private def describe(p: ParquetField.Primitive): String =
    (p.physicalType.toString +: p.logicalType.map(_.toString).toSeq).mkString(" ")

  // ---- Row evaluation ----

  /**
    * A filter checked against the rows of batches with the given schema. Building it checks every
    * column and literal, so a filter it can't evaluate exactly fails before any row is read.
    */
  private[parquet] final class RowFilter(
      filter: ParquetFilter,
      schema: ParquetSchema,
      binaryAsString: Boolean
  ):
    import RowFilter.*

    private val tree: Node = compile(filter)

    /** The top-level columns the filter reads */
    val topColumns: Seq[String] =
      filter.columnNames.flatMap(n => resolve(schema, n).map(_.head.name)).distinct

    private def compile(f: ParquetFilter): Node =
      f match
        case And(a, b) =>
          Node.AllOf(compile(a), compile(b))
        case Or(a, b) =>
          Node.AnyOf(compile(a), compile(b))
        case IsNull(c) =>
          Node.Leaf(path(c), Test.Null)
        case IsNotNull(c) =>
          Node.Leaf(path(c), Test.NotNull)
        case Compare(c, op, v) =>
          val (chain, p) = leafOf(c)
          if v == null then
            fail(
              s"Column ${c} is compared with null, which never matches. Use col(\"${c}\").isNull"
            )
          val ord = order(p, binaryAsString).getOrElse(
            fail(
              s"Column ${c} is ${describe(p)}, which filters can't compare${
                  if p.physicalType == PhysicalType.BYTE_ARRAY && p.logicalType.isEmpty then
                    ". Read it as strings with withBinaryAsString(true)"
                  else
                    ""
                }. Use withRowGroupFilter, which skips only the row groups it can check"
            )
          )
          val key = ord
            .key(v)
            .fold(
              expected =>
                fail(
                  s"Column ${c} (${describe(p)}) can't be compared with ${v} (${v
                      .getClass
                      .getSimpleName}). Use ${expected}"
                ),
              identity
            )
          Node.Leaf(chain.map(_.name), Test.Cmp(op, ord, key))
        case In(c, set) =>
          val (chain, p) = leafOf(c)
          if set == null then
            fail(s"Column ${c} is tested against a null set")
          val ord = membershipOrder(p).fold(fail, identity)
          Node.Leaf(chain.map(_.name), Test.Member(ord, set))

    private def leafOf(c: String): (Seq[ParquetField], ParquetField.Primitive) = leaf(schema, c)
      .fold(fail, identity)

    /** The path of a column checked for nulls, which may also be a struct, list, or map */
    private def path(c: String): Seq[String] =
      resolve(schema, c) match
        case None =>
          fail(leaf(schema, c).left.getOrElse(s"No column named ${c}"))
        case Some(chain) if chain.exists(_.isRepeated) =>
          fail(s"Column ${c} is inside a list or map, which filters don't support")
        case Some(chain) =>
          chain.map(_.name)

    /** Which rows of the batch match */
    def matches(batch: ColumnBatch): Array[Boolean] =
      val states = eval(tree, batch)
      val out    = new Array[Boolean](batch.numRows)
      var i      = 0
      while i < out.length do
        out(i) = states(i) == T
        i += 1
      out

    /** The numbers of the matching rows, in order */
    def keptRows(batch: ColumnBatch): Array[Int] =
      val m    = matches(batch)
      val rows = new Array[Int](m.count(identity))
      var j    = 0
      var i    = 0
      while i < m.length do
        if m(i) then
          rows(j) = i
          j += 1
        i += 1
      rows

    /** The batch with only the matching rows */
    def apply(batch: ColumnBatch): ColumnBatch =
      if batch.numRows == 0 then
        batch
      else
        val rows = keptRows(batch)
        if rows.length == batch.numRows then
          batch
        else
          ColumnBatch(rows.length, batch.columns.map(ColumnVector.take(_, rows)))

  end RowFilter

  private[parquet] object RowFilter:
    // Three-valued logic states
    final val F: Byte = 0
    final val T: Byte = 1
    final val U: Byte = 2

    // A comparison result for NaN, which satisfies no operator
    private final val Incomparable = Int.MinValue

    enum Test:
      case Null
      case NotNull
      case Cmp(op: Op, order: ColumnOrder, key: Key)
      case Member(order: IntOrder, set: IntegerSet)

    enum Node:
      case Leaf(path: Seq[String], test: Test)
      case AllOf(a: Node, b: Node)
      case AnyOf(a: Node, b: Node)

    private def fail(msg: String): Nothing = throw IllegalArgumentException(msg)

    private def eval(node: Node, batch: ColumnBatch): Array[Byte] =
      node match
        case Node.AllOf(a, b) =>
          val x = eval(a, batch)
          val y = eval(b, batch)
          var i = 0
          while i < x.length do
            x(i) =
              if x(i) == F || y(i) == F then
                F
              else if x(i) == T && y(i) == T then
                T
              else
                U
            i += 1
          x
        case Node.AnyOf(a, b) =>
          val x = eval(a, batch)
          val y = eval(b, batch)
          var i = 0
          while i < x.length do
            x(i) =
              if x(i) == T || y(i) == T then
                T
              else if x(i) == F && y(i) == F then
                F
              else
                U
            i += 1
          x
        case Node.Leaf(path, test) =>
          val (v, nulls) = vectorOf(batch, path)
          val n          = batch.numRows
          val out        = new Array[Byte](n)
          test match
            case Test.Null =>
              var i = 0
              while i < n do
                out(i) =
                  if nulls != null && nulls(i) then
                    T
                  else
                    F
                i += 1
            case Test.NotNull =>
              var i = 0
              while i < n do
                out(i) =
                  if nulls != null && nulls(i) then
                    F
                  else
                    T
                i += 1
            case Test.Cmp(op, ord, key) =>
              val r = compareAll(v, ord, key, path)
              var i = 0
              while i < n do
                out(i) =
                  if nulls != null && nulls(i) then
                    U
                  else
                    val c  = r(i)
                    val ok =
                      c != Incomparable && (
                        op match
                          case Op.Eq =>
                            c == 0
                          case Op.Lt =>
                            c < 0
                          case Op.LtEq =>
                            c <= 0
                          case Op.Gt =>
                            c > 0
                          case Op.GtEq =>
                            c >= 0
                      )
                    if ok then
                      T
                    else
                      F
                i += 1
            case Test.Member(ord, set) =>
              memberAll(v, ord, set, nulls, out, path)
          end match
          out

    /** The vector at a path, with nulls from every struct on the way (null when none) */
    private def vectorOf(batch: ColumnBatch, path: Seq[String]): (ColumnVector, Array[Boolean]) =
      val top = batch
        .columns
        .find(_.name == path.head)
        .getOrElse(throw IllegalStateException(s"Column ${path.head} was not read"))
      var nulls: Array[Boolean]           = null
      def addNulls(v: ColumnVector): Unit =
        val ns = v.nulls
        if ns != null then
          if nulls == null then
            nulls = ns.clone()
          else
            var i = 0
            while i < nulls.length do
              nulls(i) = nulls(i) || ns(i)
              i += 1
      var v = top
      addNulls(v)
      path
        .tail
        .foreach { seg =>
          v =
            v match
              case s: StructColumn =>
                s.child(seg).getOrElse(throw IllegalStateException(s"No field ${seg} in ${s.name}"))
              case other =>
                throw IllegalStateException(s"Column ${other.name} is not a struct")
          addNulls(v)
        }
      (v, nulls)

    end vectorOf

    /** Each row's state for set membership: T when accepted, F when not, U for nulls */
    private def memberAll(
        v: ColumnVector,
        ord: IntOrder,
        set: IntegerSet,
        nulls: Array[Boolean],
        out: Array[Byte],
        path: Seq[String]
    ): Unit =
      val n = out.length
      var i = 0
      v match
        case c: IntColumn =>
          val a = c.values
          if ord.unsignedBits == 32 then
            while i < n do
              out(i) =
                if set.contains(a(i).toLong & 0xffffffffL) then
                  T
                else
                  F
              i += 1
          else
            while i < n do
              out(i) =
                if set.contains(a(i).toLong) then
                  T
                else
                  F
              i += 1
        case c: LongColumn =>
          val a = c.values
          while i < n do
            out(i) =
              if set.contains(a(i)) then
                T
              else
                F
            i += 1
        case _ =>
          throw IllegalStateException(
            s"Can't test ${v.getClass.getSimpleName} at ${path.mkString(".")} for set membership"
          )
      end match
      if nulls != null then
        i = 0
        while i < n do
          if nulls(i) then
            out(i) = U
          i += 1

    end memberAll

    /** Each row's value compared with the key: negative, zero, positive, or Incomparable */
    private def compareAll(v: ColumnVector, ord: ColumnOrder, key: Key, path: Seq[String]) =
      val n = v.size
      val r = new Array[Int](n)
      var i = 0
      (v, ord, key) match
        case (c: IntColumn, o: IntOrder, Key.Num(k)) =>
          while i < n do
            r(i) = o.compareLong(o.ofInt(c.values(i)), k)
            i += 1
        case (c: LongColumn, o: IntOrder, Key.Num(k)) =>
          while i < n do
            r(i) = o.compareLong(c.values(i), k)
            i += 1
        case (c: BooleanColumn, _, Key.Num(k)) =>
          while i < n do
            r(i) = java
              .lang
              .Long
              .compare(
                if c.values(i) then
                  1L
                else
                  0L
                ,
                k
              )
            i += 1
        case (c: LongColumn, _: TimeOrder, Key.Time(floor, rest)) =>
          while i < n do
            r(i) = compareTime(c.values(i), floor, rest)
            i += 1
        case (c: DoubleColumn, _, Key.Real(k)) =>
          while i < n do
            val x = c.values(i)
            r(i) =
              if x.isNaN then
                Incomparable
              else if x < k then
                -1
              else if x > k then
                1
              else
                0
            i += 1
        case (c: FloatColumn, _, Key.Real(k)) =>
          while i < n do
            val x = c.values(i).toDouble
            r(i) =
              if x.isNaN then
                Incomparable
              else if x < k then
                -1
              else if x > k then
                1
              else
                0
            i += 1
        case (c: BinaryColumn, _, Key.Bytes(k)) =>
          // Dictionary-encoded values compare each distinct entry once
          val dict    = c.dictionary
          val idx     = c.dictIndices
          val byEntry =
            if dict != null && idx != null then
              val e = new Array[Int](dict.size)
              var j = 0
              while j < e.length do
                e(j) = compareBytes(
                  dict.data,
                  dict.offsets(j),
                  dict.offsets(j + 1) - dict.offsets(j),
                  k
                )
                j += 1
              e
            else
              null
          while i < n do
            r(i) =
              if byEntry != null && idx(i) >= 0 then
                byEntry(idx(i))
              else
                compareBytes(c.data, c.offsets(i), c.length(i), k)
            i += 1
        case (c: BinaryColumn, _, Key.Moment(k)) =>
          while i < n do
            r(i) =
              if c.length(i) == 12 then
                LogicalValues.int96ToInstant(c.data, c.offsets(i)).compareTo(k)
              else
                Incomparable
            i += 1
        case _ =>
          throw IllegalStateException(
            s"Can't compare ${v.getClass.getSimpleName} at ${path.mkString(".")} with ${key}"
          )
      end match
      r

    end compareAll

  end RowFilter

end ParquetFilter
