package wvlet.uni.parquet

import wvlet.uni.msgpack.spi.ExtTypeHeader
import wvlet.uni.msgpack.spi.MessageFormat
import wvlet.uni.msgpack.spi.MessagePack
import wvlet.uni.msgpack.spi.Unpacker
import wvlet.uni.msgpack.spi.Value
import wvlet.uni.msgpack.spi.ValueType
import wvlet.uni.parquet.ColumnVector.*
import wvlet.uni.parquet.format.LogicalType
import wvlet.uni.parquet.format.PhysicalType
import wvlet.uni.parquet.format.TimeUnit

import java.math.BigInteger
import java.time.Instant
import java.time.LocalDate

/**
  * An `Unpacker` that reads the rows of a [[ColumnBatch]] straight from its vectors, so Weaver
  * decodes case classes without the batch being packed into MessagePack first. It hands out the
  * same values [[RowPacker]] would write: a row is a map of column names to values, timestamps are
  * Instants, DATE and TIME are ISO-8601 strings, DECIMAL, UUID, and unsigned 64-bit integers past
  * Long.MaxValue are strings, and lists, maps, and structs are arrays and maps.
  *
  * How each vector is read is worked out once per batch, and column names are the schema's own
  * Strings, so nothing is looked up or built per row for them. Strings of dictionary-encoded
  * columns are built once per dictionary entry. Call [[startRow]] before Weaver reads each row.
  */
private[parquet] final class VectorUnpacker(batch: ColumnBatch, binaryAsString: Boolean)
    extends Unpacker:
  import VectorUnpacker.*

  private val root: Array[Col]         = batch.columns.map(Col(_, binaryAsString)).toArray
  private val rootNames: Array[String] = batch.columns.map(_.name).toArray

  // The value at the cursor
  private var kind: Int            = End
  private var key: String          = null
  private var col: Col             = null
  private var idx: Int             = 0
  private var tpe: ValueType       = null
  private var payload: Array[Byte] = null
  private var payloadOff: Int      = 0
  private var payloadLen: Int      = 0
  private var instant: Instant     = null
  private var row: Int             = 0

  // Open containers: maps and arrays whose entries are being read
  private var frames = new Array[Frame](4)
  private var depth  = 0

  private final class Frame:
    var kind: Int            = 0
    var cols: Array[Col]     = null
    var names: Array[String] = null
    var a: Col               = null
    var b: Col               = null
    var row: Int             = 0
    var k: Int               = 0
    var end: Int             = 0

    /** Moves the cursor to the next entry; false when there is none */
    def next(): Boolean =
      if k >= end then
        false
      else
        kind match
          case FStruct =>
            if (k & 1) == 0 then
              setKey(names(k >> 1))
            else
              setCell(cols(k >> 1), row)
          case FList =>
            setCell(a, k)
          case _ =>
            // Map entries alternate key and value; `row` is the first entry
            if (k & 1) == 0 then
              setCell(a, row + (k >> 1))
            else
              setCell(b, row + (k >> 1))
        k += 1
        true

  end Frame

  private def push(kind: Int): Frame =
    if depth == frames.length then
      frames = java.util.Arrays.copyOf(frames, depth * 2)
    if frames(depth) == null then
      frames(depth) = Frame()
    val f = frames(depth)
    depth += 1
    f.kind = kind
    f.k = 0
    f

  private def advance(): Unit =
    kind = End
    while depth > 0 && !frames(depth - 1).next() do
      depth -= 1

  /** Places the cursor on row `i` of the batch */
  def startRow(i: Int): Unit =
    depth = 0
    row = i
    kind = Row
    tpe = ValueType.MAP

  private def setKey(k: String): Unit =
    kind = Key
    key = k
    tpe = ValueType.STRING

  private def setCell(c: Col, i: Int): Unit =
    kind = Cell
    col = c
    idx = i
    tpe =
      if c.nulls != null && c.nulls(i) then
        ValueType.NIL
      else if c.tpe != null then
        c.tpe
      else
        c.dynamicType(i)

  private def mismatch(expected: String): Nothing =
    throw IllegalStateException(s"Expected ${expected}, but the next value is ${tpe}")

  private def expect(t: ValueType): Unit =
    if kind == End then
      throw IllegalStateException("No more values in the row")
    if tpe != t then
      mismatch(t.toString)

  // ---- Unpacker ----

  override def hasNext: Boolean = kind != End

  override def getNextValueType: ValueType =
    if kind == End then
      throw IllegalStateException("No more values in the row")
    tpe

  override def getNextFormat: MessageFormat =
    getNextValueType match
      case ValueType.NIL =>
        MessageFormat.NIL
      case ValueType.BOOLEAN =>
        MessageFormat.BOOLEAN
      case ValueType.INTEGER =>
        MessageFormat.INT64
      case ValueType.FLOAT =>
        MessageFormat.FLOAT64
      case ValueType.STRING =>
        MessageFormat.STR32
      case ValueType.BINARY =>
        MessageFormat.BIN32
      case ValueType.ARRAY =>
        MessageFormat.ARRAY32
      case ValueType.MAP =>
        MessageFormat.MAP32
      case _ =>
        MessageFormat.EXT8

  override def skipValue: Unit =
    if kind == End then
      throw IllegalStateException("No more values in the row")
    advance()

  override def skipValue(count: Int): Unit =
    var i = 0
    while i < count do
      skipValue
      i += 1

  override def unpackNil: Unit =
    expect(ValueType.NIL)
    advance()

  override def tryUnpackNil: Boolean =
    if kind != End && tpe == ValueType.NIL then
      advance()
      true
    else
      false

  override def unpackBoolean: Boolean =
    expect(ValueType.BOOLEAN)
    val v = col.vec.asInstanceOf[BooleanColumn].values(idx)
    advance()
    v

  // Out-of-range values throw, as a MessagePack unpacker's do, rather than wrap around
  override def unpackByte: Byte   = narrow(unpackLong, Byte.MinValue, Byte.MaxValue).toByte
  override def unpackShort: Short = narrow(unpackLong, Short.MinValue, Short.MaxValue).toShort
  override def unpackInt: Int     = narrow(unpackLong, Int.MinValue, Int.MaxValue).toInt

  private def narrow(v: Long, min: Long, max: Long): Long =
    if v < min || v > max then
      throw IllegalArgumentException(s"${v} is outside the range ${min} to ${max}")
    v

  override def unpackLong: Long =
    expect(ValueType.INTEGER)
    val c = col
    val v =
      c.code match
        case CInt =>
          c.ints(idx).toLong
        case CUInt32 =>
          c.ints(idx).toLong & 0xffffffffL
        case CUInt16 =>
          (c.ints(idx) & 0xffff).toLong
        case CUInt8 =>
          (c.ints(idx) & 0xff).toLong
        case _ =>
          c.longs(idx)
    advance()
    v

  override def unpackBigInteger: BigInteger = BigInteger.valueOf(unpackLong)

  override def unpackFloat: Float = unpackDouble.toFloat

  override def unpackDouble: Double =
    expect(ValueType.FLOAT)
    val c = col
    val v =
      c.code match
        case CDouble =>
          c.vec.asInstanceOf[DoubleColumn].values(idx)
        case CFloat =>
          c.vec.asInstanceOf[FloatColumn].values(idx).toDouble
        case _ =>
          // FLOAT16
          c.logicalBinary(idx).asInstanceOf[Float].toDouble
    advance()
    v

  override def unpackString: String =
    if kind == Key then
      val k = key
      advance()
      k
    else
      expect(ValueType.STRING)
      val s = col.string(idx)
      advance()
      s

  override def unpackTimestamp: Instant =
    if kind == Ext then
      val t = instant
      advance()
      t
    else
      expect(ValueType.EXTENSION)
      val t = col.instant(idx)
      advance()
      t

  override def unpackTimestamp(extTypeHeader: ExtTypeHeader): Instant = unpackTimestamp

  override def unpackArrayHeader: Int =
    expect(ValueType.ARRAY)
    val l    = col.vec.asInstanceOf[ListColumn]
    val from = l.offsets(idx)
    val to   = l.offsets(idx + 1)
    val f    = push(FList)
    f.a = col.elements
    f.k = from
    f.end = to
    advance()
    to - from

  override def unpackMapHeader: Int =
    if kind == Row then
      val f = push(FStruct)
      f.cols = root
      f.names = rootNames
      f.row = row
      f.end = 2 * root.length
      advance()
      root.length
    else
      expect(ValueType.MAP)
      val c = col
      if c.code == CStruct then
        val f = push(FStruct)
        f.cols = c.children
        f.names = c.names
        f.row = idx
        f.end = 2 * c.children.length
        advance()
        c.children.length
      else
        val m    = c.vec.asInstanceOf[MapColumn]
        val from = m.offsets(idx)
        val n    = m.offsets(idx + 1) - from
        val f    = push(FMap)
        f.a = c.keys
        f.b = c.values
        f.row = from
        f.end = 2 * n
        advance()
        n

  override def unpackExtTypeHeader: ExtTypeHeader =
    expect(ValueType.EXTENSION)
    instant = col.instant(idx)
    kind = Ext
    ExtTypeHeader(TimestampExtType, 12)

  override def unpackExtValue(extTypeHeader: ExtTypeHeader): Value = Value.TimestampValue(
    unpackTimestamp
  )

  override def unpackRawStringHeader: Int =
    if kind == Key then
      setPayload(key.getBytes(java.nio.charset.StandardCharsets.UTF_8))
    else
      expect(ValueType.STRING)
      if col.code == CStr then
        val b = col.vec.asInstanceOf[BinaryColumn]
        startPayload(b.data, b.offsets(idx), b.length(idx))
      else
        setPayload(col.string(idx).getBytes(java.nio.charset.StandardCharsets.UTF_8))

  override def unpackBinaryHeader: Int =
    expect(ValueType.BINARY)
    val b = col.vec.asInstanceOf[BinaryColumn]
    startPayload(b.data, b.offsets(idx), b.length(idx))

  private def setPayload(bytes: Array[Byte]): Int = startPayload(bytes, 0, bytes.length)

  /** After a string or binary header; an empty payload is done already */
  private def startPayload(data: Array[Byte], off: Int, len: Int): Int =
    payload = data
    payloadOff = off
    payloadLen = len
    if len == 0 then
      advance()
    else
      kind = Payload
    len

  override def readPayload(dst: Array[Byte]): Unit = readPayload(dst, 0, dst.length)

  override def readPayload(dst: Array[Byte], offset: Int, length: Int): Unit =
    if length > 0 then
      if kind != Payload || length > payloadLen then
        throw IllegalStateException("No payload to read")
      System.arraycopy(payload, payloadOff, dst, offset, length)
      payloadOff += length
      payloadLen -= length
      if payloadLen == 0 then
        advance()

  override def readPayload(length: Int): Array[Byte] =
    val b = new Array[Byte](length)
    readPayload(b, 0, length)
    b

  /** A whole value, for weavers that read a `Value` (JSON, Any); packed through [[RowPacker]] */
  override def unpackValue: Value =
    val v =
      kind match
        case Key =>
          Value.StringValue(key)
        case Row =>
          val p = MessagePack.newPacker()
          RowPacker(binaryAsString).packRow(p, batch, row)
          MessagePack.newUnpacker(p.toByteArray).unpackValue
        case Cell =>
          val p = MessagePack.newPacker()
          RowPacker(binaryAsString).pack(p, col.vec, idx)
          MessagePack.newUnpacker(p.toByteArray).unpackValue
        case _ =>
          throw IllegalStateException("No value to read")
    advance()
    v

  override def close(): Unit = ()

end VectorUnpacker

private[parquet] object VectorUnpacker:
  // What the cursor is on
  private final val End     = 0
  private final val Key     = 1
  private final val Cell    = 2
  private final val Row     = 3
  private final val Payload = 4
  private final val Ext     = 5

  // Frame kinds
  private final val FStruct = 0
  private final val FList   = 1
  private final val FMap    = 2

  private final val TimestampExtType: Byte = -1

  // How a vector's values are read, in the order RowPacker checks them
  private final val CBool        = 0
  private final val CInt         = 1
  private final val CUInt32      = 2
  private final val CUInt16      = 3
  private final val CUInt8       = 4
  private final val CDate        = 5
  private final val CTimeInt     = 6
  private final val CDecimalInt  = 7
  private final val CLong        = 8
  private final val CULong       = 9
  private final val CTimestamp   = 10
  private final val CTimeLong    = 11
  private final val CDecimalLong = 12
  private final val CFloat       = 13
  private final val CDouble      = 14
  private final val CStr         = 15 // UTF-8 bytes of a binary column
  private final val CBinLogical  = 16 // DECIMAL, UUID, or FLOAT16 in bytes
  private final val CInt96       = 17
  private final val CBin         = 18
  private final val CList        = 19
  private final val CMap         = 20
  private final val CStruct      = 21

  private final val DateCacheSize = 1024

  /**
    * The ISO-8601 form of an epoch day, as `LocalDate.toString` writes it. Years 1000 to 9999 are
    * formatted here (civil-from-days), which is much faster than `LocalDate` on Scala Native; other
    * years go through `LocalDate`.
    */
  private[parquet] def isoDate(day: Int): String =
    val z   = day.toLong + 719468L
    val era = Math.floorDiv(z, 146097L)
    val doe = z - era * 146097L
    val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp  = (5 * doy + 2) / 153
    val d   = (doy - (153 * mp + 2) / 5 + 1).toInt
    val m   =
      if mp < 10 then
        (mp + 3).toInt
      else
        (mp - 9).toInt
    val y = (
      yoe + era * 400 + (
        if m <= 2 then
          1
        else
          0
      )
    ).toInt
    if y < 1000 || y > 9999 then
      LocalDate.ofEpochDay(day.toLong).toString
    else
      val c = new Array[Char](10)
      c(0) = ('0' + y / 1000).toChar
      c(1) = ('0' + y / 100 % 10).toChar
      c(2) = ('0' + y / 10  % 10).toChar
      c(3) = ('0' + y       % 10).toChar
      c(4) = '-'
      c(5) = ('0' + m / 10).toChar
      c(6) = ('0' + m % 10).toChar
      c(7) = '-'
      c(8) = ('0' + d / 10).toChar
      c(9) = ('0' + d % 10).toChar
      String(c)

  end isoDate

  /** A vector with how to read it, worked out once per batch */
  private final class Col(val vec: ColumnVector, binaryAsString: Boolean):
    val nulls: Array[Boolean] = vec.nulls
    private val lt            = vec.field.logicalType
    private var scale         = 0
    private var unit          = TimeUnit.MICROS

    val code: Int =
      vec match
        case _: BooleanColumn =>
          CBool
        case _: IntColumn =>
          lt match
            case Some(LogicalType.Date) =>
              CDate
            case Some(LogicalType.Time(_, _)) =>
              CTimeInt
            case Some(LogicalType.Decimal(s, _)) =>
              scale = s
              CDecimalInt
            case Some(LogicalType.Integer(32, false)) =>
              CUInt32
            case Some(LogicalType.Integer(16, false)) =>
              CUInt16
            case Some(LogicalType.Integer(8, false)) =>
              CUInt8
            case _ =>
              CInt
        case _: LongColumn =>
          lt match
            case Some(LogicalType.Timestamp(_, u)) =>
              unit = u
              CTimestamp
            case Some(LogicalType.Time(_, u)) =>
              unit = u
              CTimeLong
            case Some(LogicalType.Decimal(s, _)) =>
              scale = s
              CDecimalLong
            case Some(LogicalType.Integer(64, false)) =>
              CULong
            case _ =>
              CLong
        case _: FloatColumn =>
          CFloat
        case _: DoubleColumn =>
          CDouble
        case b: BinaryColumn =>
          val int96 =
            b.field match
              case f: ParquetField.Primitive =>
                f.physicalType == PhysicalType.INT96
              case _ =>
                false
          lt match
            case Some(LogicalType.String | LogicalType.Enum | LogicalType.Json) =>
              CStr
            case Some(LogicalType.Decimal(_, _) | LogicalType.Uuid | LogicalType.Float16) =>
              CBinLogical
            case _ if int96 =>
              CInt96
            case _ if binaryAsString =>
              CStr
            case _ =>
              CBin
        case _: ListColumn =>
          CList
        case _: MapColumn =>
          CMap
        case _: StructColumn =>
          CStruct

    /** The type of every non-null value, or null when it depends on the value */
    val tpe: ValueType =
      code match
        case CBool =>
          ValueType.BOOLEAN
        case CInt | CUInt32 | CUInt16 | CUInt8 | CLong =>
          ValueType.INTEGER
        case CDate | CTimeInt | CDecimalInt | CTimeLong | CDecimalLong | CStr =>
          ValueType.STRING
        case CTimestamp =>
          ValueType.EXTENSION
        case CFloat | CDouble =>
          ValueType.FLOAT
        case CBin =>
          ValueType.BINARY
        case CList =>
          ValueType.ARRAY
        case CMap | CStruct =>
          ValueType.MAP
        case _ =>
          null

    val ints: Array[Int] =
      vec match
        case v: IntColumn =>
          v.values
        case _ =>
          null

    val longs: Array[Long] =
      vec match
        case v: LongColumn =>
          v.values
        case _ =>
          null

    private val bin: BinaryColumn =
      vec match
        case b: BinaryColumn =>
          b
        case _ =>
          null

    // Strings of a dictionary-encoded column, built once per dictionary entry
    private val dictIndices: Array[Int] =
      if bin != null && code == CStr then
        bin.dictIndices
      else
        null

    private val dictStrings: Array[String] =
      if dictIndices != null then
        bin.dictionary.strings
      else
        null

    val elements: Col =
      vec match
        case l: ListColumn =>
          Col(l.elements, binaryAsString)
        case _ =>
          null

    val keys: Col =
      vec match
        case m: MapColumn =>
          Col(m.keys, binaryAsString)
        case _ =>
          null

    val values: Col =
      vec match
        case m: MapColumn =>
          Col(m.values, binaryAsString)
        case _ =>
          null

    val children: Array[Col] =
      vec match
        case s: StructColumn =>
          s.children.map(Col(_, binaryAsString)).toArray
        case _ =>
          null

    val names: Array[String] =
      vec match
        case s: StructColumn =>
          s.children.map(_.name).toArray
        case _ =>
          null

    def dynamicType(i: Int): ValueType =
      code match
        case CULong =>
          if longs(i) < 0 then
            ValueType.STRING
          else
            ValueType.INTEGER
        case CBinLogical =>
          logicalBinary(i) match
            case _: Float =>
              ValueType.FLOAT
            case _: Array[Byte] =>
              ValueType.BINARY
            case _ =>
              ValueType.STRING
        case _ =>
          // INT96
          if bin.length(i) == 12 then
            ValueType.EXTENSION
          else if binaryAsString then
            ValueType.STRING
          else
            ValueType.BINARY

    def logicalBinary(i: Int): Any = LogicalValues.fromBinary(
      bin.data,
      bin.offsets(i),
      bin.length(i),
      bin.field
    )

    def string(i: Int): String =
      code match
        case CStr =>
          if dictIndices != null && dictIndices(i) >= 0 then
            val k = dictIndices(i)
            var s = dictStrings(k)
            if s == null then
              s = utf8(i)
              dictStrings(k) = s
            s
          else
            utf8(i)
        case CDate =>
          dateString(ints(i))
        case CTimeInt =>
          LogicalValues.localTime(ints(i).toLong * 1000000L).toString
        case CDecimalInt =>
          BigDecimal(BigInt(ints(i)), scale).toString
        case CTimeLong =>
          LogicalValues.localTime(nanos(longs(i))).toString
        case CDecimalLong =>
          BigDecimal(BigInt(longs(i)), scale).toString
        case CULong =>
          (BigInt(longs(i)) + (BigInt(1) << 64)).toString
        case CBinLogical =>
          logicalBinary(i).toString
        case _ =>
          // INT96 of another length, read as a string
          utf8(i)

    // Recent date strings, by epoch day: a column usually holds few distinct dates
    private lazy val dateDays    = Array.fill(DateCacheSize)(Int.MinValue)
    private lazy val dateStrings = new Array[String](DateCacheSize)

    private def dateString(day: Int): String =
      val slot = day & (DateCacheSize - 1)
      if dateDays(slot) == day then
        dateStrings(slot)
      else
        val s = isoDate(day)
        dateDays(slot) = day
        dateStrings(slot) = s
        s

    private def utf8(i: Int): String = String(
      bin.data,
      bin.offsets(i),
      bin.length(i),
      java.nio.charset.StandardCharsets.UTF_8
    )

    private def nanos(v: Long): Long =
      unit match
        case TimeUnit.MILLIS =>
          v * 1000000L
        case TimeUnit.MICROS =>
          v * 1000L
        case TimeUnit.NANOS =>
          v

    def instant(i: Int): Instant =
      if code == CTimestamp then
        LogicalValues.timestamp(longs(i), unit)
      else
        LogicalValues.int96ToInstant(bin.data, bin.offsets(i))

  end Col

end VectorUnpacker
