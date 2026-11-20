---
description: Read Apache Parquet files in pure Scala on the JVM, Scala.js, and Scala Native, from local files, byte arrays, or HTTP Range requests, into case classes or column vectors.
---

# Parquet

`uni-parquet` reads [Apache Parquet](https://parquet.apache.org/) files. It is written in Scala
with no other dependencies, so the same code runs on the JVM, Scala.js (Node.js and browsers),
and Scala Native, and it brings in no Hadoop or native libraries. It reads local files, byte
arrays, and files on any HTTP server that supports Range requests, such as object storage.

```scala sbt
libraryDependencies += "org.wvlet.uni" %% "uni-parquet" % "__UNI_VERSION__"
```

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
| DATE, TIME | `String` in ISO-8601 (`2026-01-31`, `12:34:56`) |
| DECIMAL | `BigDecimal` |
| UUID | `String` or `java.util.UUID` |
| BYTE_ARRAY without a logical type | `Array[Byte]`, or `String` with `withBinaryAsString(true)` |

Older writers, such as Hive and Impala, store strings without the STRING annotation. To read
them as strings, use `ParquetReadOptions.default.withBinaryAsString(true)`.

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
  val opts = ParquetReadOptions.default.withColumns("id", "ts")
  file.readAs[Event](opts).foreach(println)
finally
  file.close()
```

`withColumns` reads only the named top-level columns and fetches nothing for the others.
`Parquet.open` also accepts an `Array[Byte]` or a `ByteSource`.

## Reading Column Vectors

`readRowGroup` and `readBatches` return a `ColumnBatch` per row group. Each column is a
`ColumnVector` that stores primitive values unboxed, which is faster than decoding rows.

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
`Instant` or a `BigDecimal`), and `batch.row(i)` returns a whole row as a `Map[String, Any]`.

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

`readRowGroupAsync`, `readBatchesAsync`, and `readAsAsync` work the same way. The server must answer
Range requests with `206 Partial Content` and allow them through CORS; a server that sends the whole
file instead gets a clear error. Uni's own server does this with
[StaticFiles](../http/server#static-files).

## Skipping Row Groups

Writers store min, max, and null counts for each column chunk. A `RowGroupFilter` skips row groups
whose statistics show that no row can match:

```scala
import wvlet.uni.parquet.RowGroupFilter.*

val recent = ParquetReadOptions.default
  .withRowGroupFilter(col("ts") >= Instant.parse("2026-01-01T00:00:00Z") && col("country") === "JP")
file.readAs[Event](recent)
```

The filter only skips whole row groups. Rows inside the row groups that are read come back
unchanged, so filter them again in your code. It checks top-level columns of integer, floating
point, boolean, string, DATE (compare with `LocalDate`), and TIMESTAMP (compare with `Instant`)
types; for other types, or when a column has no statistics, the row group is read.

## What Is Supported

- **Encodings**: PLAIN, RLE and bit-packing, dictionary (PLAIN_DICTIONARY, RLE_DICTIONARY),
  DELTA_BINARY_PACKED, DELTA_LENGTH_BYTE_ARRAY, DELTA_BYTE_ARRAY, and BYTE_STREAM_SPLIT, in data
  pages v1 and v2.
- **Compression**: UNCOMPRESSED, SNAPPY, GZIP, ZSTD, LZ4_RAW, the deprecated LZ4 (Hadoop framing),
  and BROTLI. The decoders are written in Scala, so every codec works on every platform. On the
  JVM, Scala Native, and Node.js, GZIP uses the platform's zlib.
- **Types**: all physical types and the logical types listed above, nested lists, maps, and
  structs, and the legacy list and map layouts that older writers produce.

Not supported: writing Parquet, modular encryption, the LZO codec, the new ALP encoding, and column
chunks with more than 2 GiB of values. They fail with a `ParquetException` that names the feature.

## When to Use Another Library

On the JVM, [parquet-java](https://github.com/apache/parquet-java) and
[Apache Arrow](https://arrow.apache.org/) read and write Parquet with vectorized readers tuned
over many years, and [DuckDB](https://duckdb.org/) queries Parquet with SQL. They are good choices
for heavy analytics and for writing files. Choose `uni-parquet` when you need to read Parquet on
Scala.js or Scala Native, from a browser over HTTP, or with no dependencies beyond Uni.
