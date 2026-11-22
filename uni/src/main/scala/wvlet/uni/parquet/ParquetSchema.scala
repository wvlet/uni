package wvlet.uni.parquet

import wvlet.uni.parquet.format.*
import wvlet.uni.surface.Surface

/** A field in a Parquet schema: a leaf column or a group of fields */
sealed trait ParquetField:
  def name: String
  def repetition: Repetition
  def logicalType: Option[LogicalType]

  /** Names from the root to this field */
  def path: Seq[String]

  /** The highest definition level of this field: optional and repeated ancestors, plus itself */
  def maxDefinitionLevel: Int

  /** The highest repetition level of this field: repeated ancestors, plus itself */
  def maxRepetitionLevel: Int

  def isOptional: Boolean = repetition == Repetition.OPTIONAL
  def isRepeated: Boolean = repetition == Repetition.REPEATED
  def pathString: String  = path.mkString(".")

object ParquetField:
  /** A leaf column, stored as one column chunk per row group */
  case class Primitive(
      name: String,
      repetition: Repetition,
      physicalType: PhysicalType,
      typeLength: Int,
      logicalType: Option[LogicalType],
      path: Seq[String],
      maxDefinitionLevel: Int,
      maxRepetitionLevel: Int,
      /** Position among the leaf columns, which matches the column chunk order in a row group */
      columnIndex: Int
  ) extends ParquetField

  case class Group(
      name: String,
      repetition: Repetition,
      logicalType: Option[LogicalType],
      path: Seq[String],
      maxDefinitionLevel: Int,
      maxRepetitionLevel: Int,
      children: IndexedSeq[ParquetField]
  ) extends ParquetField:
    def field(name: String): Option[ParquetField] = children.find(_.name == name)

/**
  * The schema of a Parquet file. The top-level fields are the columns users see; nested groups hold
  * structs, lists, and maps.
  */
case class ParquetSchema(root: ParquetField.Group):
  def fields: IndexedSeq[ParquetField] = root.children

  /** Leaf columns in file order */
  lazy val columns: IndexedSeq[ParquetField.Primitive] =
    val b                              = IndexedSeq.newBuilder[ParquetField.Primitive]
    def collect(f: ParquetField): Unit =
      f match
        case p: ParquetField.Primitive =>
          b += p
        case g: ParquetField.Group =>
          g.children.foreach(collect)
    collect(root)
    b.result()

  def field(name: String): Option[ParquetField] = root.field(name)

  /**
    * The schema with only the given top-level fields, in the given order, for example to write a
    * projection of a file with [[Parquet.newBatchWriter]]. Leaf column indexes are renumbered.
    */
  def select(names: String*): ParquetSchema =
    val dup = names.diff(names.distinct)
    if dup.nonEmpty then
      throw IllegalArgumentException(s"Duplicate columns: ${dup.distinct.mkString(", ")}")
    val selected = names.map(n =>
      field(n).getOrElse(throw java.util.NoSuchElementException(s"No column named ${n}"))
    )
    ParquetSchema.fromElements(
      SchemaElements.of(ParquetSchema(root.copy(children = selected.toIndexedSeq)))
    )

  /** True when no column is nested inside a group or repeated */
  def isFlat: Boolean = fields.forall {
    case p: ParquetField.Primitive =>
      !p.isRepeated
    case _ =>
      false
  }

  override def toString: String =
    val sb                                       = StringBuilder()
    def show(f: ParquetField, indent: Int): Unit =
      sb.append("  " * indent)
      sb.append(f.repetition.toString.toLowerCase)
      sb.append(' ')
      f match
        case p: ParquetField.Primitive =>
          sb.append(p.physicalType.toString.toLowerCase)
          if p.physicalType == PhysicalType.FIXED_LEN_BYTE_ARRAY then
            sb.append(s"(${p.typeLength})")
          sb.append(s" ${p.name}")
          p.logicalType.foreach(t => sb.append(s" (${t})"))
          sb.append('\n')
        case g: ParquetField.Group =>
          sb.append(s"group ${g.name}")
          g.logicalType.foreach(t => sb.append(s" (${t})"))
          sb.append(" {\n")
          g.children.foreach(show(_, indent + 1))
          sb.append("  " * indent)
          sb.append("}\n")
    sb.append(s"message ${root.name} {\n")
    fields.foreach(show(_, 1))
    sb.append("}")
    sb.result()

end ParquetSchema

object ParquetSchema:

  /**
    * Derives a schema from a case class, for writing it with [[Parquet.newWriter]].
    *
    * | Scala field                               | Parquet column                                                    |
    * |:------------------------------------------|:------------------------------------------------------------------|
    * | `Int`, `Long`, `Short`, `Byte`            | INT32, INT64, INT32 with INT(16), INT32 with INT(8)               |
    * | `Float`, `Double`, `Boolean`              | FLOAT, DOUBLE, BOOLEAN                                            |
    * | `String`, `Char`, enums                   | BYTE_ARRAY with STRING                                            |
    * | `Array[Byte]`                             | BYTE_ARRAY                                                        |
    * | `Instant`                                 | INT64 TIMESTAMP(MICROS, UTC)                                      |
    * | `LocalDate`                               | INT32 with DATE                                                   |
    * | `LocalTime`                               | INT64 TIME(MICROS), not UTC-adjusted; nanoseconds are dropped     |
    * | `UUID`                                    | FIXED_LEN_BYTE_ARRAY(16) with UUID                                |
    * | `BigDecimal`                              | DECIMAL(38, 18) unless set with `ParquetWriteOptions.withDecimal` |
    * | `Option[A]`                               | an OPTIONAL column; other fields are REQUIRED                     |
    * | `Seq[A]`, `List[A]`, `Set[A]`, `Array[A]` | a 3-level LIST                                                    |
    * | `Map[K, V]`                               | a MAP with REQUIRED keys                                          |
    * | a case class                              | a group                                                           |
    */
  inline def of[A]: ParquetSchema = fromSurface(Surface.of[A])

  /** Derives a schema from a Surface; see [[of]] for the type mapping */
  def fromSurface(
      surface: Surface,
      options: ParquetWriteOptions = ParquetWriteOptions.default
  ): ParquetSchema = fromColumns("schema", SurfaceSchema.columns(surface, options))

  /** Starts building a schema by hand, for writing records with [[Parquet.newRecordWriter]] */
  def builder(name: String = "schema"): ParquetSchemaBuilder = ParquetSchemaBuilder(name)

  /** A schema with the given top-level columns */
  def fromColumns(name: String, columns: Seq[ParquetColumn]): ParquetSchema = fromElements(
    SchemaElements.of(name, columns)
  )

  private[parquet] def fromElements(elements: IndexedSeq[SchemaElement]): ParquetSchema =
    if elements.isEmpty then
      throw ParquetException.corrupt("the schema is empty")
    var pos       = 0
    var leafIndex = 0

    def build(parentPath: Seq[String], parentDef: Int, parentRep: Int, depth: Int): ParquetField =
      if pos >= elements.length then
        throw ParquetException.corrupt("the schema ends before all children are listed")
      if depth > 128 then
        throw ParquetException.corrupt("the schema is nested too deeply")
      val e = elements(pos)
      pos += 1
      val rep      = e.repetition.getOrElse(Repetition.REQUIRED)
      val path     = parentPath :+ e.name
      val defLevel =
        if rep == Repetition.REQUIRED then
          parentDef
        else
          parentDef + 1
      val repLevel =
        if rep == Repetition.REPEATED then
          parentRep + 1
        else
          parentRep
      e.numChildren match
        case Some(n) if n > 0 || e.physicalType.isEmpty =>
          if n < 0 || n > elements.length - pos then
            throw ParquetException.corrupt(s"group ${e.name} has an invalid child count ${n}")
          val children = IndexedSeq.fill(n)(build(path, defLevel, repLevel, depth + 1))
          ParquetField.Group(e.name, rep, e.logicalType, path, defLevel, repLevel, children)
        case _ =>
          val tpe = e
            .physicalType
            .getOrElse(throw ParquetException.corrupt(s"column ${e.name} has no physical type"))
          val idx = leafIndex
          leafIndex += 1
          ParquetField.Primitive(
            e.name,
            rep,
            tpe,
            e.typeLength.getOrElse(0),
            e.logicalType,
            path,
            defLevel,
            repLevel,
            idx
          )

    end build

    val root = elements(0)
    pos = 1
    val n = root.numChildren.getOrElse(0)
    if n < 0 || n > elements.length - 1 then
      throw ParquetException.corrupt(s"the schema root has an invalid child count ${n}")
    val children = IndexedSeq.fill(n)(build(Seq.empty, 0, 0, 1))
    ParquetSchema(
      ParquetField.Group(root.name, Repetition.REQUIRED, None, Seq.empty, 0, 0, children)
    )

  end fromElements

end ParquetSchema
