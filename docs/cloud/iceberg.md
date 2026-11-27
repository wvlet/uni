---
description: Read, write, update, and compact Apache Iceberg tables in pure Scala, on local disks, S3, GCS, and Azure Blob, with no iceberg-java, Hadoop, or cloud SDK dependency.
---

# Iceberg Tables

`wvlet.uni.cloud` reads [Apache Iceberg](https://iceberg.apache.org/) tables of format versions 1 to 3,
and writes versions 2 and 3, on a local disk or in S3, R2, MinIO, GCS, or Azure Blob. It is written in Scala on
top of Uni's [Parquet](./parquet) reader and writer and [object stores](./storage), so it brings in
no iceberg-java, Hadoop, Avro, or cloud SDK. Spark, Trino, DuckDB, and pyiceberg read the tables
Uni writes, and Uni reads theirs.

- **Reads and appends** go through `Parquet.dataset` and `Parquet.writeDataset`, so filters,
  case classes, and column batches work as for any dataset.
- **Row deletes and updates, compaction, and schema changes** are on `Iceberg` in
  `wvlet.uni.cloud.iceberg`.

| | JVM | Scala Native | Node.js | Browsers |
|---|---|---|---|---|
| Read tables (`Parquet.dataset`, `datasetAsync`) | ✓ | ✓ | ✓ | ✓ (async) |
| Create, append, overwrite (`writeDataset`, `writeDatasetAsync`) | ✓ | ✓ | ✓ | ✓ (async) |
| Delete, update, compact, alter, expire, rename, drop (`Iceberg`) | ✓ | ✓ | ✓ | — |

Uni commits to **Hadoop tables**, which keep `metadata/vN.metadata.json` and need no catalog.
Tables that a catalog (Glue, REST, Hive, or SQL) owns can be read by passing the catalog's
current metadata file, but commits to them go through the catalog, for example with Spark, Trino,
Flink, or pyiceberg. See [When to Use Another Engine](#when-to-use-another-engine).

The examples below share this setup:

```scala
import wvlet.uni.cloud.parquet.{
  Parquet,
  ParquetDatasetOptions,
  ParquetDatasetWriteOptions,
  ParquetWriteMode
}
import wvlet.uni.weaver.Weaver
import java.time.LocalDate

case class Visit(id: Long, user: String, dt: LocalDate, country: String) derives Weaver

val day    = LocalDate.parse("2026-11-20")
val visits = Seq(Visit(1L, "aina", day, "JP"), Visit(2L, "leo", day, "US"))
```

## Reading Iceberg Tables

An Apache Iceberg table keeps its metadata under `metadata/`. Each commit writes a new
`.metadata.json` file that lists the table's snapshots, and each snapshot lists its data files
through Avro manifests. Like [Delta](./delta), deletes and overwrites leave old files on disk until the
snapshots expire. Reads and appends go through `Parquet`; row deletes and updates, compaction,
schema changes, rename, and drop are on `Iceberg` in `wvlet.uni.cloud.iceberg`.
`Parquet.dataset` notices a `metadata/` folder with `.metadata.json` files at the root and reads
only the files of the current snapshot, as Spark's `format("iceberg")` and DuckDB's `iceberg_scan`
do:

```scala
val icebergTable = Parquet.dataset("s3://my-bucket/warehouse/db/visits/")
val icebergSnapshot = Parquet.dataset(
  "s3://my-bucket/warehouse/db/visits/",
  ParquetDatasetOptions.default.withSnapshotId(4827129337210554812L) // time travel
)
// The metadata file that the catalog (Glue, REST, Hive) names as current
val catalogVersion = Parquet.dataset(
  "s3://my-bucket/warehouse/db/visits/metadata/00042-6c1b0a2e.metadata.json"
)
```

- **Which metadata file.** With `metadata/version-hint.text` (Hadoop tables), the hinted version
  is read. Otherwise the newest `.metadata.json` file is read. The table's catalog is the
  authority on which file is current, and a rollback or a failed commit can leave a newer file
  that isn't. When that matters, pass the catalog's metadata file as the root, as above. Two
  files for the newest version fail, and the error asks for that file.
- **Compressed metadata.** Metadata files that iceberg-java gzip-compresses
  (`write.metadata.compression-codec = gzip`), named `vN.gz.metadata.json`,
  `NNNNN-<uuid>.gz.metadata.json`, or the older `vN.metadata.json.gz`, are read on every
  platform. A write to such a table commits the next version as plain JSON.
- **Snapshots.** The current snapshot is read by default. `withSnapshotId(id)` reads an older one
  that the metadata still lists, and an unknown id fails with the list of snapshots. The current
  snapshot reads with the table's current schema, so a schema change shows up without a new
  snapshot; an older snapshot reads with its own schema.
- **Columns** follow the schema, in table order, and match the data files by Iceberg field ID, so
  a renamed column reads under its new name from every file. A column the table dropped is hidden,
  even where older files still hold it, and a column added later (even one reusing a dropped
  column's name) reads as null in older files, or as its `initial-default` when it has one
  (format version 3; filters see the filled value). The `int`→`long` and `float`→`double` promotions
  widen as for any dataset. Data files without field IDs are matched by name, which fails for a
  table with a renamed column or a column dropped and added again.
- **Partitions.** An identity partition on a `string`, `int`, `long`, or `date` column becomes a
  partition column, with values from the manifests, so filters on it skip files. Other
  partitions (`bucket`, `day`, `truncate`) need nothing, since the data files hold their source
  columns. The `data/dt=.../` folders that Iceberg writers create are never read as `key=value`
  partitions.
- **Moved tables.** Iceberg records absolute paths. Paths under the table's recorded location
  are read under the root you pass, so a copied or moved table reads too. Other paths must be in
  the same bucket, or on the local file system for a local table.
- **Format versions** 1, 2, and 3 are read. Version 3's `timestamp_ns` and `timestamptz_ns`
  columns read as nanosecond timestamps, and an `unknown` column reads as nulls. `variant`
  columns, shredded or not, read as Scala values, and `geometry` and `geography` columns as WKB
  bytes (see [Variant and Geospatial Columns](./parquet#variant-and-geospatial-columns)). Row lineage
  fields are kept in the metadata but not read as columns.
- **Row deletes.** Merge-on-read deletes are applied: position-delete files (format version 2)
  and deletion vectors (format version 3, Puffin files) remove rows by their position in a data
  file, for the data files they are newer than. Equality-delete files, which streaming writers
  such as Flink use for upserts, remove the rows whose key columns equal one of their rows (null
  equals null), in data files strictly older than the delete and, for a partitioned delete, in
  the same partition. Deletes are read when the dataset opens, a row filter sees only the rows
  that remain, and key columns are read even when `withColumns` leaves them out. Uni writes
  position deletes and deletion vectors with
  [`Iceberg.deleteRows`](#deleting-rows-merge-on-read) and
  [`Iceberg.updateRows`](#updating-rows-merge-on-read).
- **What fails** when the dataset opens, with the reason: equality deletes keyed on a nested
  or non-primitive column, an `initial-default` on a field inside a struct, encrypted tables,
  and data files that aren't Parquet. The
  manifest Avro codecs `null`, `deflate`, `snappy`, and `zstandard` are read.
- **Encrypted tables** (`encryption-keys` in the metadata, set up through `encryption.key-id`)
  aren't read or written, and neither are encrypted Parquet files. Reading them needs a key
  management service to unwrap keys, AES-GCM for manifests, and Parquet Modular Encryption for
  data files, which Uni can't offer on every platform with its minimal dependencies. Read these
  tables with an engine built on iceberg-java, such as Spark, Trino, or Flink.
- **Properties.** `ds.tableProperties` holds the table's properties.
- **Format.** `ParquetTableFormat.Iceberg` fails when the root has no metadata and lists only
  `metadata/`. A root with both `_delta_log/` and Iceberg metadata (Delta UniForm) reads as Delta.
  `withSnapshotId` needs an Iceberg table, and `withVersion` a Delta table.

## Writing Iceberg Tables

`withTableFormat(ParquetTableFormat.Iceberg)` writes the rows as an Apache Iceberg table that
Spark, Trino, DuckDB, pyiceberg, and `Parquet.dataset` read: the first write creates the table,
and each `Append` commits a new snapshot atomically.

```scala
import wvlet.uni.cloud.parquet.ParquetTableFormat

val asIceberg = ParquetDatasetWriteOptions
  .default
  .withTableFormat(ParquetTableFormat.Iceberg)
  .withPartitionBy("dt")

Parquet.writeDataset("s3://my-bucket/warehouse/visits/", visits, asIceberg) // creates the table
Parquet.writeDataset(
  "s3://my-bucket/warehouse/visits/",
  visits.take(1),
  asIceberg.withMode(ParquetWriteMode.Append) // adds a snapshot
)
```

- **Layout.** Data files go under `data/` (`data/dt=2026-11-20/part-....parquet`) and keep every
  column, including the partition columns, with Iceberg field IDs. Each commit writes a manifest
  and a manifest list (Avro) under `metadata/`, then `metadata/vN.metadata.json`, then
  `version-hint.text`.
- **Format versions.** New tables are format version 2, which every engine reads.
  `withTableProperties(Map("format-version" -> "3"))` creates a version 3 table instead, or
  upgrades a version 2 table in the commit (a version never goes down);
  `Iceberg.alterTable(root, IcebergTableChange.SetProperty("format-version", "3"))`
  upgrades without writing rows. Commits to a version 3
  table keep row lineage: each snapshot records `first-row-id` and `added-rows`, data manifests
  get their first row IDs, and a rewritten manifest writes out the IDs its files inherited, so a
  row keeps its ID. Alter, expire, and rename keep the version 3 fields.
- **Atomic commits.** The new `vN.metadata.json` is created with `ObjectStore.putIfAbsent` (S3
  `If-None-Match: *`, GCS `ifGenerationMatch=0`, Azure `If-None-Match: *`; an exclusive create on a
  local disk), so of two concurrent writers only one commits each version. The other reloads the
  table and retries on top of it, up to 4 times, as long as the schema and partition spec stayed
  the same. Readers see either the old snapshot or the new one, never part of a write. A failed
  write deletes its data files and manifests, and the table keeps its previous snapshot.
- **Modes.** `ErrorIfExists` (the default) creates the table and fails if one exists. `Append` adds
  a snapshot, creating the table when there is none. `Overwrite` commits a snapshot that replaces
  every row (an empty write empties the table), creating the table when there is none; earlier
  snapshots stay readable. `OverwritePartitions` fails for now. Under the default `ParquetTableFormat.Auto`, a write to a root that is already an Iceberg
  table goes to the table, so an append lands in a snapshot rather than in files no reader sees. A
  root that is a Delta Lake table fails, since Uni doesn't write Delta yet. `Files` writes plain
  files whatever the root holds.
- **Schemas.** A new table takes the rows' schema, with field IDs assigned as Iceberg's Java
  library does. An append matches columns to the table's current schema by name. It needs the same
  Iceberg types, and table columns left out read as null. Extra columns (add them with
  `Iceberg.alterTable` first), type changes, and optional data for a required column fail
  with the column named. An `Overwrite` whose rows don't fit the schema commits a new schema with
  new field IDs; it keeps the partition columns if the new schema has them all, and is
  unpartitioned otherwise, unless `withPartitionBy` says. Nanosecond timestamps
  (`timestamp_ns`, `timestamptz_ns`) need a version 3 table. Types Iceberg can't hold fail
  too: unsigned integers, millisecond timestamps, and INT96.
- **Partitions** are identity partitions on string, int, long, and date columns. An append without
  `withPartitionBy` uses the table's spec. An empty string and null are different partition values,
  as Iceberg keeps them apart. Tables partitioned by other transforms (`day`, `bucket`,
  `truncate`) can be read but not appended to yet.
- **Statistics.** Each data file's manifest entry records its row count, size, and per-column
  sizes, value counts, null counts, and lower and upper bounds (except for float, double,
  decimal, and UUID columns), so engines can skip files.
- **Which tables.** Uni commits to Hadoop tables, which keep `metadata/vN.metadata.json` and need
  no catalog. A table that a catalog owns (Glue, REST, Hive, or SQL; its metadata files are named
  `00003-<uuid>.metadata.json`) fails, because the catalog would keep pointing at the old version.
  Commit to such a table through its catalog. Appends to format version 1 tables fail as
  well.
- **Properties.** `withTableProperties(Map(...))` sets table properties in the commit, keeping
  the others.

## Deleting Rows (Merge-on-Read)

`Iceberg.deleteRows` deletes the rows a filter matches without rewriting data files: one
`delete` snapshot adds delete files that say which rows of which files are gone, as DuckDB and
Trino do.

```scala
import wvlet.uni.cloud.iceberg.Iceberg
import wvlet.uni.cloud.parquet.ParquetFilter

val deleted = Iceberg.deleteRows(
  "s3://my-bucket/warehouse/visits/",
  ParquetFilter.col("user") === "bot" && ParquetFilter.col("dt") === LocalDate.of(2026, 11, 20)
)
```

- **What it writes.** A format version 3 table gets deletion vectors: one Puffin file per commit,
  with one vector per data file. A vector holds the file's earlier deletes as well, so the vectors
  and file-scoped position-delete files it replaces are marked `DELETED`; a partition-scoped
  position-delete file stays. A version 2 table gets a position-delete file per data file, next to
  it, which names it in `referenced_data_file`. Both are the delete files the Iceberg spec
  defines, and DuckDB reads the ones Uni writes.
- **Which rows.** The filter is the one reads take, checked against the table's current schema.
  Files are skipped by partition values and column statistics, only the filter's columns are read,
  and rows that earlier deletes or equality deletes removed aren't counted again. It returns the
  number of rows deleted; when none match, nothing is committed.
- **Commits.** The positions are found in the current snapshot and committed on top of it. If
  another writer commits first, the positions are found again on the new snapshot and the commit
  retried, up to 4 times, so no other writer's deletes are lost. A failed delete removes the
  files it wrote.
- **Afterwards.** Readers stop seeing the rows; time travel to an earlier snapshot still shows
  them, until `Iceberg.expireSnapshots` deletes the files only expired snapshots need. The delete
  always writes delete files, whatever the table's `write.delete.mode` says; to rewrite files
  instead, use copy-on-write below. Readers apply each delete file until
  [compaction](#compacting-tables) rewrites the files and removes it. Tables that a catalog owns
  and format version 1 tables fail, as for appends.

## Updating Rows (Merge-on-Read)

`Iceberg.updateRows` changes the rows a filter matches without rewriting the data files
that hold them. One `overwrite` snapshot adds new data files with the new rows and delete files
for the old ones, as DuckDB and Trino do.

```scala
val updated = Iceberg.updateRows(
  "s3://my-bucket/warehouse/visits/",
  ParquetFilter.col("user") === "bot"
)(row => row.updated("user", "masked"))

// The same update on case classes
val updatedVisits = Iceberg.updateRows[Visit](
  "s3://my-bucket/warehouse/visits/",
  ParquetFilter.col("user") === "bot"
)(visit => visit.copy(user = "masked"))
```

- **Rows in, rows out.** The function gets each matching row as a `Map[String, Any]`, in the
  shape `readRows` gives, and returns its new version. With a type argument, it gets and returns
  a case class instead, read as `readAs` reads it and written as `writeDataset` writes it; table
  columns the class leaves out are written as null. The new row must fit the table's current
  schema, as an appended row must. A row whose partition column changes moves to its new
  partition. A row that doesn't fit fails the update, and nothing is committed.
- **What it writes.** The old rows are deleted as [`Iceberg.deleteRows`](#deleting-rows-merge-on-read)
  deletes them: deletion vectors in a format version 3 table, position-delete files in version
  2. The new rows go into new data files, written with the table's current schema and partition
  spec. The other data files stay as they are. The call returns the number of rows updated, and
  when no row matches, nothing is committed.
- **Row lineage.** In a version 3 table an updated row keeps its row ID: the new files store
  each row's old `_row_id` and leave `_last_updated_sequence_number` null, so the row takes the
  update's sequence number. Reads don't show these columns. A table upgraded to version 3 that
  has no commit since then has no row IDs yet, so its updated rows get new ones.
- **Commits.** The rows are read from the current snapshot. If another writer commits first and
  left the update's rows alone, the update keeps the data files it wrote and plans only its
  delete files again on the new snapshot. That holds when the schema, partition spec, and format
  version are the same, every touched data file is still live with the same deletes, and no
  file the filter may match was added. Otherwise the update deletes the files it wrote, reads
  the rows again, and calls the function again. Either way it makes up to 4 attempts, so the
  function must have no side effects. Tables
  partitioned by transforms Uni can't write (`day`, `bucket`) can't take updates, though they
  can take deletes.

## Replacing Data Files (Copy-on-Write)

An update or delete that should leave no delete files rewrites only the data files it touches. Read them one at a time with
`ds.withFiles`, write the rows to keep, and commit them with `withReplacedFiles`: one snapshot
removes the old files and adds the new ones.

```scala
val visitsRoot = "s3://my-bucket/warehouse/visits/"
val visitsDs   = Parquet.dataset(visitsRoot)
val touched    = visitsDs.files.filter(_.partitions("dt") == LocalDate.of(2026, 11, 20))
val kept       =
  try visitsDs.withFiles(touched).readAs[Visit]().filterNot(_.user == "bot").toSeq
  finally visitsDs.close()
Parquet.writeDataset(
  visitsRoot,
  kept,
  ParquetDatasetWriteOptions
    .default
    .withMode(ParquetWriteMode.Overwrite)
    .withReplacedFiles(touched.map(_.path))
)
```

The rewritten manifests list the other files as `EXISTING` and the removed ones as `DELETED`, as
Iceberg's Java library does. Writing no rows only removes the files (a `delete` snapshot). The rows
must fit the table schema. A file that is no longer live fails the commit; if another writer
removed one while this write ran, the commit fails instead of bringing the file's rows back.
Delete files of the removed files stay live until a [compaction](#compacting-tables) removes them.

## Reading Data Files Directly

A reader that splits a table into its files' row groups (to read them in parallel, say) can read a data file with `Parquet.open` when `ds.readsAsPlainFile(file)` is true. That holds when no delete removes rows of the file, its columns have the table's names (no column was renamed or dropped since it was written), no column it lacks has an `initial-default`, and it holds every partition column. Columns the file lacks read as nulls through the dataset, and narrower types are widened. Read the other files with `ds.withFiles(Seq(file))`, which applies their deletes and column mapping.

```scala skip
val ds = Parquet.dataset("warehouse/visits/")
val (plain, mapped) = ds.files.partition(ds.readsAsPlainFile)
```

## Compacting Tables

Every merge-on-read delete adds files that readers apply, and every small append adds a data file.
`Iceberg.compactTable` rewrites the data files that deletes apply to, with the deletes
applied, merges small files, and removes the delete files no remaining data file needs, in one
`replace` snapshot. The rows don't change.

```scala
import wvlet.uni.cloud.iceberg.IcebergCompactionOptions

val compacted = Iceberg.compactTable("s3://my-bucket/warehouse/visits/")
// IcebergCompaction(removedDataFiles, addedDataFiles, removedDeleteFiles, rows)

// Only the files of one day, cut at 128 MiB, without merging small files that have no deletes
Iceberg.compactTable(
  "s3://my-bucket/warehouse/visits/",
  IcebergCompactionOptions
    .default
    .withFilter(ParquetFilter.col("dt") === LocalDate.of(2026, 11, 20))
    .withTargetFileSize(128L * 1024 * 1024)
    .noFileMerging()
)
```

- **Which files.** Files are grouped by partition spec and partition value. In a group, every file
  that deletes apply to is rewritten (`withMinDeleteRatio(0.3)` rewrites only files with at least
  that fraction of rows deleted), together with the group's small files (below 75% of the target
  size, `withMinFileSize`). A group without deletes is merged only when it has `withMinInputFiles`
  small files (5). New files are cut at the target size: `withTargetFileSize`, else the table's
  `write.target-file-size-bytes`, else 512 MiB. `withFilter` limits the rewrite to the files a
  filter may match, by partition values and column statistics.
- **Delete files.** After the rewrite, a deletion vector, position-delete file, or equality-delete
  file is removed when no remaining data file can match it: its data file was rewritten, its
  partition was, or (for equality deletes) no older file is left in its scope. Delete files that
  copy-on-write rewrites left behind are removed too, even when no data file needs a rewrite.
- **Partitions and schemas.** Each new file keeps its group's partition spec and partition value,
  so tables with bucket or day partitions compact too. Rows are read with the current schema, and
  the new files carry its field IDs.
- **Row lineage.** In a format version 3 table, the new files store each row's `_row_id` and
  `_last_updated_sequence_number`, so row IDs survive the rewrite, as the Iceberg spec requires.
  DuckDB reads the same `_row_id` values before and after.
- **Commits.** The new files keep the data sequence number of the snapshot the compaction read, as
  iceberg-java's rewrites do, so equality deletes committed meanwhile still apply to them. If
  another writer commits first, the compaction rebases on it when that commit didn't touch the
  rewritten files (no new position delete or deletion vector on them) or the delete files it
  removes; otherwise it plans and rewrites again, up to 4 times. A failed compaction deletes the
  files it wrote. The old files stay for time travel until `Iceberg.expireSnapshots` deletes them.

## Changing and Dropping Tables

```scala
import wvlet.uni.cloud.iceberg.IcebergTableChange
import wvlet.uni.cloud.parquet.{ParquetColumn, ParquetType}
import wvlet.uni.cloud.iceberg.IcebergTableChange.*
import wvlet.uni.cloud.parquet.format.Repetition

Iceberg.alterTable(
  visitsRoot,
  AddColumn(ParquetColumn("country", ParquetType.STRING, Repetition.OPTIONAL)),
  RenameColumn("item", "product"),
  WidenColumn("qty", ParquetType.INT64),
  SetProperty("owner", "data-team")
)
// Keep only the newest 5 snapshots, deleting the files only older ones reference
Iceberg.expireSnapshots(visitsRoot, retainLast = 5)
// Move a local table to another folder, keeping its current snapshot
Iceberg.renameTable("warehouse/visits", "warehouse/visits_2026")
Iceberg.dropTable(visitsRoot)
```

- `Iceberg.alterTable` applies the changes in order and commits them as one new metadata version,
  with one new schema when a change touches the schema. No data file is rewritten and no snapshot
  is added. Losing the commit race reloads the table and applies the changes again.
- Changes: `AddColumn` (optional columns only, null in existing rows), `DropColumn` (not a column
  the current partition spec uses), `RenameColumn` (keeps the field ID), `WidenColumn` (int to
  long, float to double, a decimal to a larger precision with the same scale), `MakeOptional`,
  `SetProperty`, and `RemoveProperty`. Each works on top-level columns.
- `Iceberg.expireSnapshots` keeps the newest `retainLast` snapshots (by commit time), the current
  one, and those a branch or tag references, and commits the rest away in a new metadata version.
  Only after that commit does it delete the files that just the expired snapshots reference
  (manifest lists, manifests, data files), and the metadata files written before the oldest kept
  snapshot. A file a kept snapshot lists stays, and nothing outside the table folder is deleted. It
  returns the number of expired snapshots; time travel to them stops working.
- `Iceberg.renameTable` moves a local table's folder, then writes the current snapshot's manifests
  and manifest list again with the new paths and commits a metadata version with the new
  `location`, so other engines read the moved table. Older snapshots are dropped, and their files
  and the older metadata versions are deleted. A data file stored outside the table folder keeps its
  recorded path. Before moving anything, it refuses a target that exists, object stores, tables
  with branches or tags other than `main` (their snapshots would be lost), and tables that set
  `write.data.path` or `write.metadata.path`. Deletion vectors are pointed at the moved data
  files. Position-delete files name data files by path in their rows, so each one is written
  again next to the old one, with the moved paths, and the old one is deleted. A position-delete
  file that isn't Parquet, or that has columns besides `file_path` and `pos` (the optional `row`),
  is refused before moving; compact the table first.
- `Iceberg.dropTable` deletes the hint and metadata files first, so a drop that fails partway leaves
  no readable table, then every file under the root. It fails when the root is not an Iceberg
  table.

## When to Use Another Engine

[Apache Spark](https://spark.apache.org/), [Trino](https://trino.io/),
[Apache Flink](https://flink.apache.org/), and [pyiceberg](https://py.iceberg.apache.org/) are built
on the Iceberg reference libraries and support every catalog, partition transform, and table
feature. They are good choices for tables a catalog owns, for large rewrites across a cluster, and
for encrypted tables. [DuckDB](https://duckdb.org/) queries Iceberg tables with SQL. Uni fits
alongside them: an application, service, CLI, or browser page that reads or appends to a table
from Scala, on any platform, with no extra dependencies.

What Uni doesn't do yet:

- Commits to catalog-owned tables (`NNNNN-<uuid>.metadata.json`) and to format version 1 tables.
- Writes to tables partitioned by `day`, `bucket`, `truncate`, or other transforms (they read and
  compact; identity partitions on string, int, long, and date columns write).
- `OverwritePartitions`, and encrypted tables.
