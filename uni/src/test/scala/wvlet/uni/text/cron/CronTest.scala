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

import wvlet.uni.test.UniTest
import wvlet.uni.text.parser.TextParseException

import java.time.Instant

class CronTest extends UniTest:

  private def t(s: String): Instant = Instant.parse(s)

  private def next(expr: String, after: String, offset: UtcOffset = UtcOffset.UTC): String =
    Cron.parse(expr).next(t(after), offset).toString

  private def parseError(expr: String): String =
    intercept[TextParseException] {
      Cron.parse(expr)
    }.getMessage

  test("keep the source text") {
    for expr <- Seq("*/15 * * * *", "  30 9 * * mon-fri ", "0\t0 1 1 *", "@Daily", "0 0 0 ? * SUN")
    do
      Cron.parse(expr).toString shouldBe expr
    Cron.parse("0 0 * * *") shouldBe Cron.parse("0 0 * * *")
    Cron.parse("0 0 * * *").hasSeconds shouldBe false
    Cron.parse("0 0 0 * * *").hasSeconds shouldBe true
  }

  test("find the next time for basic fields") {
    next("* * * * *", "2026-10-28T10:15:30Z") shouldBe "2026-10-28T10:16:00Z"
    next("* * * * *", "2026-10-28T10:15:00Z") shouldBe "2026-10-28T10:16:00Z"
    next("*/15 * * * *", "2026-10-28T10:15:00Z") shouldBe "2026-10-28T10:30:00Z"
    next("*/15 * * * *", "2026-10-28T10:50:00Z") shouldBe "2026-10-28T11:00:00Z"
    next("30 9 * * *", "2026-10-28T09:30:00Z") shouldBe "2026-10-29T09:30:00Z"
    next("0 0 1 * *", "2026-12-15T00:00:00Z") shouldBe "2027-01-01T00:00:00Z"
    next("1-30/10 * * * *", "2026-10-28T10:12:00Z") shouldBe "2026-10-28T10:21:00Z"
    next("5,45 23 * * *", "2026-12-31T23:50:00Z") shouldBe "2027-01-01T23:05:00Z"
    next("10/20 * * * *", "2026-10-28T10:31:00Z") shouldBe "2026-10-28T10:50:00Z"
  }

  test("use the optional seconds field") {
    next("*/10 * * * * *", "2026-10-28T10:15:31Z") shouldBe "2026-10-28T10:15:40Z"
    next("30 0 12 * * *", "2026-10-28T12:00:30Z") shouldBe "2026-10-29T12:00:30Z"
    next("59 59 23 31 12 *", "2026-01-01T00:00:00Z") shouldBe "2026-12-31T23:59:59Z"
    // A fraction of a second still moves to the next second
    Cron.parse("* * * * * *").next(t("2026-10-28T10:15:30.500Z")).toString shouldBe
      "2026-10-28T10:15:31Z"
  }

  test("resolve month and day-of-week names") {
    // 2026-10-30 is a Friday
    next("30 9 * * MON-FRI", "2026-10-30T12:00:00Z") shouldBe "2026-11-02T09:30:00Z"
    next("0 0 * * sat,SUN", "2026-10-28T00:00:00Z") shouldBe "2026-10-31T00:00:00Z"
    next("0 0 1 jan-mar *", "2026-04-01T00:00:00Z") shouldBe "2027-01-01T00:00:00Z"
    // 7 is Sunday too
    next("0 0 * * 7", "2026-10-28T00:00:00Z") shouldBe "2026-11-01T00:00:00Z"
    next("0 0 * * 5-7", "2026-11-01T00:00:00Z") shouldBe "2026-11-06T00:00:00Z"
    // 7/2 is Sunday, Tuesday, Thursday, and Saturday
    next("0 0 * * 7/2", "2026-11-01T00:00:00Z") shouldBe "2026-11-03T00:00:00Z"
    next("05 08 * * *", "2026-10-28T00:00:00Z") shouldBe "2026-10-28T08:05:00Z"
    next("00005 * * * *", "2026-10-28T00:00:00Z") shouldBe "2026-10-28T00:05:00Z"
    // A line read from a crontab keeps its line break
    next("0 0 * * *\r\n", "2026-10-28T00:00:00Z") shouldBe "2026-10-29T00:00:00Z"
  }

  test("match either day field when both are restricted") {
    // Day 13 or any Friday: 2026-11-06 is a Friday
    next("0 0 13 * FRI", "2026-11-01T00:00:00Z") shouldBe "2026-11-06T00:00:00Z"
    next("0 0 13 * FRI", "2026-11-07T00:00:00Z") shouldBe "2026-11-13T00:00:00Z"
    // `?` and a star-prefixed day-of-month leave the other field in charge
    next("0 0 ? * FRI", "2026-11-01T00:00:00Z") shouldBe "2026-11-06T00:00:00Z"
    next("0 0 1 * ?", "2026-11-02T00:00:00Z") shouldBe "2026-12-01T00:00:00Z"
    // Vixie: */2 counts as `*`, so both fields must match (odd days that are Fridays)
    next("0 0 */2 * FRI", "2026-11-01T00:00:00Z") shouldBe "2026-11-13T00:00:00Z"
  }

  test("handle leap days and month lengths") {
    next("0 0 29 2 *", "2026-01-01T00:00:00Z") shouldBe "2028-02-29T00:00:00Z"
    next("0 0 31 * *", "2026-04-01T00:00:00Z") shouldBe "2026-05-31T00:00:00Z"
    next("0 0 29 2 *", "2096-03-01T00:00:00Z") shouldBe "2104-02-29T00:00:00Z"
  }

  test("expand macros") {
    next("@yearly", "2026-10-28T00:00:00Z") shouldBe "2027-01-01T00:00:00Z"
    next("@annually", "2026-10-28T00:00:00Z") shouldBe "2027-01-01T00:00:00Z"
    next("@monthly", "2026-10-28T00:00:00Z") shouldBe "2026-11-01T00:00:00Z"
    next("@weekly", "2026-10-28T00:00:00Z") shouldBe "2026-11-01T00:00:00Z"
    next("@daily", "2026-10-28T00:00:00Z") shouldBe "2026-10-29T00:00:00Z"
    next("@midnight", "2026-10-28T00:00:00Z") shouldBe "2026-10-29T00:00:00Z"
    next(" @hourly ", "2026-10-28T10:30:00Z") shouldBe "2026-10-28T11:00:00Z"
  }

  test("compute times in a fixed offset") {
    val tokyo = UtcOffset.ofHours(9)
    // 09:00 in +09:00 is 00:00 UTC
    next("0 9 * * *", "2026-10-28T01:00:00Z", tokyo) shouldBe "2026-10-29T00:00:00Z"
    // 05:00 UTC is still Saturday 23:30 in -05:30, and Monday 00:30 there is 06:00 UTC
    next("30 0 * * MON", "2026-11-01T05:00:00Z", UtcOffset.ofHoursMinutes(-5, -30)) shouldBe
      "2026-11-02T06:00:00Z"
    Cron.parse("0 9 * * *").matches(t("2026-10-29T00:00:00Z"), tokyo) shouldBe true
    Cron.parse("0 9 * * *").matches(t("2026-10-29T00:00:00Z")) shouldBe false
  }

  test("list the next n times") {
    Cron.parse("0 */6 * * *").nextN(t("2026-10-28T05:00:00Z"), 3).map(_.toString) shouldBe
      Seq("2026-10-28T06:00:00Z", "2026-10-28T12:00:00Z", "2026-10-28T18:00:00Z")
    Cron.parse("@daily").nextN(t("2026-10-28T00:00:00Z"), 0) shouldBe Seq.empty
  }

  test("check times against the schedule") {
    val c = Cron.parse("*/5 9-17 * * MON-FRI")
    c.matches(t("2026-10-28T09:05:00Z")) shouldBe true
    c.matches(t("2026-10-28T09:05:01Z")) shouldBe false
    c.matches(t("2026-10-28T18:00:00Z")) shouldBe false
    c.matches(t("2026-10-31T09:05:00Z")) shouldBe false
  }

  test("describe schedules") {
    def d(expr: String) = Cron.parse(expr).describe
    d("30 9 * * 1-5") shouldBe "At 09:30, Monday through Friday"
    d("* * * * *") shouldBe "Every minute"
    d("*/15 * * * *") shouldBe "Every 15 minutes"
    d("0 * * * *") shouldBe "Every hour"
    d("0 */2 * * *") shouldBe "Every 2 hours"
    d("5 * * * *") shouldBe "At 5 minutes past the hour"
    d("1 * * * *") shouldBe "At 1 minute past the hour"
    d("0,30 * * * *") shouldBe "At 0 and 30 minutes past the hour"
    d("5-10 * * * *") shouldBe "Minutes 5 through 10 past the hour"
    d("*/5 9-17 * * *") shouldBe "Every 5 minutes, between 09:00 and 17:59"
    d("*/5 9 * * *") shouldBe "Every 5 minutes, between 09:00 and 09:59"
    d("*/5 9,17 * * *") shouldBe "Every 5 minutes, at 09:00 and 17:00"
    d("0 9,12,17 * * *") shouldBe "At 09:00, 12:00, and 17:00"
    d("@daily") shouldBe "At 00:00"
    d("@yearly") shouldBe "At 00:00, on day 1 of the month, only in January"
    d("@weekly") shouldBe "At 00:00, only on Sunday"
    d("0 0 1,15 * *") shouldBe "At 00:00, on day 1 and 15 of the month"
    d("0 0 */2 * *") shouldBe "At 00:00, every 2 days"
    d("0 0 1-7 * *") shouldBe "At 00:00, between day 1 and 7 of the month"
    d("0 9 1 * MON") shouldBe "At 09:00, on day 1 of the month or on Monday"
    d("0 0 * * SAT,SUN") shouldBe "At 00:00, only on Saturday and Sunday"
    d("0 0 1 JAN-MAR *") shouldBe "At 00:00, on day 1 of the month, January through March"
    d("0 0 * * 1-5/2") shouldBe "At 00:00, every 2 days of the week, Monday through Friday"
    d("0 0 1 */3 *") shouldBe "At 00:00, on day 1 of the month, every 3 months"
    d("15 30 9 * * *") shouldBe "At 09:30:15"
    d("0 30 9 * * *") shouldBe "At 09:30"
    d("* * * * * *") shouldBe "Every second"
    d("*/10 * * * * *") shouldBe "Every 10 seconds"
    d("15 * * * * *") shouldBe "At 15 seconds past the minute"
    d("5,10-20,*/30 9 * * *") shouldBe
      "At minutes 5, 10 through 20, and every 30 minutes past the hour, between 09:00 and 09:59"
  }

  test("report errors with line and column") {
    parseError("") shouldContain "empty cron expression"
    parseError("* * * *") shouldContain "but found 4"
    parseError("* * * * * * *") shouldContain "(line 1, column 13)"
    parseError("60 * * * *") shouldContain "minute 60 is out of range (0-59)"
    parseError("* 24 * * *") shouldContain "(line 1, column 3)"
    parseError("* * 0 * *") shouldContain "day-of-month 0 is out of range"
    parseError("* * * 13 *") shouldContain "month 13 is out of range"
    parseError("* * * * 8") shouldContain "day-of-week 8 is out of range"
    parseError("* * * FOO *") shouldContain "unknown month name 'FOO'"
    parseError("* * * * MON-FOO") shouldContain "(line 1, column 13)"
    parseError("MON * * * *") shouldContain "the minute field takes only numbers"
    parseError("30-10 * * * *") shouldContain "range 30-10 goes backwards"
    parseError("*/0 * * * *") shouldContain "step 0 is out of range"
    parseError("*/ * * * *") shouldContain "expected a step number"
    parseError("? * * * *") shouldContain "'?' is allowed only in day-of-month and day-of-week"
    parseError("1,,2 * * * *") shouldContain "(line 1, column 3)"
    parseError("* * * * * #") shouldContain "unexpected character '#'"
    parseError("@reboot") shouldContain "unknown cron macro @reboot"
    parseError("@daily *") shouldContain "unexpected '*' after @daily"
    parseError("0 0 30 2 *") shouldContain "never runs"
    parseError("0 0 31 4,6 *") shouldContain "never runs"
    parseError("99999999999 * * * *") shouldContain "out of range"

    val e = intercept[TextParseException] {
      Cron.parse("* * * * MON-FOO")
    }
    e.offset shouldBe 12
    e.getMessage shouldContain "* * * * MON-FOO\n            ^"
  }

  test("format and parse UTC offsets") {
    UtcOffset.UTC.toString shouldBe "Z"
    UtcOffset.ofHours(9).toString shouldBe "+09:00"
    UtcOffset.ofHoursMinutes(-5, -30).toString shouldBe "-05:30"
    UtcOffset.parse("+09:00") shouldBe UtcOffset.ofHours(9)
    UtcOffset.parse("-0530") shouldBe UtcOffset.ofHoursMinutes(-5, -30)
    UtcOffset.parse("+9") shouldBe UtcOffset.ofHours(9)
    UtcOffset.parse("Z") shouldBe UtcOffset.UTC
    intercept[IllegalArgumentException] {
      UtcOffset.parse("Asia/Tokyo")
    }
    intercept[IllegalArgumentException] {
      UtcOffset.parse("+09:75")
    }
    intercept[IllegalArgumentException] {
      UtcOffset.ofHours(19)
    }
  }

  test("convert civil dates like java.time") {
    var mismatches = List.empty[Long]
    var days       = -800000L
    while days < 800000L do
      val (y, m, d) = CivilTime.civilFromDays(days)
      if CivilTime.daysFromCivil(y, m, d) != days then
        mismatches = days :: mismatches
      // Instant.toString formats years before 1000 differently across platforms
      if y >= 1000 && days % 997 == 0 &&
        Instant.ofEpochSecond(days * 86400L).toString.takeWhile(_ != 'T') !=
          f"${y}%04d-${m}%02d-${d}%02d"
      then
        mismatches = days :: mismatches
      days += 1
    mismatches shouldBe Nil
  }

end CronTest
