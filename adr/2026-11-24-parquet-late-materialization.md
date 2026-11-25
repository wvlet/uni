# Parquet row filters decode filter columns first (late materialization)

- Status: Accepted
- Date: 2026-11-24
- Plan: `plans/2026-11-24-parquet-late-materialization.md`
- Amends: [`2026-11-21-parquet-row-filter.md`](2026-11-21-parquet-row-filter.md) (its first consequence)

## Context

`withFilter` decoded every selected column for every row of a row group, built the vectors, and
then gathered the matching rows. On selective scans most of that work is for rows thrown away.
The wvlet engine reads one batch per row group and wants to hand its scan predicates to uni. DuckDB
decodes filter columns first, builds a selection, and reads the other columns only for selected
rows. DataFusion's `RowFilter` evaluates predicates one at a time, each narrowing the rows the next
one decodes.

## Decision

- **Filters run in steps per batch.** The filter is split into its top-level AND conjuncts, in the
  caller's order. The first conjunct's columns are decoded for every row by `ColumnChunkCursor`,
  as before. Each later conjunct decodes only the columns it adds, and only for the rows still
  selected. A conjunct whose columns are already decoded joins the step before it. The caller's
  AND order decides which columns are decoded first; uni doesn't reorder conjuncts, so results
  and costs stay predictable.
- **The other columns are decoded last, for the rows that pass.** When no row passes, no value of
  them is decoded.
- **`SelectiveCursor` reads flat columns for selected rows.** Each entry of a flat column (no
  repetition levels) is a row, so a data page header gives the page's row count. A page with no
  selected row is skipped without being decompressed. In other pages the levels are decoded, the
  values between selected rows are skipped, and runs of selected rows are decoded in bulk. PLAIN
  and dictionary pages decode only the selected values (`RleBitPackedDecoder.skip` skips indices).
  The dictionary page is kept compressed until a page needs it. Other encodings (delta,
  byte-stream-split, RLE booleans) decode the page once and copy the selected values out.
- **A second cursor instead of a mode in `ColumnChunkCursor`.** That cursor's buffer handover, row
  slots, and soft-limit batch cuts are tuned for decoding everything. A selection mode would have
  added branches to that hot path.
- **Some columns still decode for every row:** lists and maps (selecting rows needs their
  repetition levels), and chunks whose uncompressed size passes the batch soft limit. A batch
  where nearly every row is selected could otherwise overflow an array, because only
  `ColumnChunkCursor` cuts batches at the soft limit. These columns are gathered as before.
- **Vectors are identical.** Values, nulls, and dictionary-backed `BinaryColumn`s with the same
  dictionary indices as decoding everything and calling `ColumnVector.take`. One exception: a
  batch whose selected rows of a column are all null has no dictionary attached, so its
  `dictionarySize` is 0, which its contract allows.
- **No API change.** Sync and async `ParquetFile` reads with `withFilter` get it. A package-private
  `ParquetFile.lateMaterialization` switch keeps the old path for tests and in-process A/B timing.

## Consequences

- Filtered scans skip decode, assembly, and gather work for discarded rows of the columns outside
  the first step. Snappy still decompresses every page that holds a selected row.
- `ParquetDataset` still filters rows after widening each file's batch to the dataset schema, so
  datasets get none of this yet. Pushing a file's residual filter into the file read has to keep
  positional deletes and row lineage counting unfiltered rows. That is future work.
- Engines get the most from filter-first decoding by putting their most selective conjunct
  first. `ParquetFilter` can't compare DECIMAL columns or two columns, so engines keep those
  predicates. An `In(column, values)` conjunct for runtime join filters plugs into the same steps.
- Evaluating comparisons once per dictionary entry, reordering conjuncts by observed selectivity,
  page-index skipping, and bloom filters remain future work.
