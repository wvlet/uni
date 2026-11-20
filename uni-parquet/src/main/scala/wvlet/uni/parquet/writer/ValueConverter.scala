package wvlet.uni.parquet.writer

import wvlet.uni.msgpack.spi.Value
import wvlet.uni.msgpack.spi.Value.*
import wvlet.uni.parquet.ParquetException
import wvlet.uni.parquet.ParquetField
import wvlet.uni.parquet.format.*

import java.time.Instant
import java.time.LocalDate

/**
  * Converts a MessagePack value into the physical value of a leaf column: `Boolean`, `Int`, `Long`,
  * `Float`, `Double`, or `Array[Byte]`. Inputs come from Weaver (case classes), JSON, or Scala
  * values, so each column accepts every form of its type that these produce: a timestamp from a
  * MessagePack timestamp, epoch milliseconds, or an ISO-8601 string; a decimal from a number or a
  * string; a UUID from a string or 16 bytes; binary from bytes, a string, or an array of byte
  * values.
  */
private[parquet] object ValueConverter:

  /** Thrown for a value that does not fit its column; the writer adds the column path */
  final class Mismatch(message: String) extends RuntimeException(message)

  private def mismatch(v: Value, expected: String): Nothing =
    throw Mismatch(s"expected ${expected}, but got ${describe(v)}")

  private def describe(v: Value): String =
    val s     = v.toJson
    val shown =
      if s.length > 40 then
        s.take(37) + "..."
      else
        s
    s"${v.valueType.toString.toLowerCase} ${shown}"

  def forField(p: ParquetField.Primitive): Value => Any =
    (p.physicalType, p.logicalType) match
      case (PhysicalType.BOOLEAN, _) =>
        boolean
      case (PhysicalType.INT32 | PhysicalType.INT64, Some(LogicalType.Decimal(scale, precision))) =>
        val isInt = p.physicalType == PhysicalType.INT32
        v =>
          val unscaled = decimal(v, scale, precision)
          if isInt then
            unscaled.toInt
          else
            unscaled.toLong
      case (PhysicalType.INT32, Some(LogicalType.Date)) =>
        date
      case (PhysicalType.INT32, Some(LogicalType.Integer(bits, signed))) =>
        val (min, max) =
          if signed then
            (-(1L << (bits - 1)), (1L << (bits - 1)) - 1)
          else
            (0L, (1L << bits) - 1)
        v => integer(v, min, max).toInt
      case (PhysicalType.INT32, None) =>
        v => integer(v, Int.MinValue, Int.MaxValue).toInt
      case (PhysicalType.INT64, Some(LogicalType.Timestamp(_, unit))) =>
        v => timestamp(v, unit)
      case (PhysicalType.INT64, Some(LogicalType.Integer(64, false))) =>
        v => integer(v, 0L, Long.MaxValue)
      case (PhysicalType.INT64, None | Some(LogicalType.Integer(_, true))) =>
        v => integer(v, Long.MinValue, Long.MaxValue)
      case (PhysicalType.FLOAT, None) =>
        v => double(v).toFloat
      case (PhysicalType.DOUBLE, None) =>
        double
      case (PhysicalType.BYTE_ARRAY, Some(LogicalType.String | LogicalType.Enum)) =>
        string
      case (PhysicalType.BYTE_ARRAY, Some(LogicalType.Json)) =>
        json
      case (
            PhysicalType.BYTE_ARRAY | PhysicalType.FIXED_LEN_BYTE_ARRAY,
            Some(LogicalType.Decimal(scale, precision))
          ) =>
        val fixed = p.physicalType == PhysicalType.FIXED_LEN_BYTE_ARRAY
        val len   = p.typeLength
        v =>
          val unscaled = decimal(v, scale, precision).toByteArray
          if fixed then
            signExtend(unscaled, len)
          else
            unscaled
      case (PhysicalType.FIXED_LEN_BYTE_ARRAY, Some(LogicalType.Uuid)) if p.typeLength == 16 =>
        uuid
      case (PhysicalType.BYTE_ARRAY, None) =>
        binary
      case (PhysicalType.FIXED_LEN_BYTE_ARRAY, None) =>
        val len = p.typeLength
        v =>
          val b = binary(v)
          if b.length != len then
            throw Mismatch(s"expected ${len} bytes, but got ${b.length}")
          b
      case (tpe, lt) =>
        val name = lt.map(t => s"${tpe} (${t})").getOrElse(tpe.toString)
        throw ParquetException.unsupported(s"writing ${name} columns (${p.pathString})")

  private def boolean(v: Value): Any =
    v match
      case BooleanValue(b) =>
        b
      case StringValue("true") =>
        true
      case StringValue("false") =>
        false
      case _ =>
        mismatch(v, "a boolean")

  private def integer(v: Value, min: Long, max: Long): Long =
    val l =
      v match
        case LongValue(x) =>
          x
        case BigIntegerValue(x) if x.bitLength() < 64 =>
          x.longValue()
        case DoubleValue(d) if d == math.rint(d) && !d.isInfinite =>
          d.toLong
        case StringValue(s) =>
          s.trim.toLongOption.getOrElse(mismatch(v, "an integer"))
        case _ =>
          mismatch(v, "an integer")
    if l < min || l > max then
      throw Mismatch(s"${l} is outside the column's range ${min} to ${max}")
    l

  private def double(v: Value): Double =
    v match
      case DoubleValue(d) =>
        d
      case LongValue(x) =>
        x.toDouble
      case BigIntegerValue(x) =>
        x.doubleValue()
      case StringValue(s) =>
        s.trim.toDoubleOption.getOrElse(mismatch(v, "a number"))
      case _ =>
        mismatch(v, "a number")

  private def string(v: Value): Any =
    v match
      case StringValue(s) =>
        s.getBytes("UTF-8")
      case BinaryValue(b) =>
        b
      case LongValue(_) | DoubleValue(_) | BooleanValue(_) | BigIntegerValue(_) =>
        v.toJson.getBytes("UTF-8")
      case TimestampValue(t) =>
        t.toString.getBytes("UTF-8")
      case _ =>
        mismatch(v, "a string")

  private def json(v: Value): Any =
    v match
      case StringValue(s) =>
        // A string is taken as JSON text already
        s.getBytes("UTF-8")
      case _ =>
        v.toJson.getBytes("UTF-8")

  private def binary(v: Value): Array[Byte] =
    v match
      case BinaryValue(b) =>
        b
      case StringValue(s) =>
        s.getBytes("UTF-8")
      case ArrayValue(elems) =>
        // Weaver packs Array[Byte] fields of case classes as arrays of numbers
        val b = new Array[Byte](elems.size)
        var i = 0
        while i < b.length do
          elems(i) match
            case LongValue(x) if x >= -128 && x <= 255 =>
              b(i) = x.toByte
            case other =>
              mismatch(other, "a byte value")
          i += 1
        b
      case _ =>
        mismatch(v, "binary")

  private def uuid(v: Value): Any =
    v match
      case StringValue(s) =>
        val u =
          try
            java.util.UUID.fromString(s.trim)
          catch
            case _: IllegalArgumentException =>
              mismatch(v, "a UUID")
        val b = new Array[Byte](16)
        var i = 0
        while i < 8 do
          b(i) = (u.getMostSignificantBits >>> (56 - 8 * i)).toByte
          b(8 + i) = (u.getLeastSignificantBits >>> (56 - 8 * i)).toByte
          i += 1
        b
      case BinaryValue(b) if b.length == 16 =>
        b
      case _ =>
        mismatch(v, "a UUID")

  private def date(v: Value): Any =
    v match
      case StringValue(s) =>
        try
          LocalDate.parse(s.trim).toEpochDay.toInt
        catch
          case _: java.time.DateTimeException =>
            mismatch(v, "a date (yyyy-mm-dd)")
      case LongValue(x) if x >= Int.MinValue && x <= Int.MaxValue =>
        x.toInt
      case BigIntegerValue(x) if x.bitLength() < 32 =>
        x.intValue()
      case _ =>
        mismatch(v, "a date")

  private def instant(v: Value): Instant =
    v match
      case TimestampValue(t) =>
        t
      case LongValue(x) =>
        // Epoch milliseconds, as Weaver reads integers into Instant
        Instant.ofEpochMilli(x)
      case BigIntegerValue(x) if x.bitLength() < 64 =>
        Instant.ofEpochMilli(x.longValue())
      case StringValue(s) =>
        try
          Instant.parse(s.trim)
        catch
          case _: java.time.DateTimeException =>
            mismatch(v, "an ISO-8601 timestamp")
      case _ =>
        mismatch(v, "a timestamp")

  private def timestamp(v: Value, unit: TimeUnit): Long =
    val t = instant(v)
    try
      unit match
        case TimeUnit.MILLIS =>
          t.toEpochMilli
        case TimeUnit.MICROS =>
          Math.addExact(Math.multiplyExact(t.getEpochSecond, 1000000L), t.getNano / 1000L)
        case TimeUnit.NANOS =>
          Math.addExact(Math.multiplyExact(t.getEpochSecond, 1000000000L), t.getNano.toLong)
    catch
      case _: ArithmeticException =>
        throw Mismatch(s"${t} is outside the range of TIMESTAMP(${unit})")

  /** The unscaled value of a decimal, checked against the column's precision and scale */
  private def decimal(v: Value, scale: Int, precision: Int): BigInt =
    val d: BigDecimal =
      v match
        case LongValue(x) =>
          BigDecimal(x)
        case BigIntegerValue(x) =>
          BigDecimal(BigInt(x))
        case DoubleValue(x) if !x.isNaN && !x.isInfinite =>
          BigDecimal.decimal(x)
        case StringValue(s) =>
          try
            BigDecimal(s.trim)
          catch
            case _: NumberFormatException =>
              mismatch(v, "a decimal")
        case _ =>
          mismatch(v, "a decimal")
    val scaled =
      try
        d.setScale(scale, BigDecimal.RoundingMode.UNNECESSARY)
      catch
        case _: ArithmeticException =>
          throw Mismatch(s"${d} has more than ${scale} digits after the decimal point")
    if scaled.precision > precision && scaled.signum != 0 then
      throw Mismatch(s"${d} does not fit DECIMAL(${precision}, ${scale})")
    BigInt(scaled.underlying.unscaledValue)

  /** Sign-extends a big-endian two's complement integer to `len` bytes */
  private def signExtend(b: Array[Byte], len: Int): Array[Byte] =
    if b.length == len then
      b
    else if b.length > len then
      throw Mismatch(s"the decimal needs ${b.length} bytes, but the column holds ${len}")
    else
      val out  = new Array[Byte](len)
      val fill =
        if b(0) < 0 then
          0xff.toByte
        else
          0.toByte
      java.util.Arrays.fill(out, 0, len - b.length, fill)
      System.arraycopy(b, 0, out, len - b.length, b.length)
      out

end ValueConverter
