# Faster ZSTD match finding in uni-parquet

## Goal

Speed up the match finder of `ZstdCompressor`, which a JFR profile after
`plans/2026-11-20-zstd-entropy-coding.md` showed taking most of the time: about 60% of level 3
and 89% of level 9 (`search`, walking hash chains up to 64 deep).

## Changes

- The compressor's `load32` uses `Word8.le32` (added by #1044) instead of `Zstd.le32`, four
  byte loads; every searched position does 2-5 of them. Output unchanged. `Word8.le32` on Scala
  Native read 8 bytes through `le64`, so it needed `b(i)` to `b(i + 7)` inside the array; it now
  reads the aligned words holding `b(i)` and `b(i + 3)`, so `load32` at the input's last 4 bytes
  stays inside the array on every platform. `matchLength` is `Word8.matchLength`, also from #1044.
- The main loop's check of the last offset is not repeated inside `search`. Output unchanged.
- In the chain walk, a candidate is measured only when the 4 bytes up to the byte that would
  extend the best match agree (one `le32` comparison instead of one byte). A candidate that
  fails cannot be longer, so the output is unchanged; with byte-assembled loads this was slower.
- A chain search stops at a 32-byte match (zstd's target length).
- The lazy steps' searches at the next one or two positions walk half the chain depth: they only
  ask whether a later match is better.

## Results

`main`'s compressor and this one in the same JVM, alternated over 40 rounds, median; 1 MiB pages
of words, a CSV table, log text, and INT64 values:

| Level | Size vs `main` | `main` | This change | Speedup |
|---|---|---|---|---|
| 3 | identical | 287 MB/s | 304 MB/s | 1.06x |
| 4 | +0.04% | 165 MB/s | 196 MB/s | 1.19x |
| 6 | +0.04% | 100 MB/s | 136 MB/s | 1.36x |
| 9 | -0.18% | 23 MB/s | 43 MB/s | 1.86x |

Tried and dropped:

- Checking the second and third repeat offsets only within 1, 8, or 32 bytes after a match: no
  measurable change at level 3 beyond `le32`.
- Target lengths of 64 and 128: less speedup at level 9 (1.09x, 0.99x) for no size gain.
- Lazy searches at a quarter depth: faster at level 6 (1.56x) but 0.22% larger at level 4.

## Checks

The `zstd` CLI decodes frames from all 22 levels on 9 inputs (198 frames); the DuckDB check;
the full uni-parquet suites on JVM, Scala.js, and Native; a new `le32` test at every offset.

## Next

Level 3 gained least: its time is the hash-table lookup and the three repeat-offset checks at
every position, which the variants above did not reduce.
