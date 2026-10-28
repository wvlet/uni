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

import wvlet.uni.text.cron.CronItem.*

/**
  * Renders cron fields as English, in the style of cronstrue: "At 09:30, Monday through Friday".
  * Times use the 24-hour clock.
  */
private[cron] object CronDescriber:

  private val monthNames = IndexedSeq(
    "January",
    "February",
    "March",
    "April",
    "May",
    "June",
    "July",
    "August",
    "September",
    "October",
    "November",
    "December"
  )

  private val dayNames = IndexedSeq(
    "Sunday",
    "Monday",
    "Tuesday",
    "Wednesday",
    "Thursday",
    "Friday",
    "Saturday"
  )

  // Show at most this many clock times ("At 09:00, 09:30, 17:00, and 17:30")
  private val maxClockTimes = 6

  def describe(f: CronFields): String =
    // A seconds field of 0 reads the same as no seconds field
    val seconds = f.seconds.filterNot(_.singleValues.contains(List(0)))
    val parts   =
      timePhrase(seconds, f.minute, f.hour) :: dayPhrases(f) ++ monthPhrase(f.month).toList
    val text = parts.mkString(", ")
    s"${text.head.toUpper}${text.tail}"

  private def timePhrase(seconds: Option[CronField], minute: CronField, hour: CronField): String =
    val clockTimes =
      for
        ss <- seconds.map(_.singleValues.filter(_.size == 1)).getOrElse(Some(Nil))
        ms <- minute.singleValues
        hs <- hour.singleValues
        if ms.size * hs.size <= maxClockTimes
      yield
        for
          h <- hs
          m <- ms
        yield
          val hm = f"${h}%02d:${m}%02d"
          ss.headOption.map(s => f"${hm}:${s}%02d").getOrElse(hm)

    clockTimes match
      case Some(times) =>
        s"at ${join(times)}"
      case None
          if seconds.isEmpty && minute.singleValues.contains(List(0)) &&
            hourlyStep(hour).isDefined =>
        hourlyStep(hour).get
      case None =>
        val secondPart = seconds.map(unitPhrase(_, "second", "minute"))
        val minutePart =
          if secondPart.isDefined && minute.isAll then
            None
          else
            Some(unitPhrase(minute, "minute", "hour"))
        val hourPart =
          if minutePart.isEmpty && hour.isAll then
            None
          else
            hourPhrase(hour)
        (secondPart ++ minutePart ++ hourPart).mkString(", ")

  end timePhrase

  // "every hour" or "every 2 hours" for minute 0
  private def hourlyStep(hour: CronField): Option[String] =
    hour.items match
      case List(All) =>
        Some("every hour")
      case List(Step(_, _, n, true)) =>
        Some(every(n, "hour"))
      case _ =>
        None

  private def unitPhrase(f: CronField, unit: String, parent: String): String =
    f.items match
      case List(All) =>
        s"every ${unit}"
      case List(Step(_, _, n, true)) =>
        every(n, unit)
      case List(Range(a, b)) =>
        s"${unit}s ${a} through ${b} past the ${parent}"
      case List(Step(a, b, n, false)) =>
        s"${every(n, unit)}, ${unit}s ${a} through ${b} past the ${parent}"
      case items =>
        f.singleValues match
          case Some(List(1)) =>
            s"at 1 ${unit} past the ${parent}"
          case Some(vs) =>
            s"at ${join(vs.map(_.toString))} ${unit}s past the ${parent}"
          case None =>
            s"at ${unit}s ${join(items.map(itemText(_, _.toString, unit)))} past the ${parent}"

  private def hourPhrase(f: CronField): Option[String] =
    def clock(h: Int)    = f"${h}%02d:00"
    def endClock(h: Int) = f"${h}%02d:59"
    f.items match
      case List(All) =>
        None
      case List(Value(h)) =>
        Some(s"between ${clock(h)} and ${endClock(h)}")
      case List(Step(_, _, n, true)) =>
        Some(every(n, "hour"))
      case List(Range(a, b)) =>
        Some(s"between ${clock(a)} and ${endClock(b)}")
      case List(Step(a, b, n, false)) =>
        Some(s"${every(n, "hour")}, between ${clock(a)} and ${endClock(b)}")
      case items =>
        f.singleValues match
          case Some(hs) =>
            Some(s"at ${join(hs.map(clock))}")
          case None =>
            Some(s"at hours ${join(items.map(itemText(_, clock, "hour")))}")

  private def dayPhrases(f: CronFields): List[String] =
    val dom = dayOfMonthPhrase(f.dayOfMonth)
    // Vixie cron: when both fields are restricted, either one matches
    val either = !f.dayOfMonth.isStar && !f.dayOfWeek.isStar
    val dow    = dayOfWeekPhrase(f.dayOfWeek, either)
    (dom, dow) match
      case (Some(d), Some(w)) if either =>
        List(s"${d} or ${w}")
      case _ =>
        dom.toList ++ dow.toList

  private def dayOfMonthPhrase(f: CronField): Option[String] =
    f.items match
      case List(All) =>
        None
      case List(Step(_, _, n, true)) =>
        Some(every(n, "day"))
      case List(Range(a, b)) =>
        Some(s"between day ${a} and ${b} of the month")
      case List(Step(a, b, n, false)) =>
        Some(s"${every(n, "day")}, between day ${a} and ${b} of the month")
      case items =>
        f.singleValues match
          case Some(ds) =>
            Some(s"on day ${join(ds.map(_.toString))} of the month")
          case None =>
            Some(s"on days ${join(items.map(itemText(_, _.toString, "day")))} of the month")

  private def dayOfWeekPhrase(f: CronField, either: Boolean): Option[String] =
    def name(d: Int) = dayNames(d % 7)
    f.items match
      case List(All) =>
        None
      case List(Step(_, _, n, true)) =>
        Some(s"${every(n, "day")} of the week")
      case List(Range(a, b)) =>
        Some(s"${name(a)} through ${name(b)}")
      case List(Step(a, b, n, false)) =>
        Some(s"${every(n, "day")} of the week, ${name(a)} through ${name(b)}")
      case items =>
        f.singleValues match
          case Some(ds) if either =>
            Some(s"on ${join(ds.map(name))}")
          case Some(ds) =>
            Some(s"only on ${join(ds.map(name))}")
          case None =>
            Some(s"on ${join(items.map(itemText(_, name, "day")))}")

  private def monthPhrase(f: CronField): Option[String] =
    def name(m: Int) = monthNames(m - 1)
    f.items match
      case List(All) =>
        None
      case List(Step(_, _, n, true)) =>
        Some(every(n, "month"))
      case List(Range(a, b)) =>
        Some(s"${name(a)} through ${name(b)}")
      case List(Step(a, b, n, false)) =>
        Some(s"${every(n, "month")}, ${name(a)} through ${name(b)}")
      case items =>
        f.singleValues match
          case Some(ms) =>
            Some(s"only in ${join(ms.map(name))}")
          case None =>
            Some(s"in ${join(items.map(itemText(_, name, "month")))}")

  private def every(n: Int, unit: String): String =
    if n == 1 then
      s"every ${unit}"
    else
      s"every ${n} ${unit}s"

  private def itemText(item: CronItem, fmt: Int => String, unit: String): String =
    item match
      case All =>
        s"every ${unit}"
      case Value(v) =>
        fmt(v)
      case Range(a, b) =>
        s"${fmt(a)} through ${fmt(b)}"
      case Step(_, _, n, true) =>
        every(n, unit)
      case Step(a, b, n, false) =>
        s"${every(n, unit)} from ${fmt(a)} through ${fmt(b)}"

  /**
    * "a", "a and b", or "a, b, and c"
    */
  private def join(xs: List[String]): String =
    xs match
      case Nil =>
        ""
      case List(a) =>
        a
      case List(a, b) =>
        s"${a} and ${b}"
      case _ =>
        s"${xs.init.mkString(", ")}, and ${xs.last}"

end CronDescriber
