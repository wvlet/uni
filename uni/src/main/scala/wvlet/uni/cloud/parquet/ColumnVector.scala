package wvlet.uni.cloud.parquet

import wvlet.uni.cloud.parquet.format.LogicalType
import wvlet.uni.cloud.parquet.format.PhysicalType

import scala.collection.immutable.VectorMap

/**
  * The values of one column for every row of a [[ColumnBatch]]. Primitive values are stored
  * unboxed. Values are the raw physical values; [[getValue]] applies the logical type (for example,
  * TIMESTAMP becomes an Instant and DECIMAL a BigDecimal).
  */
sealed trait ColumnVector:
  def field: ParquetField
  def size: Int

  /** Null flags by row, or null when no row is null */
  protected[parquet] def nulls: Array[Boolean]

  def isNull(i: Int): Boolean = nulls != null && nulls(i)
  def hasNulls: Boolean       = nulls != null && nulls.exists(identity)

  /**
    * Null flags by row (true for a null row), or null when no row is null. The array is shared with
    * this vector, so callers must not modify it. Lets columnar consumers take the flags as is
    * instead of copying them through [[isNull]].
    */
  def nullFlags: Array[Boolean] = nulls

  /** The value of row i converted to a Scala value, or null */
  def getValue(i: Int): Any

  def name: String = field.name

object ColumnVector:

  final class BooleanColumn(
      val field: ParquetField,
      val values: Array[Boolean],
      protected[parquet] val nulls: Array[Boolean]
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
      protected[parquet] val nulls: Array[Boolean]
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
      protected[parquet] val nulls: Array[Boolean]
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
      protected[parquet] val nulls: Array[Boolean]
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
      protected[parquet] val nulls: Array[Boolean]
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
    *
    * @param binaryAsString
    *   return values without a logical type from [[getValue]] as UTF-8 strings, as
    *   [[ParquetReadOptions.withBinaryAsString]] asks
    */
  final class BinaryColumn(
      val field: ParquetField,
      val offsets: Array[Int],
      val data: Array[Byte],
      protected[parquet] val nulls: Array[Boolean],
      val binaryAsString: Boolean = false
  ) extends ColumnVector:
    def size: Int           = offsets.length - 1
    def length(i: Int): Int = offsets(i + 1) - offsets(i)

    // Set by the reader when values come from a dictionary page: row i is entry dictIndices(i) of
    // `dictionary`, or -1 for a null or PLAIN value
    private[parquet] var dictionary: column.BinaryBuffer = null
    private[parquet] var dictIndices: Array[Int]         = null

    private[parquet] def withDictionary(d: column.BinaryBuffer, indices: Array[Int]): BinaryColumn =
      if d != null then
        dictionary = d
        dictIndices = indices
      this

    def getBytes(i: Int): Array[Byte] = java
      .util
      .Arrays
      .copyOfRange(data, offsets(i), offsets(i + 1))

    /**
      * The dictionary entry row i was decoded from, or -1 when the row is null, PLAIN-encoded, or
      * the column has no dictionary. Rows with the same entry hold the same bytes.
      */
    def dictionaryIndex(i: Int): Int =
      if dictIndices == null then
        -1
      else
        dictIndices(i)

    /** The number of entries of the dictionary rows were decoded from, or 0 without one */
    def dictionarySize: Int =
      if dictionary == null then
        0
      else
        dictionary.size

    /**
      * Row i as a UTF-8 string. Values decoded from a dictionary are converted once per dictionary
      * entry, and rows with the same entry share one String.
      */
    def getString(i: Int): String =
      val k = dictionaryIndex(i)
      if k < 0 then
        utf8(i)
      else
        val cache = dictionary.strings
        var s     = cache(k)
        if s == null then
          s = utf8(i)
          cache(k) = s
        s

    private def utf8(i: Int): String = String(
      data,
      offsets(i),
      length(i),
      java.nio.charset.StandardCharsets.UTF_8
    )

    def getValue(i: Int): Any =
      if isNull(i) then
        null
      else
        LogicalValues.fromBinary(data, offsets(i), length(i), field, binaryAsString)

  end BinaryColumn

  /** A list per row: row i holds child elements [offsets(i), offsets(i + 1)) */
  final class ListColumn(
      val field: ParquetField,
      val offsets: Array[Int],
      val elements: ColumnVector,
      protected[parquet] val nulls: Array[Boolean]
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
      protected[parquet] val nulls: Array[Boolean]
  ) extends ColumnVector:
    def size: Int             = offsets.length - 1
    def getValue(i: Int): Any =
      if isNull(i) then
        null
      else
        VectorMap.from(
          (offsets(i) until offsets(i + 1)).map(j => keys.getValue(j) -> values.getValue(j))
        )

  /** A struct (group) per row */
  final class StructColumn(
      val field: ParquetField,
      val size: Int,
      val children: IndexedSeq[ColumnVector],
      protected[parquet] val nulls: Array[Boolean]
  ) extends ColumnVector:
    def child(name: String): Option[ColumnVector] = children.find(_.name == name)

    // Parsed VARIANT metadata by dictionary entry of the `metadata` column, filled as rows decode
    private[parquet] var variantMetadata: Array[AnyRef] = null

    /** Whether this is a VARIANT group, whose rows [[getValue]] decodes */
    def isVariant: Boolean = field.logicalType.contains(LogicalType.Variant)

    /**
      * Row i as a map of field names to values, or for a VARIANT group the decoded value: null,
      * Boolean, Int, Long, Float, Double, BigDecimal, String, Array[Byte], LocalDate, LocalTime,
      * Instant, UUID, a `Map[String, Any]` for an object, or a `Seq[Any]` for an array
      */
    def getValue(i: Int): Any =
      if isNull(i) then
        null
      else if isVariant then
        Variant.decode(this, i)
      else
        VectorMap.from(children.map(c => c.name -> c.getValue(i)))

  /** The given rows of a vector, in order, at any depth */
  private[parquet] def take(v: ColumnVector, rows: Array[Int]): ColumnVector =
    val n     = rows.length
    val nulls =
      if v.nulls == null then
        null
      else
        val ns = new Array[Boolean](n)
        var i  = 0
        while i < n do
          ns(i) = v.nulls(rows(i))
          i += 1
        ns
    inline def pick[A: scala.reflect.ClassTag](values: Array[A]): Array[A] =
      val out = new Array[A](n)
      var i   = 0
      while i < n do
        out(i) = values(rows(i))
        i += 1
      out
    // The child rows of each picked row's [offsets(r), offsets(r + 1)) range, and new offsets
    def ranges(offsets: Array[Int]): (Array[Int], Array[Int]) =
      val newOffsets = new Array[Int](n + 1)
      var i          = 0
      while i < n do
        newOffsets(i + 1) = newOffsets(i) + offsets(rows(i) + 1) - offsets(rows(i))
        i += 1
      val childRows = new Array[Int](newOffsets(n))
      i = 0
      while i < n do
        var j = offsets(rows(i))
        var k = newOffsets(i)
        while j < offsets(rows(i) + 1) do
          childRows(k) = j
          j += 1
          k += 1
        i += 1
      (newOffsets, childRows)

    // Each picked array is bound to a local before the constructor call. A loop inside a
    // constructor's arguments runs with the new object on the operand stack, where HotSpot can't
    // enter compiled code mid-loop (OSR), so the loop would stay interpreted until a full compile.
    v match
      case c: BooleanColumn =>
        val values = pick(c.values)
        BooleanColumn(c.field, values, nulls)
      case c: IntColumn =>
        val values = pick(c.values)
        IntColumn(c.field, values, nulls)
      case c: LongColumn =>
        val values = pick(c.values)
        LongColumn(c.field, values, nulls)
      case c: FloatColumn =>
        val values = pick(c.values)
        FloatColumn(c.field, values, nulls)
      case c: DoubleColumn =>
        val values = pick(c.values)
        DoubleColumn(c.field, values, nulls)
      case c: BinaryColumn =>
        val offsets = new Array[Int](n + 1)
        var i       = 0
        while i < n do
          offsets(i + 1) = offsets(i) + c.length(rows(i))
          i += 1
        val data = new Array[Byte](offsets(n))
        i = 0
        while i < n do
          System.arraycopy(c.data, c.offsets(rows(i)), data, offsets(i), c.length(rows(i)))
          i += 1
        val indices =
          if c.dictIndices == null then
            null
          else
            pick(c.dictIndices)
        BinaryColumn(c.field, offsets, data, nulls, c.binaryAsString).withDictionary(
          c.dictionary,
          indices
        )
      case c: ListColumn =>
        val (offsets, childRows) = ranges(c.offsets)
        val elements             = take(c.elements, childRows)
        ListColumn(c.field, offsets, elements, nulls)
      case c: MapColumn =>
        val (offsets, childRows) = ranges(c.offsets)
        val keys                 = take(c.keys, childRows)
        val values               = take(c.values, childRows)
        MapColumn(c.field, offsets, keys, values, nulls)
      case c: StructColumn =>
        val children = c.children.map(take(_, rows))
        StructColumn(c.field, n, children, nulls)
    end match

  end take

  /** The vector with binary columns at any depth set to decode as strings */
  private[parquet] def withBinaryAsString(v: ColumnVector): ColumnVector =
    v match
      case b: BinaryColumn =>
        BinaryColumn(b.field, b.offsets, b.data, b.nulls, binaryAsString = true).withDictionary(
          b.dictionary,
          b.dictIndices
        )
      case l: ListColumn =>
        ListColumn(l.field, l.offsets, withBinaryAsString(l.elements), l.nulls)
      case m: MapColumn =>
        MapColumn(
          m.field,
          m.offsets,
          withBinaryAsString(m.keys),
          withBinaryAsString(m.values),
          m.nulls
        )
      case s: StructColumn if !s.isVariant =>
        StructColumn(s.field, s.size, s.children.map(withBinaryAsString), s.nulls)
      case other =>
        other

end ColumnVector

/** The columns of one row group */
case class ColumnBatch(numRows: Int, columns: IndexedSeq[ColumnVector]):
  def column(name: String): ColumnVector = columns
    .find(_.name == name)
    .getOrElse(throw java.util.NoSuchElementException(s"No column named ${name}"))

  def columnNames: Seq[String] = columns.map(_.name)

  /** The batch with only the given columns, in the given order */
  def select(names: String*): ColumnBatch = ColumnBatch(numRows, names.map(column).toIndexedSeq)

  /**
    * Row i as a map from column name to [[ColumnVector.getValue]], in schema column order.
    * Convenient for generic code; slower than reading the vectors.
    */
  def row(i: Int): Map[String, Any] = VectorMap.from(columns.map(c => c.name -> c.getValue(i)))

  /** Every row as a map from column name to value */
  def rows: Iterator[Map[String, Any]] = Iterator.range(0, numRows).map(row)
