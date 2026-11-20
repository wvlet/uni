# Reading Parquet files

`uni-parquet` (`"org.wvlet.uni" %% "uni-parquet"`) reads Apache Parquet in pure Scala on the JVM,
Scala.js, and Scala Native. It is read-only. Do not add parquet-java or Hadoop for reading.

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

`file.schema`, `file.numRows`, and `file.rowGroups` (with statistics) come from the footer.
`batch.row(i)` gives a `Map[String, Any]`; `vector.getValue(i)` applies the logical type.

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

## Skip row groups with statistics

```scala
import wvlet.uni.parquet.RowGroupFilter.col

val since2026 = ParquetReadOptions.default
  .withRowGroupFilter(col("ts") >= Instant.parse("2026-01-01T00:00:00Z"))
```

This skips whole row groups only; rows of the groups that are read are not filtered, so filter
them again. Only top-level integer, float, boolean, string, DATE (`LocalDate`), and TIMESTAMP
(`Instant`) columns are checked.

## Limits

Read-only. No modular encryption, LZO codec, or ALP encoding; a column chunk must hold under
2 GiB of values. These fail with `ParquetException`.
