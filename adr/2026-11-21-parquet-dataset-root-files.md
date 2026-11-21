# Parquet dataset root files: `_SUCCESS` and `_common_metadata`

## Context

`Parquet.dataset` reads partitions as STRING unless the caller declares a type, because guessing types from folder values lets one odd folder change a type ([datasets ADR](2026-11-21-parquet-datasets.md)). For data that `Parquet.writeDataset` wrote, the writer already knows the types, so making every reader repeat `withPartitionType` was busywork. Pipelines built on Spark and Hadoop also wait for a `_SUCCESS` marker, which the writer did not write.

## Decision

- On a successful `close`, the dataset writer writes `_common_metadata` and then an empty `_SUCCESS` at the root. They come after every data file is published and the replaced files are deleted, and before the writer closes its store. If either write fails, the data files are kept and the error names the root file.
- `_common_metadata` is Parquet's summary-file name: a Parquet file with no row groups. Its schema holds the data files' columns. The partition keys and types (STRING, INT32, INT64, DATE) are in key-value metadata under `wvlet.uni.parquet.partition.types`, as a JSON array of `{name, type}`. Partition columns stay out of the schema, so Spark with `respectSummaryFiles` doesn't see them twice.
- Readers apply recorded types like declared ones. An explicit `withPartitionType` wins, and `withReadCommonMetadata(false)` ignores the file. Root and glob datasets read the file at the listed base, from the listing they already make. Glob datasets read it before the glob filter. Explicit file lists have no root and don't read it. A recorded key list that differs from the folder keys, or an unreadable file, is ignored with a warning, so the data files stay readable. `datasetAsync` reads the file with the store's async get, so browsers work.
- A root whose listing has no data files but has `_common_metadata` (a write without rows) reads as an empty dataset with the recorded schema and partition columns. A glob or extension filter that matches nothing stays an error, as does an empty root without the file.
- The writer records types only for folders it owns:
  - `ErrorIfExists` and `Overwrite` always write the file.
  - `Append` and `OverwritePartitions` write it when the root had no data files, or when the existing `_common_metadata` is Uni's and matches.
  - Recorded types that differ fail the write before any file is written.
  - A `_common_metadata` that Uni didn't write is left alone.
- Stale files: `Overwrite` and `OverwritePartitions` delete the old `_SUCCESS` before they change anything. `Overwrite` deletes an old `_common_metadata` that it doesn't rewrite. `Append` keeps an earlier `_SUCCESS`, as Hadoop's committer does.

Rejected alternatives:
- Guessing types from values, as Spark and DuckDB do: one odd folder silently changes the type.
- A JSON manifest in `_SUCCESS`: Hadoop's S3A committers already write their own JSON there.
- A Uni-specific JSON file: Parquet already has a summary file and key-value metadata.
- A full `_metadata` with every row group: large, rewritten on every append, and deprecated in Spark. Table formats cover that need.

## Consequences

- Datasets written by Uni read back typed with no options, in Uni. Spark and DuckDB still infer or declare types their own way.
- A folder value that doesn't fit a recorded type fails the read with the same error as a declared type. Declare STRING or ignore the file to read such data.
- A write is still not a transaction. `_SUCCESS` says the last write finished; it doesn't make the files appear atomically.
