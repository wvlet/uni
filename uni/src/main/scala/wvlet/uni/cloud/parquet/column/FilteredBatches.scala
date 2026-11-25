package wvlet.uni.cloud.parquet.column

import wvlet.uni.cloud.parquet.ColumnBatch
import wvlet.uni.cloud.parquet.ColumnVector
import wvlet.uni.cloud.parquet.ParquetException
import wvlet.uni.cloud.parquet.ParquetField
import wvlet.uni.cloud.parquet.ParquetFilter

/** One step of a row filter: the fields it adds to the decoded ones, and the test of its rows */
private[parquet] final class FilterStep(
    val fields: IndexedSeq[ParquetField],
    val filter: ParquetFilter.RowFilter
)

/**
  * The batches of one row group under a row filter, decoded filter-first (late materialization).
  *
  * Each batch decodes the `eager` fields for every row with [[ColumnChunkCursor]]s, as
  * [[RowGroupBatches]] does: the first step's fields, and those a [[SelectiveCursor]] can't read.
  * The steps then run in order, each decoding its fields for the rows still selected, and dropping
  * the rows its test rejects. A step tests the decoded columns in place, through a selection
  * vector, rather than gathering them for the rows that remain: each column is gathered once, for
  * the output. The `payload` fields are decoded last, for the rows that pass every step; when none
  * does, later fields read no values. Batches come with the `output` fields only, in their order,
  * and may have no rows.
  */
private[parquet] final class FilteredBatches(
    output: IndexedSeq[ParquetField],
    eagerFields: IndexedSeq[ParquetField],
    eager: IndexedSeq[ColumnChunkCursor],
    steps: IndexedSeq[FilterStep],
    payload: IndexedSeq[ParquetField],
    selective: Map[Int, SelectiveCursor],
    numRows: Long,
    batchSize: Int,
    binaryAsString: Boolean,
    int32DecimalAsLong: Boolean
) extends Iterator[ColumnBatch]:
  if numRows < 0 then
    throw ParquetException.corrupt(s"row group has ${numRows} rows")

  if eager.isEmpty then
    throw IllegalArgumentException("a filtered read decodes at least the first step's columns")

  private var remaining = numRows
  // A row group without rows still gives one (empty) batch
  private var first = true

  def hasNext: Boolean = remaining > 0 || first

  def next(): ColumnBatch =
    if !hasNext then
      throw java.util.NoSuchElementException("no more batches in this row group")
    first = false
    val target = math.min(remaining, batchSize.toLong).toInt
    val rows   = eager.map(_.fill(target, toEnd = target == remaining)).min
    if rows == 0 && target > 0 then
      throw ParquetException.corrupt(
        s"a column ends ${remaining} rows before the row group's ${numRows} rows"
      )
    val columns = eager.map(c => c.field.columnIndex -> c.take(rows)).toMap
    remaining -= rows
    // Each decoded column, with the level it was decoded at. A level's index map gives, for each
    // row still selected, the index of the row in the level's columns: null while they are equal,
    // which holds for the latest level and for every level until a step drops a row
    val decoded = scala.collection.mutable.LinkedHashMap.empty[String, (ColumnVector, Int)]
    val index   = scala.collection.mutable.ArrayBuffer.empty[Array[Int]]
    def addLevel(vs: IndexedSeq[ColumnVector]): Unit =
      val level = index.size
      index += null
      vs.foreach(v => decoded.getOrElseUpdate(v.name, (v, level)))
    addLevel(vectors(Assembly.build(eagerFields, columns, rows, int32DecimalAsLong)))
    columns.values.foreach(_.releaseLevels())

    // The rows still selected, as row numbers of the batch (null for all of them), and their count
    var selected: Array[Int] = null
    var n                    = rows
    // Every row, until a step drops one
    lazy val all = Array.range(0, rows)
    def current  =
      if selected != null then
        selected
      else
        all
    val column   = (name: String) => decoded(name)._1
    val position = (name: String) => index(decoded(name)._2)
    steps.foreach { step =>
      if step.fields.nonEmpty then
        addLevel(decode(step.fields, rows, current, n))
      if n > 0 then
        // Tests the selected rows in place, by their positions among them
        val kept = step.filter.select(column, position, Array.range(0, n), n)
        if kept.length < n then
          var l = 0
          while l < index.size do
            val ix = index(l)
            index(l) =
              if ix == null then
                kept
              else
                kept.map(ix(_))
            l += 1
          selected =
            if selected == null then
              kept
            else
              kept.map(selected(_))
          n = kept.length
    }
    addLevel(decode(payload, rows, current, n))
    if remaining == 0 then
      eager.foreach(_.checkDone())
      selective.values.foreach(_.checkDone())
    // Each output column is gathered once, from the level it was decoded at
    ColumnBatch(
      n,
      output.map { f =>
        val (v, level) = decoded(f.name)
        val ix         = index(level)
        if ix == null then
          v
        else
          ColumnVector.take(v, ix)
      }
    )

  end next

  /** Decodes the fields for the `n` selected rows of the next `rows` */
  private def decode(
      fields: IndexedSeq[ParquetField],
      rows: Int,
      selected: => Array[Int],
      n: Int
  ): IndexedSeq[ColumnVector] =
    if fields.isEmpty then
      IndexedSeq.empty
    else
      val sel     = selected
      val columns =
        fields
          .flatMap(FilteredBatches.leaves)
          .map(p => p.columnIndex -> selective(p.columnIndex).select(rows, sel, n))
          .toMap
      val out = vectors(Assembly.build(fields, columns, n, int32DecimalAsLong))
      columns.values.foreach(_.releaseLevels())
      out

  private def vectors(vs: IndexedSeq[ColumnVector]): IndexedSeq[ColumnVector] =
    if binaryAsString then
      vs.map(ColumnVector.withBinaryAsString)
    else
      vs

end FilteredBatches

private[parquet] object FilteredBatches:
  def leaves(f: ParquetField): Seq[ParquetField.Primitive] =
    f match
      case p: ParquetField.Primitive =>
        Seq(p)
      case g: ParquetField.Group =>
        g.children.flatMap(leaves)
