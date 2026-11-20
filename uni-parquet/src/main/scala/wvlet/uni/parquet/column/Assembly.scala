package wvlet.uni.parquet.column

import wvlet.uni.parquet.ColumnVector
import wvlet.uni.parquet.ColumnVector.*
import wvlet.uni.parquet.ParquetException
import wvlet.uni.parquet.ParquetField
import wvlet.uni.parquet.format.LogicalType

/**
  * Builds column vectors from repetition and definition levels (the record shredding scheme of the
  * Dremel paper).
  *
  * Each leaf column is a sequence of level entries. An instance of a schema node is a range of
  * entries [start, end) of a leaf below it. Rows split the entries where the repetition level is 0;
  * a repeated node splits its parent's range again where the repetition level equals its own. A
  * node is present in an instance when the definition level at the instance start reaches the
  * node's own level.
  */
private[parquet] object Assembly:

  private final class Leaf(val data: ColumnData):
    val m: Int                = data.numLevels
    private val defs          = data.definitionLevels
    private val reps          = data.repetitionLevels
    inline def d(i: Int): Int =
      if defs == null then
        0
      else
        defs(i)

    inline def r(i: Int): Int =
      if reps == null then
        0
      else
        reps(i)

    private val maxDef = data.field.maxDefinitionLevel
    // valueIndex(i): the number of non-null values before entry i
    lazy val valueIndex: Array[Int] =
      val a = new Array[Int](m + 1)
      var c = 0
      var i = 0
      while i < m do
        a(i) = c
        if d(i) == maxDef then
          c += 1
        i += 1
      a(m) = c
      a

  /** Instance ranges of one node for one leaf */
  private final class Ranges(val starts: Array[Int], val ends: Array[Int]):
    def size: Int = starts.length

  private type Instances = Map[Int, Ranges]

  /**
    * Builds the vectors of the given top-level fields. `columns` maps a leaf's column index to its
    * decoded data, and must cover every leaf below the fields.
    */
  def build(
      fields: IndexedSeq[ParquetField],
      columns: Map[Int, ColumnData],
      numRows: Int
  ): IndexedSeq[ColumnVector] =
    val leaves                   = columns.view.mapValues(Leaf(_)).toMap
    val rootInstances: Instances = leaves.map { case (idx, leaf) =>
      idx -> rowRanges(leaf, numRows)
    }
    fields.map(f => buildField(f, leaves, rootInstances, numRows))

  private def rowRanges(leaf: Leaf, numRows: Int): Ranges =
    if leaf.data.field.maxRepetitionLevel == 0 then
      if leaf.m != numRows then
        throw ParquetException.corrupt(
          s"column ${leaf.data.field.pathString} has ${leaf.m} values, expected ${numRows} rows"
        )
      val starts = Array.tabulate(numRows)(identity)
      Ranges(starts, Array.tabulate(numRows)(_ + 1))
    else
      val starts = Array.newBuilder[Int]
      var i      = 0
      while i < leaf.m do
        if leaf.r(i) == 0 then
          starts += i
        i += 1
      val s = starts.result()
      if s.length != numRows || (leaf.m > 0 && leaf.r(0) != 0) then
        throw ParquetException.corrupt(
          s"column ${leaf.data.field.pathString} has ${s.length} rows, expected ${numRows}"
        )
      Ranges(s, endsOf(s, leaf.m))

  private def endsOf(starts: Array[Int], last: Int): Array[Int] =
    val ends = new Array[Int](starts.length)
    var i    = 0
    while i < starts.length do
      ends(i) =
        if i + 1 < starts.length then
          starts(i + 1)
        else
          last
      i += 1
    ends

  private def leafIndices(f: ParquetField): Seq[Int] =
    f match
      case p: ParquetField.Primitive =>
        Seq(p.columnIndex)
      case g: ParquetField.Group =>
        g.children.flatMap(leafIndices)

  private def firstLeaf(f: ParquetField, leaves: Map[Int, Leaf], inst: Instances): (Leaf, Ranges) =
    val idx = leafIndices(f)
      .find(inst.contains)
      .getOrElse(throw ParquetException.corrupt(s"field ${f.pathString} has no column data"))
    (leaves(idx), inst(idx))

  /** Null flags: an instance is null when its definition level does not reach the node's level */
  private def nullsOf(
      f: ParquetField,
      leaves: Map[Int, Leaf],
      inst: Instances,
      n: Int
  ): Array[Boolean] =
    if f.maxDefinitionLevel == 0 then
      null
    else
      val (leaf, ranges)        = firstLeaf(f, leaves, inst)
      val level                 = f.maxDefinitionLevel
      var nulls: Array[Boolean] = null
      var k                     = 0
      while k < n do
        if leaf.d(ranges.starts(k)) < level then
          if nulls == null then
            nulls = new Array[Boolean](n)
          nulls(k) = true
        k += 1
      nulls

  private def buildField(
      f: ParquetField,
      leaves: Map[Int, Leaf],
      inst: Instances,
      n: Int
  ): ColumnVector =
    if f.isRepeated then
      // An unannotated repeated field is a list of required elements
      val (offsets, elemInst, count) = splitRepeated(f, leaves, inst, n)
      ListColumn(f, offsets, buildValue(f, leaves, elemInst, count), null)
    else
      buildValue(f, leaves, inst, n)

  /**
    * Builds a field ignoring its own repetition: a top-level or struct field, or one element of a
    * repeated field. Elements are always present, so their null flags come out empty.
    */
  private def buildValue(
      f: ParquetField,
      leaves: Map[Int, Leaf],
      inst: Instances,
      n: Int
  ): ColumnVector =
    f match
      case p: ParquetField.Primitive =>
        buildLeaf(p, leaves(p.columnIndex), inst(p.columnIndex), n)
      case g: ParquetField.Group if isList(g) =>
        val r                          = g.children(0)
        val (offsets, elemInst, count) = splitRepeated(r, leaves, inst, n)
        val elements                   =
          if isThreeLevelList(g, r) then
            buildField(r.asInstanceOf[ParquetField.Group].children(0), leaves, elemInst, count)
          else
            buildValue(r, leaves, elemInst, count)
        ListColumn(g, offsets, elements, nullsOf(g, leaves, inst, n))
      case g: ParquetField.Group if isMap(g) =>
        val kv                         = g.children(0).asInstanceOf[ParquetField.Group]
        val (offsets, elemInst, count) = splitRepeated(kv, leaves, inst, n)
        MapColumn(
          g,
          offsets,
          buildField(kv.children(0), leaves, elemInst, count),
          buildField(kv.children(1), leaves, elemInst, count),
          nullsOf(g, leaves, inst, n)
        )
      case g: ParquetField.Group =>
        StructColumn(
          g,
          n,
          g.children.map(buildField(_, leaves, inst, n)),
          nullsOf(g, leaves, inst, n)
        )

  private def isList(g: ParquetField.Group): Boolean =
    g.logicalType.contains(LogicalType.List) && g.children.size == 1 && g.children(0).isRepeated

  private def isMap(g: ParquetField.Group): Boolean =
    (g.logicalType.contains(LogicalType.Map) || g.logicalType.contains(LogicalType.MapKeyValue)) &&
      g.children.size == 1 && g.children(0).isRepeated && (
        g.children(0) match
          case kv: ParquetField.Group =>
            kv.children.size == 2
          case _ =>
            false
      )

  /** The backward-compatibility rules for LIST in the Parquet format spec */
  private def isThreeLevelList(g: ParquetField.Group, r: ParquetField): Boolean =
    r match
      case _: ParquetField.Primitive =>
        false
      case rg: ParquetField.Group =>
        rg.children.size == 1 && rg.name != "array" && rg.name != s"${g.name}_tuple"

  /**
    * Splits each parent instance into the elements of repeated node `f`. Returns list offsets into
    * the element instances, the element instances for every leaf below `f`, and the element count.
    */
  private def splitRepeated(
      f: ParquetField,
      leaves: Map[Int, Leaf],
      inst: Instances,
      n: Int
  ): (Array[Int], Instances, Int) =
    val level               = f.maxDefinitionLevel
    val rep                 = f.maxRepetitionLevel
    var offsets: Array[Int] = null
    var count               = -1
    val elemInst            =
      leafIndices(f)
        .filter(inst.contains)
        .map { idx =>
          val leaf   = leaves(idx)
          val ranges = inst(idx)
          val starts = Array.newBuilder[Int]
          val ends   = Array.newBuilder[Int]
          val offs   = new Array[Int](n + 1)
          var c      = 0
          var k      = 0
          while k < n do
            offs(k) = c
            val s = ranges.starts(k)
            val e = ranges.ends(k)
            if s < e && leaf.d(s) >= level then
              var elemStart = s
              var i         = s + 1
              while i < e do
                if leaf.r(i) == rep then
                  starts += elemStart
                  ends += i
                  c += 1
                  elemStart = i
                else if leaf.r(i) < rep then
                  throw ParquetException.corrupt(
                    s"column ${leaf.data.field.pathString} has an unexpected repetition level"
                  )
                i += 1
              starts += elemStart
              ends += e
              c += 1
            k += 1
          offs(n) = c
          if offsets == null then
            offsets = offs
            count = c
          else if c != count then
            throw ParquetException.corrupt(
              s"columns below ${f.pathString} disagree on the number of list elements"
            )
          idx -> Ranges(starts.result(), ends.result())
        }
        .toMap
    if offsets == null then
      throw ParquetException.corrupt(s"field ${f.pathString} has no column data")
    (offsets, elemInst, count)

  end splitRepeated

  private def buildLeaf(
      p: ParquetField.Primitive,
      leaf: Leaf,
      ranges: Ranges,
      n: Int
  ): ColumnVector =
    val maxDef                = p.maxDefinitionLevel
    val vidx                  = leaf.valueIndex
    var nulls: Array[Boolean] = null
    // Row k's value index, or -1 when the value is null
    val pick = new Array[Int](n)
    var k    = 0
    while k < n do
      val s = ranges.starts(k)
      if s < leaf.m && leaf.d(s) == maxDef then
        pick(k) = vidx(s)
      else
        pick(k) = -1
        if nulls == null then
          nulls = new Array[Boolean](n)
        nulls(k) = true
      k += 1
    val available = vidx(leaf.m)
    if available > leaf.data.values.size then
      throw ParquetException.corrupt(
        s"column ${p.pathString} has fewer values than its definition levels require"
      )

    leaf.data.values match
      case b: BooleanBuffer =>
        BooleanColumn(p, gather(n, pick)(i => b.values(i), false), nulls)
      case b: IntBuffer =>
        val out = new Array[Int](n)
        var i   = 0
        while i < n do
          if pick(i) >= 0 then
            out(i) = b.values(pick(i))
          i += 1
        IntColumn(p, out, nulls)
      case b: LongBuffer =>
        val out = new Array[Long](n)
        var i   = 0
        while i < n do
          if pick(i) >= 0 then
            out(i) = b.values(pick(i))
          i += 1
        LongColumn(p, out, nulls)
      case b: FloatBuffer =>
        val out = new Array[Float](n)
        var i   = 0
        while i < n do
          if pick(i) >= 0 then
            out(i) = b.values(pick(i))
          i += 1
        FloatColumn(p, out, nulls)
      case b: DoubleBuffer =>
        val out = new Array[Double](n)
        var i   = 0
        while i < n do
          if pick(i) >= 0 then
            out(i) = b.values(pick(i))
          i += 1
        DoubleColumn(p, out, nulls)
      case b: BinaryBuffer =>
        // Values are already in row order, so the data array is shared when nothing is skipped
        val offsets = new Array[Int](n + 1)
        var dense   = true
        var next    =
          if n > 0 && pick(0) >= 0 then
            pick(0)
          else
            0
        var i     = 0
        var total = 0
        while i < n do
          offsets(i) = total
          if pick(i) >= 0 then
            if pick(i) != next then
              dense = false
            next = pick(i) + 1
            total += b.length(pick(i))
          i += 1
        offsets(n) = total
        val startValue = (0 until n).find(pick(_) >= 0).map(pick).getOrElse(0)
        if dense && startValue == 0 then
          BinaryColumn(p, offsets, b.data, nulls)
        else
          val data = new Array[Byte](total)
          i = 0
          while i < n do
            if pick(i) >= 0 then
              System.arraycopy(b.data, b.offsets(pick(i)), data, offsets(i), b.length(pick(i)))
            i += 1
          BinaryColumn(p, offsets, data, nulls)
    end match

  end buildLeaf

  private inline def gather(n: Int, pick: Array[Int])(
      inline f: Int => Boolean,
      inline default: Boolean
  ): Array[Boolean] =
    val out = new Array[Boolean](n)
    var i   = 0
    while i < n do
      out(i) =
        if pick(i) >= 0 then
          f(pick(i))
        else
          default
      i += 1
    out

end Assembly
