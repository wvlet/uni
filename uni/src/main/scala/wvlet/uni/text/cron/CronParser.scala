/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package wvlet.uni.text.cron

import wvlet.uni.text.parser.TextParseException
import wvlet.uni.text.parser.TokenData

import scala.collection.mutable.ListBuffer

/**
  * The kind of a cron field, with its value range and names
  */
private[cron] enum FieldKind(
    val label: String,
    val min: Int,
    val max: Int,
    val names: IndexedSeq[String] = IndexedSeq.empty,
    val firstNameValue: Int = 0
):
  case Second     extends FieldKind("second", 0, 59)
  case Minute     extends FieldKind("minute", 0, 59)
  case Hour       extends FieldKind("hour", 0, 23)
  case DayOfMonth extends FieldKind("day-of-month", 1, 31)
  case Month
      extends FieldKind(
        "month",
        1,
        12,
        IndexedSeq(
          "JAN",
          "FEB",
          "MAR",
          "APR",
          "MAY",
          "JUN",
          "JUL",
          "AUG",
          "SEP",
          "OCT",
          "NOV",
          "DEC"
        ),
        1
      )

  // 7 is also Sunday
  case DayOfWeek
      extends FieldKind(
        "day-of-week",
        0,
        7,
        IndexedSeq("SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT")
      )

  /**
    * The range that `*` covers. Day-of-week stops at 6, so stepping from `*` doesn't count Sunday
    * twice.
    */
  def starMax: Int =
    if this == DayOfWeek then
      6
    else
      max

end FieldKind

/**
  * One comma-separated item of a cron field
  */
private[cron] enum CronItem:
  // `*` or `?`
  case All
  case Value(v: Int)
  case Range(start: Int, end: Int)
  // `*/n` (fromStar), `a-b/n`, or `a/n` (from a to the field's maximum)
  case Step(start: Int, end: Int, step: Int, fromStar: Boolean)

/**
  * A parsed cron field.
  *
  * @param bits
  *   bit i is set when value i matches. Day-of-week 7 is stored as 0.
  * @param isStar
  *   the field starts with `*` or `?`. Vixie cron uses this to decide whether day-of-month or
  *   day-of-week is restricted, so a step from `*` counts as unrestricted.
  */
private[cron] case class CronField(
    kind: FieldKind,
    items: List[CronItem],
    bits: Long,
    isStar: Boolean
):
  def matches(v: Int): Boolean = (bits & (1L << v)) != 0

  /**
    * The smallest matching value at or after `from`, or -1
    */
  def nextValue(from: Int): Int =
    if from > 63 then
      -1
    else
      val m = bits & (-1L << from)
      if m == 0 then
        -1
      else
        java.lang.Long.numberOfTrailingZeros(m)

  def isAll: Boolean = items == List(CronItem.All)

  /**
    * The values when the field lists only single values
    */
  def singleValues: Option[List[Int]] =
    if items.forall(_.isInstanceOf[CronItem.Value]) then
      Some(
        items.collect { case CronItem.Value(v) =>
          v
        }
      )
    else
      None

end CronField

private[cron] object CronField:
  def zero(kind: FieldKind): CronField = CronField(kind, List(CronItem.Value(0)), 1L, false)

/**
  * A recursive-descent parser over [[CronScanner]] tokens.
  *
  * {{{
  *   expr  := macro | field (WS field){4,5}
  *   field := item (',' item)*
  *   item  := ('*' | '?' | value ('-' value)?) ('/' number)?
  *   value := number | name
  * }}}
  */
private[cron] class CronParser(expr: String):
  import CronToken.*

  private val scanner                   = CronScanner(expr)
  private var tok: TokenData[CronToken] = scanner.nextToken()

  private def advance(): Unit = tok = scanner.nextToken()

  private def error(message: String, offset: Int = tok.offset): TextParseException = CronScanner
    .error(expr, message, offset)

  private def describeToken(t: TokenData[CronToken]): String =
    t.token match
      case EOF =>
        "end of input"
      case WS =>
        "whitespace"
      case _ =>
        s"'${t.str}'"

  // A field value before it is checked against the field's range
  private case class RawValue(text: String, isName: Boolean, offset: Int)

  private enum RawItem(val offset: Int):
    case All(at: Int)                           extends RawItem(at)
    case Single(v: RawValue)                    extends RawItem(v.offset)
    case Range(start: RawValue, end: RawValue)  extends RawItem(start.offset)
    case Stepped(base: RawItem, step: RawValue) extends RawItem(base.offset)
    case Question(at: Int)                      extends RawItem(at)

  private case class RawField(items: List[RawItem], offset: Int)

  def parse(): Cron =
    if tok.token == WS then
      advance()
    if tok.token == EOF then
      throw error("empty cron expression")
    else if tok.token == MACRO then
      val name = tok.str
      val at   = tok.offset
      advance()
      if tok.token == WS then
        advance()
      if tok.token != EOF then
        throw error(s"unexpected ${describeToken(tok)} after ${name}")
      val expanded = CronParser
        .macros
        .getOrElse(name.toLowerCase, throw error(s"unknown cron macro ${name}", at))
      val fields = CronParser(expanded).parseFields()
      Cron(expr, fields)
    else
      Cron(expr, parseFields())

  private def parseFields(): CronFields =
    val raw = ListBuffer.empty[RawField]
    raw += parseField()
    while tok.token == WS do
      advance()
      if tok.token != EOF then
        raw += parseField()
    if tok.token != EOF then
      throw error(s"unexpected ${describeToken(tok)}")

    val fields = raw.toList
    if fields.size < 5 || fields.size > 6 then
      val at =
        if fields.size > 6 then
          fields(6).offset
        else
          tok.offset
      throw error(
        s"expected 5 fields (minute hour day-of-month month day-of-week), " +
          s"or 6 with a leading seconds field, but found ${fields.size}",
        at
      )

    val (seconds, rest) =
      if fields.size == 6 then
        (Some(resolve(fields.head, FieldKind.Second)), fields.tail)
      else
        (None, fields)
    val List(minute, hour, dom, month, dow) = rest: @unchecked
    val result                              = CronFields(
      seconds,
      resolve(minute, FieldKind.Minute),
      resolve(hour, FieldKind.Hour),
      resolve(dom, FieldKind.DayOfMonth),
      resolve(month, FieldKind.Month),
      resolve(dow, FieldKind.DayOfWeek)
    )
    checkDaysExist(result, dom.offset)
    result

  end parseFields

  private def parseField(): RawField =
    val start = tok.offset
    val items = ListBuffer.empty[RawItem]
    items += parseItem()
    while tok.token == COMMA do
      advance()
      items += parseItem()
    RawField(items.toList, start)

  private def parseItem(): RawItem =
    val base =
      tok.token match
        case STAR =>
          val at = tok.offset
          advance()
          RawItem.All(at)
        case QUESTION =>
          val at = tok.offset
          advance()
          RawItem.Question(at)
        case NUMBER | NAME =>
          val start = parseValue()
          if tok.token == HYPHEN then
            advance()
            RawItem.Range(start, parseValue())
          else
            RawItem.Single(start)
        case _ =>
          throw error(s"expected a cron field value, but found ${describeToken(tok)}")
    if tok.token == SLASH then
      advance()
      if tok.token != NUMBER then
        throw error(s"expected a step number after '/', but found ${describeToken(tok)}")
      val step = parseValue()
      RawItem.Stepped(base, step)
    else
      base

  private def parseValue(): RawValue =
    tok.token match
      case NUMBER | NAME =>
        val v = RawValue(tok.str, tok.token == NAME, tok.offset)
        advance()
        v
      case _ =>
        throw error(s"expected a number or a name, but found ${describeToken(tok)}")

  private def resolve(field: RawField, kind: FieldKind): CronField =
    var bits = 0L

    def set(start: Int, end: Int, step: Int): Unit =
      var v = start
      while v <= end do
        bits |= 1L << (
          if kind == FieldKind.DayOfWeek && v == 7 then
            0
          else
            v
        )
        v += step

    def range(start: RawValue, end: RawValue): (Int, Int) =
      val a = value(start, kind)
      val b = value(end, kind)
      if a > b then
        throw error(s"the ${kind.label} range ${a}-${b} goes backwards", start.offset)
      (a, b)

    def toItem(item: RawItem): CronItem =
      item match
        case RawItem.All(_) =>
          set(kind.min, kind.starMax, 1)
          CronItem.All
        case RawItem.Question(at) =>
          if kind != FieldKind.DayOfMonth && kind != FieldKind.DayOfWeek then
            throw error(s"'?' is allowed only in day-of-month and day-of-week", at)
          set(kind.min, kind.starMax, 1)
          CronItem.All
        case RawItem.Single(v) =>
          val x = value(v, kind)
          set(x, x, 1)
          CronItem.Value(x)
        case RawItem.Range(s, e) =>
          val (a, b) = range(s, e)
          set(a, b, 1)
          CronItem.Range(a, b)
        case RawItem.Stepped(base, stepValue) =>
          val step             = number(stepValue, 1, kind.starMax.max(1), "step")
          val (a, b, fromStar) =
            base match
              case RawItem.All(_) =>
                (kind.min, kind.starMax, true)
              case RawItem.Single(v) =>
                (value(v, kind), kind.starMax, false)
              case RawItem.Range(s, e) =>
                val (a, b) = range(s, e)
                (a, b, false)
              case _ =>
                throw error("a step needs '*', a value, or a range before '/'", base.offset)
          set(a, b, step)
          CronItem.Step(a, b, step, fromStar)

    val items  = field.items.map(toItem)
    val isStar =
      field.items.head match
        case RawItem.All(_) | RawItem.Question(_) =>
          true
        case RawItem.Stepped(RawItem.All(_), _) =>
          true
        case _ =>
          false
    CronField(kind, items, bits, isStar)

  end resolve

  private def value(v: RawValue, kind: FieldKind): Int =
    if v.isName then
      val i = kind.names.indexOf(v.text.toUpperCase)
      if i < 0 then
        val hint =
          if kind.names.isEmpty then
            s"the ${kind.label} field takes only numbers"
          else
            s"expected one of ${kind.names.mkString(", ")}"
        throw error(s"unknown ${kind.label} name '${v.text}': ${hint}", v.offset)
      i + kind.firstNameValue
    else
      number(v, kind.min, kind.max, kind.label)

  private def number(v: RawValue, min: Int, max: Int, label: String): Int =
    if v.isName then
      throw error(s"expected a number for the ${label}, but found '${v.text}'", v.offset)
    val n =
      if v.text.length > 4 then
        Int.MaxValue
      else
        v.text.toInt
    if n < min || n > max then
      throw error(s"${label} ${v.text} is out of range (${min}-${max})", v.offset)
    n

  /**
    * Reject a day-of-month that no selected month has (`0 0 30 2 *`), so `next` always finds a
    * time. When day-of-week is also restricted, either one matches, so any day can fire.
    */
  private def checkDaysExist(f: CronFields, domOffset: Int): Unit =
    if f.dayOfWeek.isStar && !f.dayOfMonth.isStar then
      val possible = (1 to 12).exists { m =>
        f.month.matches(m) && f.dayOfMonth.nextValue(1) <= CronParser.maxDaysInMonth(m)
      }
      if !possible then
        throw error(
          "the day-of-month never occurs in the selected months, so this schedule never runs",
          domOffset
        )

end CronParser

private[cron] object CronParser:
  val macros: Map[String, String] = Map(
    "@yearly"   -> "0 0 1 1 *",
    "@annually" -> "0 0 1 1 *",
    "@monthly"  -> "0 0 1 * *",
    "@weekly"   -> "0 0 * * 0",
    "@daily"    -> "0 0 * * *",
    "@midnight" -> "0 0 * * *",
    "@hourly"   -> "0 * * * *"
  )

  // February has 29 days in leap years
  def maxDaysInMonth(month: Int): Int =
    month match
      case 2 =>
        29
      case 4 | 6 | 9 | 11 =>
        30
      case _ =>
        31

/**
  * The parsed fields of a cron expression. `seconds` is None for 5-field expressions.
  */
private[cron] case class CronFields(
    seconds: Option[CronField],
    minute: CronField,
    hour: CronField,
    dayOfMonth: CronField,
    month: CronField,
    dayOfWeek: CronField
)
