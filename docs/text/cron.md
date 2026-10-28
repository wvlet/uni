# Cron

`wvlet.uni.text.cron` parses cron expressions, computes when a schedule fires next, and describes it in English. It works on JVM, Scala.js, and Scala Native.

## Next Run Times

```scala
import wvlet.uni.text.cron.{Cron, UtcOffset}
import java.time.Instant

val cron = Cron.parse("30 9 * * MON-FRI")

cron.next(Instant.parse("2026-10-30T12:00:00Z"))     // 2026-11-02T09:30:00Z (Monday)
cron.nextN(Instant.parse("2026-10-30T12:00:00Z"), 3) // Mon, Tue, and Wed at 09:30
cron.matches(Instant.parse("2026-11-02T09:30:00Z"))  // true
```

`next` returns the first time strictly after the given instant. Fractions of a second are ignored.

## Describing a Schedule

`describe` renders the schedule in English, in the style of [cronstrue](https://github.com/bradymholt/cRonstrue), with 24-hour times:

```scala
Cron.parse("30 9 * * 1-5").describe   // At 09:30, Monday through Friday
Cron.parse("*/15 * * * *").describe   // Every 15 minutes
Cron.parse("*/5 9-17 * * *").describe // Every 5 minutes, between 09:00 and 17:59
Cron.parse("@yearly").describe        // At 00:00, on day 1 of the month, only in January
```

`toString` returns the expression exactly as given, so a parsed `Cron` can be stored and shown back unchanged:

```scala
Cron.parse("30 9 * * mon-fri").toString // 30 9 * * mon-fri
```

## Syntax

An expression has five fields, or six with a leading seconds field:

| Field | Values | Names |
|-------|--------|-------|
| second (optional) | 0-59 | |
| minute | 0-59 | |
| hour | 0-23 | |
| day of month | 1-31 | |
| month | 1-12 | `JAN`-`DEC` |
| day of week | 0-7 (0 and 7 are Sunday) | `SUN`-`SAT` |

Each field is a comma-separated list of items:

| Item | Meaning |
|------|---------|
| `*` | every value |
| `?` | same as `*` (day of month and day of week only) |
| `5` | a single value |
| `1-5` | a range |
| `*/15` | every 15th value from the first |
| `1-30/5` | every 5th value in a range |
| `10/20` | every 20th value from 10 to the field's maximum |

Names are case-insensitive and also work in ranges (`MON-FRI`). The macros `@yearly`, `@annually`, `@monthly`, `@weekly`, `@daily`, `@midnight`, and `@hourly` stand for their usual five-field schedules.

When both day of month and day of week are restricted, a day matches if **either** one matches, as in Vixie cron and most Unix crontabs: `0 0 13 * FRI` runs on the 13th and on every Friday. A field that starts with `*` or `?` counts as unrestricted, so `0 0 */2 * FRI` runs on Fridays that fall on odd days.

Quartz extensions (`L`, `W`, `#`, a year field) are not supported.

## Time Zones

Fields are read in UTC by default. Pass a fixed offset to read them in local time:

```scala
val tokyo = UtcOffset.ofHours(9) // or UtcOffset.parse("+09:00")
Cron.parse("0 9 * * *").next(Instant.parse("2026-10-28T01:00:00Z"), tokyo)
// 2026-10-29T00:00:00Z, which is 09:00 in +09:00
```

Named time zones such as `Asia/Tokyo` need `java.time.ZoneId`, which Scala.js doesn't provide, so `next` takes only a fixed offset. To follow daylight saving time, resolve the zone's offset yourself (on the JVM, `ZoneId.of(name).getRules.getOffset(instant)`) and pass it in. For full time-zone support on the JVM, [cron-utils](https://github.com/jmrozanec/cron-utils) is a good choice and also covers Quartz and Spring cron dialects.

## Errors

Invalid expressions throw `TextParseException` with the column and a caret under the problem:

```text
unknown day-of-week name 'FOO': expected one of SUN, MON, TUE, WED, THU, FRI, SAT (line 1, column 13)
* * * * MON-FOO
            ^
```

A day of month that never occurs in the selected months, such as `0 0 30 2 *`, is rejected at parse time, so `next` always finds a time.
