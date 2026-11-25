# Decode Parquet filter columns first, then only the matching rows of the others

## Goals

- `withFilter` reads should decode the columns the filter needs, decide which rows match, and decode the other columns only for those rows (late materialization). Today every selected column is decoded for every row, then the filter drops rows (wvlet/uni#1086).
- Selective scans get cheaper: on lineitem alone, q6's literal conjuncts keep ~2% of rows, and q19's and q12's a few percent. Payload columns (`l_extendedprice`, `l_comment`, ...) stop being decoded, assembled, and then gathered for rows that are thrown away.
- Results stay identical, in pure Scala on JVM, JS, and Native.
- The wvlet engine can then hand its translatable scan conjuncts (and later runtime join filters) to uni as a row filter and get back only surviving rows.

## Background

- The engine reads one batch per row group (no batch size), with `withRowGroupFilter` only, and evaluates predicates itself. TPC-H SF10 lineitem: 488 row groups of 122,880 rows, nullable flat columns, PLAIN or dictionary pages, Snappy, about one page per column chunk. So skipping whole pages helps little there; decoding only the selected values of a page is where the gain is.
- `ColumnChunkCursor` decodes a whole page's values at once into buffers it hands over to vectors without copying. That path is tuned and stays as is.
- Page decoders are incremental already for dictionary indices (`RleBitPackedDecoder` keeps its run state); PLAIN pages are fixed strides or length-prefixed values, so skipping values is cheap.
- DuckDB (`parquet_reader.cpp`) does the same: filter columns first, a selection vector, then payload columns read only for selected rows, skipping entirely when nothing passes. DataFusion's `RowFilter` evaluates predicates one at a time, each narrowing the rows the next decodes.

## Design

Lane: Standard — a new read path for every `withFilter` read; the direction comes from the launch prompt, merge held.

- **Steps per batch.** The filter is split into its top-level AND conjuncts, in the order given. Step 1 decodes the columns of the first conjunct for every row (today's cursor) and evaluates it. Each later conjunct decodes only the columns it adds, only for rows still selected, and narrows the selection. Conjuncts whose columns are already decoded join the current step.
- **Payload last.** The remaining selected columns are decoded for the final selection only. When no row survives, later steps and payload columns read no values: pages with no selected row are skipped without decompressing them (a flat column's page header gives its row count).
- **A selective cursor for flat columns.** A new cursor reads a flat column chunk (no lists) given a batch's row count and its selected rows: it skips values between selected rows and decodes runs of selected rows in bulk. PLAIN and dictionary pages decode only selected values; the dictionary page is decoded on first use; other encodings (delta, byte-stream-split, RLE booleans) decode the page once and copy the selected values.
- **Fallbacks keep today's path.** Columns with lists or maps, and column chunks larger than the batch soft limit (so a nearly-all-selected batch can't overflow an array), are decoded in step 1 with the existing cursor and then gathered.
- **API unchanged.** `ParquetFile` reads with `ParquetReadOptions.withFilter`, sync and async, get this as is. The engine moves its translatable literal conjuncts from `withRowGroupFilter` to `withFilter` and drops them from its own evaluation. Ranges are `Compare` conjuncts and small IN lists an `Or` of `===`. Column-to-column compares (q12's `l_commitdate < l_receiptdate`) stay in the engine.
- **Room for runtime filters.** The steps work per conjunct, so a later `In(column, values)` (a hash set from a join build side) plugs in as one more conjunct type. The caller's AND order decides which columns are decoded first.
- **Results identical, vectors too.** Values, nulls, and dictionary-backed `BinaryColumn`s with the same dictionary indices, which `take` already kept.
- **Datasets stay as they are for now.** `ParquetDataset` filters rows after widening each file's batch to the dataset schema (row-filter ADR), and positional deletes and row lineage count unfiltered rows. Pushing a file's residual filter into the file read is a follow-up.
- A new ADR records the decision; it replaces the row-filter ADR's "every filter column is decoded for every row" consequence.
- Tests compare filtered reads against decode-everything-then-filter over random data: nulls, structs, lists, every physical type and encoding, multi-page chunks, small batch sizes, and batches with no survivors. A JVM probe over SF10 lineitem measures q6/q12/q19 literal conjuncts, old and new path back to back in one process. JS and Native are compiled and tested, not timed.

## Alternatives and Why Not?

- **Decode everything, then gather (today).** Pays full decode, assembly, and a gather copy for discarded rows.
- **Add selection to `ColumnChunkCursor` itself.** Its buffer handover, row slots, and soft-limit cutting are tuned for full decode; mixing a second mode in risks that hot path.
- **Evaluate filters per dictionary entry (DuckDB DirectFilter).** A further win for filter columns; a follow-up once this lands.
- **Reorder conjuncts by observed selectivity.** Also a follow-up; the caller's order is a good default and keeps results deterministic.
