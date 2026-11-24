---
description: Read Delta Lake tables in pure Scala on the JVM, Scala.js, and Scala Native, from a local folder, S3, GCS, or Azure Blob, with time travel and typed partitions.
---

# Delta Lake Tables

`Parquet.dataset` in `wvlet.uni.cloud.parquet` reads [Delta Lake](https://delta.io/) tables from a
local folder or from S3, R2, MinIO, GCS, or Azure Blob, with no Spark, Hadoop, or delta-kernel
dependency. Delta tables are read-only in Uni: write them with Spark, delta-rs, or DuckDB, and read
them from any Uni platform. On Scala.js, including browsers, use `Parquet.datasetAsync` and
`readAllAsync`, as for any [dataset](./parquet#reading-a-dataset-of-files).

Everything a dataset does applies to a Delta table too: [filters](./parquet#filtering-rows), column
selection, and reading into case classes, maps, or column batches.

## Reading a Table

A Delta Lake table is a folder of Parquet files plus a `_delta_log/` folder that records which
files each version of the table has. Deletes, updates, and overwrites leave the old files on disk
until a vacuum, so reading every file would return removed rows. `Parquet.dataset` notices
`_delta_log/` at the root and reads only the table's live files, as Spark's `format("delta")` and
DuckDB's `delta_scan` do:

```scala
import wvlet.uni.cloud.parquet.{Parquet, ParquetDatasetOptions, ParquetTableFormat}

val latest = Parquet.dataset("s3://my-bucket/events/") // the latest version
val v12 = Parquet.dataset(
  "s3://my-bucket/events/",
  ParquetDatasetOptions.default.withVersion(12)         // time travel
)
val rawFiles = Parquet.dataset(
  "s3://my-bucket/events/",
  ParquetDatasetOptions.default.withTableFormat(ParquetTableFormat.Files)
)
```

- **Partitions** come from the log, typed by the table schema: `string`, `byte`, `short`,
  `integer`, `long`, and `date` partition columns read as STRING, INT32, INT64, and DATE. Other
  partition types fail with a hint to read them as strings with
  `withPartitionType(key, ParquetType.STRING)`. Filters on partition columns skip files as for any
  dataset.
- **Versions.** The reader starts from the newest checkpoint at or before the version, then
  replays the later commits. A version is readable while the log still has a checkpoint at or
  before it and every commit after that; otherwise the error names the readable range.
- **Reader features** that change what a file means fail when the dataset opens, naming the
  feature: deletion vectors, column mapping, and v2 checkpoints. `timestampNtz`,
  `vacuumProtocolCheck`, and type widening are read.
- **A table without files** reads as an empty dataset with the schema from the log.
- **Format.** `withTableFormat(ParquetTableFormat.Files)` reads every Parquet file under a table
  root as plain files. `ParquetTableFormat.Delta` fails when the root has no log, and lists only
  `_delta_log/`, which skips listing old files that a vacuum hasn't removed yet.
  `withVersion` and `ParquetTableFormat.Delta` need the table's root, not a glob or a list of files.

## What Is Not Supported

- **Writes.** Uni doesn't write Delta tables yet. A write to a Delta root fails rather than adding
  files no Delta reader would see. To write a table Uni and other engines share, use
  [Iceberg](./iceberg).
- **Deletion vectors, column mapping, and v2 checkpoints** fail when the dataset opens, by name.
  Spark, delta-rs, and DuckDB read these tables.
- **Delta UniForm.** A root with both `_delta_log/` and Iceberg metadata reads as Delta.
