# Dataset `_SUCCESS` marker and typed partition hints

## Goals

- A dataset written by `Parquet.writeDataset` reads back with typed partitions (`dt` as DATE, `hour` as INT32) without the reader repeating `withPartitionType`. The writer already knows the types.
- The writer marks a finished write with `_SUCCESS`, as Spark and Hadoop do. Schedulers and sensors that wait for `_SUCCESS` (Airflow, Spark pipelines) work on Uni-written data.
- Spark, DuckDB, Trino, and Hive keep reading the same folders unchanged.

## Background

- The reader declares partitions STRING unless the caller sets `withPartitionType`, on purpose: Spark and DuckDB guess types from folder values, so one odd folder silently changes a type (adr/2026-11-21-parquet-datasets.md). A type the writer recorded is a declaration, not a guess, so it keeps that guarantee.
- Discovery already skips every file or folder whose name starts with `_` or `.`, and so do Spark, Hive, Trino, and DuckDB. New `_` files are invisible as data to all of them.
- The dataset writer's overwrite modes delete only reader-visible files, so `_` files at the root survive between writes. The writer must rewrite them itself.
- uni-parquet already writes and reads Parquet key-value metadata (`ParquetWriteOptions.withKeyValueMetadata`, `ParquetFile.keyValueMetadata`).
- Parquet has a standard summary-file name, `_common_metadata`: a Parquet file with a footer and no row groups, written by parquet-mr and Spark's old summary option. Spark ignores it unless `spark.sql.parquet.respectSummaryFiles` is on. It has no `.parquet` extension and no rows, so extension globs (DuckDB's usual `**/*.parquet`) skip it, and Hive and Trino skip `_` files.

## Design

- Lane: Standard. New files in user data folders, plus new reader behaviour.
- User flow:
  - Write: `Parquet.writeDataset(root, rows, options.withPartitionBy("dt", "hour"))` works as today. On a successful `close`, the writer then writes two root files: `_common_metadata` and an empty `_SUCCESS`.
  - Read: `Parquet.dataset(root)` finds `_common_metadata` in the listing it already makes. It types `dt` and `hour` from it, so filters take `LocalDate` and `Int`, and `readRows` gives typed values.
  - Override: an explicit `withPartitionType` always wins over the hint. `ParquetDatasetOptions.withReadCommonMetadata(false)` ignores the hint completely.
  - Opt out on write: `withWriteSuccessMarker(false)` and `withWriteCommonMetadata(false)`, each defaulting to true.
  - Recover: a folder value that doesn't fit the recorded type fails with the same error a declared type gives today, naming the file and the hint. The fix is to declare STRING or turn the hint off.
- `_common_metadata` holds the data columns' schema (what the files hold), plus partition key names and types in key-value metadata. Partition columns stay out of the schema, so a Spark reader that respects summary files sees no duplicate columns.
- Order on `close`:
  - Publish the data files.
  - Delete the replaced files.
  - Write `_common_metadata`, then `_SUCCESS`, before the writer's store closes.
- A failed or aborted write leaves no new `_SUCCESS`.
- If writing either root file fails, the data files are kept and the error names that file.
- Stale files: `Overwrite` and `OverwritePartitions` delete the old `_SUCCESS` before they publish or delete anything, as Spark's overwrite clears the folder first. Sensors therefore never see "done" over a mix of old and new files.
- `Overwrite` also deletes an old `_common_metadata` that it doesn't rewrite, for example with the hint turned off. A replaced dataset never keeps stale types.
- `Append` keeps an earlier `_SUCCESS`, as Hadoop's committer does.
- When to write the hint, so it never claims types for folders the writer didn't create:
  - `ErrorIfExists` and `Overwrite` always write it.
  - `Append` and `OverwritePartitions` write it when the root had no data files, or when an existing hint matches.
  - An existing hint with different keys or types fails the write before any file is written.
  - Appending to a dataset another tool wrote, with no hint, writes no hint.
- Root and glob datasets read the hint at the listed base. For a glob, this is the folder before the first glob segment, read from the listing before the glob filter is applied.
- Explicit file lists have no root, so they don't read a hint.
- A hint whose keys differ from the folder keys is ignored with a warning, not an error.
- Reading the hint costs one small read, and only when the file is present. There is no extra listing, because both the local and object listings already include `_` files.
- `datasetAsync` reads it with the store's async get, so browsers work.
- Empty writes: a write with no rows still writes `_common_metadata` and `_SUCCESS`. The default proposal is that `Parquet.dataset` reads such a root as an empty dataset with the schema from `_common_metadata`, as Spark reads empty output. Without the hint, an empty root stays an error.
- Docs: `docs/text/parquet.md`, `for-ai-agents.md`, the skill's `references/parquet.md`, a plugin bump, and updates to the "Not covered" lists in both dataset ADRs.
- Tests cover:
  - Typed partition reads without options, on JVM and JS, including `datasetAsync` over an object store.
  - Override and opt-out.
  - Glob roots.
  - Each write mode's hint rules, the conflict error, and stale-file deletion on overwrite.
  - No `_SUCCESS` after a failure or abort.
  - Empty writes.

## Alternatives and Why Not?

- Infer partition types from folder values, as Spark and DuckDB do: rejected earlier for silent type changes. It still works for foreign data via `withPartitionType`.
- Put a JSON manifest in `_SUCCESS`: Hadoop's S3A committers already write their own JSON there, so the content would be ambiguous.
- A Uni-specific `_uni_dataset.json`: works, but adds a private format where Parquet already has a summary file and key-value metadata.
- Put partition columns in the `_common_metadata` schema: simpler to read, but Spark with `respectSummaryFiles` would see the columns twice.
- A full `_metadata` with every file's row groups: large, rewritten on every append, and Spark deprecated it. A table format (Delta, Iceberg) covers that need.
