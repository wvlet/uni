# Reading Parquet rows as Map[String, Any]

## Goals

- Make "read each row as a `Map[String, Any]`" a first-class, documented API on the JVM, Scala.js,
  and Scala Native, for users who don't want to declare a case class (exploration, generic tools,
  schema-less pipelines).
- Give the same values everywhere a row map appears: `readRows`, `batch.row(i)`, the async API.
- Cover the common neighbours of row maps that Airframe's airframe-parquet users expect: rows as
  JSON and a per-column statistics summary.

## Background

- `ParquetFile.readRows` and `ColumnBatch.row(i)` exist, with values from `ColumnVector.getValue`
  (`Instant`, `LocalDate`, `BigDecimal`, `UUID`, lists as `Seq`, maps as `Map`, structs as maps).
- Gaps found by probing the DuckDB fixture:
  - Rows are built with `.toMap`, so columns come back in hash order, not schema order.
  - `getValue` ignores `withBinaryAsString`; Hive/Impala strings come back as `Array[Byte]`.
  - `readAs[Map[String, Any]]` goes through MessagePack and Weaver's `AnyWeaver`: INT32 and FLOAT
    widen to `Long`/`Double`, DATE/DECIMAL/UUID become strings, and timestamps come back as the raw
    MessagePack extension payload (`Array[Byte]`), which is unusable.
  - No top-level `Parquet.readRows`, and no async row-map API for browsers.
- `readAs[A]` intentionally goes through MessagePack (ADR 2026-11-20) so Weaver can match names
  loosely; that path stays.

## Design

Lane: pre-approved direction from the launch prompt (new public API in a new module, merge
authorized); my own decisions inside it are listed in the PR.

- **Row maps keep schema column order.** `row(i)` and struct values become insertion-ordered maps
  (`VectorMap`); the declared type stays `Map[String, Any]`.
- **`binaryAsString` applies to the vectors, not only to Weaver rows.** The option is passed when a
  row group is decoded, so `getValue`, `batch.row(i)`, and `readRows` all return `String` for
  unannotated BYTE_ARRAY columns, including inside lists, maps, and structs.
- **New entry points**, mirroring `readAs`:
  - `Parquet.readRows(path | bytes, options): Seq[Map[String, Any]]` (and `readAs(bytes)`).
  - `ParquetFile.readRowsAsync(options): Rx[Map[String, Any]]` and
    `readAllRowsAsync(options): Rx[Seq[Map[String, Any]]]`.
- **`readAs[Map[String, Any]]` stays Weaver's view, documented as such.** It cannot see that `A` is
  a map, so it returns Weaver's JSON-friendly values (`Long`, ISO strings, decimal strings). The docs
  point to `readRows` for typed values.
  - Fix the one real bug in that path in Uni core: `AnyWeaver` decodes the MessagePack timestamp
    extension into an `Instant` instead of raw bytes. This also helps any `Map[String, Any]` read
    from MessagePack outside Parquet.
- **Rows as JSON:** `readJson(options): Iterator[JSONObject]` and `readJsonAsync`, plus
  `Parquet.readJson(path)`. Built from the row maps: timestamps, dates, times, UUIDs, and decimals
  become strings (decimals keep their digits), binary becomes base64, map keys become strings.
  `.map(_.toJSON)` gives JSON lines.
- **Column statistics:** `ParquetFile.columnStatistics: Map[String, ColumnStatistics]`, keyed by
  dotted column path, summed over row groups from the footer (no column I/O): value count, null
  count, compressed and uncompressed sizes, and min/max as Scala values only for types whose
  statistics order is unambiguous (the same rules `RowGroupFilter` uses), otherwise `None`.
- Tests cover all of this on JVM, JS, and Native with the existing DuckDB and parquet-testing
  fixtures (column order, binary-as-string on `alltypes_plain`, async rows, JSON, statistics).

## Alternatives and Why Not?

- Make `readAs[Map[String, Any]]` call `readRows` by detecting the weaver: `Weaver[A]` carries no
  type tag, so detection would be fragile.
- Airframe's `newReader(...).read()` returning null at EOF: an `Iterator` / `Rx` is the Scala 3
  shape Uni already uses.
- JSON through `JSONValueWeaver` on the MessagePack rows: it renders binary as a quoted base64
  string inside a string, and loses DECIMAL as a JSON number path; converting the typed row maps
  is simpler and exact.
