# Fuzz the Parquet reader

## Goals

- Corrupt or hostile Parquet files (from object stores, HTTP, user uploads) must fail with `ParquetException`, never an index, cast, negative-size, or out-of-memory error, and never spin on counts taken from the file.
- What Uni's writer writes, under every codec and for generated nested schemas, must read back as the same records.
- Bugs the fuzzer finds get fixed, with a corpus file and a unit test each.

## Background

- The reader plan (plans/2026-11-20-parquet-reader.md) already requires that every length from the file be checked against the file size and `uncompressed_page_size` before allocating, and asks for fuzz tests. No Parquet fuzz target exists yet.
- `ParquetException` is the reader's own error: `ParquetException.corrupt(...)` for bad data, `unsupported(...)` for features Uni lacks, and `fromCodec` turns codec errors into it.
- The decompressor half (Snappy, LZ4, zstd, gzip, DEFLATE, Brotli) is fuzzed in #1211, whose `CodecFuzzTest` is the model here. This PR fuzzes the codecs only as page payloads inside Parquet files. (`CliFuzzTest` does not exist in the repo.)
- The page decoders are `private[parquet]`, so the suite must live in the `wvlet.uni.cloud.parquet` test package. #1211 changed the nightly job from `wvlet.uni.fuzz.*` to `*FuzzTest` for the same reason.
- Uni's writer emits only PLAIN, RLE_DICTIONARY, and RLE levels. DELTA_*, BYTE_STREAM_SPLIT, and page v2 seeds must come from the committed Apache and DuckDB fixture files (most under 3 KB).

## Design

Lane: Express — tests plus fixes for the bugs they find; no API or behavior change beyond rejecting bad input with `ParquetException`.

- One new suite, `ParquetFuzzTest` in `uni/src/test/scala/wvlet/uni/cloud/parquet/`, picked up by the nightly `*FuzzTest` pattern. It follows `CodecFuzzTest`: corpus in `uni/src/test/resources/fuzz/<target>/`, `ParquetException` as the only expected exception, fewer iterations on Native.
- **Whole-file target:** seeds are small fixture files plus writer output under each codec. The body opens the file and reads every row with `readRows` (footer Thrift, page headers, every page decoder, assembly) and with `readAs[Any]` (`VectorUnpacker`). A dictionary of Thrift field headers and `PAR1` helps mutations hit metadata.
- **Footer target:** the fuzzed bytes become the footer only (valid `PAR1` framing added around them), so mutations go into the Thrift metadata instead of being wasted on page bytes.
- **Page decoder targets:** fuzz `ValueDecoder.decode` (per encoding and physical type, including dictionary indices past the dictionary size), `DeltaDecoder`, and `RleBitPackedDecoder` directly. The first bytes of an input choose the encoding, type, bit width, and value count; the rest is the page body. Seeds are page bodies cut out of writer and fixture files.
- **Round-trip target:** `fuzzGen` generates a schema (primitives, logical types, optional fields, and lists, maps, and structs via `Gen.recursive`), rows that fit it, a codec, and dictionary on/off. Writing with `newRecordWriter` and reading back with `readRows` and `readAs` must give the same rows, compared after normalizing what the reader intentionally returns differently (for example, timestamps as `Instant`).
- **Fixing bugs:** sizes and counts from the file are checked against the remaining bytes (or a page/file bound) before an array is allocated; a bad value raises `ParquetException.corrupt` with a message naming the field. Each fix adds a reduced crash file to the corpus and a unit test next to the existing Parquet tests.
- **Slow inputs:** `withSlowInputMillis` on the file targets catches inputs whose claimed row or value counts make the reader spin. A true infinite loop still blocks the test (no thread-based timeout, per the ADR).
- The ADR gets an "Update:" section listing the targets and the bugs found.

## Alternatives and Why Not?

- **Fuzz only whole files.** Most mutations break the footer or page headers early (the reader does not check the page CRC), so the page decoders rarely see interesting bodies; direct decoder targets reach them.
- **Use pyarrow/DuckDB as a round-trip oracle.** Needs external tools in CI; the reader is already checked against their files, so Uni's own writer is the oracle here.
