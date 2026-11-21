# LocalTime Weaver and Parquet TIME columns

## Goals

- `case class Shift(start: LocalTime, ...)` packs `start` as an ISO-8601 string (`"09:30"`, `"09:30:15.25"`) on JVM, Scala.js, and Scala Native, through both `derives Weaver` and `Weaver.fromSurface`.
- Parquet case-class writes accept `LocalTime` fields as TIME columns and read them back into case classes, so time-of-day data round-trips like DATE does since #1063.

## Background

- No `LocalTime` Weaver exists anywhere, not even in `JvmWeaver`. A `LocalTime` field falls back to the lossy empty object `{}`, which cannot be read back. `fromSurfaceOpt` returns `None` for it, which the router and MCP rely on.
- `LocalTime` is cross-platform: Scala.js has it from `scalajs-java-time`, Scala Native from `sjavatime`, and uni-parquet already returns `LocalTime` from `readRows` in shared code.
- Parquet reading is ready: `readRows` gives `LocalTime`, and the case-class path (`RowPacker` / `VectorUnpacker`) packs TIME as an ISO string for the Weaver to parse. MILLIS, MICROS, and NANOS are all read.
- Parquet writing is not: `SurfaceSchema` has no `LocalTime` rule, `ValueConverter` throws "unsupported" on TIME columns, and the `Map`-row packer in `ParquetWriter` rejects a `LocalTime` value. `ParquetBatchWriter` already writes TIME from `IntColumn` / `LongColumn`.
- `ValueConverter.forField` serves both the case-class writer and the record writer (through `Shredder`, where TIME falls to the generic path), so one TIME case covers both.
- `ParquetType` has no TIME constant, so a hand-built schema cannot declare a TIME column either.
- Who notices the change: JSON/RPC/HTTP encoding of `LocalTime` changes from `{}` to an ISO string; router results with such a field switch from the toString fallback to the Weaver; MCP accepts such tools. As with `LocalDate`, nobody could decode the old output.

## Design

- Lane: Standard. It changes the JSON/RPC encoding of a core type and adds Parquet write support.
- Add a shared `PrimitiveWeaver.localTimeWeaver`, exported from `Weaver` and registered in `fromSurface`'s primitive factory. It packs `LocalTime.toString` and unpacks with `LocalTime.parse`.
- Unpacking also accepts an integer as nanoseconds of the day (`LocalTime.ofNanoOfDay`), the Java API's own number form. Parquet's TIME unit varies (millis, micros, nanos), so no single Parquet unit fits as `LocalDate`'s epoch days did.
- `JvmWeaver` has no `LocalTime` given, so there is no forwarder to add.
- `SurfaceSchema` maps `LocalTime` to TIME(MICROS) on INT64, matching `Instant` → TIMESTAMP(MICROS). Nanoseconds are dropped, as for `Instant`. DuckDB's and Spark 4.1's TIME are microseconds too.
- The TIME column is written with `isAdjustedToUTC = false`, because `LocalTime` has no zone. DuckDB reads `isAdjustedToUTC = true` as `TIME WITH TIME ZONE`. The spec defines no legacy converted type for local TIME, so none is written (as parquet-java does).
- Add `ParquetType.time(unit)` plus `TIME_MILLIS`, `TIME_MICROS`, `TIME_NANOS`, mirroring `timestamp(unit)`, so record-writer schemas can declare TIME.
- Both writers accept TIME (MILLIS on INT32, MICROS or NANOS on INT64) from an ISO string or an integer as nanoseconds of the day (same rule as the Weaver); `Map` rows also take a `LocalTime`. Precision finer than the column's unit is truncated, as `timestamp` does.
- TIME stays out of partition columns (STRING, INT32, INT64, DATE today) and out of `RowGroupFilter` literals; both can follow if needed.
- Docs: `docs/core/weaver.md`; `docs/text/parquet.md` (case-class read and write type tables, record-writer accepted forms, `ParquetType` constants, plus a compiled example); `for-ai-agents.md` (the Weaver and Parquet rows); and the `uni` skill (`SKILL.md`, `references/parquet.md`). Bump `plugin.json` from origin/main.
- Update `adr/2026-11-21-localdate-weaver.md` and its `adr/README.md` entry to cover `LocalTime` too (rewriting the "still lack a shared weaver" consequence), rather than adding a near-duplicate ADR, and fix the now-stale `LocalTime` example in the fromSurfaceOpt ADR and the `Weaver.fromSurfaceOpt` scaladoc.
- Tests: Weaver round-trips on all three platforms (`derives`, `fromSurface`, JSON, nano-of-day input, a bad string giving an error); Parquet case-class write and read with a `LocalTime` field (schema shows TIME(MICROS, local)); record-writer TIME in each unit, with values at the column's precision.

## Alternatives and Why Not?

- TIME(MILLIS) on INT32: smaller, but drops microseconds that DuckDB and Spark keep.
- TIME(NANOS): keeps all of `LocalTime`, but DuckDB and Spark read it as micros anyway and older readers lack NANOS.
- `isAdjustedToUTC = true` with the legacy `TIME_MICROS` converted type: reads in very old tools, but DuckDB turns it into `TIME WITH TIME ZONE`, which misdescribes a local time.
- Integer input as microseconds (Parquet's unit): matches our column, but a JSON integer would then mean a different thing than in `LocalTime`'s own API.
- Integer input not accepted at all: simplest, but `LocalDate` and `Instant` both accept a number form.
