---
description: Write Apache Arrow IPC streams and files in pure Scala on the JVM, Scala.js, and Scala Native, to deliver column batches to pyarrow, pandas, polars, Arrow JS, and ADBC clients without converting rows.
---

# Arrow IPC

`wvlet.uni.arrow` writes [Apache Arrow IPC](https://arrow.apache.org/docs/format/Columnar.html#serialization-and-interprocess-communication-ipc)
streams and files from the column batches that [Parquet](./parquet) reads. A server can then send
query results to Python (pyarrow, pandas, polars), Node.js (Arrow JS), and ADBC clients. Those clients load the columns
directly instead of decoding JSON rows. Like the rest of Uni, it is pure Scala with no dependencies,
so the same code runs on the JVM, Scala.js, and Scala Native.

Arrow in Uni is for handing results to end users. Uni writes Arrow but does not read it yet.

## Write a stream

```scala
import wvlet.uni.arrow.Arrow
import wvlet.uni.parquet.Parquet

val in    = Parquet.open("events.parquet")
val bytes = Arrow.toStreamBytes(in.schema, in.readBatches())
in.close()
```

Each `ColumnBatch` becomes one Arrow record batch. Serve the bytes as `Arrow.StreamMediaType`
(`application/vnd.apache.arrow.stream`), and read them on the client:

```python
import pyarrow.ipc, polars
table = pyarrow.ipc.open_stream(body).read_all()   # or .to_pandas()
df = polars.read_ipc_stream(body)
```

```ts
import { tableFromIPC } from "apache-arrow";
const table = tableFromIPC(new Uint8Array(await response.arrayBuffer()));
```

For results too large to hold in memory, write batch by batch to an `OutputStream`:

```scala
import wvlet.uni.arrow.{Arrow, ArrowCompression, ArrowWriteOptions}

def send(path: String, out: java.io.OutputStream): Unit =
  val in = Parquet.open(path)
  try
    val w = Arrow.newStreamWriter(
      out,
      in.schema,
      ArrowWriteOptions.default.withCompression(ArrowCompression.ZSTD)
    )
    try
      in.readBatches().foreach(w.write)
      w.close()
    catch
      case e: Throwable =>
        w.abort()
        throw e
  finally in.close()
```

`close()` writes the end-of-stream marker and closes the output. When a write fails, call `abort()`
and fail the response instead. Readers accept a stream that ends between batches, so a closed stream
would look like a complete, shorter result.

## Write a file

`Arrow.newFileWriter(out, schema)` and `Arrow.toFileBytes(schema, batches)` write the file format
(`.arrow`, also called Feather v2). It is the stream with a footer that lists every batch, so
readers can seek to any batch: `pyarrow.ipc.open_file(path)`, `polars.read_ipc(path)`.

## Types

`ArrowSchema.fromParquet(schema)` returns the Arrow schema a writer writes:

| Parquet column | Arrow type |
|:---------------|:-----------|
| BOOLEAN | bool |
| INT32, INT64, INT(8/16/32/64, signed or not) | int8 to int64, uint8 to uint64 |
| FLOAT, DOUBLE, FLOAT16 | float32, float64, float16 |
| STRING, ENUM, JSON | utf8 |
| BYTE_ARRAY, BSON, GEOMETRY, GEOGRAPHY | binary (WKB for the geo types) |
| FIXED_LEN_BYTE_ARRAY(n) | fixed_size_binary(n) |
| UUID | fixed_size_binary(16) with the `arrow.uuid` extension |
| DATE | date32 |
| TIME(MILLIS), TIME(MICROS/NANOS) | time32(ms), time64(us/ns) |
| TIMESTAMP(unit), UTC-adjusted | timestamp(unit, "UTC") |
| TIMESTAMP(unit), not UTC-adjusted | timestamp(unit) with no time zone |
| INT96 | timestamp(ns, "UTC"); years outside 1677 to 2262 fail |
| INTERVAL | interval(month_day_nano) |
| DECIMAL(p, s) | decimal128(p, s), or decimal256 above precision 38 |
| UNKNOWN | null |
| LIST, repeated fields | list |
| MAP | map |
| other groups | struct |

- OPTIONAL fields are nullable, and a validity bitmap is written only for a column that has nulls.
- Strings are always plain utf8, even when Parquet stored them in a dictionary, so pandas gets
  `object` (or `string`) columns rather than `category`.
- A VARIANT column throws `ArrowException` when the writer is created, before any bytes are written.
- A batch that does not fit the schema throws `ArrowException` before any of its bytes are written.
  Examples are a missing column, a null in a required column, or a value outside an int8 column.

## Options

`ArrowWriteOptions.default` writes uncompressed record batches, which many readers load without a
copy.

| Option | Effect |
|:-------|:-------|
| `withCompression(ArrowCompression.ZSTD)` | ZSTD buffers; `withCompression(ArrowCompression.ZSTD, 9)` sets a level (1 to 22) |
| `withCompression(ArrowCompression.LZ4_FRAME)` | LZ4 frame buffers |
| `withBinaryAsString(true)` | BYTE_ARRAY columns without a logical type as utf8, for files from Hive or Impala |
| `withMetadata(map)` | Custom metadata of the schema |

A buffer that does not shrink is stored uncompressed, as the format allows. Buffers are padded to
8 bytes.
