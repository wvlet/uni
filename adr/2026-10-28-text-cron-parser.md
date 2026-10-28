# Cron parser on ScannerBase with fixed offsets (wvlet.uni.text.cron)

- Status: Accepted
- Date: 2026-10-28
- Plan: [`plans/2026-10-27-text-parsers.md`](../plans/2026-10-27-text-parsers.md) (PR 3)

## Context

Apps that move off `cronstrue` and a JavaScript scheduler need cron parsing, next-run times, and English descriptions on JVM, Scala.js, and Scala Native. Scala.js's `scalajs-java-time` 1.0.0 has `Instant` but no `ZoneId`, `ZoneOffset`, or `ZonedDateTime`, and Scala Native's `sjavatime` is similar. `wvlet.uni.text.parser.ScannerBase` already tokenizes expression-like languages.

## Decision

- **Tokenize with `CronScanner extends ScannerBase[CronToken]`** (`skipWhiteSpace = false`, because whitespace separates the fields). `fetchToken` handles only the characters cron uses: digits read their own loop rather than `getNumber`, which would accept `_`, `L`, or exponents, and `@` starts a `MACRO` token through `findToken`. Any other character throws `TextParseException` with `LineReader.formatError`, so cron errors carry the line, column, and caret like YAML and Markdown.
- **Two-step parse.** `CronParser` first reads raw fields (values unresolved), because whether the first field is seconds is known only after counting 5 or 6 fields. It then resolves each field against its `FieldKind` range and names into a `Long` bit set plus the item list that `describe` needs.
- **Vixie day semantics, decided by syntax.** A day field is "unrestricted" when it starts with `*` or `?` (so `*/2` counts as unrestricted). When both are restricted, either matches; otherwise both must match. This copies Vixie cron and cronie, so existing crontabs keep their meaning.
- **Day-of-week `*` covers 0-6**, not 0-7, so `*/2` does not list Sunday twice. `7` is stored as bit 0.
- **Impossible schedules fail at parse time.** When day-of-week is unrestricted and no selected month has the smallest selected day (`0 0 30 2 *`), `parse` throws. `next` therefore always terminates; its 400-year loop bound (one Gregorian cycle) is only a guard.
- **Own civil-date math (`CivilTime`)** with Howard Hinnant's `days_from_civil`/`civil_from_days`, and **`UtcOffset`**, a small fixed-offset type, instead of `java.time` zones. `next` walks month → day → hour → minute → second, jumping with `numberOfTrailingZeros` on the bit sets.
- **`toString` returns the source text**, and equality is by source, the lossless guarantee from the plan's revision.
- **`describe` follows cronstrue's phrasing** with 24-hour times, and writes "or" when both day fields are restricted, because that is what Vixie semantics mean.

## Consequences

- Named time zones and daylight saving time are the caller's job: resolve the zone's offset for the instant and pass it. On the JVM, cron-utils remains a good choice for full zone support and the Quartz and Spring dialects.
- Quartz extensions (`L`, `W`, `#`, a year field) and `@reboot` are rejected with a parse error. Adding them means new tokens in `CronScanner` and new `CronItem` cases, and `checkDaysExist` must learn them.
- `Instant.toString` formats years before 1000 differently on Scala.js, so the cross-platform civil-date test compares strings only from year 1000.
