package wvlet.uni.parquet

import wvlet.uni.parquet.format.LogicalType
import wvlet.uni.parquet.format.PhysicalType

/**
  * The values of one column for every row of a [[ColumnBatch]]. Primitive values are stored
  * unboxed. Values are the raw physical values; [[getValue]] applies the logical type (for example,
  * TIMESTAMP becomes an Instant and DECIMAL a BigDecimal).
  */
sealed trait ColumnVector:
  def field: ParquetField
  def size: Int

  /** Null flags by row, or null when no row is null */
  protected def nulls: Array[Boolean]

  def isNull(i: Int): Boolean = nulls != null && nulls(i)
  def hasNulls: Boolean       = nulls != null && nulls.exists(identity)

  /** The value of row i converted to a Scala value, or null */
  def getValue(i: Int): Any

  def name: String = field.name

object ColumnVector:

  final class BooleanColumn(
      val field: ParquetField,
      val values: Array[Boolean],
      protected val nulls: Array[Boolean]
  ) extends ColumnVector:
    def size: Int                   = values.length
    def getBoolean(i: Int): Boolean = values(i)
    def getValue(i: Int): Any       =
      if isNull(i) then
        null
      else
        values(i)

  /** INT32 values, including DATE (days since the epoch), TIME_MILLIS, and small DECIMALs */
  final class IntColumn(
      val field: ParquetField,
      val values: Array[Int],
      protected val nulls: Array[Boolean]
  ) extends ColumnVector:
    def size: Int             = values.length
    def getInt(i: Int): Int   = values(i)
    def getValue(i: Int): Any =
      if isNull(i) then
        null
      else
        LogicalValues.fromInt(values(i), field.logicalType)

  /** INT64 values, including TIMESTAMP, TIME_MICROS/NANOS, and DECIMALs */
  final class LongColumn(
      val field: ParquetField,
      val values: Array[Long],
      protected val nulls: Array[Boolean]
  ) extends ColumnVector:
    def size: Int             = values.length
    def getLong(i: Int): Long = values(i)
    def getValue(i: Int): Any =
      if isNull(i) then
        null
      else
        LogicalValues.fromLong(values(i), field.logicalType)

  final class FloatColumn(
      val field: ParquetField,
      val values: Array[Float],
      protected val nulls: Array[Boolean]
  ) extends ColumnVector:
    def size: Int               = values.length
    def getFloat(i: Int): Float = values(i)
    def getValue(i: Int): Any   =
      if isNull(i) then
        null
      else
        values(i)

  final class DoubleColumn(
      val field: ParquetField,
      val values: Array[Double],
      protected val nulls: Array[Boolean]
  ) extends ColumnVector:
    def size: Int                 = values.length
    def getDouble(i: Int): Double = values(i)
    def getValue(i: Int): Any     =
      if isNull(i) then
        null
      else
        values(i)

  /**
    * BYTE_ARRAY, FIXED_LEN_BYTE_ARRAY, and INT96 values. Row i is
    * `data[offsets(i), offsets(i + 1))`; null rows are empty.
    */
  final class BinaryColumn(
      val field: ParquetField,
      val offsets: Array[Int],
      val data: Array[Byte],
      protected val nulls: Array[Boolean]
  ) extends ColumnVector:
    def size: Int                     = offsets.length - 1
    def length(i: Int): Int           = offsets(i + 1) - offsets(i)
    def getBytes(i: Int): Array[Byte] = java
      .util
      .Arrays
      .copyOfRange(data, offsets(i), offsets(i + 1))

    def getString(i: Int): String = String(data, offsets(i), length(i), "UTF-8")
    def getValue(i: Int): Any     =
      if isNull(i) then
        null
      else
        LogicalValues.fromBinary(data, offsets(i), length(i), field)

  /** A list per row: row i holds child elements [offsets(i), offsets(i + 1)) */
  final class ListColumn(
      val field: ParquetField,
      val offsets: Array[Int],
      val elements: ColumnVector,
      protected val nulls: Array[Boolean]
  ) extends ColumnVector:
    def size: Int             = offsets.length - 1
    def getValue(i: Int): Any =
      if isNull(i) then
        null
      else
        (offsets(i) until offsets(i + 1)).map(elements.getValue)

  /** A map per row: row i holds entries [offsets(i), offsets(i + 1)) of keys and values */
  final class MapColumn(
      val field: ParquetField,
      val offsets: Array[Int],
      val keys: ColumnVector,
      val values: ColumnVector,
      protected val nulls: Array[Boolean]
  ) extends ColumnVector:
    def size: Int             = offsets.length - 1
    def getValue(i: Int): Any =
      if isNull(i) then
        null
      else
        (offsets(i) until offsets(i + 1)).map(j => keys.getValue(j) -> values.getValue(j)).toMap

  /** A struct (group) per row */
  final class StructColumn(
      val field: ParquetField,
      val size: Int,
      val children: IndexedSeq[ColumnVector],
      protected val nulls: Array[Boolean]
  ) extends ColumnVector:
    def child(name: String): Option[ColumnVector] = children.find(_.name == name)
    def getValue(i: Int): Any                     =
      if isNull(i) then
        null
      else
        children.map(c => c.name -> c.getValue(i)).toMap

end ColumnVector

/** The columns of one row group */
case class ColumnBatch(numRows: Int, columns: IndexedSeq[ColumnVector]):
  def column(name: String): ColumnVector = columns
    .find(_.name == name)
    .getOrElse(throw java.util.NoSuchElementException(s"No column named ${name}"))

  def columnNames: Seq[String] = columns.map(_.name)

  /** Row i as a map from column name to value. Convenient for inspection; slower than vectors. */
  def row(i: Int): Map[String, Any] = columns.map(c => c.name -> c.getValue(i)).toMap
