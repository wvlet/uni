# Parquet datasets: write Apache Iceberg tables

## Goals

- Write rows as an Apache Iceberg table with the dataset writer, so Spark, Trino, DuckDB, pyiceberg, and `Parquet.dataset` read them as a table: atomic commits, snapshots, and time travel, not only a folder of files.
- Stop the silent wrong answer on the write side: `Parquet.writeDataset(icebergRoot, rows, Append)` today adds files under the root that no Iceberg reader sees, because no snapshot lists them.
- Keep Uni's pure-Scala rule: no iceberg-java and no Avro library, on JVM, Scala.js, and Native.

## Background

- Reading Iceberg landed in #1093 (`IcebergTable`, the `Avro` reader, `ParquetTableFormat`, `withSnapshotId`). The write side reuses its metadata parsing.
- A commit writes data files, then a manifest (Avro) that lists them, then a manifest list (Avro) that names the new manifest plus the parent snapshot's manifests, then a new table metadata JSON with the new snapshot. Only the last step publishes anything.
- The metadata swap must be atomic: two writers must not both create version N+1.
  - Catalog tables (Glue, REST, Hive, SQL) swap a pointer in the catalog. Writing files next to them is wrong, because the catalog keeps pointing at the old version.
  - Hadoop tables keep `metadata/vN.metadata.json` plus a `version-hint.text` pointer. iceberg-java creates `vN+1` with a rename that fails if the file exists, which is not safe on S3.
  - S3 (`If-None-Match: *`), GCS (`ifGenerationMatch=0`), and Azure (`If-None-Match: *`) now all support create-if-absent puts, so a Hadoop table is safe on every store we support. `ObjectStore` has no conditional put yet.
- Iceberg data files carry a field ID on each column (`PARQUET:field_id`), and Spark and Trino match columns by ID. `SchemaElement` already reads and writes field IDs, but `ParquetField` drops them.
- Iceberg data files keep the identity partition columns. The Hive dataset writer drops them from the files and puts them in folder names.
- Manifests carry per-file column stats (value and null counts, lower and upper bounds by field ID), which engines use to prune files. Parquet's row-group stats use the same byte encoding as Iceberg's bounds for ints, longs, dates, timestamps, strings, binary, and booleans.

## Design

Lane: Standard. It adds public API (a table format on the write options, `ObjectStore.putIfAbsent`), changes what an append to an Iceberg root does, and updates docs and the uni skill.

User flow:

- A job writes events to `s3://lake/events/` as Iceberg: `Parquet.writeDataset(root, events, ParquetDatasetWriteOptions.default.withTableFormat(ParquetTableFormat.Iceberg).withPartitionBy("dt"))`. The first write creates the table with snapshot 1, and later writes with `withMode(Append)` add snapshots.
- The record, batch, and async writers take the same options. `writer.writtenFiles` lists the data files.
- `Parquet.dataset(root)` and other engines read the new snapshot. Older snapshots stay readable with `withSnapshotId`.
- A failed write (bad row, I/O error, lost commit race after retries) deletes its data and manifest files, and the table stays at its previous snapshot.
- An append to an existing Iceberg root under the default `Auto` format writes Iceberg, so the files land in a snapshot. An append to a Delta root fails and says that Uni doesn't write Delta yet. `withTableFormat(Files)` keeps today's plain-file behaviour.

Decisions:

- **Options**: `ParquetDatasetWriteOptions.withTableFormat(ParquetTableFormat)` reuses the read-side enum. `Auto` (default) writes plain files unless the root already holds a table. `Iceberg` creates or appends to a table. `Delta` fails for now.
- **Writable tables are Hadoop tables only**: `metadata/vN.metadata.json` plus `version-hint.text`. A root whose metadata files have catalog names (`00003-<uuid>.metadata.json`) is refused, and the message says to commit through the table's catalog. A catalog client is later work.
- **Modes**:
  - `ErrorIfExists` (default) creates the table and fails if one exists.
  - `Append` adds a snapshot, creating the table when there is none.
  - `Overwrite` and `OverwritePartitions` fail for now and name the follow-up, rather than half-doing Iceberg's delete semantics.
- **Format v2**: new tables are v2, and appends need a v2 table. Appending to a v1 table would mean upgrading its format version, so it is refused. v3 tables are refused, as reads are.
- **Atomic commit**: `metadata/v{N+1}.metadata.json` is created with `putIfAbsent`. Only then is `version-hint.text` rewritten.
  - Losing the race reloads the table and retries on the new parent, up to 4 times, if its schema and partition spec are unchanged. Otherwise the write fails and names the change.
  - The manifest and the data files are kept across retries, and only the manifest list and metadata are rewritten.
  - A failed hint write is reported as an error that says the commit itself succeeded. The reader now rolls forward past a stale hint, as iceberg-java does.
- **`ObjectStore.putIfAbsent(uri, bytes): Boolean`** (and `putIfAbsentAsync`), false when the object exists. Implemented for S3, GCS, Azure, the memory store, and local files (exclusive create). It's useful beyond Iceberg, for example for the Delta commits that come later.
- **Schema and field IDs**:
  - `ParquetField` gains an optional `fieldId`, read from footers and written to them.
  - A new table assigns IDs in schema order.
  - An append maps the write schema onto the table's current schema by name. It requires the same Iceberg types and allows required data into optional columns, and table columns left out read as null. Extra columns and type changes fail with the column named. Schema evolution is later work.
- **Type mapping**: boolean, int (INT32 and its 8- and 16-bit annotations), long, float, double, date, time (micros), timestamp and timestamptz (micros), string, binary, uuid, fixed, decimal, and nested struct, list, and map. Types Iceberg v2 can't hold fail with the column named: unsigned ints, millisecond and nanosecond timestamps, and INT96.
- **Partitioning**: identity partitions from `withPartitionBy` on STRING, INT32, INT64, and DATE columns.
  - Files go to `data/<key>=<value>/` and keep the partition columns, as Iceberg writers do. Partition values in the manifest are typed, and an empty string stays an empty string.
  - An append without `withPartitionBy` uses the table's spec. One that names different columns fails.
  - Tables whose spec uses other transforms (day, bucket, truncate) are refused for appends. Those transforms are later work.
- **Manifests**:
  - A small private `Avro` writer, beside the reader, writes manifests and manifest lists with Iceberg's schemas (field IDs on Avro fields, the spec's partition struct) and the `null` codec, since Uni has no deflate compressor and every Avro reader supports `null`.
  - Each data file entry carries record count, size, column sizes, value counts, null counts, and lower and upper bounds. Bounds come from the Parquet row-group stats where the encodings agree: ints, longs, dates, times, timestamps, strings, binary, fixed, and booleans. Float, double, decimal, and UUID bounds are left out for now.
  - The manifest list keeps the parent's manifests unchanged and adds the new one, a fast append. The snapshot summary has the added files, rows, and sizes, plus totals.
- **Sync and async share one commit routine** written as steps (fetch, put, put-if-absent), like the reader's `Step`. Scala.js and Native get async commits with no thread.
- **Tests**:
  - Round trips through `Parquet.dataset`: create, appends, time travel, partitions, nested types, and stats.
  - Appends onto the checked-in pyiceberg Hadoop-style fixtures.
  - Concurrent-commit retry and conflict on `MemoryObjectStore`.
  - Refusals: catalog tables, v3, Delta, overwrite modes, schema mismatch, and unsupported types.
  - `check_uni_tables.py` reads uni-written tables with pyiceberg, fastavro, and DuckDB. It runs locally, and the PR records its output.
  - `putIfAbsent` and an Iceberg append run against versitygw, fake-gcs-server, and Azurite.
- **Docs**: a "Writing Iceberg tables" section in `docs/text/parquet.md`, plus the AI-agent page row, the skill reference with a version bump, and an ADR.

## Not covered (later)

- Overwrite and dynamic partition overwrite, deletes, and compaction.
- Transforms other than identity (day, hour, month, year, bucket, truncate).
- Schema evolution on write, and catalog clients (REST, Glue).
- Snapshot expiry, and float, double, and decimal bounds.

## Alternatives and Why Not?

- **Write into catalog tables by adding a metadata file**: the catalog wouldn't see it, so the write would vanish or fork the table.
- **iceberg-java's rename commit**: not atomic on object stores, and Uni has no rename there. A conditional put is atomic on all of them.
- **Drop partition columns from files, as Hive does**: Iceberg readers that don't fill identity values from the manifest would read nulls. Keeping the columns costs little, thanks to dictionary encoding.
- **Overwrite by listing only the new manifest**: readers would be right, but expiry and incremental readers rely on DELETED entries, so overwrite waits until it can write them.
