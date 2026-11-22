package wvlet.uni.parquet

import wvlet.uni.parquet.format.*

/**
  * The type of a column in a schema built with [[ParquetSchema.builder]]: a primitive with an
  * optional logical type, a list, a map, or a struct of named columns.
  *
  * {{{
  * import wvlet.uni.parquet.ParquetType.*
  *
  * val schema = ParquetSchema
  *   .builder("event")
  *   .required("id", INT64)
  *   .optional("tags", list(STRING))
  *   .optional("price", decimal(10, 2))
  *   .build()
  * }}}
  */
sealed trait ParquetType

object ParquetType:
  case class Primitive(
      physicalType: PhysicalType,
      logicalType: Option[LogicalType] = None,
      typeLength: Int = 0
  ) extends ParquetType

  /** A 3-level LIST. Elements are required unless `optionalElements` is set. */
  case class ListOf(element: ParquetType, optionalElements: Boolean = false) extends ParquetType

  /** A MAP. Keys are always required; values are required unless `optionalValues` is set. */
  case class MapOf(key: ParquetType, value: ParquetType, optionalValues: Boolean = false)
      extends ParquetType

  case class Struct(columns: Seq[ParquetColumn]) extends ParquetType

  val BOOLEAN: Primitive = Primitive(PhysicalType.BOOLEAN)
  val INT8: Primitive    = Primitive(PhysicalType.INT32, Some(LogicalType.Integer(8, true)))
  val INT16: Primitive   = Primitive(PhysicalType.INT32, Some(LogicalType.Integer(16, true)))
  val INT32: Primitive   = Primitive(PhysicalType.INT32)
  val INT64: Primitive   = Primitive(PhysicalType.INT64)
  val FLOAT: Primitive   = Primitive(PhysicalType.FLOAT)
  val DOUBLE: Primitive  = Primitive(PhysicalType.DOUBLE)
  val BINARY: Primitive  = Primitive(PhysicalType.BYTE_ARRAY)
  val STRING: Primitive  = Primitive(PhysicalType.BYTE_ARRAY, Some(LogicalType.String))
  val ENUM: Primitive    = Primitive(PhysicalType.BYTE_ARRAY, Some(LogicalType.Enum))
  val JSON: Primitive    = Primitive(PhysicalType.BYTE_ARRAY, Some(LogicalType.Json))
  val DATE: Primitive    = Primitive(PhysicalType.INT32, Some(LogicalType.Date))
  val TIMESTAMP_MILLIS: Primitive = timestamp(TimeUnit.MILLIS)
  val TIMESTAMP_MICROS: Primitive = timestamp(TimeUnit.MICROS)
  val TIMESTAMP_NANOS: Primitive  = timestamp(TimeUnit.NANOS)
  val TIME_MILLIS: Primitive      = time(TimeUnit.MILLIS)
  val TIME_MICROS: Primitive      = time(TimeUnit.MICROS)
  val TIME_NANOS: Primitive       = time(TimeUnit.NANOS)
  val UUID: Primitive = Primitive(PhysicalType.FIXED_LEN_BYTE_ARRAY, Some(LogicalType.Uuid), 16)

  /** A TIMESTAMP in UTC (`isAdjustedToUTC = true`), stored as INT64 */
  def timestamp(unit: TimeUnit): Primitive = Primitive(
    PhysicalType.INT64,
    Some(LogicalType.Timestamp(true, unit))
  )

  /**
    * A TIME of day with no zone (`isAdjustedToUTC = false`), as `LocalTime` holds. MILLIS is stored
    * as INT32, MICROS and NANOS as INT64.
    */
  def time(unit: TimeUnit): Primitive =
    val physical =
      if unit == TimeUnit.MILLIS then
        PhysicalType.INT32
      else
        PhysicalType.INT64
    Primitive(physical, Some(LogicalType.Time(false, unit)))

  /**
    * A DECIMAL, stored as INT32 up to precision 9, INT64 up to 18, and FIXED_LEN_BYTE_ARRAY of the
    * smallest length above that, as the Parquet spec recommends
    */
  def decimal(precision: Int, scale: Int): Primitive =
    if precision < 1 || precision > 76 then
      throw IllegalArgumentException(s"DECIMAL precision must be 1 to 76: ${precision}")
    if scale < 0 || scale > precision then
      throw IllegalArgumentException(s"DECIMAL scale must be 0 to ${precision}: ${scale}")
    val lt = Some(LogicalType.Decimal(scale, precision))
    if precision <= 9 then
      Primitive(PhysicalType.INT32, lt)
    else if precision <= 18 then
      Primitive(PhysicalType.INT64, lt)
    else
      Primitive(PhysicalType.FIXED_LEN_BYTE_ARRAY, lt, decimalBytes(precision))

  /** The smallest number of bytes whose two's complement range holds `precision` digits */
  private[parquet] def decimalBytes(precision: Int): Int =
    val limit = BigInt(10).pow(precision) - 1
    var n     = 1
    while (BigInt(1) << (8 * n - 1)) <= limit do
      n += 1
    n

  def fixed(length: Int): Primitive =
    if length <= 0 then
      throw IllegalArgumentException(s"FIXED_LEN_BYTE_ARRAY length must be positive: ${length}")
    Primitive(PhysicalType.FIXED_LEN_BYTE_ARRAY, None, length)

  def list(element: ParquetType, optionalElements: Boolean = false): ListOf = ListOf(
    element,
    optionalElements
  )

  def map(key: ParquetType, value: ParquetType, optionalValues: Boolean = false): MapOf = MapOf(
    key,
    value,
    optionalValues
  )

  def struct(columns: ParquetColumn*): Struct = Struct(columns)

end ParquetType

/** A named column with its repetition, for [[ParquetSchema.builder]] and [[ParquetType.struct]] */
case class ParquetColumn(name: String, tpe: ParquetType, repetition: Repetition)

object ParquetColumn:
  def required(name: String, tpe: ParquetType): ParquetColumn = ParquetColumn(
    name,
    tpe,
    Repetition.REQUIRED
  )

  def optional(name: String, tpe: ParquetType): ParquetColumn = ParquetColumn(
    name,
    tpe,
    Repetition.OPTIONAL
  )

/** Builds a [[ParquetSchema]] column by column. Each call returns a new builder. */
case class ParquetSchemaBuilder(name: String, columns: Seq[ParquetColumn] = Seq.empty):
  def required(name: String, tpe: ParquetType): ParquetSchemaBuilder = column(
    ParquetColumn.required(name, tpe)
  )

  def optional(name: String, tpe: ParquetType): ParquetSchemaBuilder = column(
    ParquetColumn.optional(name, tpe)
  )

  def column(c: ParquetColumn): ParquetSchemaBuilder = this.copy(columns = columns :+ c)

  def build(): ParquetSchema = ParquetSchema.fromColumns(name, columns)

/** Converts [[ParquetType]] trees into the footer's flattened schema elements */
private[parquet] object SchemaElements:

  def of(rootName: String, columns: Seq[ParquetColumn]): IndexedSeq[SchemaElement] =
    val b = IndexedSeq.newBuilder[SchemaElement]
    b += group(rootName, None, None, columns.size)
    checkNames(rootName, columns.map(_.name))
    columns.foreach(c => add(b, c.name, c.repetition, c.tpe))
    b.result()

  private def checkNames(parent: String, names: Seq[String]): Unit =
    names.foreach { n =>
      if n.isEmpty then
        throw IllegalArgumentException(s"${parent} has a column with an empty name")
    }
    val dup = names.diff(names.distinct)
    if dup.nonEmpty then
      throw IllegalArgumentException(
        s"${parent} has duplicate columns: ${dup.distinct.mkString(", ")}"
      )

  private def group(
      name: String,
      rep: Option[Repetition],
      lt: Option[LogicalType],
      n: Int
  ): SchemaElement = SchemaElement(name, None, None, rep, Some(n), lt, None)

  private def add(
      b: scala.collection.mutable.Builder[SchemaElement, ?],
      name: String,
      rep: Repetition,
      tpe: ParquetType
  ): Unit =
    tpe match
      case p: ParquetType.Primitive =>
        if p.physicalType == PhysicalType.INT96 then
          throw ParquetException.unsupported("writing INT96 columns")
        val len =
          if p.physicalType == PhysicalType.FIXED_LEN_BYTE_ARRAY then
            Some(p.typeLength)
          else
            None
        b += SchemaElement(name, Some(p.physicalType), len, Some(rep), None, p.logicalType, None)
      case ParquetType.ListOf(elem, optionalElements) =>
        b += group(name, Some(rep), Some(LogicalType.List), 1)
        b += group("list", Some(Repetition.REPEATED), None, 1)
        add(b, "element", repetitionOf(optionalElements), elem)
      case ParquetType.MapOf(key, value, optionalValues) =>
        key match
          case _: ParquetType.Primitive =>
          case _                        =>
            throw IllegalArgumentException(s"map ${name} needs a primitive key type")
        b += group(name, Some(rep), Some(LogicalType.Map), 1)
        b += group("key_value", Some(Repetition.REPEATED), None, 2)
        add(b, "key", Repetition.REQUIRED, key)
        add(b, "value", repetitionOf(optionalValues), value)
      case ParquetType.Struct(columns) =>
        if columns.isEmpty then
          throw IllegalArgumentException(s"struct ${name} has no columns")
        checkNames(name, columns.map(_.name))
        b += group(name, Some(rep), None, columns.size)
        columns.foreach(c => add(b, c.name, c.repetition, c.tpe))

  private def repetitionOf(optional: Boolean): Repetition =
    if optional then
      Repetition.OPTIONAL
    else
      Repetition.REQUIRED

  /** The schema elements of a schema, for example one read from another file */
  def of(schema: ParquetSchema): IndexedSeq[SchemaElement] =
    val b                           = IndexedSeq.newBuilder[SchemaElement]
    def walk(f: ParquetField): Unit =
      f match
        case p: ParquetField.Primitive =>
          val len =
            if p.physicalType == PhysicalType.FIXED_LEN_BYTE_ARRAY then
              Some(p.typeLength)
            else
              None
          b +=
            SchemaElement(
              p.name,
              Some(p.physicalType),
              len,
              Some(p.repetition),
              None,
              p.logicalType,
              p.fieldId
            )
        case g: ParquetField.Group =>
          b +=
            group(g.name, Some(g.repetition), g.logicalType, g.children.size).copy(fieldId =
              g.fieldId
            )
          g.children.foreach(walk)
    b += group(schema.root.name, None, None, schema.fields.size)
    schema.fields.foreach(walk)
    b.result()

end SchemaElements
