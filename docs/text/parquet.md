---
description: Read and write Apache Parquet files in pure Scala on the JVM, Scala.js, and Scala Native. Read from local files, byte arrays, or HTTP Range requests into case classes, Map[String, Any] rows, JSON, or column vectors, and write from case classes, maps, or JSON.
---

# Parquet

`uni-parquet` reads and writes [Apache Parquet](https://parquet.apache.org/) files. It is written
in Scala with no other dependencies, so the same code runs on the JVM, Scala.js (Node.js and
browsers), and Scala Native, and it brings in no Hadoop or native libraries. It reads local files,
byte arrays, files on any HTTP server that supports Range requests, and objects in S3, R2, GCS,
MinIO, or Azure Blob Storage. It writes case classes, maps, and JSON records to local files, byte arrays, or object storage.

```scala sbt
resolvers += "wvlet" at "https://repo.wvlet.org/maven2"
libraryDependencies += "org.wvlet.uni" %% "uni-parquet" % "__UNI_VERSION__"
```

## Quick Start: Case Classes In and Out

Define a case class that `derives Weaver`. That one line is all you need to write it to Parquet and
read it back. You don't declare a schema, add annotations, or write any glue code:

```scala
import wvlet.uni.parquet.{Parquet, ParquetSchema}
import wvlet.uni.weaver.Weaver

case class Address(city: String, zip: Option[String]) derives Weaver
case class Customer(id: Long, name: String, emails: Seq[String], address: Option[Address])
    derives Weaver

val customers = Seq(
  Customer(1, "aina", Seq("aina@example.com"), Some(Address("Tokyo", Some("100-0001")))),
  Customer(2, "leo", Seq.empty, None)
)

Parquet.write("customers.parquet", customers)                 // schema derived from Customer
val loaded: Seq[Customer] = Parquet.readAs[Customer]("customers.parquet")
// loaded == customers
```

The writer derives the schema from the case class. `Option` fields become OPTIONAL columns,
`Seq` becomes a LIST, and a nested case class becomes a group. `ParquetSchema.of[Customer]` shows
the derived schema:

```text
message schema {
  required int64 id
  required byte_array name (String)
  required group emails (List) {
    repeated group list {
      required byte_array element (String)
    }
  }
  optional group address {
    required byte_array city (String)
    optional byte_array zip (String)
  }
}
```

Other engines, such as DuckDB, Spark, and pandas, can read the file. To get the file as bytes
instead of on disk, for example in a browser, use `Parquet.writeBytes(customers)` and read it with
`Parquet.readAs[Customer](bytes)`. [Writing Case Classes](#writing-case-classes) covers streaming
writes, type mappings, and options.

## Reading Rows into Case Classes

```scala
import wvlet.uni.parquet.Parquet
import wvlet.uni.weaver.Weaver

import java.time.Instant

case class Event(id: Long, userName: String, ts: Instant, tags: Seq[String]) derives Weaver

val events: Seq[Event] = Parquet.readAs[Event]("events.parquet")
```

Columns are matched to fields by name, the way [Weaver](../core/weaver) matches JSON keys, so a
`user_name` column fills a `userName` field. Optional columns map to `Option[A]`, lists to
`Seq[A]`, maps to `Map[K, V]`, and groups to nested case classes. Columns without a matching field
are skipped, and fields without a column take their default values.

| Parquet type | Scala field |
|---|---|
| INT32, INT64, FLOAT, DOUBLE, BOOLEAN | `Int`, `Long`, `Float`, `Double`, `Boolean` |
| STRING, ENUM, JSON | `String` |
| TIMESTAMP (millis, micros, nanos), INT96 | `Instant` |
| DATE | `java.time.LocalDate`, or `String` in ISO-8601 (`2026-01-31`) |
| TIME (millis, micros, nanos) | `java.time.LocalTime`, or `String` in ISO-8601 (`12:34:56`) |
| DECIMAL | `BigDecimal` |
| UUID | `String` or `java.util.UUID` |
| BYTE_ARRAY without a logical type | `Array[Byte]`, or `String` with `withBinaryAsString(true)` |

Older writers, such as Hive and Impala, store strings without the STRING annotation. To read
them as strings, use `ParquetReadOptions.default.withBinaryAsString(true)`. The option applies to
every way of reading: case classes, row maps, JSON, and `getValue` on column vectors.

## Reading Rows as Maps

When you don't know the schema ahead of time, or don't want to declare a case class, read each
row as a `Map[String, Any]` from column name to value:

```scala
val eventRows: Seq[Map[String, Any]] = Parquet.readRows("events.parquet")
eventRows.foreach { row =>
  val ts = row("ts") // an Instant
}
```

Keys follow the schema's column order. Values keep their Parquet types: `Int`, `Long`, `Float`,
`Double`, `Boolean`, `String`, `Instant` for TIMESTAMP and INT96, `LocalDate` for DATE,
`LocalTime` for TIME, `BigDecimal` for DECIMAL, `java.util.UUID` for UUID, and `Array[Byte]` for
binary without a logical type. Lists become `Seq`, maps `Map`, and groups nested
`Map[String, Any]`. A null value is `null`. `Parquet.readRows` also accepts an `Array[Byte]`, and
`ParquetFile.readRows(options)` reads with column projection and row filters.

`readRows` is usually a better fit than `readAs[Map[String, Any]]`. `readAs` decodes rows through
Weaver, which only knows that the target is a map, so integers come back as `Long`, FLOAT as
`Double`, and DATE, TIME, DECIMAL, and UUID as strings.

### Rows as JSON

`readJson` returns each row as a `JSONObject`. Call `toJSON` on each one to write JSON lines:

```scala
Parquet.readJson("events.parquet").foreach(json => println(json.toJSON))
// {"id":1,"userName":"leo","ts":"2026-01-31T12:34:56Z","tags":["a","b"]}
```

TIMESTAMP, DATE, TIME, and UUID values become ISO-8601 or canonical strings. DECIMAL becomes a
string so that no digits are lost, binary becomes base64, and map keys become strings.

## Opening a File

`Parquet.open` reads the footer, which holds the schema and row group metadata. Column data is
read one row group at a time.

```scala
import wvlet.uni.parquet.{ParquetFile, ParquetReadOptions}

val file: ParquetFile = Parquet.open("events.parquet")
try
  println(file.schema)  // message schema { optional int64 id ... }
  file.numRows          // Long
  file.rowGroups        // row counts, sizes, and column statistics
  file.columnStatistics // per column: value and null counts, sizes, min, and max
  val opts = ParquetReadOptions.default.withColumns("id", "ts")
  file.readAs[Event](opts).foreach(println)
finally
  file.close()
```

`withColumns` reads only the named top-level columns and fetches nothing for the others.
`Parquet.open` also accepts an `Array[Byte]` or a `ByteSource`.

`columnStatistics` summarizes each leaf column over all row groups, keyed by its dotted path (for
example `tags.list.element`), using only the footer. `min` and `max` hold values of the same types
as `readRows` (for example an `Instant`). They are `None` when a row group has no statistics, or
when the column's type has no order that statistics can be trusted for, such as DECIMAL. Writers
may truncate long strings, so for strings treat min and max as bounds.

## Reading Column Vectors

`readRowGroup(i)` returns row group `i` as one `ColumnBatch`, and `readBatches` returns the
batches of every row group in order. Each column is a `ColumnVector` that stores primitive values
unboxed, which is faster than decoding rows.

```scala
import wvlet.uni.parquet.ColumnVector.*

val batch = file.readRowGroup(0)
batch.column("id") match
  case ids: LongColumn =>
    var sum = 0L
    for i <- 0 until ids.size do
      if !ids.isNull(i) then sum += ids.getLong(i)
  case _ =>
```

Vector types: `BooleanColumn`, `IntColumn`, `LongColumn`, `FloatColumn`, `DoubleColumn`,
`BinaryColumn` (`getString`, `getBytes`), `ListColumn`, `MapColumn`, and `StructColumn`.
`getValue(i)` returns any row as a Scala value with its logical type applied (for example an
`Instant` or a `BigDecimal`). `batch.row(i)` returns a whole row as a `Map[String, Any]`, the same
map `readRows` returns, and `batch.rows` iterates over all of them.

### Batches and Large Row Groups

By default, `readBatches` returns one batch per row group. To get batches of at most a given number
of rows, for example to bound memory, set a batch size:

```scala
val batches = file.readBatches(ParquetReadOptions.default.withBatchSize(10000))
```

Each column of a batch is held in arrays, which are limited to 2 GiB. So when a column of a row
group holds more than 1 GiB of values, `readBatches` (and `readAs`, `readRows`, and `readJson`,
which read through it) also splits the row group, ending a batch at the first row after that
point. Files with such column chunks read without settings. `readRowGroup` always returns the whole
row group, up to 2 GiB of values per column; for a larger row group it fails and points to
`readBatches`. Column chunks of more than 256 MiB are read in windows of that size rather than in
one range. The asynchronous methods decode all batches of a row group before emitting the first,
so they hold a whole row group in memory.

## Reading over HTTP

Parquet keeps its footer at the end of the file, so the reader starts with one Range request for
the last 64 KiB, then fetches only the column chunks it needs. Nearby chunks are merged into one
request; tune the gap with `withMaxMergeGap(bytes)`.

```scala
val remote = Parquet.openUrl("https://example.com/data/events.parquet")
```

`openUrl` uses the synchronous [HTTP client](../http/client) on the JVM, Scala Native, and
Node.js. In browsers, use the asynchronous API:

```scala
import wvlet.uni.rx.Rx

val rows: Rx[Seq[Event]] = Parquet
  .openUrlAsync("https://example.com/data/events.parquet")
  .flatMap(_.readAllAsync[Event]())
```

`readRowGroupAsync`, `readBatchesAsync`, and `readAsAsync` work the same way. For row maps, use
`readRowsAsync` (one event per row) or `readAllRowsAsync` (all rows in one `Seq`), and for JSON,
`readJsonAsync`. The server must answer
Range requests with `206 Partial Content` and allow them through CORS; a server that sends the whole
file instead gets a clear error. Uni's own server does this with
[StaticFiles](../http/server#static-files).

## Reading and Writing in S3, GCS, and Azure Blob

`Parquet.openS3` reads from AWS S3 and S3-compatible stores (Cloudflare R2, MinIO) the same way,
and `Parquet.openGcs` from Google Cloud Storage with Google credentials, with ranged reads through
[Object Storage](../core/object-storage):

```scala
import wvlet.uni.storage.{ObjectStore, S3Config}

val fromS3 = Parquet.openS3("s3://my-bucket/events.parquet")
val fromR2 = Parquet.openS3(
  "s3://my-bucket/events.parquet",
  S3Config.r2("<account-id>").withCredentials("<key-id>", "<secret>")
)
// Application Default Credentials: a service-account key, gcloud login, or the metadata server
val fromGcs = Parquet.openGcs("gs://my-bucket/events.parquet")
```

To write, pass an object store upload stream to `newWriter`. The file appears when the writer
closes; if the writer fails, or you call `abort()`, the upload is discarded:

```scala
val store  = ObjectStore.s3()
val writer = Parquet.newWriter[Event](store.newOutputStream("s3://my-bucket/out.parquet"))
writer.write(Event(1, "aina", Instant.now(), Seq("a")))
writer.close()
```

`openS3Async` and `openGcsAsync` read in browsers, given explicit credentials or a bucket that allows anonymous
reads, and a CORS rule for the page.

Azure Blob Storage works the same way with `az://container/key` URIs. The storage account and
credentials come from an `AzureConfig`, by default from `AZURE_STORAGE_CONNECTION_STRING` or
`AZURE_STORAGE_ACCOUNT`:

```scala
import wvlet.uni.storage.AzureConfig

val fromAzure = Parquet.openAzure(
  "az://my-container/events.parquet",
  AzureConfig().withSharedKey("myaccount", "<account-key>")
)

val azureStore = ObjectStore.azure()
val toAzure    = Parquet.newWriter[Event](azureStore.newOutputStream("az://my-container/out.parquet"))
```

`ByteSource.ofObject(store, uri)` opens a file in any store you already hold.

## Filtering Rows

`withFilter` returns only the rows that match a `ParquetFilter`, as Spark and DuckDB do with
pushed-down predicates. Writers store min, max, and null counts for each column chunk, so row
groups that can't match are skipped without being read, and the rest are filtered on the column
vectors:

```scala
import wvlet.uni.parquet.ParquetFilter.*

val recent = ParquetReadOptions.default
  .withFilter(col("ts") >= Instant.parse("2026-01-01T00:00:00Z") && col("country") === "JP")
file.readAs[Event](recent)
```

The filter applies to `readAs`, `readRows`, `readJson`, `readBatches`, `readRowGroup`, and their
async variants. A column the filter needs but `withColumns` leaves out is read, then dropped.

- **Nulls.** As in SQL, a comparison with null is unknown, and a row is kept only when the whole
  filter is true. Use `col("x").isNull` or `isNotNull` to match nulls; `col("x") === null` is an
  error.
- **Floating point.** Comparisons follow IEEE 754: NaN matches no comparison, and `-0.0` equals
  `0.0`. Spark and DuckDB instead sort NaN above every number.
- **Literals** convert to the column's type only without loss: integers to INT32 and INT64
  columns (signed or unsigned, also from a string of digits such as `"2026"`), `LocalDate` or an
  ISO date string to DATE, `Instant` to TIMESTAMP and INT96 (compared exactly, below the column's
  unit too), `String` to STRING and ENUM, `Boolean` to BOOLEAN, and numbers to FLOAT and DOUBLE
  (integers only within ±2^53). Strings compare in UTF-8 byte order, which is code point order.
- **Nested columns.** A dotted name such as `col("address.city")` names a field inside structs; a
  top-level column with that exact name wins. A null struct makes its fields null.
- **Errors.** A filter that can't be evaluated exactly fails when the read starts, naming the
  column: an unknown column, a list or map, a type such as DECIMAL, TIME, or UUID, or a literal of
  the wrong type. Untyped binary columns compare with strings under `withBinaryAsString(true)`.

A batch from `readBatches` holds only matching rows, so it can be smaller than `withBatchSize`;
batches with no rows are not returned. `readRowGroup` always returns its one batch, which can be
empty.

### Skipping Row Groups Only

`withRowGroupFilter` takes the same filter but only skips row groups (and dataset files) that
can't match; rows inside the row groups that are read come back unchanged. It never fails on a
column or literal it doesn't understand, and reads that row group instead:

```scala
val hint = ParquetReadOptions.default.withRowGroupFilter(col("country") === "JP")
file.selectRowGroups(hint) // the row groups a read with this filter opens
```

`RowGroupFilter` is the old, deprecated name of `ParquetFilter`.

## Reading a Dataset of Files

Data that Spark, Hive, Trino, DuckDB, or Athena writes is usually many files under one folder or
bucket prefix, split into `key=value` folders such as `dt=2026-11-20/country=JP/part-0.parquet`.
`Parquet.dataset` lists them and reads them as one table. It works on a local directory, or on an
`s3://`, `gs://`, `az://`, or `azure://` prefix:

```scala
import wvlet.uni.parquet.ParquetFilter.*
import java.time.LocalDate

case class Visit(id: Long, user: String, dt: LocalDate, country: String) derives Weaver

val ds = Parquet.dataset("s3://my-bucket/visits/")
try
  ds.files            // every file found, with its partition values
  ds.partitionColumns // dt, country
  ds.schema           // the files' columns, then the partition columns

  val recentJP = ParquetReadOptions
    .default
    .withFilter(col("dt") >= "2026-11-15" && col("country") === "JP")
  ds.selectFiles(recentJP)                   // the files this read opens
  val visits = ds.readAs[Visit](recentJP).toSeq
finally ds.close()
```

- **Partitions.** Each `key=value` folder becomes a column, appended after the files' columns, so
  `readAs`, `readRows`, `readJson`, and `readBatches` all see it. Values are URL-decoded, and
  `__HIVE_DEFAULT_PARTITION__` reads as null. If a file also stores a column with a partition key's
  name, the folder value wins.
- **Partition types.** A dataset that `Parquet.writeDataset` wrote records its partition types in
  `_common_metadata`, so its partitions read back typed (DATE, INT32, INT64) with no options. For
  other data, partition values are strings unless you declare a type, so a type never changes
  because of one odd folder name. ISO dates compare correctly as strings. Declare INT32, INT64, or
  DATE with `ParquetDatasetOptions`; a declared type wins over a recorded one, and
  `withReadCommonMetadata(false)` ignores the recorded types:

  ```scala
  import wvlet.uni.parquet.{ParquetDatasetOptions, ParquetType}

  val typed = Parquet.dataset(
    "s3://my-bucket/visits/",
    ParquetDatasetOptions.default.withPartitionType("dt", ParquetType.DATE)
  )
  // Filters on dt now take LocalDate (or an ISO date string)
  ```

- **Skipping files.** A filter on partition columns is checked exactly against each file's
  folder values, so files that can't match are never opened. The rest of the filter skips row
  groups by statistics, and `withFilter` then filters the rows. Rows are filtered in the dataset's
  schema, so a column a file lacks is null there (only `isNull` matches), and a narrow column is
  widened before it is compared. A filter value of the wrong type for a
  partition fails, instead of quietly reading every file. When the read selects any file, a
  column name that none of its files has also fails before any rows are read.
- **Columns across files.** Columns are matched by name. The schema is the union of the columns
  of the files a read selects, so a column added in newer files reads as null in older ones. Numeric
  types widen as DuckDB's `union_by_name` promotes them: INT8, INT16, and INT32 read as INT64 or DOUBLE when
  another file has that type, and FLOAT as DOUBLE. INT64 never widens to DOUBLE, which would round
  large values. Any other type difference fails with both file names; leave the column out with
  `withColumns` to read the rest.
- **Which files.** Every file below the root is read, except those under a folder, or with a name,
  starting with `_` or `.` (`_SUCCESS`, `_temporary/`, `.crc` files). Files without an extension,
  as Hive and Trino write them, are kept. `withFileExtension(".parquet")` keeps only Parquet files
  in mixed folders. The root always ends with `/`, so `visits` doesn't also match `visits_old/`.
- **Glob patterns.** The root can be a pattern, as in DuckDB and Spark:
  `Parquet.dataset("s3://my-bucket/visits/dt=2026-11-{19,20}/country=JP")`. Only the part before the
  first glob segment is listed (`visits/` here), and a path is kept when it or one of its folders
  matches. `*` and `?` match within a folder name, `[a-z]` and `[!a-z]` are sets, `{a,b}` lists
  alternatives, `**` matches any number of folders, and `\` escapes a character. Partition columns
  still come from the folders below the listed part.
- **A list of files.** `Parquet.dataset(Seq(path1, path2))` reads exactly those files, as
  `read_parquet([...])` does in DuckDB, for example the `writtenFiles` of a write. The paths are all
  local, or all in one bucket. Partition columns come from the `key=value` folders below the files'
  common parent, and a listed file is read even when its name starts with `_` or `.`.
- **Order and memory.** Files are read in path order, then row group order. Synchronous reads
  return an `Iterator` that opens one file at a time. A read fetches the footers of the selected
  files several at a time (`withPrefetch`, 4 by default), which hides object store latency: sync
  reads do so on the JVM, and `readAllAsync`, `readAllRowsAsync`, and `readBatchesAsync`
  everywhere.
- **Stores.** `Parquet.dataset(uri)` creates the store with its default config and closes it with
  the dataset. For R2, MinIO, or other credentials, pass a store, which the dataset leaves open:
  `Parquet.dataset(ObjectStore.s3(S3Config.r2("<account-id>")), "s3://my-bucket/visits/")`.

`_metadata` summary files and table formats such as Delta Lake and Apache Iceberg aren't
supported yet.

## Writing a Dataset

`Parquet.writeDataset` writes rows into `key=value` folders that `Parquet.dataset` reads back, as
DuckDB's `COPY ... TO 'dir' (FORMAT parquet, PARTITION_BY (dt, country))` or Spark's `partitionBy`
does. It writes to a local directory, or to an `s3://`, `gs://`, `az://`, or `azure://` prefix:

```scala
import wvlet.uni.parquet.{ParquetDatasetWriteOptions, ParquetWriteMode}

val day     = LocalDate.parse("2026-11-20")
val visits  = Seq(Visit(1L, "aina", day, "JP"), Visit(2L, "leo", day, "US"))
val options = ParquetDatasetWriteOptions.default.withPartitionBy("dt", "country")
val written = Parquet.writeDataset("s3://my-bucket/visits/", visits, options)
// dt=2026-11-20/country=JP/part-3f9a01c2-00000.parquet, ...
written.foreach(f => println(s"${f.path} ${f.partitions} ${f.numRows} rows"))
```

For rows that arrive over time, `Parquet.newDatasetWriter[Visit](root, options)` returns a writer
with `write`, `writeAll`, `close`, and `abort`. `newDatasetRecordWriter(root, schema, options)`
takes maps, JSON, or `Seq` rows, and `newDatasetBatchWriter(root, schema, options)` takes
`ColumnBatch`es. Each also accepts an `ObjectStore` first, which the writer leaves open.

- **Partition columns** are fields of the rows, or columns of the schema. They become folder names
  and are left out of the files. They must be top-level STRING, INT32, INT64, or DATE columns; a
  case-class `LocalDate` field is a DATE column.
  Folder values are percent-escaped as Hive does, and null or empty values are written as
  `__HIVE_DEFAULT_PARTITION__`, so both read back as null.
- **Root files.** When a write finishes, the writer adds `_common_metadata` and an empty `_SUCCESS`
  at the root, after every data file is published. `_common_metadata` is Parquet's summary file: a
  Parquet file without rows that holds the files' schema and, in its key-value metadata, the
  partition types, so `Parquet.dataset` reads INT32, INT64, and DATE partitions back typed and
  reads a write without rows as an empty dataset. `_SUCCESS` is the marker that Spark and Hadoop
  write, for schedulers that wait on it. Spark, DuckDB, Trino, and Hive skip both. `Append` and
  `OverwritePartitions` record types only for folders the writer owns (an empty root, or a
  matching `_common_metadata`), and fail before writing when the recorded types differ. The
  overwrite modes delete an old `_SUCCESS` before they change anything. Turn either file off with
  `withWriteSuccessMarker(false)` or `withWriteCommonMetadata(false)`.
- **Write modes.** `withMode` decides what happens when the root already holds data files:
  `ErrorIfExists` (the default) fails before writing, `Append` adds files next to them (with the
  same partition keys), `Overwrite` replaces the dataset, and `OverwritePartitions` replaces only
  the partitions the write touches, like Spark's dynamic partition overwrite. Old files are deleted
  only after every new file is written, so a failed write keeps the old data. Only files the
  reader would read are deleted: dot files and other files that start with `_` or `.` stay, except
  the root files above. A bucket or file system root can't be overwritten.
- **Failures.** Each file is published only when it's finished: an object store file is a
  multipart upload, and a local file is renamed from a hidden `.inprogress` name. When writing
  fails, or on `abort()`, every file of the write is deleted, including finished ones. A row that
  doesn't fit the schema throws `ParquetException` without stopping the writer. A reader listing
  the folder while a write runs can see the files finished so far.
- **File names and sizes.** Files are named `part-<writeId>-<n>.parquet`, unique across writes.
  `withMaxRowsPerFile(n)` and `withMaxFileSize(bytes)` start a new file in the same partition at
  the limit.
- **Memory.** Each open file buffers up to one row group (`withRowGroupSize`, 128 MiB by default).
  `withMaxOpenFiles` (32) finishes the file that went longest without a row when another partition
  needs one, and `withMaxBufferedBytes` (256 MiB) writes the largest buffered row group out early.
  With many partitions, sort rows by the partition columns first, or set a smaller row group size.
- **File settings** such as the codec go in `withWriteOptions(ParquetWriteOptions...)`.

### Sorting Within Partitions

`withSortBy` sorts each partition's rows before they are written, as Spark's
`sortWithinPartitions` or DuckDB's `ORDER BY` before `COPY` does. Sorted files have tight row group
min and max values, so a `RowGroupFilter` on a sort column skips most row groups:

```scala
import wvlet.uni.parquet.ParquetSortKey

val byUser = ParquetDatasetWriteOptions
  .default
  .withPartitionBy("dt")
  .withSortBy("user", "id")
Parquet.writeDataset("s3://my-bucket/visits-by-user/", visits, byUser)

// Newest first, with nulls before every value
val newestFirst = ParquetDatasetWriteOptions
  .default
  .withPartitionBy("dt")
  .withSortBy(ParquetSortKey.desc("id").withNullsFirst(true))
```

- **Order.** Keys are compared as Parquet statistics compare them: integers, dates, timestamps,
  and decimals signed; unsigned integers unsigned; strings and binary by their UTF-8 bytes; false
  before true; NaN after every other number. Nulls go last by default, as in DuckDB, in either
  direction. Rows with equal keys keep the order they were written in.
- **Sort columns** are top-level primitive columns other than the partition columns. INT96,
  INTERVAL, and FLOAT16 columns have no defined order and are rejected when the writer is created.
- **Runs.** A partition's rows are held in memory and written as one sorted run when the writer
  closes. File rolling (`withMaxRowsPerFile`, `withMaxFileSize`) splits a run into files that
  cover consecutive key ranges. When the held rows exceed `withMaxBufferedBytes`, the largest
  partition is written early as a separate run, and the writer logs a warning once: each file is
  still sorted, but the runs overlap. Raise `withMaxBufferedBytes` to keep one run per partition.
  `withMaxOpenFiles` has no effect, because a run writes one file at a time.
- **Metadata.** Every row group records Parquet's `sorting_columns`, which other engines can use.
  `Parquet.open(path).rowGroups.map(_.sortingColumns)` reads it back.
- A row that doesn't fit the schema still throws from `write`, before it is held.

### Writing with Async I/O

In browsers, and in code that must not block, `Parquet.writeDatasetAsync` writes a dataset to S3,
GCS, or Azure Blob with the stores' async calls only, as `datasetAsync` reads one:

```scala
import wvlet.uni.parquet.WrittenFile
import wvlet.uni.rx.Rx

val done: Rx[Seq[WrittenFile]] = Parquet.writeDatasetAsync(
  "s3://my-bucket/visits/",
  visits,
  ParquetDatasetWriteOptions.default.withPartitionBy("dt", "country")
)
```

`Parquet.newDatasetWriterAsync[A](store, root, options)` (and `newDatasetRecordWriterAsync`,
`newDatasetBatchWriterAsync`) returns an `Rx` of a writer. `write(row)` encodes a row and queues the
files it finishes, `flush()` uploads them, `writeAll(rows)` writes and uploads in turn, and
`close()` uploads the rest and writes the root files. Write modes, `_common_metadata`, `_SUCCESS`,
and cleanup work as in the sync writer: a failure, or `abort()`, deletes every uploaded file.

- **Memory.** Each file is built in memory and uploaded with one put, so files are capped at 128
  MiB unless `withMaxFileSize` sets another size. `withMaxBufferedBytes` (256 MiB) counts whole
  files, open and queued: over it, the largest open file is finished and queued. A `write` fails
  with a message to call `flush()` while the queued files exceed it; `writeAll` never does.
- **Browsers** also need the bucket's CORS rules to allow `PUT` and `DELETE`, besides `GET`.
- A local root uses the sync writer, wrapped in `Rx`.

## Writing Case Classes

`Parquet.write` writes a collection of case classes. The schema comes from the type, so any case
class that `derives Weaver` can be written as it is, including one you already use for JSON or RPC:

```scala
Parquet.write("events.parquet", Seq(Event(1, "aina", Instant.now(), Seq("a"))))
```

To write rows one at a time, for example from an iterator too large to keep in memory, open a
writer and close it when you are done. The file can be read only after `close()`, which writes
the footer:

```scala
import wvlet.uni.control.Control

Control.withResource(Parquet.newWriter[Event]("events.parquet")) { w =>
  w.write(Event(1, "aina", Instant.now(), Seq("a")))
  w.write(Event(2, "leo", Instant.now(), Seq.empty))
}
```

`Parquet.writeBytes(rows)` returns the file as an `Array[Byte]`, and `Parquet.newWriter[A](out)`
writes to any `java.io.OutputStream`. Both work on every platform, including browsers.

### Schemas from Case Classes

`ParquetSchema.of[A]` shows the schema a writer derives:

| Scala field | Parquet column |
|---|---|
| `Int`, `Long`, `Short`, `Byte` | INT32, INT64, INT32 with INT(16), INT32 with INT(8) |
| `Float`, `Double`, `Boolean` | FLOAT, DOUBLE, BOOLEAN |
| `String`, `Char`, enums | STRING |
| `Array[Byte]` | BYTE_ARRAY |
| `Instant` | TIMESTAMP(MICROS, UTC); nanoseconds are dropped |
| `LocalDate` | DATE |
| `LocalTime` | TIME(MICROS), not adjusted to UTC; nanoseconds are dropped |
| `UUID` | UUID (FIXED_LEN_BYTE_ARRAY(16)) |
| `BigDecimal` | DECIMAL(38, 18) by default |
| `Option[A]` | an OPTIONAL column; other fields are REQUIRED |
| `Seq[A]`, `List[A]`, `Set[A]`, `Array[A]` | LIST (the standard 3-level layout) |
| `Map[K, V]` | MAP |
| a nested case class | a group |

A `LocalTime` field is a TIME column with no time zone, which DuckDB reads as `TIME`:

```scala
import java.time.LocalTime

case class Shift(worker: String, start: LocalTime, end: Option[LocalTime]) derives Weaver

val shifts     = Seq(Shift("aina", LocalTime.of(9, 30), Some(LocalTime.of(17, 0))))
val shiftBytes = Parquet.writeBytes(shifts)
```

Columns keep the field names as written (`userName`). A file written from a case class reads back
into the same case class with `readAs`. Recursive types, such as a case class with a
`Seq` of itself, and nested `Option[Option[A]]` have no Parquet layout, so the writer rejects them
with an error. DECIMAL(38, 18) holds any value with up to
20 digits before the point and 18 after, and reads in Spark and DuckDB. Set a narrower type for all
`BigDecimal` fields, or for one field by its path:

```scala
import wvlet.uni.parquet.ParquetWriteOptions

val opts = ParquetWriteOptions.default
  .withDecimal(18, 4)               // every BigDecimal field
  .withDecimal("price", 10, 2)      // one field (use "order.price" inside a nested case class)
```

A value that does not fit, such as `1.234` in a column with scale 2, is an error and is never
rounded.

### Records with an Explicit Schema

When rows are not case classes, build a schema and use a record writer. `write` accepts a
`Map[String, Any]`, a JSON object string, a `Seq` of values in schema order, or a JSON array
string:

```scala
import wvlet.uni.parquet.{ParquetSchema, ParquetType}

val schema = ParquetSchema
  .builder("event")
  .required("id", ParquetType.INT64)
  .optional("name", ParquetType.STRING)
  .optional("tags", ParquetType.list(ParquetType.STRING))
  .optional("price", ParquetType.decimal(10, 2))
  .build()

Control.withResource(Parquet.newRecordWriter("records.parquet", schema)) { w =>
  w.write(Map("id" -> 1, "name" -> "aina", "tags" -> Seq("a", "b")))
  w.write("""{"id": 2, "name": "leo", "price": "12.50"}""")
  w.write(Seq(3, "kai", Seq.empty, BigDecimal("1.5")))
  w.write("""[4, null, ["x"], null]""")
}
```

Missing columns are null, and keys without a column are ignored. Keys match column names loosely,
as when reading (`user_name` fills `userName`). Each column accepts the usual forms of its type: a
TIMESTAMP takes an `Instant`, an ISO-8601 string, or epoch milliseconds; a DECIMAL takes a number
or a string (use strings in JSON for exact values); a DATE takes a `LocalDate` or `yyyy-mm-dd`;
a TIME takes a `LocalTime`, `hh:mm:ss[.fraction]`, or nanoseconds of the day.
Other column types are `ParquetType.INT8`, `INT16`, `INT32`, `FLOAT`, `DOUBLE`, `BOOLEAN`,
`BINARY`, `ENUM`, `JSON`, `DATE`, `TIMESTAMP_MILLIS`, `TIMESTAMP_MICROS`, `TIMESTAMP_NANOS`,
`TIME_MILLIS`, `TIME_MICROS`, `TIME_NANOS`, `UUID`, `fixed(n)`, `map(key, value)`, and `struct(columns*)`.

A row that does not fit the schema, such as a missing required value or a string in an INT64
column, throws `ParquetException` naming the column. The row is not written, and the writer
stays usable. If the output fails (for example, the disk is full), the writer closes without a
footer, and the partial file does not read as Parquet.

### Column Batches

Code that already holds columns can write them without building rows. `Parquet.newBatchWriter`
takes `ColumnBatch`es, the same batches `readBatches` returns, so a batch read from one file can be
written to another unchanged. This copies two columns of a file:

```scala
val source  = Parquet.open("events.parquet")
val idsOnly = source.schema.select("id", "ts")
Control.withResource(Parquet.newBatchWriter("ids.parquet", idsOnly)) { w =>
  source.readBatches(ParquetReadOptions.default.withColumns("id", "ts")).foreach(w.write)
}
source.close()
```

`ColumnBatch.select(names*)` picks columns from a batch that holds more. To build vectors by hand,
use their constructors with the schema's fields. Values are physical, as when reading: a
TIMESTAMP is a `Long` in its unit, a DATE is days in an `IntColumn`, a DECIMAL is its unscaled
value, and strings are UTF-8 bytes in a `BinaryColumn`, where row `i` is
`data[offsets(i), offsets(i + 1))`. The last argument marks null rows (or `null` when no row is
null):

```scala
import wvlet.uni.parquet.{ColumnBatch, ColumnVector}

val metrics = ParquetSchema
  .builder("metric")
  .required("ts", ParquetType.TIMESTAMP_MILLIS)
  .optional("host", ParquetType.STRING)
  .required("value", ParquetType.DOUBLE)
  .build()

val metricBatch = ColumnBatch(
  3,
  IndexedSeq(
    ColumnVector.LongColumn(metrics.field("ts").get, Array(1000L, 2000L, 3000L), null),
    ColumnVector.BinaryColumn(
      metrics.field("host").get,
      Array(0, 1, 2, 2), // "a", "b", and a null row
      "ab".getBytes("UTF-8"),
      Array(false, false, true)
    ),
    ColumnVector.DoubleColumn(metrics.field("value").get, Array(0.5, 1.5, 2.5), null)
  )
)
Control.withResource(Parquet.newBatchWriter("metrics.parquet", metrics))(_.write(metricBatch))
```

Lists, maps, and structs take `ListColumn`, `MapColumn`, and `StructColumn`: row `i` of a list or
map holds children `[offsets(i), offsets(i + 1))`. Columns are matched to the schema by name, in
any order. Every batch is checked before any of it is written: a missing or extra column, a vector
of the wrong type or size, malformed offsets, or a null in a required column throws
`ParquetException`, and the writer stays usable. Row groups close at the row group size, inside a
batch if needed, so batch sizes do not show in the file. On the JVM, writing batches is about 2 to
4 times faster than writing case classes.

### File Layout and Options

The writer produces files that other engines read: data page v1, PLAIN and RLE encodings, and
dictionary encoding for every column except booleans. A column whose dictionary grows past
`dictionaryPageSize` (1 MiB) switches to PLAIN for the rest of its row group. Each column chunk
stores min, max, and null counts, so `ParquetFilter`, DuckDB, and Spark can skip row groups.

```scala
import wvlet.uni.parquet.format.CompressionCodec

val tuned = ParquetWriteOptions.default
  .withCompression(CompressionCodec.LZ4_RAW)
  .withRowGroupSize(64L * 1024 * 1024) // default 128 MiB
  .withPageSize(512 * 1024)            // default 1 MiB
  .noDictionary()
```

Compression is SNAPPY by default, with UNCOMPRESSED (`noCompression()`), LZ4_RAW, ZSTD, and GZIP.
ZSTD gives the smallest files (30-60% smaller than SNAPPY on text and numbers) and, at its default
level 3, writes at about half SNAPPY's speed. `withCompression(CompressionCodec.ZSTD, 9)` (or
`withCompressionLevel(9)`) sets the level, from 1 to 22 as in other ZSTD writers. Level 9 writes
files 10-25% smaller than level 3, at about a seventh of the speed. Levels 1-3 compress alike, as do
9-22: the compressor has no faster mode, and no optimal parser for the top levels. Other codecs
take no level. GZIP uses the
platform's zlib, so browsers cannot write it. BROTLI files can be read but not written.

The writer holds one row group in memory, as compressed pages, and writes it when it reaches the
row group size. On the JVM and Scala Native, each row group goes to the file as soon as it is
complete. On Scala.js, a file output stream keeps the whole file in memory and writes it on
`close()`, so peak memory is the file size.

## From TypeScript

The same reader and writer are published to npm as `@wvlet/uni`, compiled with Scala.js, so
TypeScript and JavaScript code can use them without a Scala toolchain. The package carries its own
type declarations and needs no WebAssembly or native add-on.

```sh
npm install @wvlet/uni
```

```ts
import { openParquet, writeParquet } from "@wvlet/uni/parquet";

const bytes: Uint8Array = writeParquet(
  [{ id: 1n, name: "apple", at: new Date() }],
  [
    { name: "id", type: "int64", optional: false },
    { name: "name", type: "string" },
    { name: "at", type: "timestamp-micros" },
  ],
  { compression: "snappy" },
);

// A Uint8Array or ArrayBuffer, a Blob or File, a local path (Node.js), or an http(s) URL
const file = await openParquet(bytes);
const rows = await file.readRows({ columns: ["id", "name"] });
for await (const batch of file.batches()) {
  const ids = batch.column("id").values; // BigInt64Array
}
```

- INT64 is `bigint`, other numbers are `number`, decimals are strings, DATE is a `Date` at UTC
  midnight, and TIME and UUID are strings. Timestamps are `Date` (milliseconds) by default; pass
  `{ timestamps: "bigint" }` to read epoch values in the column's own unit without loss.
- In batches, plain INT32, INT64, FLOAT, and DOUBLE columns come as typed arrays with a `nulls`
  mask; other columns come as arrays of values.
- `writeParquet` takes `{ name, type, optional? }` columns, where `type` is a name such as
  `"int64"` or `"timestamp-micros"`, or `{ decimal: { precision, scale } }`, `{ list: T }`,
  `{ map: { key, value } }`, or `{ struct: [...] }`. Columns are optional unless they say
  `optional: false`. Its options are `compression` (`"snappy"`, `"none"`, `"lz4"`, `"zstd"`, or
  `"gzip"`) and `compressionLevel` for ZSTD.
- The package is about 360 KB minified and gzipped. In browsers, bundlers must leave out the
  Node.js built-ins it refers to (`os`, `path`, `zlib`): Vite does this by itself; with webpack, set
  `resolve.fallback: { os: false, path: false, zlib: false }`.

For browser pages that only read files and need a small bundle,
[hyparquet](https://github.com/hyparam/hyparquet) is a good choice.

## What Is Supported

- **Encodings**: PLAIN, RLE and bit-packing, dictionary (PLAIN_DICTIONARY, RLE_DICTIONARY),
  DELTA_BINARY_PACKED, DELTA_LENGTH_BYTE_ARRAY, DELTA_BYTE_ARRAY, and BYTE_STREAM_SPLIT, in data
  pages v1 and v2.
- **Compression**: UNCOMPRESSED, SNAPPY, GZIP, ZSTD, LZ4_RAW, the deprecated LZ4 (Hadoop framing),
  and BROTLI. The decoders are written in Scala, so every codec works on every platform. On the
  JVM, Scala Native, and Node.js, GZIP uses the platform's zlib.
- **Types**: all physical types and the logical types listed above, nested lists, maps, and
  structs, and the legacy list and map layouts that older writers produce.

Not supported: modular encryption, the LZO codec, the new ALP encoding, and a single row with
more than 2 GiB of values in one column. The writer does not produce data page v2, the DELTA encodings, page
indexes, or bloom filters, and does not compress with BROTLI. These fail with a
`ParquetException` that names the feature, or are left out of written files.

## When to Use Another Library

On the JVM, [parquet-java](https://github.com/apache/parquet-java) and
[Apache Arrow](https://arrow.apache.org/) read and write Parquet with vectorized readers and
writers tuned over many years, and [DuckDB](https://duckdb.org/) queries Parquet with SQL. They are
good choices for heavy analytics, very large files, and features such as page indexes and bloom
filters. Choose `uni-parquet` when you need Parquet on Scala.js or Scala Native, from a browser
over HTTP, or with no dependencies beyond Uni.
