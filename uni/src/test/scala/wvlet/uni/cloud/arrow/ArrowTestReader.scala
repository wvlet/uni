package wvlet.uni.cloud.arrow

import wvlet.uni.io.codec.Lz4Frame
import wvlet.uni.io.codec.Zstd

/**
  * A minimal Arrow IPC decoder for tests: it checks what [[ArrowWriter]] writes without a uni
  * reader. It is first checked against apache/arrow-testing files written by Arrow C++ (see
  * ArrowGoldenTest), then used to read the writer's output back.
  *
  * Supports the types the writer writes, plus date64, duration, and fixed_size_list from the golden
  * files. Dictionaries, unions, large and view types fail.
  */
object ArrowTestReader:

  /** Binary values, as uppercase hex, so they compare by value */
  case class Hex(hex: String)

  object Hex:
    def of(b: Array[Byte], off: Int, len: Int): Hex =
      val sb = StringBuilder()
      var i  = 0
      while i < len do
        sb.append(f"${b(off + i) & 0xff}%02X")
        i += 1
      Hex(sb.result())

  /** Interval month_day_nano values */
  case class MonthDayNano(months: Int, days: Int, nanos: Long)

  /** A schema field: the type name of Schema.fbs and its parameters */
  case class RField(
      name: String,
      typeName: String,
      params: Map[String, Any],
      nullable: Boolean,
      children: IndexedSeq[RField],
      metadata: Map[String, String]
  )

  /**
    * One decoded array. `validity` is null when the bitmap is absent. `values` holds a value per
    * row for leaves (also at null rows), and `offsets` the offsets of lists, maps, and binaries.
    */
  case class Col(
      field: RField,
      length: Int,
      nullCount: Int,
      validity: Array[Boolean],
      values: IndexedSeq[Any],
      offsets: IndexedSeq[Int],
      children: IndexedSeq[Col]
  ):
    def isNull(i: Int): Boolean = field.typeName == "Null" || (validity != null && !validity(i))

    /** Row i as a Scala value: null, a leaf value, a Seq for lists, a Map for structs and maps */
    def value(i: Int): Any =
      if isNull(i) then
        null
      else
        field.typeName match
          case "List" =>
            (offsets(i) until offsets(i + 1)).map(children(0).value)
          case "FixedSizeList" =>
            val w = field.params("listSize").asInstanceOf[Int]
            (i * w until (i + 1) * w).map(children(0).value)
          case "Struct_" =>
            children.map(c => c.field.name -> c.value(i)).toMap
          case "Map" =>
            val entries = children(0)
            (offsets(i) until offsets(i + 1))
              .map(j => entries.children(0).value(j) -> entries.children(1).value(j))
              .toMap
          case _ =>
            values(i)

  end Col

  case class Batch(length: Int, columns: IndexedSeq[Col]):
    def column(name: String): Col          = columns.find(_.field.name == name).get
    def rows: IndexedSeq[Map[String, Any]] = (0 until length).map(i =>
      columns.map(c => c.field.name -> c.value(i)).toMap
    )

  case class Result(
      fields: IndexedSeq[RField],
      metadata: Map[String, String],
      batches: IndexedSeq[Batch],
      /** Whether the stream ended with the end-of-stream marker */
      sawEndOfStream: Boolean
  ):
    def field(name: String): RField        = fields.find(_.name == name).get
    def rows: IndexedSeq[Map[String, Any]] = batches.flatMap(_.rows)

  private def fail(msg: String): Nothing = throw AssertionError(s"Arrow test reader: ${msg}")

  private def int32(b: Array[Byte], i: Int): Int =
    (b(i) & 0xff) | ((b(i + 1) & 0xff) << 8) | ((b(i + 2) & 0xff) << 16) | ((b(i + 3) & 0xff) << 24)

  private def int16(b: Array[Byte], i: Int): Int = ((b(i) & 0xff) | ((b(i + 1) & 0xff) << 8))
    .toShort

  private def int64(b: Array[Byte], i: Int): Long =
    (int32(b, i) & 0xffffffffL) | (int32(b, i + 4).toLong << 32)

  /** A FlatBuffers table at `pos` */
  final class Table(val b: Array[Byte], val pos: Int):
    private val vt     = pos - int32(b, pos)
    private val vtSize = int16(b, vt)

    private def off(slot: Int): Int =
      val o = 4 + 2 * slot
      if o >= vtSize then
        0
      else
        int16(b, vt + o)

    def has(slot: Int): Boolean = off(slot) != 0

    def byte(slot: Int, default: Int): Int =
      val o = off(slot)
      if o == 0 then
        default
      else
        b(pos + o) & 0xff

    def bool(slot: Int, default: Boolean): Boolean =
      byte(
        slot,
        if default then
          1
        else
          0
      ) != 0

    def short(slot: Int, default: Int): Int =
      val o = off(slot)
      if o == 0 then
        default
      else
        int16(b, pos + o)

    def int(slot: Int, default: Int): Int =
      val o = off(slot)
      if o == 0 then
        default
      else
        int32(b, pos + o)

    def long(slot: Int, default: Long): Long =
      val o = off(slot)
      if o == 0 then
        default
      else
        int64(b, pos + o)

    private def indirect(slot: Int): Int =
      val p = pos + off(slot)
      p + int32(b, p)

    def table(slot: Int): Table =
      if !has(slot) then
        null
      else
        Table(b, indirect(slot))

    def string(slot: Int): String =
      if !has(slot) then
        null
      else
        val s = indirect(slot)
        String(b, s + 4, int32(b, s), java.nio.charset.StandardCharsets.UTF_8)

    /** The start and length of a vector */
    def vector(slot: Int): (Int, Int) =
      if !has(slot) then
        (0, 0)
      else
        val v = indirect(slot)
        (v + 4, int32(b, v))

    def tables(slot: Int): IndexedSeq[Table] =
      val (start, n) = vector(slot)
      (0 until n).map { i =>
        val p = start + 4 * i
        Table(b, p + int32(b, p))
      }

  end Table

  private def root(b: Array[Byte], off: Int): Table = Table(b, off + int32(b, off))

  private def keyValues(t: Table, slot: Int): Map[String, String] =
    t.tables(slot).map(kv => kv.string(0) -> kv.string(1)).toMap

  private val typeNames = IndexedSeq(
    "NONE",
    "Null",
    "Int",
    "FloatingPoint",
    "Binary",
    "Utf8",
    "Bool",
    "Decimal",
    "Date",
    "Time",
    "Timestamp",
    "Interval",
    "List",
    "Struct_",
    "Union",
    "FixedSizeBinary",
    "FixedSizeList",
    "Map",
    "Duration",
    "LargeBinary",
    "LargeUtf8",
    "LargeList"
  )

  private def field(t: Table): RField =
    val tag      = t.byte(2, 0)
    val typeName = typeNames.applyOrElse(tag, _ => fail(s"unsupported type tag ${tag}"))
    if t.has(4) then
      fail(s"dictionary-encoded field ${t.string(0)} is not supported")
    val tt     = t.table(3)
    val params =
      typeName match
        case "Int" =>
          Map("bitWidth" -> tt.int(0, 0), "isSigned" -> tt.bool(1, false))
        case "FloatingPoint" =>
          Map("precision" -> tt.short(0, 0))
        case "Decimal" =>
          Map("precision" -> tt.int(0, 0), "scale" -> tt.int(1, 0), "bitWidth" -> tt.int(2, 128))
        case "Date" =>
          Map("unit" -> tt.short(0, 1))
        case "Time" =>
          Map("unit" -> tt.short(0, 1), "bitWidth" -> tt.int(1, 32))
        case "Timestamp" =>
          Map("unit" -> tt.short(0, 0), "timezone" -> Option(tt.string(1)))
        case "Interval" =>
          Map("unit" -> tt.short(0, 0))
        case "Duration" =>
          Map("unit" -> tt.short(0, 1))
        case "FixedSizeBinary" =>
          Map("byteWidth" -> tt.int(0, 0))
        case "FixedSizeList" =>
          Map("listSize" -> tt.int(0, 0))
        case "Map" =>
          Map("keysSorted" -> tt.bool(0, false))
        case "Union" | "LargeBinary" | "LargeUtf8" | "LargeList" | "NONE" =>
          fail(s"type ${typeName} is not supported")
        case _ =>
          Map.empty[String, Any]
    RField(t.string(0), typeName, params, t.bool(1, false), t.tables(5).map(field), keyValues(t, 6))

  end field

  private def schemaOf(t: Table): (IndexedSeq[RField], Map[String, String]) =
    if t.short(0, 0) != 0 then
      fail("big-endian data is not supported")
    (t.tables(1).map(field), keyValues(t, 2))

  /** Reads a stream; `requireEos` checks the end-of-stream marker */
  def readStream(bytes: Array[Byte]): Result =
    var pos                        = 0
    var fields: IndexedSeq[RField] = null
    var metadata                   = Map.empty[String, String]
    val batches                    = IndexedSeq.newBuilder[Batch]
    var eos                        = false
    while !eos && pos < bytes.length do
      var len = int32(bytes, pos)
      pos += 4
      if len == -1 then
        len = int32(bytes, pos)
        pos += 4
      if len == 0 then
        eos = true
      else
        if (pos + len) % 8 != 0 then
          fail(s"the body at ${pos + len} is not 8-byte aligned")
        val msg = root(bytes, pos)
        pos += len
        if msg.short(0, 0) != 4 then
          fail(s"metadata version ${msg.short(0, 0)} is not V5")
        val bodyLength = msg.long(3, 0L)
        msg.byte(1, 0) match
          case 1 =>
            val (f, m) = schemaOf(msg.table(2))
            fields = f
            metadata = m
          case 3 =>
            if fields == null then
              fail("a record batch came before the schema")
            batches += recordBatch(fields, msg.table(2), bytes, pos)
          case other =>
            fail(s"message type ${other} is not supported")
        pos += bodyLength.toInt
    if fields == null then
      fail("no schema")
    Result(fields, metadata, batches.result(), eos)

  end readStream

  /** Reads a file through its footer, and checks that its stream part reads the same */
  def readFile(bytes: Array[Byte]): Result =
    val magic = "ARROW1".getBytes("US-ASCII")
    if !bytes.take(6).sameElements(magic) || !bytes.takeRight(6).sameElements(magic) then
      fail("the file magic is missing")
    val footerLen = int32(bytes, bytes.length - 10)
    val footerPos = bytes.length - 10 - footerLen
    val footer    = root(bytes, footerPos)
    if footer.short(0, 0) != 4 then
      fail("the footer is not V5")
    val (fields, metadata) = schemaOf(footer.table(1))
    val (start, n)         = footer.vector(3)
    val batches            = (0 until n).map { i =>
      val p          = start + 24 * i
      val offset     = int64(bytes, p).toInt
      val metaLength = int32(bytes, p + 8)
      val bodyLength = int64(bytes, p + 16)
      if offset % 8 != 0 then
        fail(s"block ${i} starts at ${offset}, not 8-byte aligned")
      if int32(bytes, offset) != -1 then
        fail(s"block ${i} has no continuation marker")
      val len = int32(bytes, offset + 4)
      if metaLength != len + 8 then
        fail(s"block ${i} metadata length ${metaLength} is not ${len + 8}")
      val msg = root(bytes, offset + 8)
      if msg.byte(1, 0) != 3 || msg.long(3, 0L) != bodyLength then
        fail(s"block ${i} is not the record batch it describes")
      recordBatch(fields, msg.table(2), bytes, offset + metaLength)
    }
    val stream = readStream(bytes.slice(8, footerPos))
    if stream.fields != fields then
      fail("the footer schema differs from the stream schema")
    if stream.batches.size != batches.size then
      fail(s"the stream has ${stream.batches.size} batches, the footer ${batches.size}")
    Result(fields, metadata, batches, stream.sawEndOfStream)

  end readFile

  private def recordBatch(fields: IndexedSeq[RField], rb: Table, b: Array[Byte], body: Int): Batch =
    val length          = rb.long(0, 0L).toInt
    val (nodeStart, nn) = rb.vector(1)
    val (bufStart, nb)  = rb.vector(2)
    val comp            = rb.table(3)
    val codec           =
      if comp == null then
        -1
      else
        comp.byte(0, 0)
    var node                   = 0
    var buf                    = 0
    def nextNode(): (Int, Int) =
      if node >= nn then
        fail("too few field nodes")
      val p = nodeStart + 16 * node
      node += 1
      (int64(b, p).toInt, int64(b, p + 8).toInt)
    // A buffer's bytes, decompressed
    def nextBuffer(): Array[Byte] =
      if buf >= nb then
        fail("too few buffers")
      val p = bufStart + 16 * buf
      buf += 1
      val off = int64(b, p).toInt
      val len = int64(b, p + 8).toInt
      if off % 8 != 0 then
        fail(s"buffer ${buf - 1} at ${off} is not 8-byte aligned")
      val start = body + off
      if codec < 0 || len == 0 then
        b.slice(start, start + len)
      else
        val size = int64(b, start)
        if size == -1L then
          b.slice(start + 8, start + len)
        else if codec == 0 then
          Lz4Frame.decompress(b, start + 8, len - 8, size.toInt)
        else
          Zstd.decompress(b, start + 8, len - 8, size.toInt)
    def bit(bits: Array[Byte], i: Int): Boolean = (bits(i >> 3) & (1 << (i & 7))) != 0

    def col(f: RField): Col =
      val (n, nulls) = nextNode()
      if f.typeName == "Null" then
        return Col(f, n, nulls, null, IndexedSeq.fill(n)(null), IndexedSeq.empty, IndexedSeq.empty)
      val vb       = nextBuffer()
      val validity =
        if vb.isEmpty then
          if nulls != 0 then
            fail(s"${f.name} has ${nulls} nulls and no validity bitmap")
          null
        else
          Array.tabulate(n)(bit(vb, _))
      if validity != null && validity.count(!_) != nulls then
        fail(s"${f.name} null count ${nulls} does not match its bitmap")
      def offsetsOf(ob: Array[Byte]): IndexedSeq[Int] =
        if n == 0 && ob.isEmpty then
          IndexedSeq(0)
        else
          (0 to n).map(i => int32(ob, 4 * i))
      def fixed(width: Int)(read: (Array[Byte], Int) => Any): IndexedSeq[Any] =
        val db = nextBuffer()
        if db.length < width * n then
          fail(s"${f.name} has ${db.length} data bytes for ${n} values of ${width}")
        (0 until n).map(i => read(db, i * width))
      def unsignedOrSigned(bits: Int, signed: Boolean)(db: Array[Byte], off: Int): Any =
        val v =
          bits match
            case 8 =>
              b8(db, off, signed)
            case 16 =>
              val x = int16(db, off)
              if signed then
                x.toLong
              else
                (x & 0xffff).toLong
            case 32 =>
              val x = int32(db, off)
              if signed then
                x.toLong
              else
                x & 0xffffffffL
            case _ =>
              int64(db, off)
        if bits == 64 && !signed && v < 0 then
          BigInt(v) + (BigInt(1) << 64)
        else
          BigInt(v)
      def leaf(values: IndexedSeq[Any], offsets: IndexedSeq[Int] = IndexedSeq.empty) = Col(
        f,
        n,
        nulls,
        validity,
        values,
        offsets,
        IndexedSeq.empty
      )
      f.typeName match
        case "Bool" =>
          val db = nextBuffer()
          leaf((0 until n).map(bit(db, _)))
        case "Int" =>
          val bits   = f.params("bitWidth").asInstanceOf[Int]
          val signed = f.params("isSigned").asInstanceOf[Boolean]
          leaf(fixed(bits / 8)(unsignedOrSigned(bits, signed)))
        case "FloatingPoint" =>
          f.params("precision") match
            case 0 =>
              leaf(fixed(2)((db, o) => halfToFloat(int16(db, o) & 0xffff)))
            case 1 =>
              leaf(fixed(4)((db, o) => java.lang.Float.intBitsToFloat(int32(db, o))))
            case _ =>
              leaf(fixed(8)((db, o) => java.lang.Double.longBitsToDouble(int64(db, o))))
        case "Utf8" | "Binary" =>
          val offsets = offsetsOf(nextBuffer())
          val db      = nextBuffer()
          val values  = (0 until n).map { i =>
            val s = offsets(i)
            val e = offsets(i + 1)
            if f.typeName == "Utf8" then
              String(db, s, e - s, java.nio.charset.StandardCharsets.UTF_8)
            else
              Hex.of(db, s, e - s)
          }
          leaf(values, offsets)
        case "FixedSizeBinary" =>
          val w = f.params("byteWidth").asInstanceOf[Int]
          leaf(fixed(w)((db, o) => Hex.of(db, o, w)))
        case "Decimal" =>
          val w = f.params("bitWidth").asInstanceOf[Int] / 8
          leaf(fixed(w)((db, o) => BigInt(db.slice(o, o + w).reverse)))
        case "Date" =>
          if f.params("unit") == 0 then
            leaf(fixed(4)((db, o) => BigInt(int32(db, o))))
          else
            leaf(fixed(8)((db, o) => BigInt(int64(db, o))))
        case "Time" =>
          val w = f.params("bitWidth").asInstanceOf[Int] / 8
          leaf(fixed(w)(unsignedOrSigned(w * 8, true)))
        case "Timestamp" | "Duration" =>
          leaf(fixed(8)((db, o) => BigInt(int64(db, o))))
        case "Interval" =>
          f.params("unit") match
            case 2 =>
              leaf(
                fixed(16)((db, o) => MonthDayNano(int32(db, o), int32(db, o + 4), int64(db, o + 8)))
              )
            case 1 =>
              leaf(fixed(8)((db, o) => (int32(db, o), int32(db, o + 4))))
            case _ =>
              leaf(fixed(4)((db, o) => int32(db, o)))
        case "List" | "Map" =>
          val offsets = offsetsOf(nextBuffer())
          Col(f, n, nulls, validity, IndexedSeq.empty, offsets, f.children.map(col))
        case "Struct_" | "FixedSizeList" =>
          Col(f, n, nulls, validity, IndexedSeq.empty, IndexedSeq.empty, f.children.map(col))
        case other =>
          fail(s"type ${other} is not supported")
      end match

    end col

    val columns = fields.map(col)
    if node != nn || buf != nb then
      fail(s"used ${node} of ${nn} field nodes and ${buf} of ${nb} buffers")
    Batch(length, columns)

  end recordBatch

  private def b8(db: Array[Byte], off: Int, signed: Boolean): Long =
    if signed then
      db(off).toLong
    else
      (db(off) & 0xff).toLong

  /** IEEE 754 half precision to Float */
  def halfToFloat(h: Int): Float =
    val sign = (h >>> 15) & 1
    val exp  = (h >>> 10) & 0x1f
    val frac = h & 0x3ff
    val v    =
      if exp == 0 then
        frac * math.pow(2, -24)
      else if exp == 31 then
        if frac == 0 then
          Double.PositiveInfinity
        else
          Double.NaN
      else
        (1 + frac / 1024.0) * math.pow(2, exp - 15)
    (
      if sign == 1 then
        -v
      else
        v
    ).toFloat

end ArrowTestReader
