# Read Parquet datasets: a directory or bucket prefix of files

## Goals

- Read every Parquet file under a local directory or an S3, GCS, or Azure prefix with one call, as rows, case classes, JSON, or column batches. Today each file has to be listed and opened by hand.
- Read Hive-style partition folders (`dt=2026-11-20/country=JP/part-0.parquet`) as columns. Skip whole files whose partition values can't match a filter, before any byte of them is fetched.
- Keep it pure Scala on every platform, with no Hadoop, Arrow, or cloud SDK. Together, uni-parquet and `ObjectStore` become a small reader for data lakes that runs on the JVM, Node.js, and Scala Native.

## Background

- Data written by Spark, Hive, Trino, DuckDB, or Athena lands as many files under a prefix, usually split into `key=value` folders. One file alone is rarely the whole dataset. Not every writer names files `*.parquet`: Hive writes `000000_0`, and Trino writes files with no extension.
- What's there now:
  - `Parquet.openS3`/`openGcs`/`openAzure` open one object, and each builds and owns its own `ObjectStore` (HTTP client and credential cache).
  - `ObjectStore.list` lists recursively by raw key prefix, so `s3://b/events` also matches `events_old/`.
  - `FileSystem.list` lists only one level by default and hides dot files.
- `ParquetReadOptions` (per file) already has column selection and `RowGroupFilter`, which skips row groups by min/max statistics. Today a filter on an unknown column is ignored without a word, while an unknown name in `withColumns` throws.
- `RowGroupFilter` is a small expression tree (`col("x") >= v && ...`) that only answers "may match". Partition values give it an exact answer per file, so the same expressions can drive file pruning.
- Other tools set the conventions users expect: pyarrow.dataset, DuckDB `read_parquet(..., hive_partitioning = true)`, Spark. They skip `_SUCCESS`, `_temporary/`, and dot or underscore paths, treat `__HIVE_DEFAULT_PARTITION__` as null, and match columns across files by name. Spark and DuckDB also guess partition types, including DATE for `dt=2026-11-20`. That guess changes with the data: a single `hour=ab` folder turns the whole key into a string.

## Design

Lane: Standard. It adds a new public API to uni-parquet and new docs (the Parquet page, `for-ai-agents.md`, and the uni plugin skill).

User flow:

- A Scala user on a laptop, server, or Node.js script has events in `s3://bucket/events/` (or `./events/`) and wants this week's JP rows as `Event` case classes.
- `Parquet.dataset("s3://bucket/events/")` lists the prefix and returns a `ParquetDataset`. `dataset.files`, `dataset.partitionColumns`, and `dataset.schema` show what was found, so the user can check before reading any rows.
- `dataset.readAs[Event](options)` with `col("dt") >= "2026-11-15" && col("country") === "JP"` returns an `Iterator[Event]` (`readAllAsync` collects a `Seq` asynchronously). Partition columns fill case-class fields like any other column.
- To check that pruning worked, `dataset.selectFiles(options)` lists the files that would be read. A misspelled column name in a filter or column selection fails before any rows are read, with the list of known columns.
- Failures name the file: a corrupt or unreadable file fails the read with its URI. Nothing is written, so there is nothing to undo.
- `dataset.close()` (it is `AutoCloseable`) releases the store, but only if the dataset created it. A store the caller passed in is left open.

Decisions:

- **Entry point picks the backend by scheme**: `s3://`, `gs://`, `az://` or `azure://`, or a local path. An overload takes an `ObjectStore` for R2, MinIO, custom credentials, or a store the caller already has. One store is shared by every file, rather than one per file as the single-file `openS3` does.
- **Dataset options** live in a new `ParquetDatasetOptions`, next to the per-file `ParquetReadOptions`. It holds partition types, the file filter, and prefetch, with `withXXX`/`noXXX` for each, per AGENTS.md.
- **File discovery**:
  - The root always gets a trailing `/`, so sibling prefixes never match.
  - Listing is recursive on every backend.
  - Every path below the root is kept, unless a folder or file name in it starts with `_` or `.`. So Hive and Trino files with no extension are kept, and `_temporary/`, `.spark-staging-*`, and `_SUCCESS` are skipped.
  - An optional extension filter (e.g. `.parquet`) is available for mixed folders.
  - Files are sorted by path, so reads are repeatable. An empty listing is an error, since it usually means a wrong prefix.
- **Partitions**:
  - `key=value` folder names below the root become columns, appended after the file's columns.
  - The values are added to every batch as constant vectors, so `readAs`, `readRows`, `readJson`, and `readBatches` all see them with no special cases.
  - Values are URL-decoded, and `__HIVE_DEFAULT_PARTITION__` becomes null.
  - A file whose folders don't have the same keys as the others fails discovery with a clear error.
  - If a file also stores a column with a partition key's name, the folder value wins and the file's column is not read.
- **Partition types (proposed default)**: every partition is STRING unless the user declares a type with `withPartitionType("year", ParquetType.INT64)`. Declared types cover INT64, DATE, and STRING. The type never changes with the data, and ISO dates compare correctly as text. Pick a different default on the card if you prefer.
- **Pruning**:
  - One `RowGroupFilter` covers both kinds of column. Partition columns are checked exactly against each file's values, which drops files before they are opened. File columns are then checked against row-group statistics, as today.
  - A literal whose type doesn't match a partition column is converted when that's lossless (`"2026"` → INT64), or fails with the column's type in the message. It never silently keeps every file.
  - Rows are still not filtered, as with `RowGroupFilter` today.
  - The column-name check happens once, at the dataset level. A single `ParquetFile` keeps today's behavior.
- **Schema across files**:
  - Columns are matched by name. The dataset schema is the union, by name, of the footers of the files that pass partition pruning, plus the partition columns. A column added in newer files therefore shows up.
  - Each file reads only the selected columns it has, and missing ones are filled with all-null vectors built from the dataset schema. This uses the same constant-vector path as partition columns.
  - A column whose type differs between files is an error that names both files. Merging types is out of scope.
  - When every file is pruned, the schema has only the partition columns and reads return nothing.
  - Building the union reads every selected file's footer before the first row. That's one small ranged read per file, prefetched on the async path.
- **Reading order and concurrency**:
  - Rows come in file order, then row-group order.
  - The sync path reads one file at a time and closes each before the next, so the Iterator APIs hold one row group in memory.
  - The async path (`readAllAsync`, `readBatchesAsync`) fetches footers several at a time (bounded, 4 by default) to hide object-store latency, and still emits rows in order.
- **Platforms**: the sync API runs where file and object reads are sync today (JVM, Native, Node.js). The async API works wherever `ObjectStore`'s async methods do.
- **Out of scope, for later PRs**: writing partitioned datasets, `_metadata` summary files, Delta and Iceberg table formats, the npm facade, glob patterns, an explicit list of files, and reading files in parallel out of order.
- **Tests** run on a local directory. They cover discovery and skip rules, sibling-prefix isolation, partition parsing, nulls and declared types, pruning (with counts of files actually opened), literal conversion errors, schema union with missing columns, type-conflict errors, and store ownership on close. One S3 dataset also runs through the existing versitygw setup.

## Alternatives and Why Not?

- **Users list and open files themselves**: they can today, but each one rewrites listing, skip rules, partition parsing, and pruning, and it's easy to open one store per file.
- **A separate filter type for partitions**: users would have to know which columns come from folders. One `RowGroupFilter` covers both, as `WHERE` does in SQL engines.
- **Infer partition types like Spark and DuckDB**: familiar, but the type flips when one folder value doesn't parse, which breaks filters and case-class fields far from the cause.
- **Use the first file's schema**: cheaper, but with path-sorted `dt=` folders the first file is the oldest, so columns added later disappear.
- **Keep only `*.parquet` files**: misses Hive and Trino output. An opt-in extension filter covers mixed folders instead.
- **Read whole files in parallel by default**: out-of-order rows surprise callers, and memory grows with the number of files in flight. Prefetching footers gets most of the latency back.
