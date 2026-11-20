# ZSTD compression in the uni-parquet writer

## Goal

`ParquetWriteOptions.withCompression(CompressionCodec.ZSTD)` writes ZSTD pages on every platform,
readable by libzstd-based engines (DuckDB, Spark, Arrow) and by uni-parquet's own decoder. ZSTD is
the common production codec, and Polars uses it by default; SNAPPY stays Uni's default.

## Design

- `codec/ZstdCompressor.scala`, the inverse of the existing `Zstd` decoder, which it reuses for the
  code tables (`LLBase`, `MLBase`, the predefined distributions).
- One frame per page, with the content size: single segment up to a 2 MiB window, a window
  descriptor above it. No checksum or dictionary.
- Blocks of 128 KiB: an RLE block for a run of one byte, a raw block when compressing does not
  shrink it (the repeat offsets are then rolled back), otherwise a compressed block.
- Matches: a hash of 4-byte sequences (one position per bucket), one step of lazy matching, a
  last-offset check, backward extension, and a step that grows over data that does not match.
  Minimum match lengths of 5-7 and a 6-byte hash were measured; none helped overall.
- Literals: raw, RLE, or Huffman (11-bit limit; one stream up to 1023 literals, four above), with
  the tree's weights FSE-coded or stored 4 bits each, whichever is smaller. Weight probabilities
  are capped at half the table so the decoder finds the end of the two-state stream.
- Sequences: for each of LL, OF, and ML, an RLE, predefined, or in-block FSE table, picked by an
  estimated bit cost. Repeat codes when the offset is one of the last three.

## Checks

- Round trips through the uni-parquet decoder (fixed and randomized inputs, frames over 2 MiB),
  and the writer's codec test.
- Frames decoded by the `zstd` CLI (libzstd), and `scripts/check-writer-with-duckdb.sh` with an
  `orders-zstd.parquet` sample.
- Sizes against `zstd -3`: about equal on `/usr/share/dict/words` (855 KB vs 853 KB) and on INT64
  PLAIN data; 35-60% smaller than SNAPPY. Warm JVM speed about 125 MB/s, half of SNAPPY.

## Not included

Compression levels, per-column codecs, BROTLI compression.
