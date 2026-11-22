package wvlet.uni.parquet.writer

import wvlet.uni.parquet.ColumnBatch
import wvlet.uni.parquet.ColumnVector
import wvlet.uni.parquet.ColumnVector.*
import wvlet.uni.parquet.ParquetException
import wvlet.uni.parquet.ParquetField
import wvlet.uni.parquet.ParquetSchema
import wvlet.uni.parquet.column.Assembly
import wvlet.uni.parquet.format.*

/**
  * Turns the vectors of a [[ColumnBatch]] into leaf values with repetition and definition levels,
  * the inverse of the reader's assembly. Fields are classified as the assembly classifies them, so
  * a batch read from a file has the shapes written here.
  *
  * [[check]] validates a whole batch before [[write]] appends anything, so a rejected batch leaves
  * no values behind. Positions under a null parent (or outside every list) are not checked, since
  * they are never written.
  */
private[parquet] final class BatchShredder(schema: ParquetSchema, parallelism: Int = 1):
  import BatchShredder.*

  schema
    .columns
    .find(_.physicalType == PhysicalType.INT96)
    .foreach(c => throw ParquetException.unsupported(s"writing INT96 column ${c.pathString}"))

  private val fields: Array[Node] = schema.fields.map(build).toArray

  /** The batch's vectors in schema order; throws ParquetException when the batch does not fit */
  def check(batch: ColumnBatch): Array[ColumnVector] =
    val n = batch.numRows
    if n < 0 then
      throw ParquetException(s"A batch cannot have ${n} rows")
    val names = schema.fields.map(_.name).toSet
    batch
      .columns
      .groupBy(_.name)
      .foreach { (name, cs) =>
        if !names.contains(name) then
          throw ParquetException(s"The batch has column ${name}, which is not in the schema")
        if cs.size > 1 then
          throw ParquetException(s"The batch has column ${name} ${cs.size} times")
      }
    fields.map { node =>
      val v = batch
        .columns
        .find(_.name == node.field.name)
        .getOrElse(
          throw ParquetException(s"The batch has no column ${node.field.name} of the schema")
        )
      checkField(node, v, n, null)
      v
    }

  /**
    * Appends rows [from, to) of checked vectors to the column writers. Each top-level field writes
    * only its own column writers, so with `parallelism` above 1 the fields of a large slice are
    * written in parallel.
    */
  def write(
      vectors: Array[ColumnVector],
      writers: IndexedSeq[ColumnWriter],
      from: Int,
      to: Int
  ): Unit =
    if parallelism > 1 && fields.length > 1 && to - from >= ParallelRows then
      wvlet
        .uni
        .parquet
        .WriteParallelism
        .run(fields.length, parallelism)(c => writeTopField(c, vectors, writers, from, to))
    else
      var c = 0
      while c < fields.length do
        writeTopField(c, vectors, writers, from, to)
        c += 1

  private def writeTopField(
      c: Int,
      vectors: Array[ColumnVector],
      writers: IndexedSeq[ColumnWriter],
      from: Int,
      to: Int
  ): Unit =
    fields(c) match
      case leaf: Leaf if !leaf.field.isRepeated =>
        writeFlat(leaf, vectors(c), writers(leaf.col), from, to)
      case node =>
        var i = from
        while i < to do
          writeField(node, vectors(c), i, 0, writers)
          var col = node.first
          while col <= node.last do
            writers(col).endRow()
            col += 1
          i += 1

  /** Drops the struct vectors cached by [[write]], so the writer does not keep a batch alive */
  def release(): Unit = fields.foreach(releaseNode)

  private def releaseNode(node: Node): Unit =
    node match
      case s: Struct =>
        s.cachedFor = null
        s.cached = null
        s.children.foreach(releaseNode)
      case l: ListOf =>
        releaseNode(l.element)
      case m: MapOf =>
        releaseNode(m.key)
        releaseNode(m.value)
      case _: Leaf =>

  // Validation

  private def fail(f: ParquetField, message: String): Nothing =
    throw ParquetException(s"Cannot write column ${f.pathString}: ${message}")

  /** Checks a field at positions [0, n), honouring its own repetition */
  private def checkField(node: Node, v: ColumnVector, n: Int, skip: Array[Boolean]): Unit =
    val f = node.field
    f.repetition match
      case Repetition.REQUIRED =>
        checkValue(node, v, n, skip)
      case Repetition.OPTIONAL =>
        // Sizes first: orNulls reads the null flags
        checkSize(f, v, n)
        checkValue(node, v, n, orNulls(skip, v))
      case Repetition.REPEATED =>
        // An unannotated repeated field; a null row is written as an empty one
        v match
          case l: ListColumn =>
            checkSize(f, l, n)
            val m = l.elements.size
            checkOffsets(f, l.offsets, n, m)
            checkValue(node, l.elements, m, elementSkip(l.offsets, n, m, orNulls(skip, l)))
          case other =>
            mismatch(f, other, "ListColumn")

  /** Checks a field whose values are present at every position not in `skip` */
  private def checkValue(node: Node, v: ColumnVector, n: Int, skip: Array[Boolean]): Unit =
    val f = node.field
    checkSize(f, v, n)
    if v.nulls != null then
      var i = 0
      while i < n do
        if v.nulls(i) && (skip == null || !skip(i)) then
          fail(f, s"row ${i} is null in a required field")
        i += 1
    val inner = orNulls(skip, v)
    node match
      case leaf: Leaf =>
        checkLeaf(leaf, v, n, inner)
      case s: Struct =>
        v match
          case sc: StructColumn =>
            sc.children
              .foreach { c =>
                if !s.children.exists(_.field.name == c.name) then
                  fail(f, s"the struct has field ${c.name}, which is not in the schema")
              }
            s.children
              .foreach { child =>
                val cs = sc.children.filter(_.name == child.field.name)
                if cs.isEmpty then
                  fail(f, s"the struct has no field ${child.field.name}")
                if cs.size > 1 then
                  fail(f, s"the struct has field ${child.field.name} ${cs.size} times")
                checkField(child, cs.head, n, inner)
              }
          case other =>
            mismatch(f, other, "StructColumn")
      case l: ListOf =>
        v match
          case lc: ListColumn =>
            val m = lc.elements.size
            checkOffsets(f, lc.offsets, n, m)
            val elemSkip = elementSkip(lc.offsets, n, m, inner)
            if l.elementIsField then
              checkField(l.element, lc.elements, m, elemSkip)
            else
              checkValue(l.element, lc.elements, m, elemSkip)
          case other =>
            mismatch(f, other, "ListColumn")
      case mp: MapOf =>
        v match
          case mc: MapColumn =>
            val m = mc.keys.size
            checkOffsets(f, mc.offsets, n, m)
            val elemSkip = elementSkip(mc.offsets, n, m, inner)
            checkField(mp.key, mc.keys, m, elemSkip)
            checkField(mp.value, mc.values, m, elemSkip)
          case other =>
            mismatch(f, other, "MapColumn")

    end match

  end checkValue

  private def checkLeaf(leaf: Leaf, v: ColumnVector, n: Int, skip: Array[Boolean]): Unit =
    val p = leaf.field
    p.physicalType match
      case PhysicalType.BOOLEAN =>
        if !v.isInstanceOf[BooleanColumn] then
          mismatch(p, v, "BooleanColumn")
      case PhysicalType.INT32 =>
        if !v.isInstanceOf[IntColumn] then
          mismatch(p, v, "IntColumn")
      case PhysicalType.INT64 =>
        if !v.isInstanceOf[LongColumn] then
          mismatch(p, v, "LongColumn")
      case PhysicalType.FLOAT =>
        if !v.isInstanceOf[FloatColumn] then
          mismatch(p, v, "FloatColumn")
      case PhysicalType.DOUBLE =>
        if !v.isInstanceOf[DoubleColumn] then
          mismatch(p, v, "DoubleColumn")
      case _ =>
        v match
          case b: BinaryColumn =>
            checkOffsets(p, b.offsets, n, b.data.length)
            if p.physicalType == PhysicalType.FIXED_LEN_BYTE_ARRAY then
              var i = 0
              while i < n do
                if (skip == null || !skip(i)) && b.length(i) != p.typeLength then
                  fail(
                    p,
                    s"row ${i} has ${b.length(i)} bytes, but the column holds ${p.typeLength}"
                  )
                i += 1
          case other =>
            mismatch(p, other, "BinaryColumn")
    end match

  end checkLeaf

  private def checkSize(f: ParquetField, v: ColumnVector, n: Int): Unit =
    val size = v.size
    if size != n then
      fail(f, s"the vector has ${size} values, but ${n} are expected")
    if v.nulls != null && v.nulls.length != n then
      fail(f, s"the null flags have ${v.nulls.length} entries, but ${n} are expected")
    v match
      case m: MapColumn if m.values.size != m.keys.size =>
        fail(f, s"the map has ${m.keys.size} keys and ${m.values.size} values")
      case _ =>

  /** Offsets of n rows into m children: non-decreasing, from 0 or more up to m */
  private def checkOffsets(f: ParquetField, offsets: Array[Int], n: Int, m: Int): Unit =
    if offsets.length != n + 1 then
      fail(f, s"the vector has ${offsets.length} offsets, but ${n + 1} are expected")
    if offsets(0) < 0 then
      fail(f, s"the first offset is ${offsets(0)}")
    var i = 0
    while i < n do
      if offsets(i + 1) < offsets(i) then
        fail(f, s"offset ${i + 1} (${offsets(i + 1)}) is less than offset ${i} (${offsets(i)})")
      i += 1
    if offsets(n) > m then
      fail(f, s"the last offset ${offsets(n)} is past the ${m} values it points into")

  private def mismatch(f: ParquetField, v: ColumnVector, expected: String): Nothing = fail(
    f,
    s"expected a ${expected}, but got a ${v.getClass.getSimpleName}"
  )

  // Writing

  private def writeFlat(leaf: Leaf, v: ColumnVector, w: ColumnWriter, from: Int, to: Int): Unit =
    val nulls = v.nulls
    var i     = from
    v match
      case c: IntColumn =>
        val a = c.values
        while i < to do
          if nulls != null && nulls(i) then
            w.addNull(0, 0)
          else
            w.addInt(0, a(i))
          w.endRow()
          i += 1
      case c: LongColumn =>
        val a = c.values
        while i < to do
          if nulls != null && nulls(i) then
            w.addNull(0, 0)
          else
            w.addLong(0, a(i))
          w.endRow()
          i += 1
      case c: DoubleColumn =>
        val a = c.values
        while i < to do
          if nulls != null && nulls(i) then
            w.addNull(0, 0)
          else
            w.addDouble(0, a(i))
          w.endRow()
          i += 1
      case c: FloatColumn =>
        val a = c.values
        while i < to do
          if nulls != null && nulls(i) then
            w.addNull(0, 0)
          else
            w.addFloat(0, a(i))
          w.endRow()
          i += 1
      case c: BooleanColumn =>
        val a = c.values
        while i < to do
          if nulls != null && nulls(i) then
            w.addNull(0, 0)
          else
            w.addBoolean(0, a(i))
          w.endRow()
          i += 1
      case c: BinaryColumn =>
        while i < to do
          if nulls != null && nulls(i) then
            w.addNull(0, 0)
          else
            addBinary(w, 0, c, i)
          w.endRow()
          i += 1
      case other =>
        mismatch(leaf.field, other, "primitive vector")
    end match

  end writeFlat

  /** Row i of a binary vector, through the reader's dictionary entry when it has one */
  private inline def addBinary(w: ColumnWriter, r: Int, c: BinaryColumn, i: Int): Unit =
    val off     = c.offsets(i)
    val len     = c.offsets(i + 1) - off
    val entries = c.dictIndices
    if entries != null && entries(i) >= 0 then
      w.addDictBytes(r, c.data, off, len, c.dictionary, entries(i))
    else
      w.addBytes(r, c.data, off, len)

  /** Nulls for every leaf under a node, at definition level d */
  private def nulls(node: Node, r: Int, d: Int, writers: IndexedSeq[ColumnWriter]): Unit =
    var col = node.first
    while col <= node.last do
      writers(col).addNull(r, d)
      col += 1

  private def writeField(
      node: Node,
      v: ColumnVector,
      i: Int,
      r: Int,
      writers: IndexedSeq[ColumnWriter]
  ): Unit =
    val f = node.field
    f.repetition match
      case Repetition.REQUIRED =>
        writeValue(node, v, i, r, writers)
      case Repetition.OPTIONAL =>
        if v.isNull(i) then
          nulls(node, r, f.maxDefinitionLevel - 1, writers)
        else
          writeValue(node, v, i, r, writers)
      case Repetition.REPEATED =>
        val l     = v.asInstanceOf[ListColumn]
        val start = l.offsets(i)
        val end   = l.offsets(i + 1)
        if l.isNull(i) || start == end then
          nulls(node, r, f.maxDefinitionLevel - 1, writers)
        else
          var j = start
          while j < end do
            writeValue(
              node,
              l.elements,
              j,
              if j == start then
                r
              else
                f.maxRepetitionLevel
              ,
              writers
            )
            j += 1

  end writeField

  /** Writes a value present at position i, with repetition level r for its first leaf value */
  private def writeValue(
      node: Node,
      v: ColumnVector,
      i: Int,
      r: Int,
      writers: IndexedSeq[ColumnWriter]
  ): Unit =
    node match
      case leaf: Leaf =>
        val w = writers(leaf.col)
        v match
          case c: IntColumn =>
            w.addInt(r, c.values(i))
          case c: LongColumn =>
            w.addLong(r, c.values(i))
          case c: DoubleColumn =>
            w.addDouble(r, c.values(i))
          case c: FloatColumn =>
            w.addFloat(r, c.values(i))
          case c: BooleanColumn =>
            w.addBoolean(r, c.values(i))
          case c: BinaryColumn =>
            addBinary(w, r, c, i)
          case other =>
            mismatch(leaf.field, other, "primitive vector")
      case s: Struct =>
        val sc = v.asInstanceOf[StructColumn]
        // The first child carries the struct's repetition level; the others repeat it too, since
        // each leaf has its own level stream
        var k = 0
        while k < s.children.length do
          writeField(s.children(k), childOf(sc, s, k), i, r, writers)
          k += 1
      case l: ListOf =>
        val lc    = v.asInstanceOf[ListColumn]
        val start = lc.offsets(i)
        val end   = lc.offsets(i + 1)
        if start == end then
          nulls(l, r, l.field.maxDefinitionLevel, writers)
        else
          var j = start
          while j < end do
            val rep =
              if j == start then
                r
              else
                l.repeatedLevel
            if l.elementIsField then
              writeField(l.element, lc.elements, j, rep, writers)
            else
              writeValue(l.element, lc.elements, j, rep, writers)
            j += 1
      case mp: MapOf =>
        val mc    = v.asInstanceOf[MapColumn]
        val start = mc.offsets(i)
        val end   = mc.offsets(i + 1)
        if start == end then
          nulls(mp, r, mp.field.maxDefinitionLevel, writers)
        else
          var j = start
          while j < end do
            val rep =
              if j == start then
                r
              else
                mp.repeatedLevel
            writeField(mp.key, mc.keys, j, rep, writers)
            writeField(mp.value, mc.values, j, rep, writers)
            j += 1

  end writeValue

  // The struct's vectors in schema order, resolved once per StructColumn instance
  private def childOf(sc: StructColumn, s: Struct, k: Int): ColumnVector =
    if s.cachedFor ne sc then
      s.cached = s.children.map(c => sc.children.find(_.name == c.field.name).get)
      s.cachedFor = sc
    s.cached(k)

end BatchShredder

private[parquet] object BatchShredder:
  /** The rows a slice needs before its fields are written in parallel */
  private val ParallelRows = 1024

  /** Leaf columns under a field are the contiguous column indexes first to last */
  sealed abstract class Node(val field: ParquetField):
    val first: Int = edgeLeaf(field, first = true)
    val last: Int  = edgeLeaf(field, first = false)

  final class Leaf(override val field: ParquetField.Primitive) extends Node(field):
    val col: Int = field.columnIndex

  final class Struct(g: ParquetField.Group, val children: Array[Node]) extends Node(g):
    var cachedFor: StructColumn     = null
    var cached: Array[ColumnVector] = null

  /**
    * A LIST group. `element` is the element field of a 3-level list (written with its own
    * repetition), or the repeated field itself in the legacy 2-level layouts.
    */
  final class ListOf(
      g: ParquetField.Group,
      val repeatedLevel: Int,
      val element: Node,
      val elementIsField: Boolean
  ) extends Node(g)

  final class MapOf(g: ParquetField.Group, val repeatedLevel: Int, val key: Node, val value: Node)
      extends Node(g)

  def build(f: ParquetField): Node =
    f match
      case p: ParquetField.Primitive =>
        Leaf(p)
      case g: ParquetField.Group if Assembly.isList(g) =>
        val r = g.children(0)
        if Assembly.isThreeLevelList(g, r) then
          ListOf(
            g,
            r.maxRepetitionLevel,
            build(r.asInstanceOf[ParquetField.Group].children(0)),
            elementIsField = true
          )
        else
          ListOf(g, r.maxRepetitionLevel, build(r), elementIsField = false)
      case g: ParquetField.Group if Assembly.isMap(g) =>
        val kv = g.children(0).asInstanceOf[ParquetField.Group]
        MapOf(g, kv.maxRepetitionLevel, build(kv.children(0)), build(kv.children(1)))
      case g: ParquetField.Group =>
        Struct(g, g.children.map(build).toArray)

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

  /** Positions to skip: those already skipped, plus the null ones of v */
  def orNulls(skip: Array[Boolean], v: ColumnVector): Array[Boolean] =
    val nulls = v.nulls
    if nulls == null then
      skip
    else if skip == null then
      nulls
    else
      val out = new Array[Boolean](skip.length)
      var i   = 0
      while i < out.length do
        out(i) = skip(i) || nulls(i)
        i += 1
      out

  /**
    * The child positions to skip: those of skipped rows and those no row points to. Null when every
    * child is written.
    */
  def elementSkip(offsets: Array[Int], n: Int, m: Int, rowSkip: Array[Boolean]): Array[Boolean] =
    if rowSkip == null && offsets(0) == 0 && offsets(n) == m then
      null
    else
      val out = Array.fill(m)(true)
      var i   = 0
      while i < n do
        if rowSkip == null || !rowSkip(i) then
          java.util.Arrays.fill(out, offsets(i), offsets(i + 1), false)
        i += 1
      out

end BatchShredder
