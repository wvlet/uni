# Parquet set-membership filters take a caller's IntegerSet

- Status: Accepted
- Date: 2026-11-25
- Plan: `plans/2026-11-25-parquet-in-filter.md`
- Builds on: [`2026-11-24-parquet-late-materialization.md`](2026-11-24-parquet-late-materialization.md)

## Context

The wvlet engine builds a per-row filter over a hash join's build keys (a bitmap, a Bloom filter,
or only a [lo, hi] range) and applies it to every probe batch after the scan has decoded all of
its columns. In TPC-DS q73 at SF10 that decode is most of the scan. Filter-first decoding (#1189)
can drop those rows before their other columns are decoded, but `ParquetFilter` had no
membership test: an `Or` of `===` costs one pass per value.

## Decision

- **`ParquetFilter.In(column, values: IntegerSet)`**, built with `col(c).isIn(longs*)`,
  `isIn(Iterable[Long])`, or `isIn(set)`. It is a conjunct like any other, so it joins the
  filter-first steps unchanged.
- **`IntegerSet` is a public trait** (`contains(v: Long)`, `min`, `max`, `mayIntersect(lo, hi)`),
  so an engine hands over its bitmap or Bloom filter without copying it. A set may accept values
  it doesn't hold; the filter then keeps those rows, which is what a probe-side join filter
  needs. `contains` must be safe to call from several threads.
- **Only INT32, INT64 (signed, unsigned up to 32 bits), and DATE as epoch days.** These are the
  Long domain join keys use. UINT64 (unsigned order), timestamps (unit-dependent values), and
  other types fail when the read starts, as other filters do.
- **`IntegerSet.of` is exact:** a bitmap over [min, max] when the span is at most
  max(64 Ki, 64 × count) values, otherwise a linear-probing hash table, plus the sorted values
  for an exact `mayIntersect` (binary search) in row-group pruning.
- **Sparse dictionary pages read only the selected indices.** A dictionary page whose selection
  is sparser than 1 in 8 reads the index of each selected value with
  `RleBitPackedDecoder.readAt`, which skips RLE runs in one step and reads a single bit-packed
  value in place. Expanding every index up to the last selected one was 27% of the q73 scan.

## Consequences

- On SF10 store_sales with q73's date, store, and household key sets (50,039 of 28.8M rows
  kept), the filter-first `isIn` scan takes 68% of the CPU time of reading every row and testing
  the keys afterwards. The first conjunct's column alone takes 32%, and Snappy still
  decompresses every page that holds a selected row.
- Evaluating membership once per dictionary entry of the first conjunct needs a cursor that
  yields integer dictionary indices; it is left for later, as are Bloom filters in the file and
  page-index skipping.
