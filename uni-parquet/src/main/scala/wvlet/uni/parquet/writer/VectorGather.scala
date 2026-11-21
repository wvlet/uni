package wvlet.uni.parquet.writer

import wvlet.uni.parquet.ColumnBatch
import wvlet.uni.parquet.ColumnVector
import wvlet.uni.parquet.ColumnVector.*

/**
  * Copies selected rows of column vectors into new vectors, so a batch can be split by partition.
  * Nested vectors (lists, maps, structs) copy the child elements of each selected row.
  */
private[parquet] object VectorGather:

  /** The batch's rows `rows[0, n)`, in that order */
  def take(batch: ColumnBatch, rows: Array[Int], n: Int): ColumnBatch = ColumnBatch(
    n,
    batch.columns.map(take(_, rows, n))
  )

  def take(v: ColumnVector, rows: Array[Int], n: Int): ColumnVector =
    val nulls = takeNulls(v, rows, n)
    v match
      case c: BooleanColumn =>
        val a = new Array[Boolean](n)
        var i = 0
        while i < n do
          a(i) = c.values(rows(i))
          i += 1
        BooleanColumn(c.field, a, nulls)
      case c: IntColumn =>
        val a = new Array[Int](n)
        var i = 0
        while i < n do
          a(i) = c.values(rows(i))
          i += 1
        IntColumn(c.field, a, nulls)
      case c: LongColumn =>
        val a = new Array[Long](n)
        var i = 0
        while i < n do
          a(i) = c.values(rows(i))
          i += 1
        LongColumn(c.field, a, nulls)
      case c: FloatColumn =>
        val a = new Array[Float](n)
        var i = 0
        while i < n do
          a(i) = c.values(rows(i))
          i += 1
        FloatColumn(c.field, a, nulls)
      case c: DoubleColumn =>
        val a = new Array[Double](n)
        var i = 0
        while i < n do
          a(i) = c.values(rows(i))
          i += 1
        DoubleColumn(c.field, a, nulls)
      case c: BinaryColumn =>
        val offsets = rangeOffsets(c.offsets, rows, n)
        val data    = new Array[Byte](offsets(n))
        var i       = 0
        while i < n do
          val r = rows(i)
          System.arraycopy(c.data, c.offsets(r), data, offsets(i), c.offsets(r + 1) - c.offsets(r))
          i += 1
        BinaryColumn(c.field, offsets, data, nulls, c.binaryAsString)
      case c: ListColumn =>
        val offsets = rangeOffsets(c.offsets, rows, n)
        val inner   = innerRows(c.offsets, rows, n, offsets(n))
        ListColumn(c.field, offsets, take(c.elements, inner, inner.length), nulls)
      case c: MapColumn =>
        val offsets = rangeOffsets(c.offsets, rows, n)
        val inner   = innerRows(c.offsets, rows, n, offsets(n))
        MapColumn(
          c.field,
          offsets,
          take(c.keys, inner, inner.length),
          take(c.values, inner, inner.length),
          nulls
        )
      case c: StructColumn =>
        StructColumn(c.field, n, c.children.map(take(_, rows, n)), nulls)
    end match

  end take

  private def takeNulls(v: ColumnVector, rows: Array[Int], n: Int): Array[Boolean] =
    if !v.hasNulls then
      null
    else
      val a = new Array[Boolean](n)
      var i = 0
      while i < n do
        a(i) = v.isNull(rows(i))
        i += 1
      a

  /** Offsets of the selected ranges laid end to end */
  private def rangeOffsets(offsets: Array[Int], rows: Array[Int], n: Int): Array[Int] =
    val out = new Array[Int](n + 1)
    var i   = 0
    while i < n do
      val r = rows(i)
      out(i + 1) = out(i) + offsets(r + 1) - offsets(r)
      i += 1
    out

  /** The child positions of the selected ranges, in order */
  private def innerRows(offsets: Array[Int], rows: Array[Int], n: Int, total: Int): Array[Int] =
    val out = new Array[Int](total)
    var k   = 0
    var i   = 0
    while i < n do
      val r = rows(i)
      var j = offsets(r)
      while j < offsets(r + 1) do
        out(k) = j
        k += 1
        j += 1
      i += 1
    out

end VectorGather
