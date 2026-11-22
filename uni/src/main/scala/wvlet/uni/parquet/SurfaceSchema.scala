package wvlet.uni.parquet

import wvlet.uni.surface.*
import wvlet.uni.surface.Primitive as SP
import wvlet.uni.parquet.format.Repetition

/**
  * Derives Parquet column types from a Surface: `Option` is OPTIONAL, collections are LISTs, `Map`
  * is a MAP, and case classes are groups. See [[ParquetSchema.fromSurface]] for the type table.
  */
private[parquet] object SurfaceSchema:

  def columns(surface: Surface, options: ParquetWriteOptions): Seq[ParquetColumn] =
    val s = unwrap(surface)
    if s.params.isEmpty || s.isPrimitive then
      throw IllegalArgumentException(s"${surface.name} is not a case class with fields")
    structColumns(s, Seq.empty, options, Set(s.fullName))

  private def unwrap(s: Surface): Surface =
    s match
      case a: Alias =>
        unwrap(a.ref)
      case t: TaggedSurface =>
        unwrap(t.base)
      case other =>
        other

  private def structColumns(
      s: Surface,
      path: Seq[String],
      options: ParquetWriteOptions,
      visiting: Set[String]
  ): Seq[ParquetColumn] = s
    .params
    .map { p =>
      val fieldPath       = path :+ p.name
      val (tpe, optional) = typeOf(p.surface, fieldPath, options, visiting)
      ParquetColumn(
        p.name,
        tpe,
        if optional then
          Repetition.OPTIONAL
        else
          Repetition.REQUIRED
      )
    }

  /** The column type of a field, and whether it is optional */
  private def typeOf(
      surface: Surface,
      path: Seq[String],
      options: ParquetWriteOptions,
      visiting: Set[String]
  ): (ParquetType, Boolean) =
    val s = unwrap(surface)
    if s.isOption then
      val elem = unwrap(s.typeArgs.head)
      if elem.isOption then
        throw unsupported(path, s, "nested Option")
      (required(elem, path, options, visiting), true)
    else
      (required(s, path, options, visiting), false)

  private def required(
      s: Surface,
      path: Seq[String],
      options: ParquetWriteOptions,
      visiting: Set[String]
  ): ParquetType =
    import ParquetType.{Primitive as _, *}
    s match
      case SP.Int =>
        INT32
      case SP.Long =>
        INT64
      case SP.Short =>
        INT16
      case SP.Byte =>
        INT8
      case SP.Float =>
        FLOAT
      case SP.Double =>
        DOUBLE
      case SP.Boolean =>
        BOOLEAN
      case SP.String | SP.Char =>
        STRING
      case a: ArraySurface if unwrap(a.elementSurface) == SP.Byte =>
        BINARY
      case a: ArraySurface =>
        listOf(a.elementSurface, path, options, visiting)
      case _: LazySurface =>
        throw unsupported(path, s, "recursive type")
      case _ if s.rawType == classOf[java.time.Instant] =>
        TIMESTAMP_MICROS
      case _ if s.rawType == classOf[java.time.LocalDate] =>
        DATE
      case _ if s.rawType == classOf[java.time.LocalTime] =>
        TIME_MICROS
      case _ if s.rawType == classOf[java.util.UUID] =>
        UUID
      case _
          if s.rawType == classOf[scala.math.BigDecimal] ||
            s.rawType == classOf[java.math.BigDecimal] =>
        val (precision, scale) = options
          .decimalColumns
          .getOrElse(path.mkString("."), (options.decimalPrecision, options.decimalScale))
        decimal(precision, scale)
      case e: EnumSurface if e.hasParameterizedCases =>
        throw unsupported(path, s, "enum with parameterized cases")
      case _: EnumSurface | _: JavaEnumSurface =>
        STRING
      case _ if s.isEnum =>
        STRING
      case _ if s.isMap && s.typeArgs.size == 2 =>
        val key = unwrap(s.typeArgs(0))
        val k   = required(key, path, options, visiting)
        k match
          case _: ParquetType.Primitive =>
          case _                        =>
            throw unsupported(path, s, s"map key type ${key.name}")
        val (v, optionalValue) = typeOf(s.typeArgs(1), path, options, visiting)
        ParquetType.map(k, v, optionalValue)
      case _ if classOf[Iterable[?]].isAssignableFrom(s.rawType) && s.typeArgs.size == 1 =>
        listOf(s.typeArgs.head, path, options, visiting)
      case _ if s.params.nonEmpty && !s.isPrimitive =>
        if visiting.contains(s.fullName) then
          throw unsupported(path, s, "recursive type")
        Struct(structColumns(s, path, options, visiting + s.fullName))
      case _ =>
        throw unsupported(path, s, s"type ${s.name}")
    end match

  end required

  private def listOf(
      elem: Surface,
      path: Seq[String],
      options: ParquetWriteOptions,
      visiting: Set[String]
  ): ParquetType =
    val (t, optional) = typeOf(elem, path, options, visiting)
    ParquetType.list(t, optional)

  private def unsupported(path: Seq[String], s: Surface, what: String): ParquetException =
    ParquetException.unsupported(s"${what} in field ${path.mkString(".")} (${s.name})")

end SurfaceSchema
