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

  /** The batches' rows end to end; every batch has the same columns in the same order */
  def concat(batches: Seq[ColumnBatch]): ColumnBatch =
    if batches.size == 1 then
      batches.head
    else
      val n = batches.map(_.numRows).sum
      ColumnBatch(
        n,
        batches.head.columns.indices.map(c => concatVectors(batches.map(_.columns(c))))
      )

  private def concatVectors(vs: Seq[ColumnVector]): ColumnVector =
    val n     = vs.map(_.size).sum
    val nulls =
      if vs.exists(_.nulls != null) then
        val a   = new Array[Boolean](n)
        var pos = 0
        vs.foreach { v =>
          if v.nulls != null then
            System.arraycopy(v.nulls, 0, a, pos, v.size)
          pos += v.size
        }
        a
      else
        null
    vs.head match
      case c: BooleanColumn =>
        BooleanColumn(c.field, Array.concat(vs.map(_.asInstanceOf[BooleanColumn].values)*), nulls)
      case c: IntColumn =>
        IntColumn(c.field, Array.concat(vs.map(_.asInstanceOf[IntColumn].values)*), nulls)
      case c: LongColumn =>
        LongColumn(c.field, Array.concat(vs.map(_.asInstanceOf[LongColumn].values)*), nulls)
      case c: FloatColumn =>
        FloatColumn(c.field, Array.concat(vs.map(_.asInstanceOf[FloatColumn].values)*), nulls)
      case c: DoubleColumn =>
        DoubleColumn(c.field, Array.concat(vs.map(_.asInstanceOf[DoubleColumn].values)*), nulls)
      case c: BinaryColumn =>
        val bs = vs.map(_.asInstanceOf[BinaryColumn])
        BinaryColumn(
          c.field,
          concatOffsets(bs.map(_.offsets)),
          Array.concat(bs.map(_.data)*),
          nulls,
          c.binaryAsString
        )
      case c: ListColumn =>
        val ls = vs.map(_.asInstanceOf[ListColumn])
        ListColumn(
          c.field,
          concatOffsets(ls.map(_.offsets)),
          concatVectors(ls.map(_.elements)),
          nulls
        )
      case c: MapColumn =>
        val ms = vs.map(_.asInstanceOf[MapColumn])
        MapColumn(
          c.field,
          concatOffsets(ms.map(_.offsets)),
          concatVectors(ms.map(_.keys)),
          concatVectors(ms.map(_.values)),
          nulls
        )
      case c: StructColumn =>
        val ss = vs.map(_.asInstanceOf[StructColumn])
        StructColumn(
          c.field,
          n,
          c.children.indices.map(i => concatVectors(ss.map(_.children(i)))),
          nulls
        )
    end match

  end concatVectors

  /** Offsets of ranges laid end to end; each input starts at its own offsets(0) */
  private def concatOffsets(all: Seq[Array[Int]]): Array[Int] =
    val n   = all.map(_.length - 1).sum
    val out = new Array[Int](n + 1)
    var pos = 0
    var end = 0
    all.foreach { o =>
      var i = 0
      while i < o.length - 1 do
        out(pos + i + 1) = end + o(i + 1) - o(0)
        i += 1
      pos += o.length - 1
      end = out(pos)
    }
    out

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
