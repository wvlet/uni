package wvlet.uni.parquet

import wvlet.uni.parquet.ColumnVector.*
import wvlet.uni.parquet.format.*
import wvlet.uni.parquet.io.ByteReader

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
  * import wvlet.uni.parquet.ParquetFilter.*
  * val f = col("ts") >= Instant.parse("2026-01-01T00:00:00Z") && col("country") === "JP"
  * }}}
  */
enum ParquetFilter:
  case Compare(column: String, op: ParquetFilter.Op, value: Any)
  case IsNull(column: String)
  case IsNotNull(column: String)
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

  def col(name: String): Column = Column(name)

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
          Some(ofInt(ByteReader.intLE(b, 0)))
        case PhysicalType.INT64 if b.length == 8 =>
          Some(ByteReader.longLE(b, 0))
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
        Some(ByteReader.longLE(b, 0))
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
          Some(java.lang.Float.intBitsToFloat(ByteReader.intLE(b, 0)).toDouble)
        else if !isFloat && b.length == 8 then
          Some(java.lang.Double.longBitsToDouble(ByteReader.longLE(b, 0)))
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
          Right(Key.Bytes(s.getBytes("UTF-8")))
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
    val x = a.getBytes("UTF-8")
    compareBytes(x, 0, x.length, b.getBytes("UTF-8"))

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

    /** The batch with only the matching rows */
    def apply(batch: ColumnBatch): ColumnBatch =
      if batch.numRows == 0 then
        batch
      else
        val m = matches(batch)
        val n = m.count(identity)
        if n == m.length then
          batch
        else
          val rows = new Array[Int](n)
          var j    = 0
          var i    = 0
          while i < m.length do
            if m(i) then
              rows(j) = i
              j += 1
            i += 1
          ColumnBatch(n, batch.columns.map(ColumnVector.take(_, rows)))

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
          while i < n do
            r(i) = compareBytes(c.data, c.offsets(i), c.length(i), k)
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
