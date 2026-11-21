# Exact row filtering for uni-parquet files and datasets

## Goals

- Let a caller pass one predicate and get back only the rows that match, as Spark and DuckDB do with pushed-down filters.
- One opt-in setting covers single files and datasets, and every read path: `readAs`, `readRows`, `readJson`, `readBatches`, `readRowGroup`, and their async variants.
- Keep `withRowGroupFilter` unchanged for callers who want only the cheap skip.

## Background

- `RowGroupFilter` skips row groups by min/max/null-count statistics, and on datasets drops whole files by partition values. Rows inside a row group that is read are returned unchanged, and callers must filter them again. The reader ADR (2026-11-20) chose the name to say so.
- Every read path goes through the same per-row-group batch iterator, so filtering one `ColumnBatch` covers all of them, sync and async.
- Partition pruning already decides some semantics: a comparison with a null value is false, and a literal is converted to the column's type only when the conversion is lossless. Otherwise the read fails instead of silently matching everything.
- The filter tree has only `Compare`, `IsNull`, `IsNotNull`, `And`, and `Or`. Without `Not`, SQL's UNKNOWN acts like FALSE when choosing rows. Keeping three states inside the evaluator keeps it correct if `Not` or `In` are added later.
- Statistics pruning truncates a sub-unit `Instant` literal to the column's unit. For `<` on a MILLIS/MICROS column, that can skip a row group that holds a matching row. Exact filtering would make this wrong-skip visible, so it gets fixed here.

## Design

Lane: Standard — new public read API and new filter semantics.

User flow:

- A caller builds the same tree they use today (`col("ts") >= t && col("country") === "JP"`) and passes it with `ParquetReadOptions.withFilter(f)`. `noFilter()` removes it.
- The read skips files (datasets) and row groups as today, then drops the non-matching rows from each batch. Every row it returns matches.
- A filter the reader can't evaluate exactly fails when the read starts: an unknown column, a repeated column, a DECIMAL or untyped binary column, or a literal with no lossless conversion. The error names the column and the type it expected. The caller fixes the literal, or falls back to `withRowGroupFilter`, which keeps ignoring what it can't check.

Decisions:

- **API.** `withFilter(f)` is exact and also prunes files and row groups. `withRowGroupFilter(f)` stays as a skip-only hint. When both are set, both prune, and only `withFilter` removes rows.
- **Naming (question on the card).** Rename the tree to `ParquetFilter` and keep `RowGroupFilter` as a deprecated type alias plus value alias (so `import RowGroupFilter.*` still finds `col`), so `withFilter(f: ParquetFilter)` reads correctly and future operators (`Not`, `In`) don't carry a skip-only name.
- **Evaluated on vectors, per batch.** Each batch gets one selection mask, and its columns, nested ones included, are copied down to the kept rows. Batches left with no rows are not emitted, except that `readRowGroup` still returns its one batch, possibly with 0 rows. `withBatchSize` becomes an upper bound.
- **Columns used only by the filter** are read, evaluated, and dropped before output, so `withColumns` still decides what callers see.
- **Nulls.** A null literal (`col("x") === null`) is an error that points to `isNull`. A comparison with a null value is UNKNOWN, and a row is kept only when the predicate is TRUE. `IsNull` and `IsNotNull` are always TRUE or FALSE. A null struct makes its children null.
- **Literal conversions** use one shared table for statistics, partitions, and rows:
  - Integers of any width compare by value with signed and unsigned INT32/INT64 columns, including widened dataset columns.
  - A numeric `String` (`"2026"`) converts to an integer and an ISO `String` to a `LocalDate`, as partitions accept today, for data columns too.
  - `LocalDate` compares with DATE.
  - `Instant` compares exactly with TIMESTAMP in any unit and with INT96 (Spark's legacy timestamps), with no truncation. This fixes the wrong-skip above.
  - `String` compares with STRING/ENUM, and with untyped binary when `withBinaryAsString` is on, in UTF-8 byte order (code point order). Partition string comparisons switch to the same order.
  - `Boolean` compares with BOOLEAN.
  - Numbers compare with FLOAT/DOUBLE as doubles. An integer literal beyond ±2^53, which would round, is an error.
  - Every other column type (DECIMAL, TIME, UUID, FLOAT16, untyped binary without `withBinaryAsString`) or literal is an error under `withFilter`.
- **Floating point follows IEEE 754.** NaN never satisfies a comparison, and -0.0 equals 0.0. This matches how statistics pruning already treats NaN. Spark and DuckDB instead sort NaN above every number, and the ADR will note that difference.
- **Nested columns.** A dotted path (`col("address.city")`) names a field inside non-repeated structs, for both row and statistics filtering. An exact top-level name wins over a path. Lists and maps are rejected for row filtering, as Spark and DuckDB don't push predicates down on them either.
- **Datasets.** Partition leaves are still decided per file, and the remaining filter still prunes each file's row groups by statistics. Rows are filtered on the dataset-shaped batch, after missing columns become all-null and narrow columns are widened. So a file without the column matches `isNull` and nothing else, and a Double literal works on an INT32 file of a DOUBLE column. Partition columns are constant per file, so they never reach the row evaluator.
- **Out of scope:** late materialization (decoding other columns only for matching rows), page-index and bloom-filter skipping, and new operators. The per-batch mask leaves room for each of them.
- Tests cover null and NaN cases, each literal conversion, nested paths, filter-only columns, datasets with partitions and widened columns, and the async paths, on JVM, JS, and Native.
- Docs: `docs/text/parquet.md`, `for-ai-agents.md`, the skill reference, a plugin version bump, and a new ADR.

## Alternatives and Why Not?

- **Make `withRowGroupFilter` exact.** That silently changes results and costs for existing callers, and loses the cheap hint-only mode.
- **A selection vector on `ColumnBatch` instead of copying.** Every `readBatches` caller would have to honor it, and an unaware caller would read rows that don't match.
- **Evaluate per row on `Map[String, Any]` rows.** It boxes every value, and `readBatches` and `readAs` never build maps.
- **Spark/DuckDB NaN ordering.** It conflicts with statistics, because Parquet writers leave NaN out of min/max, so pruning would skip row groups whose NaN rows should match.
