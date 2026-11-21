# Parquet dataset writer: partitioned folders that Parquet.dataset reads back

- Status: Accepted
- Date: 2026-11-21
- Plan: `plans/2026-11-21-parquet-dataset-writer.md`

## Context

`Parquet.dataset` reads Hive `key=value` folders, but uni could only write one file at a time.
Every job that wrote a lake had to rebuild escaping, file naming, rolling, overwrite, and cleanup
on failure. Object stores have no rename, so Spark's commit through `_temporary/` plus renames
would copy every byte twice there.

## Decision

- **Three writers share one core.** `newDatasetWriter[A]` (case classes), `newDatasetRecordWriter`
  (maps, JSON, `Seq`), and `newDatasetBatchWriter` (`ColumnBatch`) all route rows through
  `DatasetWriterCore`. Rows arrive as MessagePack bytes, and the core reads the partition values
  and rebuilds the row without them, so the shredder keeps its positional fast path. Batches are
  grouped by partition: a batch with one partition is written without a copy, and others are
  gathered by row index (`VectorGather`).
- **Settings live in `ParquetDatasetWriteOptions`**, with the per-file `ParquetWriteOptions`
  inside it (`withWriteOptions`). This mirrors `ParquetDatasetOptions` plus `ParquetReadOptions` on
  the read side.
- **The format is what the reader accepts.** Partition columns are top-level STRING, INT32, INT64,
  or DATE, and are left out of the files. Values are percent-escaped with Hive's character set
  plus `<>|` for Windows. Null and empty values become `__HIVE_DEFAULT_PARTITION__`. Keys that
  start with `_` or `.` are rejected, because the reader skips them. Partition types are not
  stored, so typed reads still declare `withPartitionType`.
- **Each file commits atomically, and the write cleans up after itself.** An object file is a
  multipart upload, published at close. A local file is written as `.<name>.inprogress`, which the
  reader skips, and renamed when it's finished. A file leaves the open set only after its close
  and rename succeed, so an abort always finds it. Any I/O failure, or `abort()`, discards open
  files and deletes the files already published. A row that doesn't fit throws without stopping
  the writer, as the single-file writers do.
- **Modes.** `ErrorIfExists` is the default, as in DuckDB and Spark. `Append` and
  `OverwritePartitions` require the existing partition keys to match, because the reader fails on
  mixed layouts. Both overwrite modes delete only reader-visible data files, listed before the
  write starts, and only after every new file is published. They refuse bucket and file system
  roots. A failure while deleting keeps the new files and names the file it couldn't delete,
  because an abort at that point would lose data.
- **Memory is bounded twice.** `maxOpenFiles` (32) finishes the least recently written file when
  another partition needs one. `maxBufferedBytes` (256 MiB) flushes the largest buffered row group
  early, and that file stays open. File names are `part-<writeId>-<n>.parquet`, with an 8-hex
  `writeId` from `scala.util.Random`, because Scala Native has no `UUID.randomUUID`.

## Consequences

- A write is not a transaction. A reader listing the folder during a write can see the files
  finished so far. Atomic commits across files need a table format (Delta Lake, Iceberg).
- `ErrorIfExists`, `Append`, and the overwrite modes list the whole root before writing.
- Case classes can have `LocalDate` fields and DATE partitions since the
  [LocalDate Weaver](2026-11-21-localdate-weaver.md). Records and batches can write them too.
- `_SUCCESS` and `_common_metadata` are covered by [dataset root files](2026-11-21-parquet-dataset-root-files.md).
- Not covered: an async writer, sorting within partitions, a full `_metadata` file, and
  bucketing.
