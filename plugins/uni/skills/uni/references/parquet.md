# Reading and writing Parquet files

`uni-parquet` (`"org.wvlet.uni" %% "uni-parquet"`) reads and writes Apache Parquet in pure Scala
on the JVM, Scala.js, and Scala Native. It needs no parquet-java or Hadoop.

## Rows into case classes

```scala
import wvlet.uni.parquet.{Parquet, ParquetReadOptions}
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
- Prefer `readRows` over `readAs[Map[String, Any]]`: the latter goes through Weaver, so integers
  become `Long` and DATE, DECIMAL, and UUID become strings.
- `ParquetFile` also has `readRows(options)`, `readJson(options)`, and, for browsers,
  `readRowsAsync`, `readAllRowsAsync`, and `readJsonAsync`.

## Open a file, project columns, read vectors

```scala
import wvlet.uni.parquet.ColumnVector.LongColumn

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
import wvlet.uni.storage.{ObjectStore, S3Config}

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
import wvlet.uni.storage.AzureConfig

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

## Skip row groups with statistics

```scala
import wvlet.uni.parquet.RowGroupFilter.col

val since2026 = ParquetReadOptions.default
  .withRowGroupFilter(col("ts") >= Instant.parse("2026-01-01T00:00:00Z"))
```

This skips whole row groups only; rows of the groups that are read are not filtered, so filter
them again. Only top-level integer, float, boolean, string, DATE (`LocalDate`), and TIMESTAMP
(`Instant`) columns are checked.

## Write case classes

Any case class with `derives Weaver` writes as is: no schema, annotations, or codecs. The same
`Event` round-trips: `Parquet.readAs[Event](path)` returns what `Parquet.write(path, events)` wrote.
Recursive types and `Option[Option[A]]` are rejected.

```scala
import wvlet.uni.control.Control
import wvlet.uni.parquet.{ParquetSchema, ParquetWriteOptions}

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
  enums STRING, `Array[Byte]` BYTE_ARRAY, `Instant` TIMESTAMP(MICROS, UTC; nanoseconds dropped),
  `UUID` UUID, `Short`/`Byte` INT32 with INT(16)/INT(8).
- `BigDecimal` is DECIMAL(38, 18) by default. Change it with
  `ParquetWriteOptions.default.withDecimal(18, 4)`, or per field with
  `.withDecimal("price", 10, 2)`. A value that does not fit is an error, never rounded.
- Pass options as the second argument: `Parquet.write(path, rows, opts)`,
  `Parquet.newWriter[Event](path, opts)`. `Parquet.newWriter[A](out)` writes to an `OutputStream`.

## Write records with an explicit schema

```scala
import wvlet.uni.parquet.ParquetType

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
  DATE takes `LocalDate` or `yyyy-mm-dd`.
- More types: `INT8`, `INT16`, `INT32`, `FLOAT`, `DOUBLE`, `BOOLEAN`, `BINARY`, `ENUM`, `JSON`,
  `DATE`, `TIMESTAMP_MICROS`, `UUID`, `decimal(p, s)`, `fixed(n)`, `map(k, v)`,
  `struct(ParquetColumn.required(...), ...)`.
- A row that does not fit throws `ParquetException` naming the column; the row is skipped and the
  writer stays usable.

## Writer options

`ParquetWriteOptions.default` writes SNAPPY, data page v1, dictionary encoding (falls back to
PLAIN past `dictionaryPageSize`, 1 MiB), 1 MiB pages, and 128 MiB row groups, with min/max/null
statistics. Change them with `withCompression(CompressionCodec.LZ4_RAW)` (also `GZIP`, not in
browsers), `noCompression()`, `noDictionary()`, `withPageSize`, and `withRowGroupSize`. One row
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

## Limits

No modular encryption, LZO codec, or ALP encoding, and one row must hold under 2 GiB of values
in each column. These fail with `ParquetException`. The writer does not compress with ZSTD or BROTLI and
writes no data page v2, DELTA encodings, page indexes, or bloom filters.
