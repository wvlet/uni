package wvlet.uni.parquet.writer

import wvlet.uni.parquet.ParquetException
import wvlet.uni.parquet.ParquetField
import wvlet.uni.parquet.ParquetSchema
import wvlet.uni.parquet.format.*
import wvlet.uni.parquet.io.ByteBuf
import wvlet.uni.parquet.io.DoubleBuf
import wvlet.uni.parquet.io.IntBuf
import wvlet.uni.parquet.io.LongBuf

/**
  * Splits a row into leaf column values with repetition and definition levels (Dremel striping),
  * the inverse of the reader's assembly. A row is MessagePack bytes: a map of column names to
  * values, or an array of values in schema order. The bytes are read as a stream, with no value
  * tree in between.
  *
  * Struct fields are matched by position first, so a case class packed by Weaver (whose fields come
  * in schema order) needs no name lookup. From the first key that does not match its position on,
  * the rest of the map is looked up by name: the exact name first, then a match that ignores case,
  * `_`, and `-`, so `user_name` fills a `userName` column as it does when reading. When a key
  * appears twice, the first one counts.
  *
  * A row is shredded into a staging area first and copied to the column writers only when every
  * value fits, so a rejected row leaves no partial values behind. Staged values stay unboxed.
  */
private[parquet] final class Shredder(schema: ParquetSchema):
  import Shredder.*

  private val leaves = schema.columns
  private val stages = leaves.map(Stage(_)).toArray
  private val root   = StructNode(null, schema.fields.map(build).toArray)
  private val in     = MsgReader()
  // Seconds and nanoseconds of the last timestamp read
  private val ts = new Array[Long](2)

  private def build(f: ParquetField): Node =
    f match
      case p: ParquetField.Primitive =>
        LeafNode(p, ValueConverter.forField(p))
      case g: ParquetField.Group =>
        g.logicalType match
          case Some(LogicalType.List) if isWrapper(g) =>
            g.children.head match
              case c: ParquetField.Group if isThreeLevelList(g, c) =>
                ListNode(g, build(c), Some(build(c.children.head)))
              case c =>
                ListNode(g, build(c), None)
          case Some(LogicalType.Map | LogicalType.MapKeyValue) if isWrapper(g) =>
            g.children.head match
              case kv: ParquetField.Group if kv.children.nonEmpty =>
                MapNode(g, LeafRange(kv), kv.children.map(build).toArray)
              case _ =>
                InvalidNode(g, "the map has no key_value group")
          case _ =>
            StructNode(g, g.children.map(build).toArray)

  /** Shreds a row into the staging area; throws ParquetException when a value does not fit */
  def shred(row: Array[Byte], len: Int): Unit =
    var i = 0
    while i < stages.length do
      stages(i).reset()
      i += 1
    in.reset(row, len)
    if in.isMap then
      writeStruct(root, 0, 0)
    else if in.isArray then
      val n = in.readArrayHeader()
      if n != root.children.length then
        throw ParquetException(
          s"A row of ${n} values does not match the ${root.children.length} columns of the schema"
        )
      root.children.foreach(writeField(_, 0, 0))
    else
      throw ParquetException(s"A row must be a map or an array, but got ${in.valueType}")

  /** Copies the staged row into the column writers */
  def commit(writers: IndexedSeq[ColumnWriter]): Unit =
    val row = in.bytes
    var i   = 0
    while i < stages.length do
      stages(i).commit(writers(i), row)
      i += 1

  private def fail(f: ParquetField, message: String): Nothing =
    throw ParquetException(s"Cannot write column ${f.pathString}: ${message}")

  // The leaves under a field are the contiguous column indexes from its first leaf to its last
  private def nulls(n: Node, r: Int, d: Int): Unit = nulls(n.range, r, d)

  private def nulls(range: LeafRange, r: Int, d: Int): Unit =
    var col = range.first
    while col <= range.last do
      stages(col).addNull(r, d)
      col += 1

  /** A field whose key is missing from its map */
  private def writeMissing(n: Node, r: Int, d: Int): Unit =
    if n.field.repetition == Repetition.REQUIRED then
      fail(n.field, "a required value is missing")
    nulls(n, r, d)

  private def writeField(n: Node, r: Int, d: Int): Unit =
    n.field.repetition match
      case Repetition.REQUIRED =>
        if in.isNil then
          fail(n.field, "a required value is missing")
        writeValue(n, r, d)
      case Repetition.OPTIONAL =>
        if in.isNil then
          in.skipNil()
          nulls(n, r, d)
        else
          writeValue(n, r, d + 1)
      case Repetition.REPEATED =>
        if in.isNil then
          in.skipNil()
          nulls(n, r, d)
        else
          val count = arrayHeader(n.field)
          if count == 0 then
            nulls(n, r, d)
          else
            var i = 0
            while i < count do
              writeValue(n, repAt(i, r, n.field), d + 1)
              i += 1

  private inline def repAt(i: Int, r: Int, f: ParquetField): Int =
    if i == 0 then
      r
    else
      f.maxRepetitionLevel

  private def arrayHeader(f: ParquetField): Int =
    if !in.isArray then
      fail(f, s"expected an array, but got ${in.valueType}")
    in.readArrayHeader()

  private def writeValue(n: Node, r: Int, d: Int): Unit =
    n match
      case leaf: LeafNode =>
        writeLeaf(leaf, r, d)
      case s: StructNode =>
        if !in.isMap then
          fail(s.field, s"expected a struct, but got ${in.valueType}")
        writeStruct(s, r, d)
      case l: ListNode =>
        val count = arrayHeader(l.field)
        if count == 0 then
          nulls(l.repeated, r, d)
        else
          var i = 0
          while i < count do
            val rep = repAt(i, r, l.repeated.field)
            l.element match
              case Some(e) =>
                writeField(e, rep, d + 1)
              case None =>
                writeValue(l.repeated, rep, d + 1)
            i += 1
      case m: MapNode =>
        if !in.isMap then
          fail(m.field, s"expected a map, but got ${in.valueType}")
        val count = in.readMapHeader()
        if count == 0 then
          nulls(m.entries, r, d)
        else
          var i = 0
          while i < count do
            val rep = repAt(i, r, m.group.children.head)
            writeField(m.children(0), rep, d + 1)
            if m.children.length > 1 then
              writeField(m.children(1), rep, d + 1)
            else
              in.skip()
            var j = 2
            while j < m.children.length do
              writeMissing(m.children(j), rep, d + 1)
              j += 1
            i += 1
      case x: InvalidNode =>
        fail(x.field, x.message)

  private def writeStruct(s: StructNode, r: Int, d: Int): Unit =
    val count    = in.readMapHeader()
    val children = s.children
    val seen     = s.seen
    java.util.Arrays.fill(seen, false)
    // Keys in schema order need no lookup
    var i          = 0
    var positional = true
    while positional && i < count && i < children.length do
      val keyPos = in.pos
      if in.keyEquals(s.names(i)) then
        writeField(children(i), r, d)
        seen(i) = true
        i += 1
      else
        in.pos = keyPos
        positional = false
    if i < count then
      writeByName(s, count - i, r, d)
    var j = 0
    while j < children.length do
      if !seen(j) then
        writeMissing(children(j), r, d)
      j += 1

  /** Writes the remaining `count` entries of a map by looking each child up by name */
  private def writeByName(s: StructNode, count: Int, r: Int, d: Int): Unit =
    val keys      = new Array[String](count)
    val positions = new Array[Int](count)
    var i         = 0
    while i < count do
      keys(i) = in.readKey()
      positions(i) = in.pos
      in.skip()
      i += 1
    val end = in.pos
    // Loose forms of the keys, computed once per map when a child needs them
    var looseKeys: Array[String] = null
    var j                        = 0
    while j < s.children.length do
      if !s.seen(j) then
        val name = s.children(j).field.name
        var at   = keys.indexOf(name)
        if at < 0 then
          if looseKeys == null then
            looseKeys = keys.map(k =>
              if k == null then
                null
              else
                canonical(k)
            )
          at = looseKeys.indexOf(s.canonical(j))
        if at >= 0 then
          in.pos = positions(at)
          writeField(s.children(j), r, d)
          s.seen(j) = true
      j += 1
    in.pos = end

  end writeByName

  private def writeLeaf(n: LeafNode, r: Int, d: Int): Unit =
    val st = stages(n.col)
    n.kind match
      case Kind.Bool if in.isBoolean =>
        st.addInt(
          r,
          d,
          if in.readBoolean() then
            1
          else
            0
        )
      case Kind.Int32 if in.isLong =>
        st.addInt(r, d, inRange(n, in.readLong()).toInt)
      case Kind.Int64 if in.isLong =>
        st.addLong(r, d, inRange(n, in.readLong()))
      case Kind.Float if in.isFloat || in.isLong =>
        st.addInt(r, d, java.lang.Float.floatToRawIntBits(number().toFloat))
      case Kind.Double if in.isFloat || in.isLong =>
        st.addDouble(r, d, number())
      case Kind.Bytes if in.isString || in.isBinary =>
        val len = in.readRawHeader()
        st.addRowBytes(r, d, in.pos, len)
        in.pos += len
      case Kind.Timestamp if in.isTimestamp =>
        in.readTimestamp(ts)
        st.addLong(r, d, timestamp(n, ts(0), ts(1)))
      case Kind.Timestamp if in.isLong =>
        // Epoch milliseconds, as Weaver reads integers into Instant
        val millis = in.readLong()
        st.addLong(
          r,
          d,
          timestamp(n, Math.floorDiv(millis, 1000L), Math.floorMod(millis, 1000L) * 1000000L)
        )
      case _ =>
        val v        = in.readValue()
        val physical =
          try
            n.convert(v)
          catch
            case e: ValueConverter.Mismatch =>
              fail(n.field, e.getMessage)
        st.addConverted(r, d, physical)
    end match

  end writeLeaf

  private def number(): Double =
    if in.isFloat then
      in.readDouble()
    else
      in.readLong().toDouble

  private def inRange(n: LeafNode, v: Long): Long =
    if v < n.min || v > n.max then
      fail(n.field, s"${v} is outside the column's range ${n.min} to ${n.max}")
    v

  private def timestamp(n: LeafNode, seconds: Long, nanos: Long): Long =
    try
      n.unit match
        case TimeUnit.MILLIS =>
          Math.addExact(Math.multiplyExact(seconds, 1000L), nanos / 1000000L)
        case TimeUnit.MICROS =>
          Math.addExact(Math.multiplyExact(seconds, 1000000L), nanos / 1000L)
        case TimeUnit.NANOS =>
          Math.addExact(Math.multiplyExact(seconds, 1000000000L), nanos)
    catch
      case _: ArithmeticException =>
        val t = java.time.Instant.ofEpochSecond(seconds, nanos)
        fail(n.field, s"${t} is outside the range of TIMESTAMP(${n.unit})")

end Shredder

private object Shredder:

  /** A LIST or MAP group with a single repeated child */
  def isWrapper(g: ParquetField.Group): Boolean = g.children.size == 1 && g.children.head.isRepeated

  /**
    * True when the repeated group of a LIST wraps the element (`list.element`); false for the
    * legacy 2-level layouts, where the repeated group is the element itself
    */
  def isThreeLevelList(list: ParquetField.Group, rep: ParquetField.Group): Boolean =
    rep.children.size == 1 && rep.name != "array" && rep.name != s"${list.name}_tuple"

  def canonical(name: String): String = name.toLowerCase.filterNot(c => c == '_' || c == '-')

  /** The leaf columns under a field: first to last, inclusive */
  final class LeafRange(f: ParquetField):
    val first: Int = edgeLeaf(f, first = true)
    val last: Int  = edgeLeaf(f, first = false)

  private def edgeLeaf(f: ParquetField, first: Boolean): Int =
    f match
      case p: ParquetField.Primitive =>
        p.columnIndex
      case g: ParquetField.Group =>
        edgeLeaf(
          if first then
            g.children.head
          else
            g.children.last
          ,
          first
        )

  sealed abstract class Node(val field: ParquetField):
    lazy val range: LeafRange = LeafRange(field)

  /** The kinds of leaf with a fast path for the forms Weaver and JSON produce most */
  enum Kind:
    case Bool,
      Int32,
      Int64,
      Float,
      Double,
      Bytes,
      Timestamp,
      Other

  final class LeafNode(p: ParquetField.Primitive, val convert: wvlet.uni.msgpack.spi.Value => Any)
      extends Node(p):
    val col: Int   = p.columnIndex
    var min        = Long.MinValue
    var max        = Long.MaxValue
    var unit       = TimeUnit.MICROS
    val kind: Kind =
      (p.physicalType, p.logicalType) match
        case (PhysicalType.BOOLEAN, _) =>
          Kind.Bool
        case (PhysicalType.INT32, None) =>
          min = Int.MinValue
          max = Int.MaxValue
          Kind.Int32
        case (PhysicalType.INT32, Some(LogicalType.Integer(bits, signed))) =>
          if signed then
            min = -(1L << (bits - 1))
            max = (1L << (bits - 1)) - 1
          else
            min = 0L
            max = (1L << bits) - 1
          Kind.Int32
        case (PhysicalType.INT64, None | Some(LogicalType.Integer(_, true))) =>
          Kind.Int64
        case (PhysicalType.INT64, Some(LogicalType.Integer(64, false))) =>
          min = 0L
          Kind.Int64
        case (PhysicalType.INT64, Some(LogicalType.Timestamp(_, u))) =>
          unit = u
          Kind.Timestamp
        case (PhysicalType.FLOAT, None) =>
          Kind.Float
        case (PhysicalType.DOUBLE, None) =>
          Kind.Double
        case (PhysicalType.BYTE_ARRAY, None | Some(LogicalType.String | LogicalType.Enum)) =>
          Kind.Bytes
        case _ =>
          Kind.Other

  end LeafNode

  final class StructNode(g: ParquetField.Group, val children: Array[Node]) extends Node(g):
    val names: Array[Array[Byte]] = children.map(_.field.name.getBytes("UTF-8"))
    val canonical: Array[String]  = children.map(c => Shredder.canonical(c.field.name))
    // Which children the current map has filled. A field never contains itself, so one array per
    // node is enough.
    val seen: Array[Boolean] = new Array[Boolean](children.length)

  /**
    * A LIST group. `repeated` is its repeated child; `element` is the element under it in the
    * 3-level layout, or None when the repeated field is the element itself.
    */
  final class ListNode(g: ParquetField.Group, val repeated: Node, val element: Option[Node])
      extends Node(g)

  /** A MAP group; `children` are the key, value, and any other fields of its key_value group */
  final class MapNode(
      val group: ParquetField.Group,
      val entries: LeafRange,
      val children: Array[Node]
  ) extends Node(group)

  final class InvalidNode(g: ParquetField.Group, val message: String) extends Node(g)

  /**
    * One row's values of one leaf column, unboxed. A string or binary value read as is stays in the
    * row's bytes, which live until the row is committed; the stage keeps only its offset.
    */
  final class Stage(p: ParquetField.Primitive):
    private val maxDef   = p.maxDefinitionLevel
    private val physical = p.physicalType
    private val reps     = IntBuf(4)
    private val defs     = IntBuf(4)
    // INT32, BOOLEAN (0 or 1), and FLOAT (raw bits)
    private val ints    = IntBuf(4)
    private val longs   = LongBuf(4)
    private val doubles = DoubleBuf(4)
    // Offsets in the row's bytes, or -1 - the offset in `bytes` for a converted value
    private val offsets = IntBuf(4)
    private val lengths = IntBuf(4)
    private val bytes   = ByteBuf(16)

    def reset(): Unit =
      reps.reset()
      defs.reset()
      ints.reset()
      longs.reset()
      doubles.reset()
      offsets.reset()
      lengths.reset()
      bytes.reset()

    private inline def levels(r: Int, d: Int): Unit =
      reps.add(r)
      defs.add(d)

    def addNull(r: Int, d: Int): Unit = levels(r, d)

    def addInt(r: Int, d: Int, v: Int): Unit =
      levels(r, d)
      ints.add(v)

    def addLong(r: Int, d: Int, v: Long): Unit =
      levels(r, d)
      longs.add(v)

    def addDouble(r: Int, d: Int, v: Double): Unit =
      levels(r, d)
      doubles.add(v)

    /** A value at `[off, off + len)` of the row's bytes */
    def addRowBytes(r: Int, d: Int, off: Int, len: Int): Unit =
      levels(r, d)
      offsets.add(off)
      lengths.add(len)

    private def addOwnBytes(r: Int, d: Int, b: Array[Byte]): Unit =
      levels(r, d)
      offsets.add(-1 - bytes.size)
      lengths.add(b.length)
      bytes.write(b, 0, b.length)

    /**
      * A physical value from [[ValueConverter]], dispatched by the physical type rather than its
      * runtime class: on Scala.js every number matches `Float`
      */
    def addConverted(r: Int, d: Int, v: Any): Unit =
      physical match
        case PhysicalType.BOOLEAN =>
          addInt(
            r,
            d,
            if v.asInstanceOf[Boolean] then
              1
            else
              0
          )
        case PhysicalType.INT32 =>
          addInt(r, d, v.asInstanceOf[Int])
        case PhysicalType.INT64 =>
          addLong(r, d, v.asInstanceOf[Long])
        case PhysicalType.FLOAT =>
          addInt(r, d, java.lang.Float.floatToRawIntBits(v.asInstanceOf[Float]))
        case PhysicalType.DOUBLE =>
          addDouble(r, d, v.asInstanceOf[Double])
        case _ =>
          addOwnBytes(r, d, v.asInstanceOf[Array[Byte]])

    /** Adds the staged values to `w`, with `row` the bytes the row was read from */
    def commit(w: ColumnWriter, row: Array[Byte]): Unit =
      // One dispatch per column rather than per value
      physical match
        case PhysicalType.BOOLEAN =>
          commitInts(w, (r, v) => w.addBoolean(r, v != 0))
        case PhysicalType.INT32 =>
          commitInts(w, w.addInt)
        case PhysicalType.FLOAT =>
          commitInts(w, (r, v) => w.addFloat(r, java.lang.Float.intBitsToFloat(v)))
        case PhysicalType.INT64 =>
          var vi = 0
          var j  = 0
          while j < reps.size do
            if defs(j) < maxDef then
              w.addNull(reps(j), defs(j))
            else
              w.addLong(reps(j), longs(vi))
              vi += 1
            j += 1
        case PhysicalType.DOUBLE =>
          var vi = 0
          var j  = 0
          while j < reps.size do
            if defs(j) < maxDef then
              w.addNull(reps(j), defs(j))
            else
              w.addDouble(reps(j), doubles(vi))
              vi += 1
            j += 1
        case _ =>
          var vi = 0
          var j  = 0
          while j < reps.size do
            if defs(j) < maxDef then
              w.addNull(reps(j), defs(j))
            else
              val off = offsets(vi)
              if off >= 0 then
                w.addBytes(reps(j), row, off, lengths(vi))
              else
                w.addBytes(reps(j), bytes.array, -1 - off, lengths(vi))
              vi += 1
            j += 1
      end match
      w.endRow()

    end commit

    private inline def commitInts(w: ColumnWriter, inline add: (Int, Int) => Unit): Unit =
      var vi = 0
      var j  = 0
      while j < reps.size do
        if defs(j) < maxDef then
          w.addNull(reps(j), defs(j))
        else
          add(reps(j), ints(vi))
          vi += 1
        j += 1

  end Stage

end Shredder
