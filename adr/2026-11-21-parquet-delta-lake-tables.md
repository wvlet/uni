# Parquet datasets: read Delta Lake tables

- Status: Accepted
- Date: 2026-11-21
- Plan: `plans/2026-11-21-parquet-delta-lake-tables.md`

## Context

A Delta Lake table is a folder of Parquet files plus `_delta_log/`, which records the files of each
version. Deletes, updates, and overwrites leave old files on disk until a vacuum. `Parquet.dataset`
skipped `_delta_log/` (it starts with `_`) and read every file, so a Delta root returned removed
rows with no error. Spark (`format("delta")`) and DuckDB (`delta_scan`) read the log instead.

## Decision

- **Detect by default.** `ParquetDatasetOptions.tableFormat` is `Auto`: a plain root (not a glob or
  file list) with `_delta_log/` directly under it is a Delta table. Detection reuses the root
  listing. `Files` reads the raw files, and `Delta` fails without a log and lists only
  `_delta_log/`, which skips old files a vacuum hasn't removed. Detection is on because the
  alternative default is a silent wrong answer.
- **Plan, then replay.** `DeltaLog.plan` picks the log files from a listing: the newest complete
  checkpoint (single or multi-part, all parts listed) at or before the target version with every
  later commit present, or commit 0. `_last_checkpoint` is not read, since the listing is
  authoritative and the hint can be stale. `DeltaLog.replay` applies the checkpoint, then each
  commit in order: the last action per path wins, and the newest `protocol` and `metaData` at the
  target version decide. Sync reads fetch the plan with `ParallelMap`, async reads with
  `RxParallel.parSequenceN`, so both share one parser and run on every platform.
- **Time travel** with `withVersion(n)`. Log cleanup removes old commits but keeps checkpoints, so
  a version is readable from any checkpoint at or before it. Errors name the readable range.
- **Refuse what changes meaning.** Reader version 1 is read. Version 2 (column mapping) is refused.
  Version 3 is read only when every reader feature is `timestampNtz`, `vacuumProtocolCheck`, or
  type widening, whose changed types show up in the footers and go through the dataset's numeric
  widening or fail as a type conflict. Any `add` with a deletion vector is refused too.
- **Partitions from the log.** Values come from `add.partitionValues`, typed by the table schema:
  string, byte/short/integer, long, and date map to STRING, INT32, INT64, and DATE. Other types fail
  with a hint to use `withPartitionType(key, STRING)`, which overrides as for folders. JSON null
  and the empty string are null, as the protocol defines.
- **Paths.** Relative paths are `%`-decoded and resolved against the root. Absolute paths must be in
  the root's bucket (`s3a://` and `s3n://` count as `s3://`) or, for a local table, be `file:` URIs.
- **Schema** is still the union of the selected files' footers. A table without live files reads as
  an empty dataset whose schema is converted from the Delta schema (primitives, decimals,
  timestamps, structs, arrays, maps). `_common_metadata` is not read at a Delta root.

## Consequences

- A Delta read fetches the checkpoint and the commits after it before listing any data, so a table
  with a long uncheckpointed log pays one request per commit (`withPrefetch` at a time).
- A partition folder below a Delta table (`events/dt=2026-11-20/`) is not detected and reads as
  plain files.
- Not covered: Apache Iceberg, deletion vectors (row-level masking), column mapping, v2 checkpoints,
  file pruning with `add.stats`, change data feed, and writing Delta tables.
