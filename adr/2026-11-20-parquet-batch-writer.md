# A columnar batch writer in uni-parquet

- Status: Accepted
- Date: 2026-11-20
- Plan: `plans/2026-11-20-parquet-read-write-performance-and-batch-api.md` (PR 3),
  `plans/2026-11-20-parquet-batch-writer.md`
- Amends: the "one value path for every input" decision in
  [`2026-11-20-pure-scala-parquet-writer.md`](2026-11-20-pure-scala-parquet-writer.md), for
  columnar input only

## Context

The row writers turn every value into MessagePack and shred it by schema. Code that already holds
columns (copying or projecting a file read with `readBatches`, exporting analytics results,
generated data) paid a conversion to rows and back. A prototype that fed the column writers from
`ColumnBatch` arrays ran 5–7x faster than the row path and ahead of parquet-java's Group writer.

## Decision

- **`Parquet.newBatchWriter(path | IOPath | OutputStream, schema, options)`** returns a
  `ParquetBatchWriter` with `write(batch)`, `writeAll`, `numRows`, `abort()`, and `close()`. It
  shares `FileWriter` (row groups, footer, I/O failure handling) and `ParquetWriteOptions` with
  the row writers; only the shredding differs.
- **The reader's vectors are the input.** A batch takes the vector types `readBatches` returns,
  with physical values (DATE as days in an `IntColumn`, TIMESTAMP in its unit, DECIMAL unscaled,
  strings as UTF-8 in a `BinaryColumn`), so a batch read from one file writes to another
  unchanged. Fields are classified (LIST, legacy 2-level lists, MAP, unannotated repeated, group)
  with the same predicates as the reader's `Assembly`, so read and write shapes cannot drift.
- **Columns by name.** Batch columns are matched to the schema's top-level fields by name, in any
  order; struct children by name too.
- **Validate the whole batch, then write.** Missing, duplicate, or extra columns, vector types,
  sizes, offsets (non-decreasing, within the child), FIXED_LEN_BYTE_ARRAY lengths, and nulls in
  required fields are checked before anything is appended, so a rejected batch leaves no values
  and the writer stays usable. Positions under a null parent, or that no list row points to, are
  not checked, since the reader itself leaves required children null under a null struct. This
  replaces the row writers' staging area, which would copy the whole batch.
- **Levels straight from offsets and nulls.** Flat top-level columns loop over their arrays into
  the typed `ColumnWriter` appends; nested fields turn offsets and null flags into repetition and
  definition levels per row, with no row objects.
- **Row groups at a row boundary inside a batch.** Rows are written column by column in slices
  sized from the bytes per row seen so far (16 rows first, at most 4,096), and the row group closes
  after the slice that reaches `rowGroupSize`. Groups land within a few rows of where the row
  writers would close them, and batch boundaries leave no trace in the file.
- **Projection only.** `ParquetSchema.select(names*)` (leaf column indexes renumbered) and
  `ColumnBatch.select(names*)`. Row filtering stays with the caller; a nested filter needs offset
  rewriting.
- **INT96 is rejected when the writer opens**, before the file is created, as the schema builder
  already rejects it for the row writers.

## Consequences

- JVM, 1M-row `lineitem` and 250k-row `orders`: well under half of parquet-java's Group writer
  time on `lineitem`; see the plan for the measured table. Native stays within 2x of JVM uni and
  Node.js within 4x.
- Callers that build nested vectors by hand must get offsets and null flags right; every batch is
  validated, and errors name the column path and row.
- A second public writer to document and test on three platforms.
- `LongDictionary` keeps each key next to its index in one array, so a lookup touches one cache
  line; it was the top cost of numeric columns in both writers. Binary min/max statistics are
  updated only when the dictionary adds a value, since a repeated value cannot change them.

## Alternatives considered

- **Stage the batch and commit, like rows.** All-or-nothing too, but it doubles memory and copies
  every value.
- **Check the row group size after every row.** Exact boundaries, but summing every column's
  buffered size per row costs more than the write on wide flat data.
- **Vector builders (Arrow-style).** Useful, but a separate API decision; plain arrays already work
  and a builder can come later without changing the writer.
- **Write case classes through the batch writer.** Adds a full copy and still needs Weaver per
  field; the streaming shredder (PR 2) covers that path.
