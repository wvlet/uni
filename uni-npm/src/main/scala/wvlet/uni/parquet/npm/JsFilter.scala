package wvlet.uni.parquet.npm

import wvlet.uni.parquet.ParquetField
import wvlet.uni.parquet.ParquetFilter
import wvlet.uni.parquet.ParquetFilter.Op
import wvlet.uni.parquet.ParquetSchema
import wvlet.uni.parquet.format.LogicalType

import java.time.Instant
import java.time.LocalDate
import scala.scalajs.js

/**
  * Parses the PyArrow-style JS filter (see `Filter` in parquet.d.ts): a condition
  * `[column, op, value]`, an array of conditions (AND), or an array of such arrays (OR of ANDs).
  */
private[npm] object JsFilter:

  private def fail(msg: String): Nothing = throw IllegalArgumentException(msg)

  private def show(v: js.Any): String =
    if js.typeOf(v) == "bigint" then
      s"${v.toString}n"
    else
      js.JSON
        .stringify(
          v,
          (_: String, x: js.Any) =>
            if js.typeOf(x) == "bigint" then
              s"${x.toString}n": js.Any
            else
              x
        )

  private def isArray(v: js.Any): Boolean = js.Array.isArray(v)

  /** A condition is an array whose first element is a column name */
  private def isCondition(v: js.Any): Boolean =
    isArray(v) && {
      val a = v.asInstanceOf[js.Array[js.Any]]
      a.length > 0 && js.typeOf(a(0)) == "string"
    }

  def parse(filter: js.Any, schema: ParquetSchema): ParquetFilter =
    if !isArray(filter) then
      fail(s"A filter is an array such as [\"dt\", \">=\", \"2026-11-15\"], not ${show(filter)}")
    if isCondition(filter) then
      condition(filter.asInstanceOf[js.Array[js.Any]], schema)
    else
      val parts = filter.asInstanceOf[js.Array[js.Any]].toSeq
      if parts.isEmpty then
        fail("A filter has at least one condition")
      if parts.forall(isCondition) then
        allOf(parts, schema)
      else if parts.forall(p => isArray(p) && !isCondition(p)) then
        parts
          .map { p =>
            val conds = p.asInstanceOf[js.Array[js.Any]].toSeq
            if conds.isEmpty || !conds.forall(isCondition) then
              fail(s"Each OR group is a non-empty array of conditions: ${show(p)}")
            allOf(conds, schema)
          }
          .reduceLeft(ParquetFilter.Or(_, _))
      else
        fail(
          s"A filter is a condition, an array of conditions (AND), or an array of arrays of conditions (OR of ANDs): ${show(
              filter
            )}"
        )

  private def allOf(conds: Seq[js.Any], schema: ParquetSchema): ParquetFilter = conds
    .map(c => condition(c.asInstanceOf[js.Array[js.Any]], schema))
    .reduceLeft(ParquetFilter.And(_, _))

  private def condition(c: js.Array[js.Any], schema: ParquetSchema): ParquetFilter =
    val name = c(0).asInstanceOf[String]
    if c.length < 2 || js.typeOf(c(1)) != "string" then
      fail(s"A condition is [column, op, value]: ${show(c)}")
    val op                  = c(1).asInstanceOf[String]
    def arity(n: Int): Unit =
      if c.length != n then
        fail(s"\"${op}\" takes ${n - 2} value(s): ${show(c)}")
    def lit(v: js.Any): Any       = literal(v, name, schema, c)
    def cmp(o: Op): ParquetFilter =
      arity(3)
      ParquetFilter.Compare(name, o, lit(c(2)))
    // Lt || Gt keeps SQL semantics for !=: a null value matches neither
    def ne(v: Any): ParquetFilter = ParquetFilter.Or(
      ParquetFilter.Compare(name, Op.Lt, v),
      ParquetFilter.Compare(name, Op.Gt, v)
    )
    def values: Seq[Any] =
      arity(3)
      if !isArray(c(2)) || c(2).asInstanceOf[js.Array[js.Any]].length == 0 then
        fail(s"\"${op}\" takes a non-empty array of values: ${show(c)}")
      c(2).asInstanceOf[js.Array[js.Any]].toSeq.map(lit)
    op.toLowerCase match
      case "=" | "==" =>
        cmp(Op.Eq)
      case "<" =>
        cmp(Op.Lt)
      case "<=" =>
        cmp(Op.LtEq)
      case ">" =>
        cmp(Op.Gt)
      case ">=" =>
        cmp(Op.GtEq)
      case "!=" =>
        arity(3)
        ne(lit(c(2)))
      case "in" =>
        values.map(v => ParquetFilter.Compare(name, Op.Eq, v)).reduceLeft(ParquetFilter.Or(_, _))
      case "not in" =>
        values.map(ne).reduceLeft(ParquetFilter.And(_, _))
      case "is null" =>
        arity(2)
        ParquetFilter.IsNull(name)
      case "is not null" =>
        arity(2)
        ParquetFilter.IsNotNull(name)
      case _ =>
        fail(
          s"Unknown filter operator \"${op}\". Use =, !=, <, <=, >, >=, in, not in, is null, or is not null"
        )

  end condition

  private def isDateColumn(name: String, schema: ParquetSchema): Boolean =
    schema.field(name) match
      case Some(p: ParquetField.Primitive) =>
        p.logicalType.contains(LogicalType.Date)
      case _ =>
        false

  private def literal(v: js.Any, name: String, schema: ParquetSchema, c: js.Any): Any =
    if v == null || js.isUndefined(v) then
      fail(s"Compare with null using \"is null\": ${show(c)}")
    js.typeOf(v) match
      case "string" | "boolean" =>
        v
      case "bigint" =>
        val b = BigInt(v.toString)
        if b.isValidLong then
          b.toLong
        else
          b
      case "number" =>
        val d = v.asInstanceOf[Double]
        if d.isWhole && math.abs(d) <= 9007199254740991.0 then
          d.toLong
        else
          d
      case _ if v.isInstanceOf[js.Date] =>
        val t = v.asInstanceOf[js.Date].getTime()
        if t.isNaN then
          fail(s"Invalid Date in ${show(c)}")
        if isDateColumn(name, schema) then
          LocalDate.ofEpochDay(math.floor(t / 86400000.0).toLong)
        else
          Instant.ofEpochMilli(t.toLong)
      case other =>
        fail(s"Unsupported filter value of type ${other} in ${show(c)}")

end JsFilter
