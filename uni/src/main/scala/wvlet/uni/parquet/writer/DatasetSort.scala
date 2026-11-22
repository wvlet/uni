package wvlet.uni.parquet.writer

import wvlet.uni.parquet.ColumnBatch
import wvlet.uni.parquet.ColumnVector
import wvlet.uni.parquet.ColumnVector.*
import wvlet.uni.parquet.ParquetField
import wvlet.uni.parquet.ParquetSchema
import wvlet.uni.parquet.ParquetSortKey
import wvlet.uni.parquet.format.LogicalType
import wvlet.uni.parquet.format.PhysicalType
import wvlet.uni.parquet.format.SortingColumn

/**
  * The sort order of a dataset write: which file columns rows are sorted by, and how their physical
  * values compare. The order is the one [[ColumnStats]] uses for min and max, so the statistics of
  * a sorted row group are tight.
  *
  * Keys are physical values, as [[Shredder.stagedValue]] gives them: Boolean, Int, Long, Float,
  * Double, or Array[Byte], or null.
  */
private[parquet] final class DatasetSort(fileSchema: ParquetSchema, keys: Seq[ParquetSortKey]):
  import DatasetSort.*

  private val specs: Array[Spec] = keys.map(spec).toArray

  /** The leaf indexes of the sort columns in the file schema */
  val columns: Array[Int] = specs.map(_.col)

  /** The positions of the sort columns among the file's top-level fields */
  private val fieldPositions: Array[Int] = specs.map(s =>
    fileSchema.fields.indexWhere(_.name == s.field.name)
  )

  val sortingColumns: IndexedSeq[SortingColumn] =
    specs.map(s => SortingColumn(s.col, s.key.descending, s.key.nullsFirst)).toIndexedSeq

  private def spec(key: ParquetSortKey): Spec =
    val field =
      fileSchema.field(key.column) match
        case Some(p: ParquetField.Primitive) if !p.isRepeated =>
          p
        case Some(_) =>
          throw IllegalArgumentException(
            s"Sort column ${key.column} must be a top-level primitive column, not a group or a list"
          )
        case None =>
          throw IllegalArgumentException(
            s"Sort column ${key.column} is not a column of the files. The columns are: ${fileSchema
                .fields
                .map(_.name)
                .mkString(", ")}"
          )
    val order = orderOf(field).getOrElse(
      throw IllegalArgumentException(
        s"Sort column ${key.column} has type ${describe(field)}, which has no defined sort order"
      )
    )
    Spec(key, field, fileSchema.columns.indexWhere(_.path == field.path), order)

  /** The sort keys of row `i` of checked vectors in file schema order */
  def keysOf(vectors: IndexedSeq[ColumnVector], i: Int): Array[Any] =
    val out = new Array[Any](specs.length)
    var k   = 0
    while k < specs.length do
      out(k) = vectorValue(vectors(fieldPositions(k)), i)
      k += 1
    out

  // Checks each MessagePack row and stages its values
  private lazy val shredder = Shredder(fileSchema)

  /**
    * Checks a MessagePack row against the file schema, as the file writer would, and returns its
    * sort keys; throws ParquetException when the row doesn't fit
    */
  def keysOfRow(row: Array[Byte], len: Int): Array[Any] =
    shredder.shred(row, len)
    val out = new Array[Any](specs.length)
    var k   = 0
    while k < specs.length do
      out(k) = shredder.stagedValue(specs(k).col, row)
      k += 1
    out

  /** The row indexes `[0, n)` in sort order; equal rows keep their order */
  def order(keys: Int => Array[Any], n: Int): Array[Int] =
    val ks = Array.tabulate(n)(keys)
    val ix = Array.tabulate[Integer](n)(Integer.valueOf)
    // A stable merge sort (TimSort) on every platform
    java.util.Arrays.sort(ix, (a: Integer, b: Integer) => compare(ks(a.intValue), ks(b.intValue)))
    ix.map(_.intValue)

  def compare(a: Array[Any], b: Array[Any]): Int =
    var r = 0
    var k = 0
    while r == 0 && k < specs.length do
      r = specs(k).compare(a(k), b(k))
      k += 1
    r

end DatasetSort

private[parquet] object DatasetSort:

  /** How the physical values of a column compare */
  private enum Order:
    case Bool,
      Signed32,
      Unsigned32,
      Signed64,
      Unsigned64,
      Floating,
      UnsignedBytes,
      SignedBytes

  private final case class Spec(
      key: ParquetSortKey,
      field: ParquetField.Primitive,
      col: Int,
      order: Order
  ):
    def compare(a: Any, b: Any): Int =
      if a == null || b == null then
        if a == null && b == null then
          0
        else if (a == null) == key.nullsFirst then
          -1
        else
          1
      else
        val c = compareValues(order, a, b)
        if key.descending then
          -c
        else
          c

  private def compareValues(order: Order, a: Any, b: Any): Int =
    order match
      case Order.Bool =>
        java.lang.Boolean.compare(a.asInstanceOf[Boolean], b.asInstanceOf[Boolean])
      case Order.Signed32 =>
        Integer.compare(a.asInstanceOf[Int], b.asInstanceOf[Int])
      case Order.Unsigned32 =>
        Integer.compareUnsigned(a.asInstanceOf[Int], b.asInstanceOf[Int])
      case Order.Signed64 =>
        java.lang.Long.compare(a.asInstanceOf[Long], b.asInstanceOf[Long])
      case Order.Unsigned64 =>
        java.lang.Long.compareUnsigned(a.asInstanceOf[Long], b.asInstanceOf[Long])
      case Order.Floating =>
        // Double.compare puts NaN after every other value, and -0.0 before 0.0
        java.lang.Double.compare(toDouble(a), toDouble(b))
      case Order.UnsignedBytes =>
        val x = a.asInstanceOf[Array[Byte]]
        val y = b.asInstanceOf[Array[Byte]]
        ColumnStats.unsignedCompare(x, 0, x.length, y, 0, y.length)
      case Order.SignedBytes =>
        val x = a.asInstanceOf[Array[Byte]]
        val y = b.asInstanceOf[Array[Byte]]
        ColumnStats.signedCompare(x, 0, x.length, y, 0, y.length)

  // On Scala.js a Float and a Double are the same runtime value, so match on neither
  private def toDouble(v: Any): Double = v.asInstanceOf[Number].doubleValue()

  private def orderOf(p: ParquetField.Primitive): Option[Order] =
    p.logicalType match
      case Some(LogicalType.Float16 | LogicalType.Interval | LogicalType.Other(_)) =>
        None
      case Some(LogicalType.Integer(_, false)) =>
        p.physicalType match
          case PhysicalType.INT32 =>
            Some(Order.Unsigned32)
          case PhysicalType.INT64 =>
            Some(Order.Unsigned64)
          case _ =>
            None
      case lt =>
        p.physicalType match
          case PhysicalType.BOOLEAN =>
            Some(Order.Bool)
          case PhysicalType.INT32 =>
            Some(Order.Signed32)
          case PhysicalType.INT64 =>
            Some(Order.Signed64)
          case PhysicalType.FLOAT | PhysicalType.DOUBLE =>
            Some(Order.Floating)
          case PhysicalType.INT96 =>
            None
          case PhysicalType.BYTE_ARRAY | PhysicalType.FIXED_LEN_BYTE_ARRAY =>
            lt match
              case Some(LogicalType.Decimal(_, _)) =>
                Some(Order.SignedBytes)
              case _ =>
                Some(Order.UnsignedBytes)

  private def describe(p: ParquetField.Primitive): String =
    p.logicalType match
      case Some(lt) =>
        s"${p.physicalType} (${lt})"
      case None =>
        p.physicalType.toString

  private def vectorValue(v: ColumnVector, i: Int): Any =
    if v.isNull(i) then
      null
    else
      v match
        case c: BooleanColumn =>
          c.values(i)
        case c: IntColumn =>
          c.values(i)
        case c: LongColumn =>
          c.values(i)
        case c: FloatColumn =>
          c.values(i)
        case c: DoubleColumn =>
          c.values(i)
        case c: BinaryColumn =>
          c.getBytes(i)
        case other =>
          throw IllegalStateException(s"Sort column vector ${other.name} is not primitive")

  /** About how many bytes a batch holds in memory, for the writer's memory budget */
  def estimatedBytes(batch: ColumnBatch): Long = batch.columns.map(estimatedBytes).sum

  private def estimatedBytes(v: ColumnVector): Long =
    val nulls =
      if v.nulls == null then
        0L
      else
        v.nulls.length.toLong
    nulls + (
      v match
        case c: BooleanColumn =>
          c.values.length.toLong
        case c: IntColumn =>
          4L * c.values.length
        case c: LongColumn =>
          8L * c.values.length
        case c: FloatColumn =>
          4L * c.values.length
        case c: DoubleColumn =>
          8L * c.values.length
        case c: BinaryColumn =>
          4L * c.offsets.length + c.data.length
        case c: ListColumn =>
          4L * c.offsets.length + estimatedBytes(c.elements)
        case c: MapColumn =>
          4L * c.offsets.length + estimatedBytes(c.keys) + estimatedBytes(c.values)
        case c: StructColumn =>
          c.children.map(estimatedBytes).sum
    )

end DatasetSort
