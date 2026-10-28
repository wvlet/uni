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

import java.time.Instant

/**
  * A parsed cron expression: compute the next run times and describe the schedule in English.
  *
  * {{{
  *   val cron = Cron.parse("30 9 * * MON-FRI")
  *   cron.next(Instant.parse("2026-10-30T12:00:00Z"))  // 2026-11-02T09:30:00Z
  *   cron.describe                                     // At 09:30, Monday through Friday
  * }}}
  *
  * Supports the 5-field Unix syntax (`minute hour day-of-month month day-of-week`) and an optional
  * leading seconds field. When both day-of-month and day-of-week are restricted, a day matches if
  * either one matches, as in Vixie cron.
  */
final class Cron private[cron] (
    /**
      * The source expression, exactly as given to [[Cron.parse]]
      */
    val source: String,
    private[cron] val fields: CronFields
):
  /**
    * The source expression, so `Cron.parse(s).toString == s`
    */
  override def toString: String = source

  override def equals(other: Any): Boolean =
    other match
      case c: Cron =>
        source == c.source
      case _ =>
        false

  override def hashCode: Int = source.hashCode

  /**
    * True if the expression has a leading seconds field
    */
  def hasSeconds: Boolean = fields.seconds.isDefined

  private def secondField: CronField = fields.seconds.getOrElse(CronField.zero(FieldKind.Second))

  /**
    * True if the schedule fires at the given instant (to the second) in the given offset
    */
  def matches(instant: Instant, offset: UtcOffset = UtcOffset.UTC): Boolean =
    val t = CivilTime.fromEpochSecond(instant.getEpochSecond + offset.totalSeconds)
    fields.month.matches(t.month) && dayMatches(t.year, t.month, t.day) &&
    fields.hour.matches(t.hour) && fields.minute.matches(t.minute) && secondField.matches(t.second)

  /**
    * The first time strictly after `after` when the schedule fires, with the fields read as local
    * time in the given fixed offset. Resolve a named time zone to its current offset first.
    */
  def next(after: Instant, offset: UtcOffset = UtcOffset.UTC): Instant =
    val start = CivilTime.fromEpochSecond(after.getEpochSecond + 1 + offset.totalSeconds)
    var year  = start.year
    var month = start.month
    var day   = start.day
    var hour  = start.hour
    var min   = start.minute
    var sec   = start.second

    val seconds = secondField
    // Every satisfiable schedule fires within one 400-year Gregorian cycle
    val lastYear = start.year + 400

    def nextMonth(): Unit =
      month += 1
      if month > 12 then
        month = 1
        year += 1
      day = 1
      hour = 0
      min = 0
      sec = 0

    def nextDay(): Unit =
      day += 1
      if day > CivilTime.daysInMonth(year, month) then
        nextMonth()
      else
        hour = 0
        min = 0
        sec = 0

    var found = false
    while !found do
      if year > lastYear then
        throw IllegalStateException(s"The cron schedule '${source}' never fires")
      if !fields.month.matches(month) then
        nextMonth()
      else if !dayMatches(year, month, day) then
        nextDay()
      else
        val h = fields.hour.nextValue(hour)
        if h < 0 then
          nextDay()
        else if h > hour then
          hour = h
          min = 0
          sec = 0
        else
          val m = fields.minute.nextValue(min)
          if m < 0 then
            hour += 1
            min = 0
            sec = 0
            if hour > 23 then
              nextDay()
          else if m > min then
            min = m
            sec = 0
          else
            val s = seconds.nextValue(sec)
            if s < 0 then
              min += 1
              sec = 0
              if min > 59 then
                hour += 1
                min = 0
                if hour > 23 then
                  nextDay()
            else
              sec = s
              found = true
        end if
      end if
    end while

    val local = CivilTime(year, month, day, hour, min, sec).toEpochSecond
    Instant.ofEpochSecond(local - offset.totalSeconds)

  end next

  /**
    * The next `n` times after `after` when the schedule fires
    */
  def nextN(after: Instant, n: Int, offset: UtcOffset = UtcOffset.UTC): Seq[Instant] =
    val b = Seq.newBuilder[Instant]
    var t = after
    var i = 0
    while i < n do
      t = next(t, offset)
      b += t
      i += 1
    b.result()

  /**
    * An English description of the schedule, such as "At 09:30, Monday through Friday"
    */
  def describe: String = CronDescriber.describe(fields)

  private def dayMatches(year: Int, month: Int, day: Int): Boolean =
    val dom      = fields.dayOfMonth
    val dow      = fields.dayOfWeek
    val domMatch = dom.matches(day)
    val dowMatch = dow.matches(CivilTime.dayOfWeek(year, month, day))
    if dom.isStar || dow.isStar then
      domMatch && dowMatch
    else
      domMatch || dowMatch

end Cron

object Cron:
  /**
    * Parse a cron expression. Throws [[wvlet.uni.text.parser.TextParseException]] with the line and
    * column on a syntax error.
    */
  def parse(expr: String): Cron = CronParser(expr).parse()

/**
  * A fixed offset from UTC, such as +09:00. Named time zones (Asia/Tokyo) need `java.time.ZoneId`,
  * which Scala.js doesn't have, so resolve them to an offset before calling [[Cron.next]].
  */
case class UtcOffset(totalSeconds: Int):
  require(
    totalSeconds.abs <= 18 * 3600,
    s"An offset must be within ±18:00, but was ${totalSeconds} seconds"
  )

  override def toString: String =
    if totalSeconds == 0 then
      "Z"
    else
      val sign =
        if totalSeconds < 0 then
          "-"
        else
          "+"
      val abs = totalSeconds.abs
      val hm  = f"${abs / 3600}%02d:${abs / 60 % 60}%02d"
      if abs % 60 == 0 then
        s"${sign}${hm}"
      else
        f"${sign}${hm}:${abs % 60}%02d"

object UtcOffset:
  val UTC: UtcOffset = UtcOffset(0)

  private val OffsetPattern = """([+-])(\d{1,2})(?::?(\d{2}))?""".r

  def ofHours(hours: Int): UtcOffset = UtcOffset(hours * 3600)

  /**
    * An offset of hours and minutes. Both take the sign of the offset: `ofHoursMinutes(-5, -30)` is
    * -05:30.
    */
  def ofHoursMinutes(hours: Int, minutes: Int): UtcOffset = UtcOffset(hours * 3600 + minutes * 60)

  /**
    * Parse `Z`, `+09:00`, `-0530`, or `+9`
    */
  def parse(s: String): UtcOffset =
    s.trim match
      case "Z" | "z" =>
        UTC
      case OffsetPattern(sign, h, m) if Option(m).forall(_.toInt < 60) =>
        val minutes = h.toInt * 60 + Option(m).map(_.toInt).getOrElse(0)
        UtcOffset(
          (
            if sign == "-" then
              -minutes
            else
              minutes
          ) * 60
        )
      case _ =>
        throw IllegalArgumentException(s"Invalid UTC offset: ${s}")

end UtcOffset

/**
  * A proleptic Gregorian date and time of day, converted to and from epoch seconds without
  * `java.time` zone support, so it works on Scala.js and Scala Native too.
  */
private[cron] case class CivilTime(
    year: Int,
    month: Int,
    day: Int,
    hour: Int,
    minute: Int,
    second: Int
):
  def toEpochSecond: Long =
    CivilTime.daysFromCivil(year, month, day) * 86400L + hour * 3600L + minute * 60L + second

private[cron] object CivilTime:
  def fromEpochSecond(epochSecond: Long): CivilTime =
    val days      = Math.floorDiv(epochSecond, 86400L)
    val secOfDay  = Math.floorMod(epochSecond, 86400L).toInt
    val (y, m, d) = civilFromDays(days)
    CivilTime(y, m, d, secOfDay / 3600, secOfDay / 60 % 60, secOfDay % 60)

  def isLeapYear(year: Int): Boolean = (year % 4 == 0 && year % 100 != 0) || year % 400 == 0

  def daysInMonth(year: Int, month: Int): Int =
    if month == 2 && !isLeapYear(year) then
      28
    else
      CronParser.maxDaysInMonth(month)

  /**
    * 0 = Sunday, ..., 6 = Saturday
    */
  def dayOfWeek(year: Int, month: Int, day: Int): Int =
    // 1970-01-01 was a Thursday
    Math.floorMod(daysFromCivil(year, month, day) + 4, 7L).toInt

  // Howard Hinnant's days_from_civil: days since 1970-01-01
  def daysFromCivil(year: Int, month: Int, day: Int): Long =
    val y =
      (
        if month <= 2 then
          year - 1
        else
          year
      ).toLong
    val era = Math.floorDiv(y, 400L)
    val yoe = y - era * 400
    val mp  = (month + 9) % 12
    val doy = (153 * mp + 2) / 5 + day - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    era * 146097 + doe - 719468

  // Howard Hinnant's civil_from_days
  def civilFromDays(days: Long): (Int, Int, Int) =
    val z   = days + 719468
    val era = Math.floorDiv(z, 146097L)
    val doe = z - era * 146097
    val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp  = (5 * doy + 2) / 153
    val d   = (doy - (153 * mp + 2) / 5 + 1).toInt
    val m   =
      (
        if mp < 10 then
          mp + 3
        else
          mp - 9
      ).toInt
    val y = (
      yoe + era * 400 + (
        if m <= 2 then
          1
        else
          0
      )
    ).toInt
    (y, m, d)

end CivilTime
