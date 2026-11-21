# Write partitioned Parquet datasets

## Goals

- Write rows into Hive `key=value` folders under a local directory or an S3, GCS, or Azure prefix with one call, like DuckDB's `COPY ... TO 'dir' (FORMAT parquet, PARTITION_BY (dt, country))` or Spark's `partitionBy`. Today users write one file at a time and build the folder layout by hand.
- Whatever uni writes, `Parquet.dataset(root)` reads back with the same columns, types, and partition pruning. Null and empty partition values both read back as null, as with Spark.
- A failed write leaves no partial file behind, on disk or in a bucket.
- This is the next step toward a dataset API on par with Spark and DuckDB. The roadmap is at the end.

## Background

- The reader from #1053 sets the format the writer must produce:
  - It skips any file or folder whose name starts with `_` or `.`.
  - It decodes `%XX` escapes in folder values and reads `__HIVE_DEFAULT_PARTITION__` as null.
  - Partition values are STRING unless the reader declares INT32, INT64, or DATE with `withPartitionType`.
  - The folder value wins over a file column with the same name.
- The single-file writers (`ParquetWriter[A]`, `ParquetRecordWriter`, `ParquetBatchWriter`) share `FileWriter`. It buffers one row group (128 MiB by default) as compressed pages, then writes it out.
- `abort()` discards an `ObjectOutputStream` upload, so no object appears. A local file is only closed, so a partial file stays on disk.
- `ObjectStore` has `list`, `delete`, and multipart `newOutputStream`, but no rename. `FileSystem` has `move` and `delete`.
- What other tools do:
  - DuckDB fails when the target folder isn't empty, unless you pass `OVERWRITE` or `APPEND`.
  - Spark offers `overwrite`, `append`, `errorifexists`, and dynamic partition overwrite. It also writes `_SUCCESS` and commits through `_temporary/` plus renames.
  - Both drop partition columns from the files and percent-escape Hive's special characters.

## Design

Lane: Standard. It adds a new public writer API and changes the docs and the uni skill. **Always-gate area**: the overwrite modes delete user data, so the deletion rules below need your approval.

User flow:

- A Scala job has `Seq[Event]`, or a stream of `Event`, and wants it under `s3://bucket/events/` split by `dt` and `country`.
- `Parquet.writeDataset("s3://bucket/events/", events, ParquetDatasetWriteOptions.default.withPartitionBy("dt", "country"))` writes it in one call. `Parquet.newDatasetWriter[Event](root, options)` gives a streaming writer with `write`, `writeAll`, `close`, and `abort`.
- After `close()`, `writer.writtenFiles` lists each file with its path, partition values, row count, and size. The user can check the result there or with `Parquet.dataset(root)`.
- If anything fails (a bad row, an I/O error, or an exception in the caller's loop), `abort()` runs or the user calls it. Every file from this write is discarded, and existing data is not touched.
- A second run picks the mode: append more files, replace the whole dataset, replace only the partitions it writes, or fail (the default) when data is already there.

Decisions:

- **Three writers, one options class**:
  - `newDatasetWriter[A]` writes case classes.
  - `newDatasetRecordWriter(root, schema)` writes Maps, JSON, or Seqs, as `ParquetRecordWriter` does.
  - `newDatasetBatchWriter(root, schema)` writes `ColumnBatch`es.
  - Each has an overload that takes an `ObjectStore`, which the writer leaves open. With a URI alone, the writer creates a store and closes it, as `Parquet.dataset` does.
- **Options reach the writers through one value**: `ParquetDatasetWriteOptions.withWriteOptions(ParquetWriteOptions)` carries the per-file settings, so every entry point takes `(root, [schema,] options)`.
- **The schema passed in includes the partition columns**, the way `ParquetDataset.schema` shows them. The writer takes them out of each file's schema and keeps the rest in order.
- **Options**:
  - Dataset settings go in a new `ParquetDatasetWriteOptions`: partition keys, mode, file limits, and the open-file limit. Each setting has `withXXX` and, where it is optional, `noXXX`.
  - Per-file settings stay in `ParquetWriteOptions`: codec, row group size, and dictionary. This mirrors the read side's `ParquetDatasetOptions` plus `ParquetReadOptions`.
- **Partition columns** must be top-level columns of type STRING, INT32, INT64, or DATE, the same set the reader accepts.
  - The folder order follows the order of `withPartitionBy`.
  - Null, `None`, or an empty string is written as `__HIVE_DEFAULT_PARTITION__`, as Hive and Spark do. Empty strings therefore read back as null.
  - Writing a schema with no columns left besides the partition keys is an error. A Parquet file needs at least one column.
  - A key starting with `_` or `.` is rejected, because the reader skips such folders and the data would vanish.
  - Rows are packed to MessagePack as today. The writer reads the partition values from the packed row by key, the same path for case classes, Maps, and JSON. The Shredder already skips keys that aren't in the file schema. For Seq or JSON-array records, the writer takes the values at the partition positions out before shredding.
- **Escaping** percent-encodes Hive's character set: control characters, `"#%'*/:=?\{[]^`, and DEL. It also encodes `<>|`, so the folder names are valid on Windows. Non-ASCII text is kept as is. The reader already decodes any `%XX`, so every non-empty value round-trips, except the literal string `__HIVE_DEFAULT_PARTITION__`, which reads back as null.
- **Partition types are not stored**. Reading INT64 or DATE partitions back still needs `withPartitionType`, as for data from Spark or DuckDB. A typed `_metadata` file is on the roadmap.
- **File names** are `part-<writeId>-<n>.parquet`.
  - `writeId` is 8 random hex characters per write, from `scala.util.Random`, because Scala Native has no `UUID.randomUUID`.
  - `n` counts up across the whole write.
  - Names stay unique across appends, and a run's files are easy to find.
- **Rolling**:
  - `withMaxRowsPerFile(n)` and `withMaxFileSize(bytes)` start a new file in the same partition.
  - The size counts the bytes written plus the compressed pages still buffered, and the check runs at row boundaries. A file can therefore run over the limit by up to one row. Batches are split at the limit.
  - Both limits are off by default, as in DuckDB.
- **Modes** (`ParquetWriteMode`):
  - `ErrorIfExists` (default) fails before writing anything if the root holds any file the reader would read.
  - `Append` adds files next to the existing ones. It first checks that the existing partition keys match `withPartitionBy`, because the reader fails on mixed layouts.
  - `Overwrite` replaces the dataset under the root.
  - `OverwritePartitions` replaces only the partitions this write touched, like Spark's dynamic partition overwrite, for re-running one day's ETL.
  - What is deleted: only the data files the reader would read (paths with no `_` or `.` segment), listed **before** the write starts. `_SUCCESS`, dot files, and anything this write created are never deleted. A root that's a bucket with no key, or a filesystem root, is refused for both overwrite modes.
  - When: old files are deleted **only after every new file has committed**. If the write fails, the old data is still there. A reader listing during that short window sees old and new files together. If an `Overwrite` changes the partition keys, reads fail until the old files are gone.
- **Commit and abort**:
  - An object store file is a multipart upload, published only at `close()`.
  - A local file is written as `.<name>.inprogress` in its partition folder, which the reader skips. It is renamed when it closes.
  - On failure, files still open are aborted (and a local `.inprogress` file is deleted), and the files this write already committed are deleted. Cleanup is best-effort: delete errors are added as suppressed exceptions on the original one.
  - This is not a transaction. A concurrent reader can see some committed files before the write ends. True atomic commits need a table format (Delta or Iceberg), which is on the roadmap.
- **Memory with many partitions**: each open file buffers up to one row group (128 MiB by default) plus, on S3, an 8 MiB upload part. Two limits bound this:
  - `withMaxOpenFiles(n)`, default 32. At the limit, the writer closes the file that went longest without a write. That partition's next row starts a new file.
  - `withMaxBufferedBytes(bytes)`, default 256 MiB, across all open files. Over the budget, the writer flushes the largest buffered row group early. That file stays open, so the result is smaller row groups, not more files.
  - The worst case is then about the budget plus 32 upload parts (~512 MiB on S3). The docs state it and suggest sorting by the partition columns for many partitions.
- **Batches** are split by partition values. A batch whose rows all fall into one partition (the common case for sorted input) passes through without a copy. Other batches are gathered by row index into per-partition batches. This adds an internal gather for each vector kind.
- **Sync only** in this PR. It runs on the JVM, Scala Native, and Node.js, wherever the single-file writers and sync `ObjectStore` writes run today.
- **Tests**:
  - Round trips through `Parquet.dataset` for all three writers, covering declared INT32, INT64, and DATE partitions, null and escaped values, and pruning (counting the files selected).
  - Rolling by rows and by size, the open-file limit, and each mode.
  - Abort leaving no files, locally and on S3 through the existing versitygw setup.
- **Docs**: a "Writing Datasets" section in `docs/text/parquet.md`, plus `for-ai-agents.md` and the uni skill (version bump, snippets compiled by `docSnippets`).

Roadmap toward a dataset API on par with Spark and DuckDB (later PRs, in rough order):

- Read side:
  - Prefetch footers on sync reads.
  - An explicit file list and glob patterns.
  - Skip whole files by footer min/max statistics.
  - Row-level filter evaluation.
  - Type widening across files (INT32 → INT64, FLOAT → DOUBLE).
- Write side:
  - An async writer.
  - Sorting within partitions.
  - Writing `_SUCCESS` and a `_metadata` file with partition types.
- Table formats: reading Delta Lake and Iceberg.
- The npm facade for datasets.

## Alternatives and Why Not?

- **Stage under `_temporary/` and rename at commit, as Spark does**: object stores have no rename, so a commit would copy every byte again. Multipart uploads already publish atomically per file.
- **Delete old files before writing, as DuckDB `OVERWRITE` does**: a failed run would lose the old data.
- **One open file per partition with no limit**: memory grows with the number of distinct partitions and can run out on high-cardinality keys.
- **Require input sorted by partition**: simpler, but users would have to sort in memory first. The open-file limit covers sorted input efficiently, and unsorted input still works.
- **Keep partition columns inside the files too**: it wastes space, and the reader ignores them anyway because the folder value wins.
