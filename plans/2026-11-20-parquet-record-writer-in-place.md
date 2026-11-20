# ParquetRecordWriter: shred records in the packer's buffer

Follow-up to #1036. Parent plans: [2026-11-20-parquet-js-row-writes.md](2026-11-20-parquet-js-row-writes.md) and [2026-11-20-parquet-read-write-performance-and-batch-api.md](2026-11-20-parquet-read-write-performance-and-batch-api.md).

## Goals

- Drop the per-row `toByteArray` copy when `ParquetRecordWriter.write` takes a `Map`, `Seq`, or case-class record, as `ParquetWriter[A].write` already does since #1036.
- No change in what gets written or in error behaviour.

## Background

- #1036 made the case-class writer pack each row into one reused `PureScalaBufferPacker` and pass `writtenArray`/`writtenSize` to `FileWriter.write(row, len)`. On Node.js the per-row copy was a large share of write time.
- `ParquetRecordWriter` still packs with `MessagePack.newPacker()` and copies the bytes out through `RecordBytes.of`. `RecordBytes.of` has no other caller.
- The shredder keeps string offsets into the row bytes until commit. `FileWriter.write` shreds and commits back to back, so the packer buffer is safe to reuse once `write` returns.
- JSON-string records are parsed by `MessagePack.fromJSON` into a fresh array; there is no copy to remove.

## Design

- Lane: **Express** — an internal perf change with no change in behaviour, outside every always-gate area; direction pre-approved in the parent plan.
- `ParquetRecordWriter` holds a `PureScalaBufferPacker`, as `ParquetWriter` does.
- For non-JSON records: clear the packer, pack with `RecordBytes.pack`, and call `core.write(packer.writtenArray, packer.writtenSize)`.
- JSON strings keep the current path: `fromJSON`, then `core.write(bytes)`, with the same error message.
- `RecordBytes.of` shrinks to the JSON parse, since the writer was its only caller.
- Untouched: `codec/`, `ColumnWriter`'s typed appends, the case-class writer, the reader.
- Tests: the existing `WriterTest` record cases on JVM, JS, and Native; a quick Node.js profile of a `Map` loop if cheap.

## Alternatives and Why Not?

- Keep `RecordBytes.of` returning `(array, len)`: adds a tuple per row for one caller.
