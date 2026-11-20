package wvlet.uni.parquet.writer

import wvlet.uni.parquet.ParquetField
import wvlet.uni.parquet.format.*
import wvlet.uni.parquet.io.ByteBuf

/**
  * Tracks the min, max, and null count of a column chunk in the column's sort order: signed for
  * integers and decimals, unsigned bytes for strings and other binary, and numeric for floating
  * point (NaN is skipped). Values arrive unboxed through the method of the column's physical type.
  */
private[parquet] final class ColumnStats(field: ParquetField.Primitive):
  import ColumnStats.*

  private val physical = field.physicalType
  private val unsigned =
    field.logicalType match
      case Some(LogicalType.Integer(_, false)) =>
        true
      case _ =>
        false

  private val signedBytes =
    field.logicalType match
      case Some(LogicalType.Decimal(_, _)) =>
        true
      case _ =>
        false

  private var nulls = 0L
  private var has   = false
  // INT32, INT64, and BOOLEAN (0 or 1)
  private var minL = 0L
  private var maxL = 0L
  // FLOAT (widened, which keeps its order) and DOUBLE
  private var minD = 0.0
  private var maxD = 0.0
  // Binary bounds, copied into reused arrays
  private var minB   = new Array[Byte](16)
  private var minLen = 0
  private var maxB   = new Array[Byte](16)
  private var maxLen = 0

  def addNull(): Unit = nulls += 1

  def addInt(v: Int): Unit =
    if !has then
      has = true
      minL = v
      maxL = v
    else if unsigned then
      if Integer.compareUnsigned(v, minL.toInt) < 0 then
        minL = v
      if Integer.compareUnsigned(v, maxL.toInt) > 0 then
        maxL = v
    else
      if v < minL then
        minL = v
      if v > maxL then
        maxL = v

  def addLong(v: Long): Unit =
    if !has then
      has = true
      minL = v
      maxL = v
    else if unsigned then
      if java.lang.Long.compareUnsigned(v, minL) < 0 then
        minL = v
      if java.lang.Long.compareUnsigned(v, maxL) > 0 then
        maxL = v
    else
      if v < minL then
        minL = v
      if v > maxL then
        maxL = v

  def addBoolean(v: Boolean): Unit = addInt(
    if v then
      1
    else
      0
  )

  /** FLOAT and DOUBLE values; -0.0 and +0.0 compare equal */
  def addDouble(v: Double): Unit =
    if !v.isNaN then
      if !has then
        has = true
        minD = v
        maxD = v
      else
        if java.lang.Double.compare(v + 0.0, minD + 0.0) < 0 then
          minD = v
        if java.lang.Double.compare(v + 0.0, maxD + 0.0) > 0 then
          maxD = v

  def addBytes(src: Array[Byte], off: Int, len: Int): Unit =
    if !has then
      has = true
      setMin(src, off, len)
      setMax(src, off, len)
    else
      if compare(src, off, len, minB, 0, minLen) < 0 then
        setMin(src, off, len)
      if compare(src, off, len, maxB, 0, maxLen) > 0 then
        setMax(src, off, len)

  private def compare(a: Array[Byte], ao: Int, al: Int, b: Array[Byte], bo: Int, bl: Int): Int =
    if signedBytes then
      signedCompare(a, ao, al, b, bo, bl)
    else
      unsignedCompare(a, ao, al, b, bo, bl)

  private def setMin(src: Array[Byte], off: Int, len: Int): Unit =
    if minB.length < len then
      minB = new Array[Byte](len)
    System.arraycopy(src, off, minB, 0, len)
    minLen = len

  private def setMax(src: Array[Byte], off: Int, len: Int): Unit =
    if maxB.length < len then
      maxB = new Array[Byte](len)
    System.arraycopy(src, off, maxB, 0, len)
    maxLen = len

  def reset(): Unit =
    nulls = 0
    has = false

  def result(): Statistics =
    val (lo, hi) =
      if !has then
        (None, None)
      else
        val (a, b) = bounds
        // Long binary bounds are left out rather than truncated
        if a.length > MaxBinaryStat || b.length > MaxBinaryStat then
          (None, None)
        else
          (Some(a), Some(b))
    Statistics(min = lo, max = hi, nullCount = Some(nulls), distinctCount = None)

  // A zero bound of a floating-point column is written as -0.0 for min and +0.0 for max, as the
  // spec asks, because the two compare equal
  private def bounds: (Array[Byte], Array[Byte]) =
    val lo = ByteBuf(8)
    val hi = ByteBuf(8)
    physical match
      case PhysicalType.BYTE_ARRAY | PhysicalType.FIXED_LEN_BYTE_ARRAY =>
        lo.write(minB, 0, minLen)
        hi.write(maxB, 0, maxLen)
      case PhysicalType.INT64 =>
        lo.writeLongLE(minL)
        hi.writeLongLE(maxL)
      case PhysicalType.BOOLEAN =>
        lo.writeByte(minL.toInt)
        hi.writeByte(maxL.toInt)
      case PhysicalType.FLOAT =>
        lo.writeIntLE(java.lang.Float.floatToRawIntBits(zeroAs(minD, -0.0).toFloat))
        hi.writeIntLE(java.lang.Float.floatToRawIntBits(zeroAs(maxD, 0.0).toFloat))
      case PhysicalType.DOUBLE =>
        lo.writeLongLE(java.lang.Double.doubleToRawLongBits(zeroAs(minD, -0.0)))
        hi.writeLongLE(java.lang.Double.doubleToRawLongBits(zeroAs(maxD, 0.0)))
      case _ =>
        lo.writeIntLE(minL.toInt)
        hi.writeIntLE(maxL.toInt)
    (lo.toByteArray, hi.toByteArray)

  private def zeroAs(v: Double, zero: Double): Double =
    if v == 0.0 then
      zero
    else
      v

end ColumnStats

private[writer] object ColumnStats:
  private final val MaxBinaryStat = 64

  def unsignedCompare(a: Array[Byte], ao: Int, al: Int, b: Array[Byte], bo: Int, bl: Int): Int =
    val n = math.min(al, bl)
    var i = 0
    var r = 0
    while r == 0 && i < n do
      r = Integer.compare(a(ao + i) & 0xff, b(bo + i) & 0xff)
      i += 1
    if r != 0 then
      r
    else
      Integer.compare(al, bl)

  /**
    * Compares big-endian two's complement integers of any length, as if the shorter one were sign
    * extended
    */
  def signedCompare(a: Array[Byte], ao: Int, al: Int, b: Array[Byte], bo: Int, bl: Int): Int =
    val n  = math.max(al, bl)
    val sa =
      if al > 0 && a(ao) < 0 then
        0xff
      else
        0
    val sb =
      if bl > 0 && b(bo) < 0 then
        0xff
      else
        0
    var k = 0
    var r = 0
    while r == 0 && k < n do
      val x =
        if k < n - al then
          sa
        else
          a(ao + k - (n - al)) & 0xff
      val y =
        if k < n - bl then
          sb
        else
          b(bo + k - (n - bl)) & 0xff
      if x != y then
        r =
          if k == 0 then
            Integer.compare(x.toByte.toInt, y.toByte.toInt)
          else
            Integer.compare(x, y)
      k += 1
    r

  end signedCompare

end ColumnStats
