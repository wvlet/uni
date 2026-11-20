package wvlet.uni.parquet.npm

import wvlet.uni.parquet.ColumnVector
import wvlet.uni.parquet.ColumnVector.*
import wvlet.uni.parquet.ParquetField
import wvlet.uni.parquet.format.LogicalType
import wvlet.uni.parquet.format.TimeUnit

import java.time.Instant
import scala.scalajs.js
import scala.scalajs.js.typedarray.*

/** Converts decoded Parquet values into the JS values the npm API documents in parquet.d.ts */
private[npm] object JsValues:
  private final val MillisPerDay = 86400000.0

  /** Read options that change how values are mapped */
  case class Mapping(timestampsAsBigInt: Boolean)

  private final val MaxSafeInteger = 9007199254740991L

  // Most values are within 2^53, where a bigint can come from the number without a string
  def bigint(v: Long): js.BigInt =
    if v >= -MaxSafeInteger && v <= MaxSafeInteger then
      js.BigInt(v.toDouble)
    else
      js.BigInt(v.toString)

  def bytes(a: Array[Byte]): Uint8Array =
    val i = byteArray2Int8Array(a)
    Uint8Array(i.buffer, i.byteOffset, i.length)

  def fromBytes(u: Uint8Array): Array[Byte] = int8Array2ByteArray(
    Int8Array(u.buffer, u.byteOffset, u.length)
  )

  private def pad(v: Long, width: Int): String =
    val s = v.toString
    if s.length >= width then
      s
    else
      ("0" * (width - s.length)) + s

  /** A TIME value as HH:mm:ss.fffffffff */
  def timeString(nanosOfDay: Long): String =
    val nanos = nanosOfDay % 1000000000L
    val secs  = nanosOfDay / 1000000000L
    s"${pad(secs / 3600, 2)}:${pad((secs / 60) % 60, 2)}:${pad(secs % 60, 2)}.${pad(nanos, 9)}"

  private def nanosOf(v: Long, unit: TimeUnit): Long =
    unit match
      case TimeUnit.MILLIS =>
        v * 1000000L
      case TimeUnit.MICROS =>
        v * 1000L
      case TimeUnit.NANOS =>
        v

  private def epochMillis(v: Long, unit: TimeUnit): Double =
    unit match
      case TimeUnit.MILLIS =>
        v.toDouble
      case TimeUnit.MICROS =>
        Math.floorDiv(v, 1000L).toDouble
      case TimeUnit.NANOS =>
        Math.floorDiv(v, 1000000L).toDouble

  private def instant(t: Instant, m: Mapping): js.Any =
    if m.timestampsAsBigInt then
      js.BigInt((BigInt(t.getEpochSecond) * 1000000000L + t.getNano).toString)
    else
      new js.Date(t.toEpochMilli.toDouble)

  private def decimal(unscaled: BigInt, scale: Int): String =
    BigDecimal(unscaled, scale).bigDecimal.toPlainString

  /** The value of row i, or null */
  def value(v: ColumnVector, i: Int, m: Mapping): js.Any =
    if v.isNull(i) then
      null
    else
      v match
        case c: BooleanColumn =>
          c.values(i)
        case c: IntColumn =>
          int(c.values(i), c.field.logicalType)
        case c: LongColumn =>
          long(c.values(i), c.field.logicalType, m)
        case c: FloatColumn =>
          c.values(i).toDouble
        case c: DoubleColumn =>
          c.values(i)
        case c: BinaryColumn =>
          binary(c, i, m)
        case c: ListColumn =>
          val arr = js.Array[js.Any]()
          var j   = c.offsets(i)
          while j < c.offsets(i + 1) do
            arr.push(value(c.elements, j, m))
            j += 1
          arr
        case c: MapColumn =>
          val d = js.Dictionary.empty[js.Any]
          var j = c.offsets(i)
          while j < c.offsets(i + 1) do
            d(String.valueOf(value(c.keys, j, m))) = value(c.values, j, m)
            j += 1
          d
        case c: StructColumn =>
          val d = js.Dictionary.empty[js.Any]
          c.children.foreach(ch => d(ch.name) = value(ch, i, m))
          d

  private def int(v: Int, lt: Option[LogicalType]): js.Any =
    lt match
      case Some(LogicalType.Date) =>
        new js.Date(v.toDouble * MillisPerDay)
      case Some(LogicalType.Time(_, unit)) =>
        timeString(nanosOf(v.toLong, unit))
      case Some(LogicalType.Decimal(scale, _)) =>
        decimal(BigInt(v), scale)
      case Some(LogicalType.Integer(32, false)) =>
        (v.toLong & 0xffffffffL).toDouble
      case Some(LogicalType.Integer(16, false)) =>
        v & 0xffff
      case Some(LogicalType.Integer(8, false)) =>
        v & 0xff
      case _ =>
        v

  private def long(v: Long, lt: Option[LogicalType], m: Mapping): js.Any =
    lt match
      case Some(LogicalType.Timestamp(_, unit)) =>
        if m.timestampsAsBigInt then
          bigint(v)
        else
          new js.Date(epochMillis(v, unit))
      case Some(LogicalType.Time(_, unit)) =>
        timeString(nanosOf(v, unit))
      case Some(LogicalType.Decimal(scale, _)) =>
        decimal(BigInt(v), scale)
      case Some(LogicalType.Integer(64, false)) =>
        js.BigInt(java.lang.Long.toUnsignedString(v))
      case _ =>
        bigint(v)

  private def binary(c: BinaryColumn, i: Int, m: Mapping): js.Any =
    c.getValue(i) match
      case s: String =>
        s
      case d: BigDecimal =>
        d.bigDecimal.toPlainString
      case u: java.util.UUID =>
        u.toString
      case f: Float =>
        f.toDouble
      case t: Instant =>
        instant(t, m)
      case b: Array[Byte] =>
        bytes(b)
      case other =>
        String.valueOf(other)

  private def plainNumeric(f: ParquetField): Boolean =
    f.logicalType match
      case None | Some(LogicalType.Integer(_, true)) =>
        true
      case _ =>
        false

  /**
    * A column of a batch: plain numeric columns as a copied typed array, others as an array of
    * values mapped like rows. `nulls` is a Uint8Array (1 for null) or null when no row is null.
    */
  def column(v: ColumnVector, m: Mapping): js.Object =
    val values: js.Any =
      v match
        case c: IntColumn if plainNumeric(c.field) =>
          val a = Int32Array(c.values.length)
          var j = 0
          while j < c.values.length do
            a(j) = c.values(j)
            j += 1
          a
        case c: LongColumn if plainNumeric(c.field) =>
          val a = BigInt64Array(c.values.length)
          var j = 0
          while j < c.values.length do
            a(j) = bigint(c.values(j))
            j += 1
          a
        case c: FloatColumn =>
          val a = Float32Array(c.values.length)
          var j = 0
          while j < c.values.length do
            a(j) = c.values(j)
            j += 1
          a
        case c: DoubleColumn =>
          val a = Float64Array(c.values.length)
          var j = 0
          while j < c.values.length do
            a(j) = c.values(j)
            j += 1
          a
        case _ =>
          val arr = js.Array[js.Any]()
          var j   = 0
          while j < v.size do
            arr.push(value(v, j, m))
            j += 1
          arr
    val nulls: js.Any =
      if !v.hasNulls then
        null
      else
        val a = Uint8Array(v.size)
        var j = 0
        while j < v.size do
          if v.isNull(j) then
            a(j) = 1
          j += 1
        a
    js.Dynamic.literal(name = v.name, values = values, nulls = nulls)

  end column

end JsValues
