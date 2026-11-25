# Parquet set-membership (IN) filter

## Goals

- Let `withFilter` keep only rows whose integer column value is in a set, so filter-first decode
  (#1189) can drop probe-side rows of a hash join before their other columns are decoded.
- The wvlet engine's runtime join filters (a bitmap, a Bloom filter, or a [lo, hi] range over the
  build keys) must plug in without copying their bits.
- Target: TPC-DS q73/q34 at SF10, where the store_sales probe scan decodes every row of 5
  dictionary-encoded columns before the engine's row filter drops most of them (≈55% of engine CPU).

## Background

- `ParquetFilter` has `Compare`, `IsNull`, `IsNotNull`, `And`, `Or`. An IN list today is an `Or`
  of `===`, which is evaluated per literal and is fine for a handful of values, not thousands.
- Filter-first decode splits the filter into top-level AND conjuncts. The first conjunct's columns
  are decoded for every row; later conjuncts and the remaining columns only for selected rows. A
  new conjunct type joins these steps with no change to the decode path.
- The engine's `RowKeyFilter` exposes `mayContain(x: Long)` plus its `lo`/`hi` range, and is shared
  by parallel scan copies, so the predicate must be safe to call from several threads.
- Join keys in TPC-DS are INT32/INT64 surrogate keys; dates pass as epoch days, the same Long
  domain the engine uses for its ranges.

## Design

- Lane: Standard (new public API), pre-approved by the work item and launch prompt.
- **User flow (engine):** after a join build, the engine wraps its `RowKeyFilter` as a
  `ParquetFilter.IntegerSet` and puts `col(key).isIn(set)` first in the scan's `withFilter`
  conjuncts. Rows whose key is not in the set are never decoded beyond the key column. If the
  filter can't apply (non-integer column) building the read fails up front, as other filters do.
- **New case `ParquetFilter.In(column, values: IntegerSet)`.** Builders:
  `col("k").isIn(1L, 2L, 3L)` / `isIn(Iterable[Long])` for literal sets, `isIn(set)` for a
  caller's own predicate.
- **`IntegerSet` is a small public trait:** `contains(v: Long)`, `min`, `max`, and
  `mayIntersect(lo, hi)` (default: range overlap). An implementation may have false positives
  (Bloom filters): `withFilter` then returns the rows the set accepts, which is what a join's
  probe filter wants.
- **Built-in `IntegerSet.of(values)`:** a bitmap over [min, max] when the values are dense, otherwise
  an open-addressing hash table; it also keeps the sorted values so `mayIntersect` is exact.
- **Columns:** INT32 and INT64 integers (signed, and unsigned up to 32 bits) and DATE (epoch days).
  Other types (UINT64, timestamps, floats, strings, booleans, decimals) fail with a message that
  points to `===`/`||` or `withRowGroupFilter`.
- **SQL semantics:** a null value is unknown, so a null row is never kept; an empty set keeps no
  rows.
- **Row-group pruning:** a row group whose [min, max] statistics don't intersect the set is
  skipped, via `mayIntersect`. Bloom filters stay engine-side. Partition values of a dataset are
  tested with `contains`.
- **Dictionary pages:** integer dictionary pages are expanded to values by the cursor before the
  filter runs, so per-dictionary-entry evaluation needs a cursor that yields dictionary indices
  for integer columns. Deferred to a follow-up; the probe measures how much of the remaining time
  is the expansion versus the membership test.
- **Validation:** RowFilter tests (sync/async, nulls, empty set, dates, unsigned, custom set,
  pruning) on JVM, JS, and Native; a `FilterProbe`-style timing probe on SF10 store_sales with
  q73's date, store, and household-demographics key sets.

## Alternatives and Why Not?

- Keep `Or` of `===`: per-literal passes over the vector; too slow past a few dozen values.
- `In(column, Seq[Any])` with literals converted per column type: forces the engine to copy
  its bitmap into a list, and loses Bloom filters.
- A `Long => Boolean` function instead of a trait: no min/max, so no row-group pruning.
