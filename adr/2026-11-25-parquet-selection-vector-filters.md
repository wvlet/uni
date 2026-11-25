# Parquet row filters run on one selection vector, in two-valued logic

- Status: Accepted
- Date: 2026-11-25
- Builds on: [`2026-11-24-parquet-late-materialization.md`](2026-11-24-parquet-late-materialization.md), [`2026-11-25-parquet-in-filter.md`](2026-11-25-parquet-in-filter.md), [`2026-11-25-parquet-decimal-filter.md`](2026-11-25-parquet-decimal-filter.md)

## Context

Filter-first decoding (#1189) ran each filter step on a batch of the rows still selected. After each step it called `ColumnVector.take` on every column decoded so far, so a filter of k steps gathered the first step's columns k times. `RowFilter.eval` built one three-valued `Array[Byte]` per leaf and per `And`/`Or`. It matched the operator on every row, and it tested every row against every leaf even when an earlier conjunct had already rejected it. The wvlet engine therefore moves a conjunct into the row filter only when it is estimated to keep at most 15% of the rows (wvlet #2415).

## Decision

- **One selection vector per batch.** `FilteredBatches` leaves each decoded column at the "level" it was decoded at: the eager columns, then each step's columns. Each level has an index map from the rows still selected to positions in its own columns. The map is null while the two are the same. When a step drops rows, only the maps are remapped (`kept.map(ix)`); no column is copied. Each output column is gathered once, at the end. Columns that only the filter needs are never gathered.
- **`RowFilter.select(column, position, rows, n)`.** It returns the matching rows among `rows`, which must be ascending. `AllOf` tests its right side only on the rows its left side kept. `AnyOf` tests its right side only on the rows its left side rejected, then merges the two ascending lists. `matches` and `keptRows`, used by decode-all reads and `ParquetDataset`, are built on `select`.
- **Typed leaves.** A comparison in a Long domain becomes an inclusive `[lo, hi]` range once per leaf (`longRange`). That covers integers, DATE, BOOLEAN, TIMESTAMP and DECIMAL, where `Key.Time`'s `rest` moves the bounds. Unsigned 64-bit columns flip the sign bit of both the value and the key. Floating-point values use an accept mask, so NaN never matches. Binary dictionaries are tested once per entry.
- **Two-valued logic.** A null on the leaf's path fails the row right away. This is exact because `ParquetFilter` has no negation. Without negation, `And` and `Or` are monotone, so the rows two-valued logic (null = false) keeps are exactly the rows three-valued logic finds true.

## Consequences

- FilterProbe at SF10, filter-first scans: q6d −39%, q19d −27%, q1 −12%. At 20% kept, filter-first now ties decode-all, where it used to be 7–9% slower. Decode-all-then-filter is about 2x faster on q6d and q19d.
- In the wvlet engine, row-filtered scans use 5–22% less CPU. Row filters still don't beat the engine's own filter on H q6, H q19 or DS q09, so that remaining gap is on the engine side (uni#1192).
- **Adding a `Not` (or any non-monotone operator) to `ParquetFilter` breaks the two-valued shortcut.** That change must bring back unknown states (for example a separate "null" selection per node) before it negates anything.
- `SelectionFilterTest` checks random `And`/`Or` trees against a Kleene-logic oracle on the source rows, and checks `longRange` against `compareTime` and unsigned compare at the Long edges.
