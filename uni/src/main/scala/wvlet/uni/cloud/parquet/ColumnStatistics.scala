package wvlet.uni.cloud.parquet

import wvlet.uni.cloud.parquet.format.PhysicalType
import wvlet.uni.cloud.parquet.format.RowGroupMeta
import wvlet.uni.cloud.parquet.format.Statistics
import wvlet.uni.cloud.parquet.io.ByteReader

import scala.collection.immutable.VectorMap

/**
  * A summary of one leaf column over every row group, from the statistics in the file footer.
  *
  * @param numValues
  *   values stored, including nulls (more than the row count for repeated columns)
  * @param numNulls
  *   null values, or None when a row group has no null count
  * @param compressedSize
  *   bytes of the column chunks as stored
  * @param uncompressedSize
  *   bytes of the column chunks after decompression
  * @param min
  *   the smallest value with its logical type applied (for example an Instant), or None when a row
  *   group has no statistics or the type has no reliable order (see [[ParquetFilter]]). Writers may
  *   truncate long strings, so for strings min and max are bounds rather than stored values.
  * @param max
  *   the largest value, under the same rules as min
  */
case class ColumnStatistics(
    numValues: Long,
    numNulls: Option[Long],
    compressedSize: Long,
    uncompressedSize: Long,
    min: Option[Any],
    max: Option[Any]
)

object ColumnStatistics:

  private[parquet] def of(
      schema: ParquetSchema,
      rowGroups: IndexedSeq[RowGroupMeta]
  ): Map[String, ColumnStatistics] = VectorMap.from(
    schema.columns.map(p => p.pathString -> summarize(p, rowGroups))
  )

  private def summarize(
      p: ParquetField.Primitive,
      rowGroups: IndexedSeq[RowGroupMeta]
  ): ColumnStatistics =
    val chunks = rowGroups.filter(_.columns.size > p.columnIndex).map(_.columns(p.columnIndex))
    val nulls  = chunks.map(_.statistics.flatMap(_.nullCount))
    // A chunk holding only nulls has no min or max and does not affect them
    val valued = chunks.filterNot(c => c.statistics.flatMap(_.nullCount).contains(c.numValues))
    val stats  = valued.map(_.statistics)

    def bound(pick: Statistics => Option[Array[Byte]], better: Int => Boolean): Option[Any] =
      if valued.isEmpty || stats.exists(_.isEmpty) then
        None
      else
        val candidates = stats
          .flatten
          .map { s =>
            for
              ord <- ParquetFilter.order(p, binaryAsString = false)
              if ord.trusts(s)
              bytes <- pick(s)
              key   <- ord.decode(bytes)
            yield (ord, key, bytes)
          }
        if candidates.exists(_.isEmpty) then
          None
        else
          val best = candidates
            .flatten
            .reduceLeft { (a, b) =>
              if better(a._1.cmp(b._2, a._2)) then
                b
              else
                a
            }
          Some(value(p, best._3))

    ColumnStatistics(
      numValues = chunks.map(_.numValues).sum,
      numNulls =
        if nulls.forall(_.isDefined) then
          Some(nulls.flatten.sum)
        else
          None
      ,
      compressedSize = chunks.map(_.totalCompressedSize).sum,
      uncompressedSize = chunks.map(_.totalUncompressedSize).sum,
      min = bound(_.min, _ < 0),
      max = bound(_.max, _ > 0)
    )

  end summarize

  /** A PLAIN-encoded statistics value as the Scala value getValue would return */
  private def value(p: ParquetField.Primitive, b: Array[Byte]): Any =
    p.physicalType match
      case PhysicalType.INT32 =>
        LogicalValues.fromInt(ByteReader.intLE(b, 0), p.logicalType)
      case PhysicalType.INT64 =>
        LogicalValues.fromLong(ByteReader.longLE(b, 0), p.logicalType)
      case PhysicalType.FLOAT =>
        java.lang.Float.intBitsToFloat(ByteReader.intLE(b, 0))
      case PhysicalType.DOUBLE =>
        java.lang.Double.longBitsToDouble(ByteReader.longLE(b, 0))
      case PhysicalType.BOOLEAN =>
        (b(0) & 1) == 1
      case _ =>
        LogicalValues.fromBinary(b, 0, b.length, p)

end ColumnStatistics
