# Reading and writing Parquet files

`wvlet.uni.cloud.parquet` (in the `uni` artifact) reads and writes Apache Parquet in pure Scala
on the JVM, Scala.js, and Scala Native. It needs no parquet-java or Hadoop. The same `Parquet.dataset`
and `Parquet.writeDataset` calls read Delta Lake tables and read and write Apache Iceberg tables.

Full reference: https://wvlet.org/uni/cloud/parquet.md, https://wvlet.org/uni/cloud/iceberg.md,
https://wvlet.org/uni/cloud/delta.md

## Rows into case classes

```scala
import wvlet.uni.cloud.parquet.{Parquet, ParquetReadOptions}
import wvlet.uni.weaver.Weaver

import java.time.Instant

case class Event(id: Long, userName: String, ts: Instant, tags: Seq[String]) derives Weaver

def readAll(path: String): Seq[Event] = Parquet.readAs[Event](path)
```

- Columns match fields by name, loosely (`user_name` fills `userName`); extra columns are ignored.
- Nullable columns need `Option[A]`; lists are `Seq[A]`, maps `Map[K, V]`, groups nested case classes.
- TIMESTAMP and INT96 decode to `Instant`, DECIMAL to `BigDecimal`, DATE and TIME to ISO-8601
  `String`, UUID to `String` or `java.util.UUID`.
- Strings from Hive or Impala lack the STRING annotation: pass
  `ParquetReadOptions.default.withBinaryAsString(true)`.

## Rows as `Map[String, Any]`, JSON, and column statistics

```scala
def explore(path: String): Unit =
  // Column name -> value, in schema column order
  val rows: Seq[Map[String, Any]] = Parquet.readRows(path) // or Parquet.readRows(bytes)
  rows.headOption.foreach(r => println(r("ts")))           // an Instant, not a string
  // One JSON object per row; .toJSON gives JSON lines
  Parquet.readJson(path).foreach(json => println(json.toJSON))
  val file = Parquet.open(path)
  try
    // Per leaf column, from the footer: numValues, numNulls, sizes, min, max
    file.columnStatistics.foreach((col, s) => println(s"${col}: ${s.min} .. ${s.max}"))
  finally
    file.close()
```

- `readRows` values have the logical type applied: `Instant`, `LocalDate`, `LocalTime`,
  `BigDecimal`, `java.util.UUID`; lists are `Seq`, maps `Map`, groups `Map[String, Any]`, nulls
  `null`. Unannotated binary is `Array[Byte]` unless `withBinaryAsString(true)`.
- A VARIANT column (shredded or not; DuckDB, Spark 4, Iceberg v3) reads as a Scala value:
  `Map[String, Any]` for objects (fields in name order), `Seq[Any]` for arrays, and `Int`, `Long`,
  `Double`, `BigDecimal`, `String`, `Instant`, and so on for primitives. GEOMETRY and GEOGRAPHY
  columns read as WKB `Array[Byte]`. Neither is written.
- Prefer `readRows` over `readAs[Map[String, Any]]`: the latter goes through Weaver, so integers
  become `Long` and DATE, DECIMAL, and UUID become strings.
- `ParquetFile` also has `readRows(options)`, `readJson(options)`, and, for browsers,
  `readRowsAsync`, `readAllRowsAsync`, and `readJsonAsync`.

## Open a file, project columns, read vectors

```scala
import wvlet.uni.cloud.parquet.ColumnVector.LongColumn

def sumIds(path: String): Long =
  val file = Parquet.open(path) // also Parquet.open(bytes: Array[Byte])
  try
    var sum = 0L
    file
      .readBatches(ParquetReadOptions.default.withColumns("id"))
      .foreach { batch =>
        batch.column("id") match
          case ids: LongColumn =>
            for i <- 0 until ids.size do
              if !ids.isNull(i) then
                sum += ids.getLong(i)
          case _ =>
      }
    sum
  finally
    file.close()
```

`readBatches` gives one batch per row group; `ParquetReadOptions.default.withBatchSize(rows)` caps
the rows per batch. A row group whose column holds over 1 GiB of values is split automatically, so
large files need no settings; `readRowGroup(i)` returns a whole row group and fails when one column
passes 2 GiB.

`file.schema`, `file.numRows`, and `file.rowGroups` (with statistics) come from the footer.
`batch.row(i)` gives the same `Map[String, Any]` as `readRows`; `vector.getValue(i)` applies the
logical type.

To open the same file many times without parsing its footer again, keep `file.metadata` and pass
it to `Parquet.open(path, metadata)` (or `ParquetFile.open(source, metadata)` for any byte
source). Uni keeps no cache: key yours on `FileSystem.info(path)`'s `size` and `lastModified`.

```scala
import wvlet.uni.cloud.parquet.ParquetFile
import wvlet.uni.cloud.parquet.format.FileMetaData

// Parse the footer once, then reopen the file without parsing it again
def openCached(path: String, cached: Option[FileMetaData]): ParquetFile =
  cached match
    case Some(footer) => Parquet.open(path, footer)
    case None         => Parquet.open(path)
```

## Over HTTP, and in browsers

```scala
import wvlet.uni.rx.Rx

def remoteEvents(url: String): Seq[Event] =
  val f = Parquet.openUrl(url) // sync client: JVM, Native, Node.js
  try f.readAs[Event]().toSeq
  finally f.close()

def remoteEventsAsync(url: String): Rx[Seq[Event]] = Parquet
  .openUrlAsync(url) // works in browsers
  .flatMap(_.readAllAsync[Event]())
```

The server must answer Range requests with 206 (and allow them through CORS in browsers).

## In S3, R2, GCS, MinIO, or Azure Blob

```scala
import wvlet.uni.cloud.storage.{ObjectStore, S3Config}

def s3Events(uri: String): Seq[Event] =
  val f = Parquet.openS3(uri) // s3://bucket/key; default AWS credential chain and region
  try f.readAs[Event]().toSeq
  finally f.close()

def r2Events(uri: String, accountId: String, keyId: String, secret: String): Seq[Event] =
  val f = Parquet.openS3(uri, S3Config.r2(accountId).withCredentials(keyId, secret))
  try f.readAs[Event]().toSeq
  finally f.close()

// gs://bucket/key with Google Application Default Credentials
def gcsEvents(uri: String): Seq[Event] =
  val f = Parquet.openGcs(uri)
  try f.readAs[Event]().toSeq
  finally f.close()

// Write: a multipart upload that completes on close; abort() discards it
def writeS3(uri: String, events: Seq[Event]): Unit =
  val store = ObjectStore.s3()
  try
    val w = Parquet.newWriter[Event](store.newOutputStream(uri))
    w.writeAll(events)
    w.close()
  finally store.close()
```

`Parquet.openS3Async(uri, config)` and `Parquet.openGcsAsync(uri, config)` read in browsers.
Azure Blob works the same way:

```scala
import wvlet.uni.cloud.storage.AzureConfig

def azureEvents(uri: String): Seq[Event] =
  val f = Parquet.openAzure(uri) // az://container/key; AZURE_STORAGE_CONNECTION_STRING or AZURE_STORAGE_ACCOUNT
  try f.readAs[Event]().toSeq
  finally f.close()

def writeAzure(uri: String, events: Seq[Event]): Unit =
  val store = ObjectStore.azure(AzureConfig.fromConnectionString(sys.env("AZURE_STORAGE_CONNECTION_STRING")))
  try
    val w = Parquet.newWriter[Event](store.newOutputStream(uri))
    w.writeAll(events)
    w.close()
  finally store.close()
```

See [storage.md](storage.md) for credentials and other stores.

## Filter rows

```scala
import wvlet.uni.cloud.parquet.ParquetFilter.col

val since2026 = ParquetReadOptions.default
  .withFilter(col("ts") >= Instant.parse("2026-01-01T00:00:00Z") && col("address.city") === "Tokyo")
```

- `withFilter` returns exactly the matching rows from `readAs`, `readRows`, `readJson`,
  `readBatches`, `readRowGroup`, and the async variants, for files and datasets. It also skips
  row groups by statistics and dataset files by partition values. Columns only the filter needs
  are read, then dropped.
- SQL nulls: a comparison with null is unknown, and only rows where the filter is true are kept.
  Match nulls with `col("x").isNull` / `isNotNull`; `=== null` throws. NaN matches no comparison
  (IEEE 754, unlike Spark and DuckDB), and `-0.0` equals `0.0`.
- Literals convert only without loss: integers (or digit strings) to INT32/INT64, `LocalDate` or
  an ISO string to DATE, `Instant` to TIMESTAMP/INT96 (exact), `String` to STRING/ENUM, numbers
  to FLOAT/DOUBLE (integers within ±2^53). `col("a.b")` names a struct field.
- An unknown column, a list or map, DECIMAL/TIME/UUID, or a wrong literal type throws
  `IllegalArgumentException` when the read starts.
- `withRowGroupFilter(f)` only skips row groups and ignores what it can't check; rows inside are
  not filtered. `RowGroupFilter` is the deprecated old name of `ParquetFilter`.

## Read a dataset: a folder or bucket prefix of files

`Parquet.dataset(root)` lists every file under a local directory or an `s3://`, `gs://`, `az://`
prefix and reads them as one table. `key=value` folders (`dt=2026-11-20/country=JP/`) become
columns after the files' own columns.

```scala
import wvlet.uni.cloud.parquet.{Parquet, ParquetDatasetOptions, ParquetReadOptions, ParquetType}
import wvlet.uni.cloud.parquet.ParquetFilter.col
import java.time.LocalDate

case class Visit(id: Long, user: String, dt: LocalDate, country: String) derives Weaver

def recentVisits(root: String): Seq[Visit] =
  val ds = Parquet.dataset(root)
  try
    val opts = ParquetReadOptions
      .default
      .withFilter(col("dt") >= "2026-11-15" && col("country") === "JP")
    ds.readAs[Visit](opts).toSeq
  finally ds.close()

def typedDataset(root: String) = Parquet.dataset(
  root,
  ParquetDatasetOptions.default.withPartitionType("dt", ParquetType.DATE)
)
```

- A dataset `Parquet.writeDataset` wrote reads its partition types from `_common_metadata`, so
  typed partitions need no options. Otherwise partition values are STRING unless declared with
  `withPartitionType` (INT32, INT64, DATE), which also wins over recorded types;
  `withReadCommonMetadata(false)` ignores them. `readAs` into a `LocalDate` field parses a string
  either way.
  `__HIVE_DEFAULT_PARTITION__` reads as null.
- A filter on partition columns skips whole files before they are opened (`ds.selectFiles(opts)`
  lists the files a read opens). A literal of the wrong type for a partition, or an unknown column
  name, throws `IllegalArgumentException`. `withFilter` filters rows in the dataset schema: a
  column a file lacks is null there, and narrow columns are widened first.
- Columns match by name across files. A column missing from a file reads as null. INT8/16/32 widen
  to INT64 or DOUBLE and FLOAT to DOUBLE when files differ (never INT64 to DOUBLE). Nested columns
  merge too: struct fields match by name (a missing field reads as null), and struct fields, list
  elements, and map keys and values widen. Other type differences throw a `ParquetException`
  naming the nested path (`user.age`) and both files.
- Files or folders starting with `_` or `.` are skipped. Files without an extension are kept;
  `withFileExtension(".parquet")` narrows the listing.
- The root can be a glob (`s3://b/visits/dt=2026-11-{19,20}/country=JP`; `*`, `?`, `[a-z]`,
  `{a,b}`, `**`, `\` escapes). Only the part before the first glob segment is listed, and a path is
  kept when it or a parent folder matches.
- `Parquet.dataset(Seq(path1, path2))` (or `Parquet.dataset(store, paths)`) reads exactly those
  files, all local or all in one bucket. Partitions come from `key=value` folders below their common
  parent; listed files are read even when named `_...` or `.`.
- `readAs`, `readRows`, `readJson`, and `readBatches` return iterators that open one file at a time.
  Footers are fetched `withPrefetch` (4) at a time: sync reads on the JVM, async reads
  (`readAllAsync[A]()`, `readAllRowsAsync()`) everywhere. Pass `Parquet.dataset(store,
  root)` for R2, MinIO, or explicit credentials; the dataset closes only a store it created.

- A root with a `_delta_log/` folder is read as a Delta Lake table: only the live files of the
  latest version (`withVersion(n)` for time travel), with partition columns typed by the table
  schema (string, byte/short/integer, long, date; others need `withPartitionType(key,
  ParquetType.STRING)`). Deletion vectors, column mapping, and v2 checkpoints fail by name.
  `withTableFormat(ParquetTableFormat.Files)` reads the raw files; `ParquetTableFormat.Delta`
  requires a log.
- A root with a `metadata/` folder holding `.metadata.json` files is read as an Apache Iceberg
  table: only the files of the current snapshot (`withSnapshotId(id)` for time travel), with the
  snapshot schema's columns (dropped columns hidden). Identity partitions on string, int, long, or
  date columns are partition columns. It reads `version-hint.text` or else the newest metadata
  file; pass the catalog's `.metadata.json` path as the root to read exactly the catalog's
  version. Gzip-compressed metadata files (`.gz.metadata.json`, `.metadata.json.gz`) read too.
  Format versions 1 to 3 are read: v3 `timestamp_ns` columns read as nanosecond
  timestamps, and a column older files lack reads as its `initial-default`. Position deletes,
  deletion vectors, and equality deletes (Flink upserts) are applied before row filters.
  `variant` columns read as Scala values and `geometry`/`geography` as WKB bytes. Encrypted
  tables (`encryption-keys`; they need a KMS) and non-Parquet data files fail by name.
- `ParquetDatasetWriteOptions.default.withTableFormat(ParquetTableFormat.Iceberg)` writes an
  Iceberg table (Hadoop table, format v2; `withTableProperties(Map("format-version" -> "3"))`
  for v3 with row lineage and nanosecond timestamps): `ErrorIfExists` creates it, `Append` commits a
  snapshot atomically (`ObjectStore.putIfAbsent` on `metadata/vN.metadata.json`, retrying a lost
  race). Data goes under `data/` with the partition columns and field IDs kept; manifests carry
  column stats. Under the default `Auto`, a write to an existing Iceberg root goes to the table.
  Table operations are on `wvlet.uni.cloud.iceberg.Iceberg`:
  `Iceberg.renameTable(from, to)` moves a local table and writes its manifests and
  position-delete files again with the moved paths (current snapshot only).
  `Iceberg.deleteRows(root, filter)` deletes the matching rows merge-on-read and returns
  how many: deletion vectors (Puffin) in v3 tables, position-delete files in v2, data files kept.
  `Iceberg.updateRows(root, filter)(row => row.updated("k", v))` (or
  `Iceberg.updateRows[A](root, filter)(a => a.copy(...))` for case classes) updates them
  merge-on-read in one `overwrite` snapshot (new data files for the new rows, deletes for the old;
  v3 rows keep their `_row_id`) and returns how many; a lost race keeps the written data files
  when the other commit left the touched files and the schema alone, and otherwise reads the rows
  and calls the function again, so keep the function free of side effects.
  `Iceberg.compactTable(root)` (or with `IcebergCompactionOptions.default
  .withTargetFileSize(bytes).withFilter(f).noFileMerging()`) rewrites files with deletes applied,
  merges small files per partition, and removes delete files no file needs, in one `replace`
  snapshot; v3 row IDs are kept. It returns an `IcebergCompaction` with the counts.
  Identity partitions on string, int, long, or date only; overwrite modes, other transforms,
  schema changes, and catalog-owned tables (`00003-<uuid>.metadata.json`) fail by name.

## Write a partitioned dataset

`Parquet.writeDataset(root, rows, options)` writes `key=value` folders that `Parquet.dataset`
reads back, locally or under an `s3://`, `gs://`, `az://` prefix, like DuckDB's `COPY ... TO
'dir' (FORMAT parquet, PARTITION_BY (...))`.

```scala
import wvlet.uni.cloud.parquet.{ParquetDatasetWriteOptions, ParquetWriteMode, WrittenFile}

def writeVisits(root: String, visits: Seq[Visit]): Seq[WrittenFile] = Parquet.writeDataset(
  root,
  visits,
  ParquetDatasetWriteOptions
    .default
    .withPartitionBy("dt", "country")
    .withMode(ParquetWriteMode.OverwritePartitions)
)

def streamVisits(root: String, chunks: Iterator[Seq[Visit]]): Seq[WrittenFile] =
  val w = Parquet.newDatasetWriter[Visit](
    root,
    ParquetDatasetWriteOptions.default.withPartitionBy("dt").withMaxRowsPerFile(1_000_000)
  )
  try
    chunks.foreach(w.writeAll)
    w.close()
    w.writtenFiles
  catch
    case e: Throwable =>
      w.abort() // deletes every file of this write
      throw e
```

- Partition columns (top-level STRING, INT32, INT64, DATE) become folders and are left out of the
  files. Values are Hive-escaped; null and `""` become `__HIVE_DEFAULT_PARTITION__`.
- A finished write adds `_common_metadata` (schema and partition types, so typed partitions and an
  empty write read back) and an empty `_SUCCESS` at the root; `withWriteSuccessMarker(false)` /
  `withWriteCommonMetadata(false)` turn them off. Append fails early when recorded types differ.
- `withMode`: `ErrorIfExists` (default), `Append` (same partition keys required), `Overwrite`,
  `OverwritePartitions`. Old files are deleted only after the new ones are written, and only files
  the reader reads (not dot files or other `_` files). Bucket and file system roots can't be
  overwritten.
- Files are published when finished (multipart upload, or a rename from `.inprogress`); a failure
  or `abort()` deletes every file of the write. Names are `part-<writeId>-<n>.parquet`.
- `newDatasetRecordWriter(root, schema, options)` takes maps, JSON, or `Seq` rows, and
  `newDatasetBatchWriter(root, schema, options)` takes `ColumnBatch`es; the schema includes the
  partition columns. Each also takes an `ObjectStore` first, which it leaves open.
- Limits: `withMaxRowsPerFile`, `withMaxFileSize`, `withMaxOpenFiles` (32), `withMaxBufferedBytes`
  (256 MiB). Per-file settings go in `withWriteOptions(ParquetWriteOptions...)`.
- `withSortBy("user", "id")` or `withSortBy(ParquetSortKey.desc("ts").withNullsFirst(true))`
  sorts each partition's rows before writing (Spark's `sortWithinPartitions`), for tight row group
  statistics. Ascending and nulls last by default, in Parquet statistics order (strings by UTF-8
  bytes). Rows are held in memory and written as one sorted run on `close`; over
  `withMaxBufferedBytes` the largest partition is written early as another run (each file stays
  sorted). Row groups record `sorting_columns`.

```scala
import wvlet.uni.cloud.parquet.ParquetSortKey

def writeSorted(root: String, visits: Seq[Visit]): Seq[WrittenFile] = Parquet.writeDataset(
  root,
  visits,
  ParquetDatasetWriteOptions
    .default
    .withPartitionBy("dt")
    .withSortBy(ParquetSortKey.asc("user"), ParquetSortKey.desc("id"))
)
```

Async I/O, for browsers or non-blocking code (object stores; a local root uses the sync writer):

```scala
import wvlet.uni.rx.Rx

def writeVisitsAsync(root: String, visits: Seq[Visit]): Rx[Seq[WrittenFile]] =
  Parquet.writeDatasetAsync(root, visits, ParquetDatasetWriteOptions.default.withPartitionBy("dt"))
```

- `newDatasetWriterAsync[A](store, root, options)` (also `...RecordWriterAsync`,
  `...BatchWriterAsync`) gives `Rx` of a writer: `write` queues finished files, `flush()` /
  `writeAll(rows)` upload them, `close()` returns `Rx[Seq[WrittenFile]]`, `abort()` deletes uploads.
- Files are built in memory and uploaded with one put: capped at 128 MiB unless `withMaxFileSize`;
  `withMaxBufferedBytes` counts whole files. Browsers need bucket CORS for PUT and DELETE.

## Write case classes

Any case class with `derives Weaver` writes as is: no schema, annotations, or codecs. The same
`Event` round-trips: `Parquet.readAs[Event](path)` returns what `Parquet.write(path, events)` wrote.
Recursive types and `Option[Option[A]]` are rejected.

```scala
import wvlet.uni.control.Control
import wvlet.uni.cloud.parquet.{ParquetSchema, ParquetWriteOptions}

def writeAll(path: String, events: Seq[Event]): Unit = Parquet.write(path, events)

def writeOneByOne(path: String, events: Iterator[Event]): Unit =
  Control.withResource(Parquet.newWriter[Event](path)) { w =>
    events.foreach(w.write) // the file is readable only after close()
  }

def inMemory(events: Seq[Event]): Array[Byte] = Parquet.writeBytes(events) // every platform

val derived: ParquetSchema = ParquetSchema.of[Event]
```

- The schema comes from the type: `Option[A]` is OPTIONAL (other fields REQUIRED), `Seq`/`List`/
  `Set`/`Array[A]` a 3-level LIST, `Map[K, V]` a MAP, a nested case class a group, `String` and
  enums STRING, `Array[Byte]` BYTE_ARRAY, `Instant` TIMESTAMP(MICROS, UTC; nanoseconds dropped), `LocalDate` DATE,
  `LocalTime` TIME(MICROS, not UTC-adjusted),
  `UUID` UUID, `Short`/`Byte` INT32 with INT(16)/INT(8).
- `BigDecimal` is DECIMAL(38, 18) by default. Change it with
  `ParquetWriteOptions.default.withDecimal(18, 4)`, or per field with
  `.withDecimal("price", 10, 2)`. A value that does not fit is an error, never rounded.
- Pass options as the second argument: `Parquet.write(path, rows, opts)`,
  `Parquet.newWriter[Event](path, opts)`. `Parquet.newWriter[A](out)` writes to an `OutputStream`.

## Write records with an explicit schema

```scala
import wvlet.uni.cloud.parquet.ParquetType

val schema = ParquetSchema
  .builder("event")
  .required("id", ParquetType.INT64)
  .optional("name", ParquetType.STRING)
  .optional("tags", ParquetType.list(ParquetType.STRING))
  .optional("ts", ParquetType.TIMESTAMP_MILLIS)
  .build()

def writeRecords(path: String): Unit =
  Control.withResource(Parquet.newRecordWriter(path, schema)) { w =>
    w.write(Map("id" -> 1, "name" -> "aina", "tags" -> Seq("a")))
    w.write("""{"id": 2, "ts": "2026-01-01T00:00:00Z"}""") // JSON object
    w.write(Seq(3, "kai", Seq.empty, null))                 // values in schema order
    w.write("""[4, null, ["x"], 1767225600000]""")          // JSON array
  }
```

- Missing columns are null; keys without a column are ignored; keys match loosely (`user_name`).
- TIMESTAMP takes `Instant`, ISO-8601 strings, or epoch millis; DECIMAL takes numbers or strings;
  DATE takes `LocalDate` or `yyyy-mm-dd`; TIME takes `LocalTime`, `hh:mm:ss`, or nanos of the day.
- More types: `INT8`, `INT16`, `INT32`, `FLOAT`, `DOUBLE`, `BOOLEAN`, `BINARY`, `ENUM`, `JSON`,
  `DATE`, `TIMESTAMP_MICROS`, `TIME_MICROS`, `UUID`, `decimal(p, s)`, `fixed(n)`, `map(k, v)`,
  `struct(ParquetColumn.required(...), ...)`.
- A row that does not fit throws `ParquetException` naming the column; the row is skipped and the
  writer stays usable.

## Write column batches

`Parquet.newBatchWriter(path or OutputStream, schema, options)` writes `ColumnBatch`es column by
column, with no rows in between. A batch from `readBatches` writes back unchanged, so copying or
projecting a file is:

```scala
def copyColumns(from: String, to: String): Unit =
  val in = Parquet.open(from)
  try
    Control.withResource(Parquet.newBatchWriter(to, in.schema.select("id", "name"))) { w =>
      in.readBatches().foreach(b => w.write(b.select("id", "name")))
    }
  finally in.close()
```

- Build vectors with their constructors and `schema.field(name).get`, for example
  `ColumnVector.LongColumn(field, Array(1L, 2L), null)`; the last argument flags null rows.
- Values are physical: TIMESTAMP is a `Long` in its unit (`LongColumn`), DATE is days
  (`IntColumn`), DECIMAL is unscaled, strings are UTF-8 in a `BinaryColumn` (`offsets`, `data`).
  LIST, MAP, and groups take `ListColumn`, `MapColumn`, and `StructColumn`.
- Columns match top-level fields by name. A missing or extra column, a wrong vector type or size,
  bad offsets, or a null in a required column throws `ParquetException` before anything is
  written; the writer stays usable. Row groups still close at `rowGroupSize`.
- Projection only: `ParquetSchema.select(names*)` and `ColumnBatch.select(names*)`. To filter
  rows, build new arrays.

## Deliver results as Arrow IPC

`wvlet.uni.cloud.arrow.Arrow` writes the same `ColumnBatch`es as Apache Arrow IPC, so Python (pyarrow,
pandas, polars), Arrow JS, and ADBC clients load columns without decoding rows. Pure Scala on every
platform, no dependencies; writing only (no Arrow reader yet).

```scala
import wvlet.uni.cloud.arrow.{Arrow, ArrowCompression, ArrowWriteOptions}
import wvlet.uni.http.Response

// An HTTP response with the whole result in memory
def arrowResponse(path: String): Response =
  val in = Parquet.open(path)
  try
    val bytes = Arrow.toStreamBytes(in.schema, in.readBatches())
    Response.ok.withBytesContent(bytes).setHeader("Content-Type", Arrow.StreamMediaType)
  finally in.close()

// Batch by batch to any OutputStream, compressed
def streamArrow(path: String, out: java.io.OutputStream): Unit =
  val in = Parquet.open(path)
  try
    val w = Arrow.newStreamWriter(
      out,
      in.schema,
      ArrowWriteOptions.default.withCompression(ArrowCompression.ZSTD)
    )
    try
      in.readBatches().foreach(w.write)
      w.close() // the end-of-stream marker, then closes `out`
    catch
      case e: Throwable =>
        w.abort() // no end marker: the client must not see a complete-looking stream
        throw e
  finally in.close()
```

- `Arrow.newStreamWriter` / `toStreamBytes` write the stream format (`Arrow.StreamMediaType`,
  read with `pyarrow.ipc.open_stream`, `polars.read_ipc_stream`, Arrow JS `tableFromIPC`);
  `newFileWriter` / `toFileBytes` write `.arrow` files with a footer (`pyarrow.ipc.open_file`).
- One `ColumnBatch` becomes one record batch. `ArrowSchema.fromParquet(schema)` shows the
  mapping: TIMESTAMP to `timestamp(unit, "UTC")` when UTC-adjusted (no zone otherwise), DATE to
  `date32`, DECIMAL to `decimal128` (`decimal256` above precision 38), UUID to `arrow.uuid`,
  STRING/ENUM/JSON to `utf8`, LIST/MAP/groups to `list`/`map`/`struct`, INT96 to
  `timestamp(ns, "UTC")`. Strings are plain `utf8`, never dictionary-encoded.
- A VARIANT column throws `ArrowException` when the writer is created, before any bytes. A batch
  that does not fit the schema (or a value over its Arrow type) throws before any of its bytes are
  written; then call `abort()` and fail the response, since stream readers accept a stream that
  ends between batches.
- `ArrowWriteOptions`: `withCompression(ArrowCompression.ZSTD or LZ4_FRAME)` (NONE by default;
  `withCompression(ArrowCompression.ZSTD, 9)` for a level), `withBinaryAsString(true)` for
  unannotated strings, `withMetadata(map)` for schema metadata.

## Writer options

`ParquetWriteOptions.default` writes SNAPPY, data page v1, dictionary encoding (falls back to
PLAIN past `dictionaryPageSize`, 1 MiB, or when the dictionary is no smaller than PLAIN after 20,000 values), 1 MiB pages, and 128 MiB row groups, with min/max/null
statistics. Change them with `withCompression(CompressionCodec.ZSTD)` (or `withCompression(CompressionCodec.ZSTD, 9)` for a
level, 1-22; default 3; also `LZ4_RAW`, and `GZIP`, not in browsers), `noCompression()`, `noDictionary()`, `withPageSize`, `withRowGroupSize`, and `withParallelism(n)` (JVM: batch columns, dataset partitions, and row-group compression run on up to 8 threads by default; same bytes at any setting; 1 for the calling thread only). One row
group is buffered in memory; on Scala.js a file stream holds the whole file until `close()`.

## From TypeScript (npm)

The same code is on npm as `@wvlet/uni` (Scala.js, with type declarations). Point TS/JS users there
instead of writing a Scala.js facade:

```ts
import { openParquet, writeParquet } from "@wvlet/uni/parquet";
const file = await openParquet(bytes); // Uint8Array, ArrayBuffer, Blob, path (Node), or URL
const rows = await file.readRows({ columns: ["id"], timestamps: "bigint" });
const out = writeParquet(rows, [{ name: "id", type: "int64", optional: false }]);
```

INT64 is `bigint`, decimals are strings, timestamps are `Date` unless `timestamps: "bigint"`.

Datasets (root, glob, or file list; local in Node, or `s3://`/`gs://`/`az://`; async I/O, so browsers work):

```ts
import { openDataset, writeDataset, newDatasetWriter } from "@wvlet/uni/parquet";
const storage = { s3: { region: "us-east-1", credentials: { accessKeyId, secretAccessKey, sessionToken } } };
await writeDataset("s3://bucket/events/", rows, schema, { partitionBy: ["dt"], mode: "overwrite-partitions", storage });
const ds = await openDataset("s3://bucket/events/dt=2026-11-*", { storage });
const hits = await ds.readRows({ filter: [["dt", ">=", "2026-11-15"], ["country", "in", ["JP"]]] });
for await (const b of ds.batches({ columns: ["id"] })) { /* one row group at a time */ }
ds.close();
```

- Filters follow PyArrow: a `[col, op, value]` condition, an array of them (AND), or an array of
  arrays (OR of ANDs). Ops: `= != < <= > >= in "not in" "is null" "is not null"`. They are exact.
- Partition types come from `_common_metadata`, or from `partitionTypes: { dt: "date" }` (needed for file lists).
- `storage` is per call: `s3 { region, endpoint, pathStyle, credentials | "anonymous" }`,
  `gcs { accessToken | credentialsJson (Node only) | anonymous }`, `azure { connectionString,
  account, sasToken | sharedKey | anonymous }`. Node falls back to the default chain. Browsers
  must pass short-lived credentials or `anonymous`.
- `newDatasetWriter(root, schema, opts)` gives `write(row)` (throws when the upload queue is over
  `maxBufferedBytes`; await `flush()`), `flush()`, `close()` (resolves to the written files), and `abort()`.

## MPC1 files

`wvlet.uni.cloud.plazma` reads and writes MPC1, the column-oriented MessagePack format of PlazmaDB
partitions: schema-less map rows stored as one compressed block of MessagePack values per column.

```scala reset
import wvlet.uni.cloud.plazma.*
import wvlet.uni.msgpack.spi.Value.*

val writer = MPC1.newWriter() // GZIP; MPC1WriteOptions.default.withCompression(MPC1Compression.NONE)
writer.addRow("time" -> LongValue(1700000000L), "user" -> StringValue("aina"))
val bytes = writer.toByteArray // or writeTo(path) / writeTo(outputStream); in memory until written

val file  = MPC1.open(bytes) // or a path, MPC1.openS3/openGcs/openAzure/openUrl
val rows  = file.readRows(Seq("time", "user")).toSeq // Iterator[MapValue], nil values left out
val times = file.readColumn("time").toSeq            // one value per row, NilValue when missing

// Typed reads: unboxed Parquet ColumnVectors (LongColumn, DoubleColumn, BooleanColumn, BinaryColumn)
file.columnType("time") // MPC1ColumnType.LONG; DOUBLE, BOOLEAN, STRING, BINARY, NIL, or ANY
val longs = file.readLongs("time")     // longs.values: Array[Long]; longs.isNull(i) for nil rows
val names = file.readStrings("user")   // names.getString(i); also readDoubles, readBooleans, readBinaries
val byIdx = file.readLongs(file.columnNames.indexOf("time")) // every reader also takes a column index
file.close()
```

Each distinct string key becomes a column; projected reads fetch only the selected column blocks.
`columnType` reads only format bytes: int + float → DOUBLE, str + bin → BINARY, arrays, maps,
timestamps, or other mixes → ANY (use `readColumn`). Schema-on-read: nil and values of another
type are null for every reader (no exception; only a corrupt block throws `MPC1Exception`).
`readDoubles` widens integers.
`MPC1Decoder.columnType(block)` / `decodeLongs(name, block)` work on one `readColumnBlock` result.
Gzip writes fail in browsers.

## Limits

No modular encryption, LZO codec, or ALP encoding, and one row must hold under 2 GiB of values
in each column. These fail with `ParquetException`. The writer does not compress with BROTLI and
writes no data page v2, DELTA encodings, page indexes, or bloom filters.
