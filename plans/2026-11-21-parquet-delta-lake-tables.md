# Parquet datasets: read Delta Lake tables (Iceberg next)

## Goals

- Read a Delta Lake table as a `ParquetDataset`, as Spark's `format("delta")` and DuckDB's `delta_scan` do: only the files of the table's current version (or a chosen version), with the table's partition values and types.
- Stop the silent wrong answer we have today: `Parquet.dataset(deltaRoot)` skips `_delta_log/` (it starts with `_`) and reads every Parquet file under the root, including files that later commits removed, so rows show up twice or after a delete.
- Set up the table-format seam so Iceberg plugs in as a separate PR (see the card's scope question).

## Background

- A Delta table is a folder of Parquet data files plus `_delta_log/`. Each commit is `NNNNNNNNNNNNNNNNNNNN.json`, newline-delimited actions: `add` and `remove` (data file path, partition values as strings, size), `metaData` (schema as Spark JSON, partition columns), `protocol` (reader version and features).
- Every so often a writer folds the log into a Parquet checkpoint, `N.checkpoint.parquet` (or `N.checkpoint.K.M.parquet` in parts), and records it in `_last_checkpoint`. A reader starts from the newest checkpoint at or before the target version and replays the later JSON commits.
- Delta data files don't store partition columns; their values live in `add.partitionValues`, typed by the table schema. `ParquetDataset` already appends partition columns as constant vectors from `DatasetFile.partitions`, so a Delta file maps onto today's `DatasetFile` directly.
- uni-parquet already reads nested Parquet (the checkpoint's `add` struct and `partitionValues` map) and parses JSON, so Delta needs no new codec. Iceberg's manifests are Avro, so Iceberg also needs a small Avro container reader. That, plus its own metadata model, is why it gets its own PR.
- Newer Delta writers turn on reader features that change what a file means: deletion vectors (rows masked by a bitmap), column mapping (physical column names differ from logical ones), and v2 checkpoints. Reading such a table while ignoring the feature returns wrong rows or null columns.

## Design

Lane: Standard. It adds a public API, changes what `Parquet.dataset` returns for a Delta root, and updates the docs and the uni skill.

User flow:

- A user has a Delta table at `s3://lake/events/` written by Spark or delta-rs. `Parquet.dataset("s3://lake/events/")` sees `_delta_log/` at the root and reads the table's latest version: the live files only, with partition columns typed from the table schema. `readAs`, `readRows`, `readBatches`, filters, and the async variants work as for any dataset.
- `dataset.files` lists the live files and their partition values, so the user can check what a read covers.
- Time travel: `ParquetDatasetOptions.default.withVersion(12)` reads version 12. A version is readable when a checkpoint at or before it (or commit 0) plus every later commit up to it is still in the log; log cleanup removes old commits but keeps checkpoints. Otherwise the error names the readable range. `withVersion` on a dataset that isn't a Delta table is an error.
- A table that uses an unsupported reader feature fails when the dataset opens, naming the feature (for example "deletionVectors"), instead of returning wrong rows.
- To read the raw files anyway (for example to inspect a broken table), `withTableFormat(ParquetTableFormat.Files)` turns detection off.

Decisions:

- **Auto-detection in `Parquet.dataset`**, for a plain root (not a glob or file list): a `_delta_log/` folder directly under the root makes it a Delta table. `withTableFormat` takes `Auto` (default), `Files`, or `Delta` (and `Iceberg` next PR). `Delta` fails if there is no log. Detection reuses the listing the dataset already does. With `Delta` set explicitly, only `_delta_log/` is listed, which skips listing removed files that a vacuum hasn't deleted yet.
  - A root below the table (a `dt=` partition folder of a Delta table) isn't detected; reading it as files is the caller's choice, as today.
  - Why default on: the alternative default is the silent wrong answer above. A user who wants raw files says so.
- **Log replay**: pick the newest checkpoint at or before the target version from the `_delta_log/` listing (a multi-part checkpoint counts only when all its parts are listed; `_last_checkpoint` is just a hint, since it can be stale), read it with our own Parquet reader, then apply the later JSON commits in order. The last action for a path wins (`add` keeps it, `remove` drops it). The newest `protocol` and `metaData` at or before the target version decide the protocol check, the schema, and the partition columns. Classic single-part and multi-part checkpoints are supported. JSON commits are fetched `withPrefetch` at a time, as footers are.
- **Paths**: `add.path` is a URI relative to the table root (`%`-encoded), or absolute. Absolute paths must be in the same store and bucket as the root; others are an error.
- **Partition types** come from the table schema: `string` → STRING, `byte`/`short`/`integer` → INT32, `long` → INT64, `date` → DATE. Other partition types (boolean, timestamp, decimal, double) fail with a message suggesting `withPartitionType(key, STRING)`, which overrides as it does for folders. A JSON null or an empty string in `partitionValues` reads as null, as the protocol defines.
- **Schema**: the union of the selected files' footers, as today, so numeric widening and missing columns keep working. A table with no live files reads as an empty dataset whose schema is converted from the Delta schema (primitives, structs, arrays, maps).
- **Root files**: `_common_metadata` is ignored at a Delta root; the log is the source of truth.
- **Protocol check**: reader version 1, or version 3 whose reader features are all ones that don't change reads for us (`timestampNtz`, `vacuumProtocolCheck`). Version 2 (column mapping), deletion vectors, `v2Checkpoint`, and any unknown feature are refused by name.
- **Sync and async share one resolver**: the replay is written as a small sequence of "fetch these paths, then continue" steps, run by a blocking loop for `dataset` and by Rx for `datasetAsync`, as the writer's RootActions do. So Scala.js and Native get async Delta reads with no thread.
- **File stats in the log** (`add.stats` min/max) are not used for pruning in this PR; partition pruning and row-group statistics apply as for any dataset.
- **Tests**: small tables made by delta-rs (via `uv run --with deltalake`) checked in as fixtures (partitioned, with removes, with a checkpoint, an empty table), plus generated logs for edge cases (multi-part and incomplete checkpoints, version range errors, relative and absolute paths, a `deletionVectors` reader feature for the refusal). The async path runs on `MemoryObjectStore`.
- **Docs**: a "Delta Lake tables" section in `docs/text/parquet.md`, the AI-agent page row, the skill reference, an ADR, and the plugin version bump.

## Not covered (later)

- Iceberg tables, in their own PR if split. It reuses `withTableFormat` and the step resolver, and adds an Avro reader, snapshot selection (`withSnapshotId`), and refusal of delete files.
- Deletion vectors and column mapping. Deletion vectors need row-level masking, which fits after the row-filter work in another session.
- Pruning files by `add.stats`, change data feed, and writing Delta tables.

## Alternatives and Why Not?

- **Only explicit entry points (`Parquet.delta(root)`), no detection**: leaves `Parquet.dataset(deltaRoot)` silently wrong. Detection plus `Files` gives both.
- **Detection that fails and points to `Parquet.delta`**: safe, but every caller then needs to know the format up front, unlike Spark's and DuckDB's readers of Parquet folders that are tables.
- **Delta and Iceberg in one PR**: two metadata models plus an Avro reader make one large review. Shared API decisions land here, so Iceberg is additive.
- **Depend on delta-kernel or the Iceberg Java library**: JVM only, many dependencies; Uni keeps uni-parquet pure Scala on all platforms.
