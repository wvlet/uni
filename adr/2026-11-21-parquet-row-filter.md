# Parquet row filters: exact filtering with `ParquetFilter`

- Status: Accepted
- Date: 2026-11-21
- Plan: `plans/2026-11-21-parquet-row-filter.md`

## Context

`RowGroupFilter` only skipped row groups by statistics, and dataset files by partition values. Rows
that didn't match were still returned, and the reader ADR chose the name to say so. Spark and
DuckDB apply pushed-down predicates exactly, so callers had to filter every row again.

## Decision

- **`ParquetReadOptions.withFilter(f)` is exact.** It skips files and row groups as before, then
  drops the rows that don't match. `withRowGroupFilter(f)` stays a skip-only hint that ignores
  columns and literals it can't check. When both are set, both prune, and only `withFilter`
  removes rows.
- **The tree is renamed `ParquetFilter`.** `RowGroupFilter` stays as a deprecated type alias and
  value alias, so `import RowGroupFilter.*` still finds `col`.
- **Filtering runs on vectors, one batch at a time.** Every read path, sync and async, goes
  through the per-row-group batch iterator. A three-valued mask per batch selects the rows, and
  `ColumnVector.take` copies the vectors at any depth, keeping dictionary indices. Batches left
  with no rows are dropped, except that `readRowGroup` still returns its one batch.
  `ColumnBatch` gets no selection vector, because a caller that ignored it would read rows that
  don't match.
- **Columns used only by the filter** are added to the read and dropped before output.
- **SQL semantics.** A comparison with null is UNKNOWN, and a row is kept only when the predicate
  is TRUE. `IsNull` on a struct, list, or map checks the value itself. A null struct makes its
  fields null. A null literal is an error that points to `isNull`. The evaluator keeps three
  states, so adding `Not` later stays correct.
- **One literal table** for statistics, partitions, and rows (`ParquetFilter.order`):
  - Integers, and digit strings as partitions accept them, compare by value with INT32 and INT64
    columns, signed or unsigned. UINT64 compares unsigned.
  - DATE takes a `LocalDate` or an ISO string.
  - TIMESTAMP takes an `Instant`. The literal becomes the floor in the column's unit plus a
    sign for the remainder, so a sub-unit Instant compares exactly. This fixes a statistics bug:
    a truncated literal skipped a row group under `<` when its min matched the truncated value.
  - INT96 takes an `Instant`. Its statistics are never trusted.
  - FLOAT and DOUBLE take numbers; an integer beyond ±2^53, which would round, is rejected.
  - STRING and ENUM (and untyped binary under `withBinaryAsString`) compare in unsigned UTF-8
    byte order. Partition strings switched from `String.compareTo` (UTF-16 order) to the same
    order.
  - Any other type (DECIMAL, TIME, UUID, FLOAT16) or literal fails when the read starts under
    `withFilter`. `withRowGroupFilter` keeps reading those row groups.
- **IEEE 754 for floating point.** NaN matches no comparison, and -0.0 equals 0.0. Spark and DuckDB
  sort NaN above every number, but writers leave NaN out of min/max, so that order would have
  forced statistics pruning to keep every row group that might hold NaN. A NaN literal is
  rejected.
- **Nested columns.** A dotted name walks non-repeated structs, for statistics and rows. An exact
  top-level name wins. Lists and maps are rejected, as Spark and DuckDB don't push down on them
  either.
- **Datasets filter rows on the dataset-shaped batch**, after missing columns become all-null and
  narrow columns are widened. The filter is checked once against the dataset schema. Partition
  leaves are still decided per file. A file whose partitions alone make the filter true skips row
  evaluation. Otherwise the whole filter runs, with partitions as constant vectors.

## Consequences

- Every column the filter names is decoded for every row of a row group that is read. Late
  materialization (decoding other columns only for matching rows), page-index skipping, and bloom
  filters are future work, and the per-batch mask leaves room for them.
- `withBatchSize` is an upper bound under a filter.
- Unsigned columns now use statistics when they come from the non-legacy min/max fields.
