# Parquet datasets: a folder or prefix of files read as one table

- Status: Accepted
- Date: 2026-11-21
- Plan: `plans/2026-11-21-parquet-datasets.md`

## Context

Spark, Hive, Trino, DuckDB, and Athena write tables as many files under a prefix, usually split
into `key=value` folders. uni-parquet and `ObjectStore` could open one file at a time, so every
caller had to rewrite listing, skip rules, partition parsing, and pruning, and `Parquet.openS3`
built a new store for each file.

## Decision

- **`Parquet.dataset(root)`** lists a local directory or an `s3://`, `gs://`, `az://`, or
  `azure://` prefix and returns an `AutoCloseable` `ParquetDataset`. One store serves every file.
  It is created from the scheme's default config and closed with the dataset. A store passed with
  `Parquet.dataset(store, root)` is shared and left open. `datasetAsync` lists asynchronously.
- **Dataset settings live in `ParquetDatasetOptions`** (partition types, file extension, footer
  prefetch). Per-read settings stay in `ParquetReadOptions`, so the same columns, filter, and batch
  size work for one file and for a dataset.
- **Discovery.** The root always gets a trailing `/`, because object listings match raw key
  prefixes, so `events` would otherwise also match `events_old/`. Every file below the root is
  kept, unless a folder or file name on its path starts with `_` or `.`. Hive and Trino files with
  no extension are therefore read, and `_SUCCESS`, `_temporary/`, and `.crc` files are skipped. The
  extension filter is opt-in. Files are sorted by path. An empty listing is an error, because it is
  almost always a wrong prefix.
- **Partition values are STRING unless declared** (`withPartitionType`: INT32, INT64, or DATE).
  Spark and DuckDB guess types from the values, so a single `hour=ab` folder silently changes a
  key's type, which breaks filters and case-class fields far from the cause. Declared types never
  change with the data, and ISO dates already compare correctly as strings. Values are
  `%XX`-decoded. `__HIVE_DEFAULT_PARTITION__` is null. If a file stores a column with a partition
  key's name, the folder value wins. Folders with different keys are an error.
- **One filter for files and row groups.** `RowGroupFilter` leaves on partition columns are
  evaluated exactly against each file's values, and the tree is simplified per file. A file is
  dropped when the result is false. Otherwise the remaining tree is checked against row-group
  statistics as before. A literal is converted to the partition's type only when that is lossless
  (`"2026"` → INT64, an ISO string → DATE). Otherwise it is an error, rather than a silent "may
  match" that reads every file. Comparisons with a null partition are false, as in SQL.
- **The schema is the by-name union over the selected files.** Using the first file's schema would
  drop columns added later, because path order puts the oldest `dt=` first. A top-level column that
  some file lacks becomes OPTIONAL and reads as all-null vectors, which are built in the shapes the
  reader's `Assembly` produces, so `readAs`, `readRows`, and `readBatches` need no special cases. A
  type conflict on a selected column names both files. An unselected column never conflicts.
  Unknown column names in `withColumns` or the filter fail once at the dataset level. A single
  `ParquetFile` keeps its behavior of ignoring unknown filter columns.
- **Partition columns are constant vectors** appended to each batch after the file columns.
- **Reading.** Building the plan reads the footers of the selected files, and each footer is cached
  and reused when the file is read. Sync reads return iterators that open one file at a time and
  close it at its end. `close()` also closes a file that an unfinished iterator left open. Async
  reads fetch footers with `RxParallel.parSequenceN(prefetch)` and emit rows in file order.

## Consequences

- A read with many files waits for all of their footers before the first row. Both paths fetch
  `withPrefetch` footers at a time: sync reads on a short-lived thread pool (JVM and Scala Native;
  Scala.js stays sequential), async reads with `RxParallel.parSequenceN`.
- `selectFiles` reflects partition pruning only. A file whose row groups are all skipped by
  statistics is still listed, though it yields no rows.
- Not covered: `_metadata` summary files, Delta Lake and Iceberg,
  type widening across files, and the npm facade.
- Writing partitioned datasets is covered by `2026-11-21-parquet-dataset-writer.md`.
- **Globs and file lists (added later).** A root with glob characters lists only the part before
  the first glob segment and keeps a path when it or one of its folders matches, so a folder
  pattern such as `dt=2026-11-*` keeps everything below it, as a plain root does. Patterns compile
  to regexes that escape only regex syntax characters, because identity escapes such as `\=` are
  invalid in JavaScript's unicode mode. An explicit `Seq` of files must be all local or all in one
  bucket. Partitions come from the folders below the files' common parent, and listed files skip
  the `_`/`.` rule and the listing request. Object sizes are then -1.
