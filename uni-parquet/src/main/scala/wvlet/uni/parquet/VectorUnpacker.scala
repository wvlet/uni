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
  * Column names are the schema's own Strings, so no key is built per row. Call [[startRow]] before
  * Weaver reads each row.
  */
private[parquet] final class VectorUnpacker(batch: ColumnBatch, binaryAsString: Boolean)
    extends Unpacker:
  import VectorUnpacker.*

  private val columns: Array[ColumnVector] = batch.columns.toArray

  // The value at the cursor
  private var kind: Int          = End
  private var key: String        = null
  private var vec: ColumnVector  = null
  private var idx: Int           = 0
  private var tpe: ValueType     = null
  private var payload: Array[Byte] = null
  private var payloadOff: Int    = 0
  private var payloadLen: Int    = 0
  private var instant: Instant   = null
  private var row: Int           = 0

  // Open containers: maps and arrays whose entries are being read
  private var frames = new Array[Frame](4)
  private var depth  = 0

  private final class Frame:
    var kind: Int                      = 0
    var children: Array[ColumnVector]  = null
    var structChildren: IndexedSeq[ColumnVector] = null
    var a: ColumnVector                = null
    var b: ColumnVector                = null
    var row: Int                       = 0
    var k: Int                         = 0
    var end: Int                       = 0

    /** Moves the cursor to the next entry; false when there is none */
    def next(): Boolean =
      if k >= end then
        false
      else
        kind match
          case FStruct =>
            val c =
              if children != null then
                children(k >> 1)
              else
                structChildren(k >> 1)
            if (k & 1) == 0 then
              setKey(c.name)
            else
              setCell(c, row)
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

  private def push(): Frame =
    if depth == frames.length then
      frames = java.util.Arrays.copyOf(frames, depth * 2)
    if frames(depth) == null then
      frames(depth) = Frame()
    val f = frames(depth)
    depth += 1
    f.children = null
    f.structChildren = null
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

  private def setCell(c: ColumnVector, i: Int): Unit =
    kind = Cell
    vec = c
    idx = i
    tpe = typeOf(c, i)

  private def typeOf(c: ColumnVector, i: Int): ValueType =
    if c.isNull(i) then
      ValueType.NIL
    else
      c match
        case _: BooleanColumn =>
          ValueType.BOOLEAN
        case v: IntColumn =>
          v.field.logicalType match
            case Some(LogicalType.Date | LogicalType.Time(_, _) | LogicalType.Decimal(_, _)) =>
              ValueType.STRING
            case _ =>
              ValueType.INTEGER
        case v: LongColumn =>
          v.field.logicalType match
            case Some(LogicalType.Timestamp(_, _)) =>
              ValueType.EXTENSION
            case Some(LogicalType.Time(_, _) | LogicalType.Decimal(_, _)) =>
              ValueType.STRING
            case Some(LogicalType.Integer(64, false)) if v.values(i) < 0 =>
              ValueType.STRING
            case _ =>
              ValueType.INTEGER
        case _: FloatColumn | _: DoubleColumn =>
          ValueType.FLOAT
        case b: BinaryColumn =>
          binaryType(b, i)
        case _: ListColumn =>
          ValueType.ARRAY
        case _: MapColumn | _: StructColumn =>
          ValueType.MAP

  private def binaryType(c: BinaryColumn, i: Int): ValueType =
    c.field.logicalType match
      case Some(LogicalType.String | LogicalType.Enum | LogicalType.Json) =>
        ValueType.STRING
      case Some(LogicalType.Decimal(_, _)) | Some(LogicalType.Uuid) | Some(LogicalType.Float16) =>
        LogicalValues.fromBinary(c.data, c.offsets(i), c.length(i), c.field) match
          case _: Float =>
            ValueType.FLOAT
          case _: Array[Byte] =>
            ValueType.BINARY
          case _ =>
            ValueType.STRING
      case _ if isInt96(c) && c.length(i) == 12 =>
        ValueType.EXTENSION
      case _ if binaryAsString =>
        ValueType.STRING
      case _ =>
        ValueType.BINARY

  private def isInt96(c: BinaryColumn): Boolean =
    c.field match
      case f: ParquetField.Primitive =>
        f.physicalType == PhysicalType.INT96
      case _ =>
        false

  /** True when the string at the cursor is the UTF-8 bytes of a binary column */
  private def isStoredString: Boolean =
    vec match
      case b: BinaryColumn =>
        b.field.logicalType match
          case Some(LogicalType.String | LogicalType.Enum | LogicalType.Json) =>
            true
          case Some(_) =>
            false
          case None =>
            binaryAsString && !(isInt96(b) && b.length(idx) == 12)
      case _ =>
        false

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
    val v = vec.asInstanceOf[BooleanColumn].values(idx)
    advance()
    v

  override def unpackByte: Byte   = unpackLong.toByte
  override def unpackShort: Short = unpackLong.toShort
  override def unpackInt: Int     = unpackLong.toInt

  override def unpackLong: Long =
    expect(ValueType.INTEGER)
    val v =
      vec match
        case c: IntColumn =>
          val x = c.values(idx)
          c.field.logicalType match
            case Some(LogicalType.Integer(32, false)) =>
              x.toLong & 0xffffffffL
            case Some(LogicalType.Integer(16, false)) =>
              (x & 0xffff).toLong
            case Some(LogicalType.Integer(8, false)) =>
              (x & 0xff).toLong
            case _ =>
              x.toLong
        case c: LongColumn =>
          c.values(idx)
        case _ =>
          mismatch("an integer")
    advance()
    v

  override def unpackBigInteger: BigInteger = BigInteger.valueOf(unpackLong)

  override def unpackFloat: Float = unpackDouble.toFloat

  override def unpackDouble: Double =
    expect(ValueType.FLOAT)
    val v =
      vec match
        case c: FloatColumn =>
          c.values(idx).toDouble
        case c: DoubleColumn =>
          c.values(idx)
        case c: BinaryColumn =>
          // FLOAT16
          LogicalValues
            .fromBinary(c.data, c.offsets(idx), c.length(idx), c.field)
            .asInstanceOf[Float]
            .toDouble
        case _ =>
          mismatch("a float")
    advance()
    v

  override def unpackString: String =
    if kind == Key then
      val k = key
      advance()
      k
    else
      expect(ValueType.STRING)
      val s = stringAt()
      advance()
      s

  private def stringAt(): String =
    vec match
      case b: BinaryColumn if isStoredString =>
        String(b.data, b.offsets(idx), b.length(idx), "UTF-8")
      case b: BinaryColumn =>
        LogicalValues.fromBinary(b.data, b.offsets(idx), b.length(idx), b.field).toString
      case c: IntColumn =>
        val v = c.values(idx)
        c.field.logicalType match
          case Some(LogicalType.Date) =>
            LocalDate.ofEpochDay(v.toLong).toString
          case Some(LogicalType.Time(_, _)) =>
            LogicalValues.localTime(v.toLong * 1000000L).toString
          case Some(LogicalType.Decimal(scale, _)) =>
            BigDecimal(BigInt(v), scale).toString
          case _ =>
            v.toString
      case c: LongColumn =>
        val v = c.values(idx)
        c.field.logicalType match
          case Some(LogicalType.Time(_, unit)) =>
            LogicalValues.localTime(nanos(v, unit)).toString
          case Some(LogicalType.Decimal(scale, _)) =>
            BigDecimal(BigInt(v), scale).toString
          case _ =>
            // UINT64 past Long.MaxValue
            (BigInt(v) + (BigInt(1) << 64)).toString
      case _ =>
        mismatch("a string")

  private def nanos(v: Long, unit: TimeUnit): Long =
    unit match
      case TimeUnit.MILLIS =>
        v * 1000000L
      case TimeUnit.MICROS =>
        v * 1000L
      case TimeUnit.NANOS =>
        v

  override def unpackTimestamp: Instant =
    if kind == Ext then
      val t = instant
      advance()
      t
    else
      expect(ValueType.EXTENSION)
      val t = instantAt()
      advance()
      t

  override def unpackTimestamp(extTypeHeader: ExtTypeHeader): Instant = unpackTimestamp

  private def instantAt(): Instant =
    vec match
      case c: LongColumn =>
        c.field.logicalType match
          case Some(LogicalType.Timestamp(_, unit)) =>
            LogicalValues.timestamp(c.values(idx), unit)
          case _ =>
            mismatch("a timestamp")
      case b: BinaryColumn =>
        LogicalValues.int96ToInstant(b.data, b.offsets(idx))
      case _ =>
        mismatch("a timestamp")

  override def unpackArrayHeader: Int =
    expect(ValueType.ARRAY)
    val l    = vec.asInstanceOf[ListColumn]
    val from = l.offsets(idx)
    val to   = l.offsets(idx + 1)
    val f    = push()
    f.kind = FList
    f.a = l.elements
    f.k = from
    f.end = to
    advance()
    to - from

  override def unpackMapHeader: Int =
    if kind == Row then
      val f = push()
      f.kind = FStruct
      f.children = columns
      f.row = row
      f.end = 2 * columns.length
      advance()
      columns.length
    else
      expect(ValueType.MAP)
      vec match
        case s: StructColumn =>
          val f = push()
          f.kind = FStruct
          f.structChildren = s.children
          f.row = idx
          f.end = 2 * s.children.size
          advance()
          s.children.size
        case m: MapColumn =>
          val from = m.offsets(idx)
          val n    = m.offsets(idx + 1) - from
          val f    = push()
          f.kind = FMap
          f.a = m.keys
          f.b = m.values
          f.row = from
          f.end = 2 * n
          advance()
          n
        case _ =>
          mismatch("a map")

  override def unpackExtTypeHeader: ExtTypeHeader =
    expect(ValueType.EXTENSION)
    instant = instantAt()
    kind = Ext
    ExtTypeHeader(TimestampExtType, 12)

  override def unpackExtValue(extTypeHeader: ExtTypeHeader): Value = Value
    .TimestampValue(unpackTimestamp)

  override def unpackRawStringHeader: Int =
    if kind == Key then
      setPayload(key.getBytes("UTF-8"))
    else
      expect(ValueType.STRING)
      vec match
        case b: BinaryColumn if isStoredString =>
          startPayload(b.data, b.offsets(idx), b.length(idx))
        case _ =>
          setPayload(stringAt().getBytes("UTF-8"))

  override def unpackBinaryHeader: Int =
    expect(ValueType.BINARY)
    val b = vec.asInstanceOf[BinaryColumn]
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
    if length == 0 then
      ()
    else if kind != Payload || length > payloadLen then
      throw IllegalStateException("No payload to read")
    else
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
          RowPacker(binaryAsString).pack(p, vec, idx)
          MessagePack.newUnpacker(p.toByteArray).unpackValue
        case _ =>
          throw IllegalStateException("No value to read")
    advance()
    v

  override def close(): Unit = ()

end VectorUnpacker

private object VectorUnpacker:
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
