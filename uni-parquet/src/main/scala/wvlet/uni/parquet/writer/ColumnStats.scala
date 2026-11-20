package wvlet.uni.parquet.writer

import wvlet.uni.parquet.ParquetField
import wvlet.uni.parquet.format.*
import wvlet.uni.parquet.io.ByteBuf

/**
  * Tracks the min, max, and null count of a column chunk in the column's sort order: signed for
  * integers and decimals, unsigned bytes for strings and other binary, and numeric for floating
  * point (NaN is skipped).
  */
private[parquet] final class ColumnStats(field: ParquetField.Primitive):
  import ColumnStats.*

  private val physical               = field.physicalType
  private val cmp: (Any, Any) => Int = comparator(field)
  private var nulls                  = 0L
  private var min: Any               = null
  private var max: Any               = null

  def addNull(): Unit = nulls += 1

  // Values are dispatched by the physical type, not their runtime class: on Scala.js every number
  // matches `Float` and `Double`
  private val isFloat  = physical == PhysicalType.FLOAT
  private val isDouble = physical == PhysicalType.DOUBLE

  def add(v: Any): Unit =
    val skip =
      (isFloat && v.asInstanceOf[Float].isNaN) || (isDouble && v.asInstanceOf[Double].isNaN)
    if !skip then
      if min == null || cmp(v, min) < 0 then
        min = v
      if max == null || cmp(v, max) > 0 then
        max = v

  def reset(): Unit =
    nulls = 0
    min = null
    max = null

  def result(): Statistics =
    val (lo, hi) =
      if min == null then
        (None, None)
      else
        val a = encode(adjustMin(min))
        val b = encode(adjustMax(max))
        // Long binary bounds are left out rather than truncated
        if a.length > MaxBinaryStat || b.length > MaxBinaryStat then
          (None, None)
        else
          (Some(a), Some(b))
    Statistics(min = lo, max = hi, nullCount = Some(nulls), distinctCount = None)

  // A zero bound is written as -0.0 for min and +0.0 for max, as the spec asks, because the two
  // compare equal
  private def adjustMin(v: Any): Any =
    if isFloat && v.asInstanceOf[Float] == 0.0f then
      -0.0f
    else if isDouble && v.asInstanceOf[Double] == 0.0 then
      -0.0
    else
      v

  private def adjustMax(v: Any): Any =
    if isFloat && v.asInstanceOf[Float] == 0.0f then
      0.0f
    else if isDouble && v.asInstanceOf[Double] == 0.0 then
      0.0
    else
      v

  private def encode(v: Any): Array[Byte] =
    physical match
      case PhysicalType.BYTE_ARRAY | PhysicalType.FIXED_LEN_BYTE_ARRAY =>
        v.asInstanceOf[Array[Byte]]
      case _ =>
        val b = ByteBuf(8)
        PlainEncoder.write(physical, v, b)
        b.toByteArray

end ColumnStats

private object ColumnStats:
  private final val MaxBinaryStat = 64

  private def comparator(field: ParquetField.Primitive): (Any, Any) => Int =
    (field.physicalType, field.logicalType) match
      case (PhysicalType.BOOLEAN, _) =>
        (a, b) => java.lang.Boolean.compare(a.asInstanceOf[Boolean], b.asInstanceOf[Boolean])
      case (PhysicalType.INT32, Some(LogicalType.Integer(_, false))) =>
        (a, b) => Integer.compareUnsigned(a.asInstanceOf[Int], b.asInstanceOf[Int])
      case (PhysicalType.INT32, _) =>
        (a, b) => Integer.compare(a.asInstanceOf[Int], b.asInstanceOf[Int])
      case (PhysicalType.INT64, Some(LogicalType.Integer(_, false))) =>
        (a, b) => java.lang.Long.compareUnsigned(a.asInstanceOf[Long], b.asInstanceOf[Long])
      case (PhysicalType.INT64, _) =>
        (a, b) => java.lang.Long.compare(a.asInstanceOf[Long], b.asInstanceOf[Long])
      case (PhysicalType.FLOAT, _) =>
        (a, b) =>
          java.lang.Float.compare(a.asInstanceOf[Float] + 0.0f, b.asInstanceOf[Float] + 0.0f)
      case (PhysicalType.DOUBLE, _) =>
        (a, b) =>
          java.lang.Double.compare(a.asInstanceOf[Double] + 0.0, b.asInstanceOf[Double] + 0.0)
      case (_, Some(LogicalType.Decimal(_, _))) =>
        (a, b) => signedCompare(a.asInstanceOf[Array[Byte]], b.asInstanceOf[Array[Byte]])
      case _ =>
        (a, b) => unsignedCompare(a.asInstanceOf[Array[Byte]], b.asInstanceOf[Array[Byte]])

  def unsignedCompare(x: Array[Byte], y: Array[Byte]): Int =
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

  /** Compares big-endian two's complement integers */
  def signedCompare(x: Array[Byte], y: Array[Byte]): Int = BigInt(x).compare(BigInt(y))

end ColumnStats
