# ZSTD compression levels in uni-parquet

## Goal

Let writers trade speed for size with ZSTD levels, as parquet-java, Spark, DuckDB, PyArrow, and
Polars do: `ParquetWriteOptions.withCompression(CompressionCodec.ZSTD, 9)` or
`withCompressionLevel(9)`, and `compressionLevel` in the npm package. Default level 3.

## API

- `ParquetWriteOptions.compressionLevel: Option[Int]`, with `withCompressionLevel`,
  `noCompressionLevel()`, and `withCompression(codec, level)`. The generic name follows the
  `compression_level` setting of PyArrow, DuckDB, and Polars, so GZIP can take one later.
- ZSTD takes 1-22. A level on another codec, or out of range, fails when the writer is created.
- npm: `writeParquet(rows, columns, { compression: "zstd", compressionLevel: 9 })`; also adds the
  `"zstd"` option that the ZSTD writer PR left out of the npm facade.

## Levels

| Level | Match finder |
|---|---|
| 1-3 | one hash slot, one lazy step |
| 4 | hash chain depth 4 |
| 5 | depth 8 |
| 6 | depth 8, two lazy steps |
| 7 / 8 | depth 16 / 32 |
| 9-22 | depth 64 |

Every level tries the three repeat offsets first and scores a repeat offset as about one bit, so a
deeper search does not trade a near repeat match for a slightly longer far one.

## Measurements (1 MiB pages, warm JVM)

| Input | Level 3 | Level 6 | Level 9 | `zstd -3` | `zstd -9` |
|---|---|---|---|---|---|
| `/usr/share/dict/words` 2.5 MB | 813 KB | 730 KB | 727 KB | 853 KB | 748 KB |
| CSV table from DuckDB 9.6 MB | 759 KB | 749 KB | 674 KB | 617 KB | 550 KB |
| Synthetic log text 1.8 MB | 159 KB | 153 KB | 117 KB | 196 KB | 159 KB |
| INT64 PLAIN 1.6 MB | 411 KB | 411 KB | 411 KB | 411 KB | 411 KB |

Speed: level 3 100-300 MB/s, level 6 45-110 MB/s, level 9 13-25 MB/s.

Tried and dropped:

- Levels 1-2 without the lazy step, or with faster skipping over unmatched data: larger files at
  the same speed, because entropy coding bounds the speed there. They compress as 3.
- Chain depths 128-2048 for levels 10-22: no smaller (sometimes 30% larger) and 4-30x slower.
  They compress as 9.

## Checks

- Round trips at every level in `CompressorTest`; frames from all 22 levels on 9 inputs decoded
  by the `zstd` CLI; the writer test and npm test at levels 1, 9, and 19; level-trend checks.

## Not included

GZIP levels (`wvlet.uni.io.Gzip` takes none), optimal parsing, faster entropy coding.
