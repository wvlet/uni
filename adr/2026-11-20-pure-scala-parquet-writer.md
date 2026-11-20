# A pure-Scala Parquet writer in uni-parquet

- Status: Accepted
- Date: 2026-11-20
- Plan: `plans/2026-11-20-parquet-writer.md`
- Supersedes: the "Writing" item under "Not supported" in
  [`2026-11-20-pure-scala-parquet-reader.md`](2026-11-20-pure-scala-parquet-reader.md)

## Context

uni-parquet read Parquet on every platform but could not write it, so a Scala.js or Scala Native
program, or a JVM program that wants no Hadoop dependencies, had no way to produce a file. The
reader already had the format enums, the footer and page-header model, `ParquetSchema`, the
bit-packing code, and the decoders, which the writer reuses.

## Decision

- **One value path for every input.** Case classes go through Weaver to MessagePack, JSON through
  the existing JSON-to-MessagePack converter, and `Map`/`Seq`/Scala values through a small
  packer. A schema-directed shredder reads each row's MessagePack bytes as a stream and turns them
  into leaf values with repetition and definition levels (Dremel striping, the inverse of the
  reader's assembly). Reading case-class fields through Surface getters would be faster, but it
  would need a second path for maps and JSON and would duplicate Weaver's type handling.
  - Amended in PR 2 of `plans/2026-11-20-parquet-read-write-performance-and-batch-api.md`: the
    first version unpacked each row into a `Value` tree (every map a `ListMap`) and looked fields
    up by name, which took about half of the write time. The path stays single, but it is now a
    byte stream: struct fields are matched by position in schema order, so Weaver's output needs
    no lookup, with the exact-then-loose name lookup as the fallback for keys out of order (JSON
    and maps). When a key appears twice, the first one counts. Values are staged and passed to the
    column writers unboxed (typed appends, statistics, and open-addressing dictionaries). Common
    value forms are converted from the bytes directly; the rest go through the same per-column
    converters as before.
  - If this ever falls short, the next step is a `Packer` that shreds as Weaver packs, with no
    bytes in between.
- **Conversion per column, not per input.** Weaver packs `BigDecimal` and `UUID` as strings,
  `Instant` as a timestamp extension, and `Array[Byte]` fields as arrays of numbers; JSON has no
  timestamps or decimals. Each leaf column has one converter that accepts all of these forms
  (timestamp extension, epoch millis, or ISO-8601 string for TIMESTAMP; number or string for
  DECIMAL; and so on). Weaver's packing is not changed, because that would change every Uni user's
  JSON. Weaver's `Array[Byte]` now also *reads* MessagePack binary, an additive change that the
  reader needed too (its docs promised `Array[Byte]` for BYTE_ARRAY).
- **Rows are all-or-nothing.** The shredder stages a row (in typed buffers per column) and copies
  it to the column writers only when every value fits, so a `ParquetException` for a bad value leaves the writer usable. An I/O
  error closes the output without a footer, so a partial file never reads as Parquet.
- **Schemas from Surface, with Scala's nullability.** `ParquetSchema.of[A]`: non-`Option` fields
  are REQUIRED, collections are 3-level LISTs, `Map` is a MAP with required keys, case classes are
  groups. Surface resolves nested case classes at compile time, so the `knownSurfaces` argument of
  airframe-parquet is not needed; recursive types fail with a clear error. Schemas built by hand
  (`ParquetSchema.builder`, `ParquetType`) go through the same footer schema elements and
  `ParquetSchema.fromElements`, so levels and column indexes are computed by one piece of code.
- **DECIMAL(38, 18) as the `BigDecimal` default**, stored as FIXED_LEN_BYTE_ARRAY(16): the widest
  decimal Spark reads, with a fixed type rather than one taken from the data. `withDecimal` changes
  it for all fields or one field path. Narrower decimals use INT32/INT64 as the spec recommends.
  Values that need rounding or more digits are rejected, never rounded.
- **`Instant` is TIMESTAMP(MICROS, UTC)**, the most widely read unit; nanoseconds are dropped.
- **The most compatible layout by default:** data page v1, PLAIN and RLE, dictionary encoding with
  RLE_DICTIONARY data pages and a PLAIN dictionary page, a fallback to PLAIN for the rest of the row
  group once the dictionary passes 1 MiB, 1 MiB pages and 128 MiB row groups. Pages close only at
  row boundaries. Both converted types and logical types are written, and `column_orders` is set
  so readers trust `min_value`/`max_value`. Numeric columns also get the deprecated min/max for
  old readers; binary columns do not, since those used signed byte order. Binary bounds over 64
  bytes are left out rather than truncated.
- **Pure-Scala Snappy and LZ4_RAW compressors** (greedy matching on a 4-byte hash), GZIP through
  `wvlet.uni.io.Gzip` where a platform zlib exists. ZSTD and BROTLI compressors are much larger and
  are left out; SNAPPY stays the default, as in parquet-java and Spark. The compressors stay
  `private[parquet]` with the decoders.
  - Amended by `plans/2026-11-20-parquet-zstd-writer.md`: a pure-Scala ZSTD compressor
    (`ZstdCompressor`), since ZSTD is the common production choice in Spark, DuckDB, and Polars.
    One frame per page with the content size (single segment up to the 2 MiB window); matches
    from a 4-byte hash with one lazy step and a last-offset check; Huffman literals; FSE sequences
    with a predefined, RLE, or in-block table per code, picked by estimated cost; blocks that do
    not shrink stored raw. No dictionaries, checksums, repeat-mode tables, or levels: one setting
    near zstd's fast levels keeps the code small. SNAPPY stays the default.
- **One row group in memory.** Pages are compressed as they close and the row group is written
  when its encoded size reaches the limit, so memory is bounded by the row group size. On Scala.js,
  `FileSystem.writeStream` itself buffers the file until `close()`, which the docs state.
- **Output is a `java.io.OutputStream`**: a file through `FileSystem.writeStream`, or any stream,
  such as `ByteArrayOutputStream` for in-memory bytes on every platform. The writer closes the
  stream it is given.

## Alternatives

- **Wrap parquet-java on the JVM.** Rejected for the same reason as the reader: two
  implementations, and Hadoop on the JVM.
- **Give Weaver a binary codec for `Array[Byte]`.** Cleaner for Parquet, but it changes JSON and
  MessagePack output for every Uni user; accepting both forms in the converter avoids that.
- **Take the decimal scale from the first value.** A file whose type depends on its data is
  surprising and breaks appends of later files to the same table.
- **Write data page v2 by default.** Some engines still read v1 best; v2 can be added as an option.

## Not supported

Data page v2, the DELTA and BYTE_STREAM_SPLIT encodings, page indexes, bloom filters, BROTLI
compression, INT96 columns, and encryption. The dev-time script
`uni-parquet/scripts/check-writer-with-duckdb.sh` checks written files against DuckDB; committed
tests only round-trip through the uni-parquet reader.
