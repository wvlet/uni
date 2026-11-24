package wvlet.uni.cloud.parquet.column

import wvlet.uni.cloud.parquet.ColumnVector
import wvlet.uni.cloud.parquet.ColumnVector.*
import wvlet.uni.cloud.parquet.ParquetException
import wvlet.uni.cloud.parquet.ParquetField
import wvlet.uni.cloud.parquet.format.LogicalType
import wvlet.uni.cloud.parquet.format.PhysicalType

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
private[cloud] object Assembly:

  private final class Leaf(val data: ColumnData):
    val m: Int         = data.numLevels
    private val defs   = data.definitionLevels
    private val reps   = data.repetitionLevels
    private val nulls  = data.nulls
    private val maxDef = data.field.maxDefinitionLevel
    // Without levels, an entry is at the maximum level (0 for required columns) unless the null
    // mask of a rowSlots column flags it
    inline def d(i: Int): Int =
      if defs != null then
        defs(i)
      else if nulls != null && nulls(i) then
        0
      else
        maxDef

    inline def r(i: Int): Int =
      if reps == null then
        0
      else
        reps(i)

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

  end Leaf

  /**
    * Instance ranges of one node for one leaf. The arrays are sized for the most instances the leaf
    * can hold, so they are filled without growing; callers know the instance count. Null arrays
    * stand for the identity (instance k is entry k), the ranges of every flat leaf.
    */
  private final class Ranges(starts: Array[Int], ends: Array[Int]):
    inline def start(k: Int): Int =
      if starts == null then
        k
      else
        starts(k)

    inline def end(k: Int): Int =
      if ends == null then
        k + 1
      else
        ends(k)

  private val identity = Ranges(null, null)

  private type Instances = Map[Int, Ranges]

  /**
    * Builds the vectors of the given top-level fields. `columns` maps a leaf's column index to its
    * decoded data, and must cover every leaf below the fields.
    */
  def build(
      fields: IndexedSeq[ParquetField],
      columns: Map[Int, ColumnData],
      numRows: Int,
      int32DecimalAsLong: Boolean = false
  ): IndexedSeq[ColumnVector] =
    val leaves                   = columns.view.mapValues(Leaf(_)).toMap
    val rootInstances: Instances = leaves.map { case (idx, leaf) =>
      idx -> rowRanges(leaf, numRows)
    }
    fields.map {
      // Widened while copying the decoded values, so no Int array is made only to be widened
      case p: ParquetField.Primitive if int32DecimalAsLong && !p.isRepeated && isInt32Decimal(p) =>
        buildLeaf(p, leaves(p.columnIndex), rootInstances(p.columnIndex), numRows, asLong = true)
      case f =>
        buildField(f, leaves, rootInstances, numRows)
    }

  private[parquet] def isInt32Decimal(p: ParquetField.Primitive): Boolean =
    p.physicalType == PhysicalType.INT32 &&
      p.logicalType.exists(_.isInstanceOf[LogicalType.Decimal])

  private def rowRanges(leaf: Leaf, numRows: Int): Ranges =
    if leaf.data.field.maxRepetitionLevel == 0 then
      if leaf.m != numRows then
        throw ParquetException.corrupt(
          s"column ${leaf.data.field.pathString} has ${leaf.m} values, expected ${numRows} rows"
        )
      identity
    else
      val starts = new Array[Int](numRows)
      val ends   = new Array[Int](numRows)
      var c      = 0
      var i      = 0
      while i < leaf.m do
        if leaf.r(i) == 0 then
          if c == numRows then
            throw ParquetException.corrupt(
              s"column ${leaf.data.field.pathString} has more than ${numRows} rows"
            )
          if c > 0 then
            ends(c - 1) = i
          starts(c) = i
          c += 1
        i += 1
      if c > 0 then
        ends(c - 1) = leaf.m
      if c != numRows || (leaf.m > 0 && leaf.r(0) != 0) then
        throw ParquetException.corrupt(
          s"column ${leaf.data.field.pathString} has ${c} rows, expected ${numRows}"
        )
      Ranges(starts, ends)

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
        if leaf.d(ranges.start(k)) < level then
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

  private[cloud] def isList(g: ParquetField.Group): Boolean =
    g.logicalType.contains(LogicalType.List) && g.children.size == 1 && g.children(0).isRepeated

  private[cloud] def isMap(g: ParquetField.Group): Boolean =
    (g.logicalType.contains(LogicalType.Map) || g.logicalType.contains(LogicalType.MapKeyValue)) &&
      g.children.size == 1 && g.children(0).isRepeated && (
        g.children(0) match
          case kv: ParquetField.Group =>
            kv.children.size == 2
          case _ =>
            false
      )

  /** The backward-compatibility rules for LIST in the Parquet format spec */
  private[cloud] def isThreeLevelList(g: ParquetField.Group, r: ParquetField): Boolean =
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
          // Each element starts at its own level entry, so a leaf holds at most m elements
          val starts = new Array[Int](leaf.m)
          val ends   = new Array[Int](leaf.m)
          val offs   = new Array[Int](n + 1)
          var c      = 0
          var k      = 0
          while k < n do
            offs(k) = c
            val s = ranges.start(k)
            val e = ranges.end(k)
            if s < e && leaf.d(s) >= level then
              var elemStart = s
              var i         = s + 1
              while i < e do
                if leaf.r(i) == rep then
                  starts(c) = elemStart
                  ends(c) = i
                  c += 1
                  elemStart = i
                else if leaf.r(i) < rep then
                  throw ParquetException.corrupt(
                    s"column ${leaf.data.field.pathString} has an unexpected repetition level"
                  )
                i += 1
              starts(c) = elemStart
              ends(c) = e
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
          idx -> Ranges(starts, ends)
        }
        .toMap
    if offsets == null then
      throw ParquetException.corrupt(s"field ${f.pathString} has no column data")
    (offsets, elemInst, count)

  end splitRepeated

  /** With `asLong`, INT32 values come out as a LongColumn */
  private def buildLeaf(
      p: ParquetField.Primitive,
      leaf: Leaf,
      ranges: Ranges,
      n: Int,
      asLong: Boolean = false
  ): ColumnVector =
    // Every entry holds a value (no nulls at this leaf) and instance k is entry k: true for flat
    // required columns and for required leaves of lists. The decoded buffer is then the vector.
    if leaf.data.rowSlots then
      // A flat column whose values are already in row slots, and whose mask is the vector's nulls
      if n != leaf.m || leaf.data.values.size != n || !(ranges eq identity) then
        throw ParquetException.corrupt(
          s"column ${p.pathString} has ${leaf.data.values.size} values for ${n} rows"
        )
      val nulls = leaf.data.nulls
      directLeaf(
        p,
        leaf.data.values,
        n,
        asLong,
        if nulls == null then
          null
        else
          exact(nulls, n)(java.util.Arrays.copyOf(nulls, n))
      )
    else if n == leaf.m && leaf.data.values.size == leaf.m && isIdentity(ranges, n) then
      directLeaf(p, leaf.data.values, n, asLong)
    else if n == leaf.m && (ranges eq identity) then
      // A flat leaf with nulls: entry k is row k, and its values are in row order
      scatterLeaf(p, leaf.data, n, asLong)
    else
      gatherLeaf(p, leaf, ranges, n, asLong)

  private def isIdentity(ranges: Ranges, n: Int): Boolean =
    (ranges eq identity) || {
      var k = 0
      while k < n && ranges.start(k) == k do
        k += 1
      k == n
    }

  private def directLeaf(
      p: ParquetField.Primitive,
      values: ValueBuffer,
      n: Int,
      asLong: Boolean = false,
      nulls: Array[Boolean] = null
  ): ColumnVector =
    import java.util.Arrays.copyOf
    // A buffer whose array is exactly the vector is handed over; the cursor does not reuse it
    values match
      case b: IntBuffer if asLong =>
        val out = new Array[Long](n)
        var i   = 0
        while i < n do
          out(i) = b.values(i)
          i += 1
        LongColumn(p, out, nulls)
      case b: BooleanBuffer =>
        BooleanColumn(p, exact(b.values, n)(copyOf(b.values, n)), nulls)
      case b: IntBuffer =>
        IntColumn(p, exact(b.values, n)(copyOf(b.values, n)), nulls)
      case b: LongBuffer =>
        LongColumn(p, exact(b.values, n)(copyOf(b.values, n)), nulls)
      case b: FloatBuffer =>
        FloatColumn(p, exact(b.values, n)(copyOf(b.values, n)), nulls)
      case b: DoubleBuffer =>
        DoubleColumn(p, exact(b.values, n)(copyOf(b.values, n)), nulls)
      case b: BinaryBuffer =>
        // The buffer's offsets start at 0; its data array is shared
        val indices =
          if b.dict != null && b.dictCount == n && b.dictIdx.length == n &&
            b.offsets.length == n + 1
          then
            // Every value came from the dictionary, and the buffer is handed over with its offsets
            b.dictIdx
          else
            dictIndices(b, n, i => i)
        BinaryColumn(p, exact(b.offsets, n + 1)(copyOf(b.offsets, n + 1)), b.data, nulls)
          .withDictionary(b.dict, indices)
    end match

  end directLeaf

  /** `a` when it has exactly `n` elements, else `copy` */
  private inline def exact[A](a: Array[A], n: Int)(inline copy: Array[A]): Array[A] =
    if a.length == n then
      a
    else
      copy

  /**
    * A flat leaf with nulls, in one pass over the definition levels: entry i is row i, and the
    * values of the non-null rows come in row order.
    */
  private def scatterLeaf(
      p: ParquetField.Primitive,
      data: ColumnData,
      n: Int,
      asLong: Boolean
  ): ColumnVector =
    val defs = data.definitionLevels
    if defs == null then
      // A required leaf has a value in every entry
      throw ParquetException.corrupt(
        s"column ${p.pathString} has ${data.values.size} values for ${n} rows"
      )
    val maxDef = p.maxDefinitionLevel
    val size   = data.values.size
    val nulls  = new Array[Boolean](n)

    // Calls put(row, value index) for each non-null row, and flags the null ones and calls
    // onNull(row, index of the next value) for them. Returns the number of values used.
    inline def scatter(
        inline put: (Int, Int) => Unit,
        inline onNull: (Int, Int) => Unit = (_, _) => ()
    ): Int =
      var j = 0
      var i = 0
      while i < n do
        if defs(i) == maxDef then
          if j == size then
            throw ParquetException.corrupt(
              s"column ${p.pathString} has fewer values than its definition levels require"
            )
          put(i, j)
          j += 1
        else
          nulls(i) = true
          onNull(i, j)
        i += 1
      j

    // When the decoded array has a slot for every row, the values move to their rows in place,
    // from the last row back, so a value never lands on one not yet moved. The cursor gives such an
    // array only when it hands out all of its values, so they are exactly the non-null rows'.
    inline def spread(inline move: (Int, Int) => Unit, inline clear: Int => Unit): Unit =
      var j = size - 1
      var i = n - 1
      while i >= 0 do
        if defs(i) == maxDef then
          if j < 0 then
            throw ParquetException.corrupt(
              s"column ${p.pathString} has fewer values than its definition levels require"
            )
          move(i, j)
          j -= 1
        else
          nulls(i) = true
          clear(i)
        i -= 1
      if j >= 0 then
        throw ParquetException.corrupt(
          s"column ${p.pathString} has more values than its definition levels allow"
        )

    data.values match
      case b: IntBuffer if asLong =>
        val out = new Array[Long](n)
        scatter((i, j) => out(i) = b.values(j))
        LongColumn(p, out, nulls)
      case b: BooleanBuffer if b.values.length == n =>
        val a = b.values
        spread((i, j) => a(i) = a(j), i => a(i) = false)
        BooleanColumn(p, a, nulls)
      case b: IntBuffer if b.values.length == n =>
        val a = b.values
        spread((i, j) => a(i) = a(j), i => a(i) = 0)
        IntColumn(p, a, nulls)
      case b: LongBuffer if b.values.length == n =>
        val a = b.values
        spread((i, j) => a(i) = a(j), i => a(i) = 0L)
        LongColumn(p, a, nulls)
      case b: FloatBuffer if b.values.length == n =>
        val a = b.values
        spread((i, j) => a(i) = a(j), i => a(i) = 0f)
        FloatColumn(p, a, nulls)
      case b: DoubleBuffer if b.values.length == n =>
        val a = b.values
        spread((i, j) => a(i) = a(j), i => a(i) = 0d)
        DoubleColumn(p, a, nulls)
      case b: BooleanBuffer =>
        val out = new Array[Boolean](n)
        scatter((i, j) => out(i) = b.values(j))
        BooleanColumn(p, out, nulls)
      case b: IntBuffer =>
        val out = new Array[Int](n)
        scatter((i, j) => out(i) = b.values(j))
        IntColumn(p, out, nulls)
      case b: LongBuffer =>
        val out = new Array[Long](n)
        scatter((i, j) => out(i) = b.values(j))
        LongColumn(p, out, nulls)
      case b: FloatBuffer =>
        val out = new Array[Float](n)
        scatter((i, j) => out(i) = b.values(j))
        FloatColumn(p, out, nulls)
      case b: DoubleBuffer =>
        val out = new Array[Double](n)
        scatter((i, j) => out(i) = b.values(j))
        DoubleColumn(p, out, nulls)
      case b: BinaryBuffer =>
        // Values are in row order from offset 0, so the data array is shared; a null row is empty
        val offsets = new Array[Int](n + 1)
        val src     = b.offsets
        val indices =
          if b.dict == null then
            null
          else
            new Array[Int](n)
        val j =
          if indices == null then
            scatter((i, j) => offsets(i) = src(j), (i, j) => offsets(i) = src(j))
          else
            scatter(
              (i, j) =>
                offsets(i) = src(j)
                indices(i) = b.dictIndex(j)
              ,
              (i, j) =>
                offsets(i) = src(j)
                indices(i) = -1
            )
        offsets(n) = src(j)
        BinaryColumn(p, offsets, b.data, nulls).withDictionary(b.dict, indices)
    end match

  end scatterLeaf

  /** The dictionary entry of each row, given the value index of row i (-1 for none) */
  private inline def dictIndices(b: BinaryBuffer, n: Int, inline valueOf: Int => Int): Array[Int] =
    if b.dict == null then
      null
    else
      val out = new Array[Int](n)
      var i   = 0
      while i < n do
        val v = valueOf(i)
        out(i) =
          if v >= 0 then
            b.dictIndex(v)
          else
            -1
        i += 1
      out

  private def gatherLeaf(
      p: ParquetField.Primitive,
      leaf: Leaf,
      ranges: Ranges,
      n: Int,
      asLong: Boolean = false
  ): ColumnVector =
    val maxDef                = p.maxDefinitionLevel
    val vidx                  = leaf.valueIndex
    var nulls: Array[Boolean] = null
    // Row k's value index, or -1 when the value is null
    val pick = new Array[Int](n)
    var k    = 0
    while k < n do
      val s = ranges.start(k)
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
      case b: IntBuffer if asLong =>
        val out = new Array[Long](n)
        var i   = 0
        while i < n do
          if pick(i) >= 0 then
            out(i) = b.values(pick(i))
          i += 1
        LongColumn(p, out, nulls)
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
        val indices    = dictIndices(b, n, i => pick(i))
        if dense && startValue == 0 then
          BinaryColumn(p, offsets, b.data, nulls).withDictionary(b.dict, indices)
        else
          val data = new Array[Byte](total)
          i = 0
          while i < n do
            if pick(i) >= 0 then
              System.arraycopy(b.data, b.offsets(pick(i)), data, offsets(i), b.length(pick(i)))
            i += 1
          BinaryColumn(p, offsets, data, nulls).withDictionary(b.dict, indices)
    end match

  end gatherLeaf

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
