# Pure-Scala Parquet Reader

## Goal

Read Apache Parquet files on JVM, Scala.js (Node.js and browsers), and Scala Native using only
Scala code, with no extra dependencies. Users get Parquet without bringing in parquet-java and its
Hadoop dependencies. The same code reads local files, files in object storage over HTTP range
requests, and in-memory buffers on every platform.

A writer is out of scope here. It gets its own plan once the reader is stable.

## Non-goals (for now)

- Writing Parquet
- Modular encryption (encrypted footers and columns)
- LZO, the deprecated Hadoop-framed LZ4 (`LZ4` codec id 5), and BROTLI (until phase 4)
- Query-engine features beyond skipping data: no joins, no expressions, no vectorized compute

## Module placement

A new cross-built module **`uni-parquet`** (`wvlet.uni.parquet`) for JVM, JS, and Native that
depends on `uni`.

- Keeping it out of `uni` keeps the main artifact small. The full reader plus codecs is roughly
  8–10k lines.
- It depends on `uni` for `IOPath`/`FileSystem`, the HTTP client (for range reads), `Rx`, and Weaver
  (to decode rows into case classes).
- Pure-Scala decompressors live in `wvlet.uni.parquet.codec` and are `private[parquet]` (decided
  2026-11-20). If `uni`'s HTTP stack wants them later (for example, `Content-Encoding: zstd`), move
  them to a shared `wvlet.uni.io.codec` package and make them public. That move is a separate
  decision.
- Scala.js removes unused code, so a browser bundle that never reads ZSTD files pays only for the
  codecs it actually calls.

## Architecture

The design keeps I/O separate from decoding. The decoding layer works only on byte arrays and never
does I/O, so the same code runs synchronously on JVM/Native and asynchronously on JS (browser file
reads and HTTP are async).

```
 ┌──────────────────────────── ParquetFile (driver) ──────────────────────────────┐
 │ 1. read last 8 bytes → footer length + "PAR1"                                  │
 │ 2. read footer → FileMetaData (Thrift compact)                                 │
 │ 3. plan: pick row groups and column chunks (projection, statistics pruning)    │
 │ 4. fetch column chunk byte ranges (merge nearby ranges into fewer requests)    │
 │ 5. hand bytes to the decoder → ColumnBatch / rows                              │
 └───────────────┬────────────────────────────────────────────────────────────────┘
                 │ ByteSource (only this layer does I/O)
   ┌─────────────┼───────────────┬─────────────────────┐
   FileSource    HttpRangeSource  BytesSource          (JS) BlobSource
   (JVM/Native/  (uni HTTP client) (Array[Byte])        (browser File/Blob)
    Node)

 Decoding (no I/O, shared by all platforms):
   thrift/  Compact Protocol reader + parquet.thrift structs
   codec/   Snappy, Gzip/inflate, LZ4 raw, ZSTD decompressors
   encoding/ PLAIN, RLE/bit-pack hybrid, dictionary, DELTA_*, BYTE_STREAM_SPLIT
   page/    data page v1 and v2, dictionary pages
   column/  ColumnChunkReader → typed column vectors
   record/  rebuild nested records from repetition/definition levels; row view for Weaver
```

### ByteSource

```scala
trait ByteSource extends AutoCloseable:
  def size: Long
  // Async read works everywhere and is the only option on JS (browser files, HTTP)
  def readAsync(offset: Long, length: Int): Rx[Array[Byte]]

// JVM/Native (and Node.js files) also have a synchronous read
trait SyncByteSource extends ByteSource:
  def read(offset: Long, length: Int): Array[Byte]
```

- `FileSource` uses a seekable channel on JVM, `pread` on Native, and `fs.readSync` with a position
  on Node. `StaticFilesCompat.readRange` already does this for static files; pull it out into
  `uni-core` `io` as a shared range-read helper instead of duplicating it.
- `HttpRangeSource` sends `HEAD` (or `Range: bytes=-N` for the footer) followed by
  `Range: bytes=a-b` requests through the uni HTTP client. It merges ranges that are close together
  to cut down on requests.
- `BytesSource` wraps an in-memory buffer. Tests use it, and so does JS code that already holds an
  `ArrayBuffer`.

### Public API sketch

Config classes follow the repo convention: `withXXX` for every field and `noXXX` for optional ones.

```scala
import wvlet.uni.parquet.*

// Open: footer and schema are read eagerly
val file = Parquet.open(IOPath("data/events.parquet"))  // sync on JVM/Native/Node
val file2 = Parquet.openAsync(HttpRangeSource("https://example.com/events.parquet")) // Rx[ParquetFile]

file.schema           // ParquetSchema: a tree of fields with physical and logical types
file.numRows          // Long
file.rowGroups        // Seq[RowGroupInfo] with byte sizes and column statistics
file.keyValueMetadata // Map[String, String] (e.g. the Arrow schema stored by pyarrow)

// Rows decoded into case classes through Weaver
case class Event(id: Long, user: String, ts: java.time.Instant, tags: Seq[String])
val events: CloseableIterator[Event] = file.readAs[Event]()

// Columns (fast path): one ColumnBatch per row group, or per batchSize rows
val opts = ParquetReadOptions.default
  .withColumns("id", "ts")
  .withBatchSize(64 * 1024)
  .withFilter(ParquetFilter.gt("ts", Instant.parse("2026-01-01T00:00:00Z"))) // only skips row groups (phase 4)
file.readBatches(opts).foreach { batch =>
  val ids: LongColumn = batch.column("id").asLong
  ...
}
```

Column vectors store primitive values unboxed:

```scala
sealed trait ColumnVector:
  def size: Int
  def isNull(i: Int): Boolean            // backed by a validity bitmap
final class IntColumn(values: Array[Int], validity: Bitmap)   extends ColumnVector
final class LongColumn(values: Array[Long], validity: Bitmap) extends ColumnVector
final class DoubleColumn(...); FloatColumn(...); BooleanColumn(...)
final class BinaryColumn(offsets: Array[Int], data: Array[Byte], validity: Bitmap) // strings too
final class FixedBinaryColumn(width: Int, data: Array[Byte], validity: Bitmap)
// Nested fields: ListColumn(offsets, child), StructColumn(fields), MapColumn(offsets, keys, values)
```

### Rows through Weaver, without re-encoding

`Weaver.unpack` reads from an `Unpacker` (`wvlet.uni.msgpack.spi.Unpacker`, a trait). The plan is to
implement `ColumnBatchUnpacker extends Unpacker`, which presents row *i* of a batch as a msgpack map
(or an array in schema order) by reading straight from the column vectors. Weaver decodes case
classes from it without serializing to msgpack bytes in between, and existing Weavers (Option, Seq,
Map, nested case classes, enums) work unchanged.

Mapping from Parquet logical types to the Scala types users expect:

| Parquet | Scala |
|---|---|
| INT32, INT64, FLOAT, DOUBLE, BOOLEAN | Int, Long, Float, Double, Boolean |
| STRING / ENUM / JSON (BYTE_ARRAY) | String |
| BYTE_ARRAY without annotation | Array[Byte] |
| DECIMAL (INT32, INT64, FLBA, BYTE_ARRAY) | BigDecimal |
| DATE | java.time.LocalDate |
| TIMESTAMP (millis/micros/nanos, UTC-adjusted or not) | Instant / LocalDateTime |
| INT96 (legacy) | Instant |
| UUID (FLBA 16) | java.util.UUID |
| LIST / MAP / group | Seq / Map / case class |
| optional field | Option[A] |

Check whether `java.time` and `UUID` already work on JS and Native in uni (Surface/Weaver may
already cover them). If not, convert those values to Long or String at first and add the types
later.

## Decoding layers

### Thrift Compact Protocol (`thrift/`)

- A reader for varints, zigzag, field-id deltas, lists, structs, and binary/string. About 300 LOC.
  It's close to the varint code in `msgpack/impl`.
- Write the structs by hand from `parquet.thrift`, covering only what a reader needs: FileMetaData,
  SchemaElement, RowGroup, ColumnChunk, ColumnMetaData, PageHeader (and the
  Data/DataV2/Dictionary page headers), Statistics, LogicalType union, KeyValue, and later
  ColumnIndex/OffsetIndex.
- Skip unknown fields so files from newer writers still parse.
- Hand-written structs are simpler than adding a code generator to the build. Revisit if the
  struct count goes past about 30.

### Encodings (`encoding/`)

| Encoding | Used for | Phase |
|---|---|---|
| PLAIN | all types | 1 |
| RLE / bit-packing hybrid | rep/def levels, dictionary indices, BOOLEAN (v2) | 1 |
| PLAIN_DICTIONARY / RLE_DICTIONARY | dictionary pages and indices | 1 |
| DELTA_BINARY_PACKED | INT32/INT64 | 2 |
| DELTA_LENGTH_BYTE_ARRAY | BYTE_ARRAY | 2 |
| DELTA_BYTE_ARRAY | BYTE_ARRAY (prefix compression) | 2 |
| BYTE_STREAM_SPLIT | FLOAT/DOUBLE (and INT/FLBA in newer specs) | 2 |

Bit unpacking is the hot loop. Write one specialized unpack function per bit width (1–32 and
33–64), generated by a small script and checked in, instead of a generic loop.

### Pages and column chunks (`page/`, `column/`)

- Data page v1: levels are compressed together with the values.
- Data page v2: levels are stored uncompressed before the values, and `is_compressed` can be
  false.
- Dictionary page: decode once per column chunk, then turn indices into values. Keep the dictionary
  in column-vector form (for example, a BinaryColumn for strings) so a dictionary-encoded string
  column only builds Strings when Weaver asks for them.
- CRC checks on pages are optional (off by default; `withVerifyChecksums(true)`).

### Nested records (`record/`)

- Rebuild records from repetition and definition levels, the method from the Dremel paper. Build
  list offsets and validity bitmaps for each nesting level, as Arrow does.
- Handle the LIST/MAP layouts that the spec's backward-compatibility rules allow: the
  `array`/`bag`/`element` naming variants and two-level lists written by old parquet-avro and
  parquet-thrift.
- Phase 1 supports flat schemas only (max repetition level 0). Optional fields work in phase 1
  because they need only definition levels.

## Compression (`codec/`)

All decompressors are written in Scala, take `(src, srcOff, srcLen, dst, dstOff, dstLen)`, and
write into a buffer sized from the page header's `uncompressed_page_size`.

| Codec | Plan | Phase |
|---|---|---|
| UNCOMPRESSED | — | 1 |
| SNAPPY | Raw-format decoder, about 150 LOC. The default in Spark, pyarrow, and DuckDB. | 1 |
| GZIP | Use `wvlet.uni.io.Gzip` where it exists (JVM, Native, Node). Add a pure-Scala inflate (~600 LOC) for browsers and use it when `FileSystem.isBrowser`. | 1 (browser inflate: 2) |
| LZ4_RAW | Block-format decoder, about 100 LOC | 2 |
| ZSTD | Decoder written from RFC 8878: frame and block parsing, FSE, Huffman, sequence execution, repeat offsets, multiple frames. About 2–3k LOC. | 3 |
| BROTLI | Decoder from RFC 7932 plus the ~120 KB static dictionary (as a resource, loaded lazily) | 4 (optional) |

Notes:

- Inside Parquet, ZSTD frames usually come from a single `ZSTD_compress` call with no dictionary.
  Dictionary frames can be rejected with a clear error at first.
- On Windows, Scala Native's javalib truncates zlib calls over 65,535 bytes. `Gzip` on Native
  must feed zlib in chunks of 8 KiB or less. Check this before using it for large pages.
- Later option: on Native, use `libzstd` through weak linking (as done for curl in
  `adr/2026-07-06-curl-shim-weak-linking.md`) when it's available, falling back to the pure
  decoder.

## Platform concerns

- **Long on Scala.js.** Scala.js has no native 64-bit integers, so INT64 and TIMESTAMP columns go
  through emulated Longs. Phase 1 uses `Array[Long]` on every platform and measures. If JS turns out
  slow, add a JS-specific `LongColumn` backed by `BigInt64Array`, or by two `Int32Array` halves,
  that creates `Long` values only when they're read.
- **Reading bytes.** Use explicit little-endian reads on `Array[Byte]` (shift and mask) everywhere.
  JVM `ByteBuffer`/`VarHandle` fast paths can come later behind a small helper, if benchmarks show
  they help.
- **Memory.** Read one column chunk at a time and release it before the next row group. Expose
  `withMaxBufferedBytes` for range merging. The off-heap work in
  `adr/2026-11-15-canvas-off-heap-memory.md` is not needed in v1.
- **Async on JS.** `readBatches` and `readAs` return an Rx on JS, built with
  `Rx` + `CloseableIterator` the way the HTTP client handles sync and async per platform (see
  `adr/2026-05-14-nodejs-sync-http.md`). Node.js local files can use the synchronous path.

## Phases

### Phase 1: Minimum reader (~3–4k LOC)

- `ByteSource` (file, bytes, and `HttpRangeSource`), footer parsing, Thrift compact reader, schema
  tree
- PLAIN, RLE/bit-pack, dictionary encodings; data page v1 and v2
- UNCOMPRESSED, SNAPPY, GZIP (platform Gzip)
- Flat schemas with optional fields; every physical type; the common logical types (string,
  date, timestamp, decimal, INT96)
- `readBatches` (columns) and `readAs[A]` (rows through `ColumnBatchUnpacker`), column
  projection
- `HttpRangeSource`: footer read with `Range: bytes=-N` (falling back to `HEAD` + range when a
  server rejects suffix ranges), merging nearby column-chunk ranges, and a clear error when a
  server ignores `Range` and answers `200` with the whole body. Tested against uni's own HTTP
  server, which already serves ranges for static files.
- Goal: read the default output of DuckDB, pyarrow, Spark, and Polars (with snappy) for flat
  tables, from local files and over HTTP

### Phase 2: Coverage

- Nested types (LIST, MAP, groups, the backward-compatible layouts)
- DELTA_* and BYTE_STREAM_SPLIT encodings
- LZ4_RAW, browser inflate
- Browser `BlobSource`

### Phase 3: ZSTD

- Pure-Scala ZSTD decoder with its own test suite: RFC examples, frames made by the `zstd` CLI
  at levels 1, 3, 9, and 19 including long-distance matching, and fuzzed inputs that must fail
  without crashing
- Turned on in the Parquet reader once it passes the test corpus

### Phase 4: Performance and data skipping

- Skip row groups using min/max/null-count statistics (`ParquetFilter`)
- Page-level skipping with ColumnIndex/OffsetIndex
- Bloom filters (optional)
- BROTLI (optional)
- JS Long optimization if benchmarks call for it

## Testing

- **Corpus.** Use a curated subset of [apache/parquet-testing](https://github.com/apache/parquet-testing)
  (Apache 2.0, the files under `data/`), checked in under `uni-parquet/src/test/resources/`. Keep
  each file small, record the source commit in a README there, and list files the reader rejects
  on purpose (encryption, LZO) with the error each one must give.
- **Files from other writers.** A script (`uni-parquet/scripts/gen-test-files.sh`, using `duckdb`
  and `pyarrow`) writes small files with every codec × encoding × page-version combination we
  support, plus a JSON file of expected rows for each. The script runs by hand, and its outputs are
  committed, so tests never call Python or DuckDB.
- **Tests compare** `readAs` and `readBatches` against the expected JSON on all three platforms
  (`parquetJVM/test`, `parquetJS/test`, `parquetNative/test`).
- **Unit tests** for each layer: Thrift structs, every bit width of bit unpacking, delta decoding at
  block and miniblock edges, and the Snappy/LZ4/ZSTD decoders against reference outputs.
- **Malformed input.** Truncated footers, wrong lengths, and corrupted pages must throw a
  `ParquetException` with a clear message. They must never cause an out-of-bounds read, an
  infinite loop, or an unbounded allocation. Check every length in the file against `size` and
  `uncompressed_page_size` limits before allocating, and add `PropertyCheck` fuzz tests.
- Follow UniTest style: `shouldBe`/`shouldMatch`, braces around test bodies, no mocks.

## Benchmarks

Add a small benchmark (like the HTTP benchmark examples) that reads a 1 GB, roughly TPC-H
`lineitem`-shaped file (snappy, then zstd) on JVM, Native, and Node, and compares rows per second
against parquet-java on the JVM, to show where Uni stands. Targets: within 2× of parquet-java on
the JVM for the column path in phase 1, and tighter after phase 4.

## Docs and housekeeping (when the API ships)

- A docs page `docs/guide/parquet.md` with reading local files, reading over HTTP, and decoding
  into case classes. Mention that DuckDB, Arrow, and parquet-java are good choices for heavier
  analytics, and that Uni fits when you need Parquet on JS and Native or with few dependencies.
- Update `docs/guide/for-ai-agents.md` and the `plugins/uni` skill with the canonical import and
  entry points, and bump the plugin version.
- Write an ADR when phase 1 is built, covering the separate module, decoding without I/O, and
  `ColumnBatchUnpacker` feeding Weaver.

## Decisions

- 2026-11-20: Compression codecs live in `uni-parquet` (`private[parquet]`) for now.
- 2026-11-20: HTTP range reading is part of phase 1.
- 2026-11-20: `readAs` decodes by field name. Weaver already matches names loosely
  (`user_id` fills `userId`), and positional decoding breaks when files differ in column order.
