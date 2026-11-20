# uni-parquet: `ParquetBatchWriter` for columnar data

PR 3 of [the read/write performance plan](2026-11-20-parquet-read-write-performance-and-batch-api.md).
The user approved that plan's direction on 2026-11-20 ("New: `ParquetBatchWriter` for columnar
data" and the "PR 3" bullet); this note only narrows it to PR 3. No merge authorization.

## Goals

- Write `ColumnBatch`es straight to a file, flat and nested, with no row objects in between.
- ≤0.5x parquet-java's Group writer on the JVM for both benchmark tables (provisional target);
  Native within 2x of JVM uni, Node.js within 4x.

## Design

Lane: Standard (pre-approved in the parent plan).

- API: `Parquet.newBatchWriter(path | IOPath | OutputStream, schema, options)` returns a
  `ParquetBatchWriter` with `write(batch)`, `writeAll(batches)`, `numRows`, `abort()`, `close()`.
  It shares `FileWriter` (row groups, footer, I/O failure handling) with the row writers.
- Columns are matched to the schema's top-level fields by name. Each vector must match its field:
  the vector type of the physical type (Scaladoc table), `ListColumn` for LIST and unannotated
  repeated fields, `MapColumn` for MAP, `StructColumn` (children by name) for other groups — the
  same shapes the reader's assembly returns, so a batch read from a file writes back unchanged.
- The whole batch is validated before anything is buffered: missing or extra columns, vector
  types, sizes, offsets (non-decreasing, within the child), FIXED_LEN_BYTE_ARRAY lengths, and
  nulls in required fields. Positions under a null parent are not checked, as the reader leaves
  them null. A failure throws `ParquetException` and the writer stays usable.
- Writing walks each top-level field column by column: flat columns loop over the value array
  into the typed `ColumnWriter` appends; nested fields turn offsets and nulls into repetition and
  definition levels per row, with no row objects.
- Row groups: rows are written in slices sized from the bytes per row seen so far, and the row
  group closes after the slice that reaches `rowGroupSize`, so a group ends at a row boundary
  inside a batch and batch boundaries leave no trace.
- Projection: `ParquetSchema.select(names*)` (leaf indexes renumbered) and
  `ColumnBatch.select(names*)`.
- INT96 is rejected when the writer opens, as the row writers already do through the schema builder.

## Checks

- Round trips on JVM, JS, Native (forced with `testOnly`): flat types, nulls, nested lists, maps,
  structs, legacy 2-level lists from the Apache test files, multi-batch row groups, rejected
  batches leave the writer usable, projection copy.
- `examples/parquet-bench` gets "write uni batch" for both tables; results go in the parent plan.
- ADR, `docs/text/parquet.md`, `for-ai-agents.md`, skill `references/parquet.md` + version bump.

## Alternatives and Why Not?

- **Stage the batch and commit**, like rows: doubles the memory and copies. Validating the whole
  batch first gives the same all-or-nothing result without a copy.
- **Close row groups exactly at the first row past `rowGroupSize`** by checking after every row:
  summing every column's buffered size per row costs more than the write itself on wide flat
  data. Slices sized from bytes per row land within a few rows of it.
