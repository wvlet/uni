# Faster ZSTD entropy coding in uni-parquet

## Goal

Write ZSTD pages faster without changing a byte of output: the bit writing and table work of
`ZstdCompressor`, after #1042 added levels.

## Profile (JFR, level 3, 1 MiB pages of words, a CSV table, log text, INT64 values)

Before:

| Part | Share |
|---|---|
| Match finding (`findSequences`, `search`, `matchLength`) | ~61% |
| Sequences (`writeSequences`): bit writing 24%, table choice 7% | ~36% |
| Huffman literals | ~3% |

Match finding, not entropy coding, takes most of level 3; the levels note said otherwise and is
corrected.

## Changes

- `BackwardBitWriter` gathers bits in a 64-bit container and writes 32 at a time into an array
  of its own, appended to the `ByteBuf` on `close`, instead of one `writeByte` per byte.
- The sequence and Huffman loops keep the bit container and the FSE tables in local variables
  (as the decoder's hot loop does), with one flush check per group of fields: the three state
  updates, the two length extras, the offset extra; two literals per check for Huffman.
- The predefined FSE tables are built once; the code arrays are allocated once per frame; the
  table cost estimate reads log2 from a table instead of calling `math.log`.
- `FseCTable.normalize` lets the most probable symbol take the rounding difference in one step
  when it can, as zstd's `FSE_normalizeCount` does, instead of one step per unit. This changes
  some tables, by at most 3 bytes per file on the benchmark inputs.

## Results

Same JVM, the old and new compressor alternated over 60 rounds, median:

| Level | Before | After | Speedup |
|---|---|---|---|
| 3 | 180-220 MB/s | 240-275 MB/s | 1.25-1.33x |
| 6 | 94 MB/s | 101 MB/s | 1.08x |
| 9 | 21 MB/s | 23 MB/s | 1.06x |

Output was byte-identical to `main` before the `normalize` change (110 frames). After the change,
the `zstd` CLI decodes frames from all 22 levels on 9 inputs, and the DuckDB check passes.

## Next

Match finding is now about two thirds of level 3: the three repeat-offset loads on every
searched position are the first thing to try (check them only where a sequence just ended, or
only `rep(0)` between matches).
