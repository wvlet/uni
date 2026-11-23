package wvlet.uni.cloud.parquet

import wvlet.uni.io.codec.Bytes
import wvlet.uni.cloud.parquet.format.LogicalType
import wvlet.uni.cloud.parquet.format.PhysicalType
import wvlet.uni.cloud.parquet.format.TimeUnit

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/** Converts physical values to the Scala values their logical type describes */
private[cloud] object LogicalValues:
  // Julian day number of 1970-01-01
  /** The Julian day of 1970-01-01, which INT96 timestamps count from */
  private[cloud] final val JulianEpochDay = 2440588L
  private final val NanosPerDay           = 86400L * 1000000000L

  def fromInt(v: Int, lt: Option[LogicalType]): Any =
    lt match
      case Some(LogicalType.Date) =>
        LocalDate.ofEpochDay(v.toLong)
      case Some(LogicalType.Time(_, TimeUnit.MILLIS)) =>
        localTime(v.toLong * 1000000L)
      case Some(LogicalType.Decimal(scale, _)) =>
        BigDecimal(BigInt(v), scale)
      case Some(LogicalType.Integer(32, false)) =>
        v.toLong & 0xffffffffL
      case Some(LogicalType.Integer(16, false)) =>
        v & 0xffff
      case Some(LogicalType.Integer(8, false)) =>
        v & 0xff
      case _ =>
        v

  def fromLong(v: Long, lt: Option[LogicalType]): Any =
    lt match
      case Some(LogicalType.Timestamp(_, unit)) =>
        timestamp(v, unit)
      case Some(LogicalType.Time(_, TimeUnit.MICROS)) =>
        localTime(v * 1000L)
      case Some(LogicalType.Time(_, TimeUnit.NANOS)) =>
        localTime(v)
      case Some(LogicalType.Time(_, TimeUnit.MILLIS)) =>
        localTime(v * 1000000L)
      case Some(LogicalType.Decimal(scale, _)) =>
        BigDecimal(BigInt(v), scale)
      case Some(LogicalType.Integer(64, false)) =>
        if v >= 0 then
          BigInt(v)
        else
          BigInt(v) + (BigInt(1) << 64)
      case _ =>
        v

  /** A TIME value as LocalTime; values outside one day are corrupt */
  def localTime(nanos: Long): LocalTime =
    if nanos < 0 || nanos >= NanosPerDay then
      throw ParquetException.corrupt(s"TIME value ${nanos} ns is outside one day")
    LocalTime.ofNanoOfDay(nanos)

  def timestamp(v: Long, unit: TimeUnit): Instant =
    unit match
      case TimeUnit.MILLIS =>
        Instant.ofEpochMilli(v)
      case TimeUnit.MICROS =>
        Instant.ofEpochSecond(Math.floorDiv(v, 1000000L), Math.floorMod(v, 1000000L) * 1000L)
      case TimeUnit.NANOS =>
        Instant.ofEpochSecond(Math.floorDiv(v, 1000000000L), Math.floorMod(v, 1000000000L))

  /** INT96 timestamps: 8 bytes of nanoseconds in the day, then 4 bytes of Julian day */
  def int96ToInstant(b: Array[Byte], off: Int): Instant =
    val nanos  = Bytes.longLE(b, off)
    val julian = Bytes.intLE(b, off + 8).toLong
    val total  = (julian - JulianEpochDay) * 86400L
    Instant.ofEpochSecond(
      total + Math.floorDiv(nanos, 1000000000L),
      Math.floorMod(nanos, 1000000000L)
    )

  /** A big-endian two's complement integer, as DECIMAL stores in binary columns */
  def bigEndianInt(b: Array[Byte], off: Int, len: Int): BigInt =
    if len == 0 then
      BigInt(0)
    else
      BigInt(java.util.Arrays.copyOfRange(b, off, off + len))

  def fromBinary(
      data: Array[Byte],
      off: Int,
      len: Int,
      field: ParquetField,
      binaryAsString: Boolean = false
  ): Any =
    val physical =
      field match
        case p: ParquetField.Primitive =>
          p.physicalType
        case _ =>
          PhysicalType.BYTE_ARRAY
    field.logicalType match
      case Some(LogicalType.String | LogicalType.Enum | LogicalType.Json) =>
        String(data, off, len, java.nio.charset.StandardCharsets.UTF_8)
      case Some(LogicalType.Decimal(scale, _)) =>
        BigDecimal(bigEndianInt(data, off, len), scale)
      case Some(LogicalType.Uuid) if len == 16 =>
        java.util.UUID(Bytes.longBE(data, off), Bytes.longBE(data, off + 8))
      case Some(LogicalType.Float16) if len == 2 =>
        halfToFloat(((data(off + 1) & 0xff) << 8) | (data(off) & 0xff))
      case _ if physical == PhysicalType.INT96 && len == 12 =>
        int96ToInstant(data, off)
      case Some(LogicalType.Geometry(_) | LogicalType.Geography(_, _)) =>
        // WKB, never text
        java.util.Arrays.copyOfRange(data, off, off + len)
      case _ if binaryAsString =>
        String(data, off, len, java.nio.charset.StandardCharsets.UTF_8)
      case _ =>
        java.util.Arrays.copyOfRange(data, off, off + len)

  end fromBinary

  /** IEEE 754 half precision (stored little-endian in Parquet) to Float */
  def halfToFloat(h: Int): Float =
    val sign      = (h >>> 15) & 1
    val exp       = (h >>> 10) & 0x1f
    val frac      = h & 0x3ff
    val magnitude =
      if exp == 0 then
        frac * math.pow(2, -24).toFloat
      else if exp == 31 then
        if frac == 0 then
          Float.PositiveInfinity
        else
          Float.NaN
      else
        java.lang.Float.intBitsToFloat(((exp + 112) << 23) | (frac << 13))
    if sign == 1 then
      -magnitude
    else
      magnitude

end LogicalValues
