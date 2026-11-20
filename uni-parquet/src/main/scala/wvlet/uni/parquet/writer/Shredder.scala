package wvlet.uni.parquet.writer

import wvlet.uni.msgpack.spi.Value
import wvlet.uni.msgpack.spi.Value.*
import wvlet.uni.parquet.ParquetException
import wvlet.uni.parquet.ParquetField
import wvlet.uni.parquet.ParquetSchema
import wvlet.uni.parquet.format.LogicalType
import wvlet.uni.parquet.format.Repetition
import wvlet.uni.parquet.io.IntBuf

import scala.collection.mutable

/**
  * Splits a row into leaf column values with repetition and definition levels (Dremel striping),
  * the inverse of the reader's assembly. A row is a MessagePack map of column names to values, or
  * an array of values in schema order.
  *
  * A row is shredded into a staging area first and copied to the column writers only when every
  * value fits, so a rejected row leaves no partial values behind.
  */
private[parquet] final class Shredder(schema: ParquetSchema):
  private val leaves     = schema.columns
  private val converters = leaves.map(ValueConverter.forField).toArray
  private val stagedRep  = Array.fill(leaves.size)(IntBuf(4))
  private val stagedDef  = Array.fill(leaves.size)(IntBuf(4))
  private val stagedVal  = Array.fill(leaves.size)(mutable.ArrayBuffer.empty[Any])

  /** Shreds a row into the staging area; throws ParquetException when a value does not fit */
  def shred(row: Value): Unit =
    var i = 0
    while i < leaves.size do
      stagedRep(i).reset()
      stagedDef(i).reset()
      stagedVal(i).clear()
      i += 1
    row match
      case MapValue(entries) =>
        val m = StructView(entries)
        schema.fields.foreach(f => writeField(f, m.get(f.name), 0, 0))
      case ArrayValue(values) =>
        if values.size != schema.fields.size then
          throw ParquetException(
            s"A row of ${values.size} values does not match the ${schema
                .fields
                .size} columns of the schema"
          )
        schema.fields.zip(values).foreach((f, v) => writeField(f, v, 0, 0))
      case other =>
        throw ParquetException(s"A row must be a map or an array, but got ${other.valueType}")

  /** Copies the staged row into the column writers */
  def commit(writers: IndexedSeq[ColumnWriter]): Unit =
    var i = 0
    while i < leaves.size do
      val w    = writers(i)
      val reps = stagedRep(i)
      val defs = stagedDef(i)
      val vals = stagedVal(i)
      var j    = 0
      while j < vals.size do
        w.add(reps(j), defs(j), vals(j))
        j += 1
      w.endRow()
      i += 1

  private def isNull(v: Value): Boolean = v == null || v == NilValue

  private def stage(col: Int, r: Int, d: Int, v: Any): Unit =
    stagedRep(col).add(r)
    stagedDef(col).add(d)
    stagedVal(col) += v

  // The leaves under a field are the contiguous column indexes from its first leaf to its last
  private def nulls(f: ParquetField, r: Int, d: Int): Unit =
    var col  = edgeLeaf(f, first = true)
    val last = edgeLeaf(f, first = false)
    while col <= last do
      stage(col, r, d, null)
      col += 1

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

  private def fail(f: ParquetField, message: String): Nothing =
    throw ParquetException(s"Cannot write column ${f.pathString}: ${message}")

  private def writeField(f: ParquetField, v: Value, r: Int, d: Int): Unit =
    f.repetition match
      case Repetition.REQUIRED =>
        if isNull(v) then
          fail(f, "a required value is missing")
        writeValue(f, v, r, d)
      case Repetition.OPTIONAL =>
        if isNull(v) then
          nulls(f, r, d)
        else
          writeValue(f, v, r, d + 1)
      case Repetition.REPEATED =>
        if isNull(v) then
          nulls(f, r, d)
        else
          val elems = asArray(f, v)
          if elems.isEmpty then
            nulls(f, r, d)
          else
            var i = 0
            while i < elems.size do
              val rep =
                if i == 0 then
                  r
                else
                  f.maxRepetitionLevel
              writeValue(f, elems(i), rep, d + 1)
              i += 1

  private def writeValue(f: ParquetField, v: Value, r: Int, d: Int): Unit =
    f match
      case p: ParquetField.Primitive =>
        val physical =
          try
            converters(p.columnIndex)(v)
          catch
            case e: ValueConverter.Mismatch =>
              fail(f, e.getMessage)
        stage(p.columnIndex, r, d, physical)
      case g: ParquetField.Group =>
        g.logicalType match
          case Some(LogicalType.List) if isWrapper(g) =>
            val c     = g.children.head
            val elems = asArray(g, v)
            val inner =
              c match
                case cg: ParquetField.Group if isThreeLevelList(g, cg) =>
                  val name = StringValue(cg.children.head.name)
                  ArrayValue(elems.map(e => MapValue(Map(name -> e))))
                case _ =>
                  ArrayValue(elems)
            writeField(c, inner, r, d)
          case Some(LogicalType.Map | LogicalType.MapKeyValue) if isWrapper(g) =>
            g.children.head match
              case kv: ParquetField.Group if kv.children.nonEmpty =>
                val keyName   = StringValue(kv.children(0).name)
                val valueName = kv.children.lift(1).map(c => StringValue(c.name))
                val entries   =
                  v match
                    case MapValue(m) =>
                      m.toIndexedSeq
                    case _ =>
                      fail(g, s"expected a map, but got ${v.valueType}")
                val inner = entries.map { (k, x) =>
                  MapValue(
                    valueName match
                      case Some(n) =>
                        Map(keyName -> k, n -> x)
                      case None =>
                        Map(keyName -> k)
                  )
                }
                writeField(kv, ArrayValue(inner), r, d)
              case _ =>
                fail(g, "the map has no key_value group")
          case _ =>
            v match
              case MapValue(entries) =>
                val m = StructView(entries)
                g.children.foreach(c => writeField(c, m.get(c.name), r, d))
              case _ =>
                fail(g, s"expected a struct, but got ${v.valueType}")

  /** A LIST or MAP group with a single repeated child */
  private def isWrapper(g: ParquetField.Group): Boolean =
    g.children.size == 1 && g.children.head.isRepeated

  /**
    * True when the repeated group of a LIST wraps the element (`list.element`); false for the
    * legacy 2-level layouts, where the repeated group is the element itself
    */
  private def isThreeLevelList(list: ParquetField.Group, rep: ParquetField.Group): Boolean =
    rep.children.size == 1 && rep.name != "array" && rep.name != s"${list.name}_tuple"

  private def asArray(f: ParquetField, v: Value): IndexedSeq[Value] =
    v match
      case ArrayValue(elems) =>
        elems
      case _ =>
        fail(f, s"expected an array, but got ${v.valueType}")

end Shredder

/**
  * Field lookup in a MessagePack map: the exact name first, then a match that ignores case, `_`,
  * and `-`, so `user_name` fills a `userName` column as it does when reading
  */
private final class StructView(entries: Map[Value, Value]):
  private lazy val canonical: Map[String, Value] = entries.collect { case (StringValue(k), v) =>
    StructView.canonical(k) -> v
  }

  def get(name: String): Value =
    entries.get(StringValue(name)).orElse(canonical.get(StructView.canonical(name))).orNull

private object StructView:
  def canonical(name: String): String = name.toLowerCase.filterNot(c => c == '_' || c == '-')
