package wvlet.uni.cloud.parquet.column

import wvlet.uni.cloud.parquet.ColumnBatch
import wvlet.uni.cloud.parquet.ColumnVector
import wvlet.uni.cloud.parquet.ParquetException
import wvlet.uni.cloud.parquet.ParquetField

/**
  * The batches of one row group. Each batch asks every column for up to `batchSize` rows, takes the
  * fewest rows any column has ready (a column stops early when its values pass the soft limit), and
  * cuts every column at that row.
  */
private[parquet] final class RowGroupBatches(
    fields: IndexedSeq[ParquetField],
    cursors: IndexedSeq[ColumnChunkCursor],
    numRows: Long,
    batchSize: Int,
    binaryAsString: Boolean,
    int32DecimalAsLong: Boolean = false
) extends Iterator[ColumnBatch]:
  if numRows < 0 then
    throw ParquetException.corrupt(s"row group has ${numRows} rows")

  private var remaining = numRows
  // A row group without rows still gives one (empty) batch
  private var first = true

  def hasNext: Boolean = remaining > 0 || first

  def next(): ColumnBatch =
    if !hasNext then
      throw java.util.NoSuchElementException("no more batches in this row group")
    first = false
    val target = math.min(remaining, batchSize.toLong).toInt
    val rows   =
      if cursors.isEmpty then
        target
      else
        cursors.map(_.fill(target)).min
    if rows == 0 && target > 0 then
      throw ParquetException.corrupt(
        s"a column ends ${remaining} rows before the row group's ${numRows} rows"
      )
    val columns = cursors.map(c => c.field.columnIndex -> c.take(rows)).toMap
    remaining -= rows
    if remaining == 0 then
      cursors.foreach(_.checkDone())
    val vectors = Assembly.build(fields, columns, rows, int32DecimalAsLong)
    ColumnBatch(
      rows,
      if binaryAsString then
        vectors.map(ColumnVector.withBinaryAsString)
      else
        vectors
    )

end RowGroupBatches
