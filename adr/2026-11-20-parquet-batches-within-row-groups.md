# uni-parquet: batches within a row group for column chunks over 2 GiB

- Status: Accepted
- Date: 2026-11-20
- Plan: `plans/2026-11-20-parquet-large-chunks-and-performance.md`

## Context

uni-parquet decoded a row group in one piece: each column's values went into one array, and each
compressed chunk was fetched into one `Array[Byte]`. Arrays are limited to 2^31 elements, so a
column chunk with more than 2 GiB of values failed (parquet-testing's
`large_string_map.brotli.parquet` has two 1 GiB strings). The format itself limits only a page
(`i32` sizes) and a single value, not a chunk.

## Decision

- **Batches within a row group.** `readBatches` and everything built on it (`readAs`, `readRows`,
  `readJson`, and the `...Async` forms) read a row group in batches of rows. Vectors stay plain
  arrays (`Array[Long]`, `offsets: Array[Int]`, `data: Array[Byte]`), so the public vector API and
  its speed do not change.
- **Split only when needed.** Without `ParquetReadOptions.withBatchSize(rows)`, a row group is one
  batch, as before, unless a column's decoded values pass 1 GiB: the batch then ends at the next
  complete row. The cut uses decoded sizes, not footer sizes, because dictionary strings expand
  when decoded. A fixed default batch size was rejected because it would change the batches that
  existing readers see for ordinary files.
- **Levels before values.** A column cursor decodes a page's levels and holds the page before
  decoding its values. The next page's first repetition level tells whether the last row is
  complete, and the cursor can stop before a page whose values would pass the 2 GiB array limit.
  Every column of a batch is cut at the same row, and each cursor keeps its leftover rows for the
  next batch.
- **`readRowGroup` stays one batch.** It may use the full 2 GiB per column, so every file that read
  before still reads; past that it fails with a message that points to `readBatches`.
- **Windowed chunks.** Compressed chunks over 256 MiB are read in windows of that size (a page is
  always contiguous, copied when it crosses windows) instead of one range. The synchronous reader
  reads windows as it decodes; the asynchronous one fetches all windows of a row group first,
  because `Rx` has no pull-based source, and also decodes all batches of a row group before emitting
  them.
- Still unsupported: one row with more than 2 GiB of values in one column.

## Alternatives

- **`Canvas`-backed vectors with `Long` offsets.** Off-heap canvases need Java 22+ (Uni supports
  17), heap canvases are still limited to 2 GiB, the vector API would change, every access would be
  a bounds-checked call instead of an array read, and the whole chunk would still be in memory.
- **Split at page boundaries only.** Pages of different columns end at different rows.

## Consequences

- Reading `large_string_map.brotli.parquet` takes about 5 GiB of heap (the 1 GiB dictionary entry, a
  decompressed 1 GiB page, and the batch's values), so its test runs only with
  `UNI_PARQUET_LARGE_TESTS=1`. Smaller internal limits test splitting and windows on every
  platform.
- Counts per row group and chunk are `Long`; a row group with more than 2^31 rows reads in
  batches.
