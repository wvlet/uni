# Decode flat nullable Parquet columns straight into row slots

## Goals

- Cut the Parquet decode CPU of the Wvlet engine's scans. DuckDB writes every TPC-H and TPC-DS column as OPTIONAL, so every column takes the nullable path.
- Remove the separate `Assembly.scatterLeaf` pass (17% self time of a TPC-DS fact-table scan) and the zeroing of byte level arrays and null masks (part of the ~11% zeroing).
- Keep every value identical, on JVM, Scala.js, and Scala Native, in pure Scala.

## Background

- The cursor decodes definition levels into a byte array, then the page's non-null values into a packed buffer. Assembly then walks the whole batch: it allocates a Boolean null mask and spreads values to their rows, back to front, over arrays that no longer fit in cache.
- For a flat column with one definition level (a top-level OPTIONAL field), the level is exactly "this row is null", so the byte levels and the null mask hold the same thing.
- TPC-H columns have no nulls: their pages decode every level as one RLE run of 1, which today still fills a byte level array per batch.
- Nested columns (lists, maps, levels above 1) keep the general Dremel path.

## Design

- Lane: Express — an internal performance change with no change in behaviour or API, outside every always-gate area. The launch prompt authorizes merge once CI is green.
- Flat columns with one definition level (no repetition) switch to a "slotted" mode in the cursor:
  - Definition levels decode straight into a Boolean null mask (true = null). A run of 1s writes nothing; the mask is allocated only when the first null shows up, so columns with no nulls carry no mask at all.
  - Dictionary pages of fixed-size values write each value to its row slot as they decode, run by run between nulls; other pages decode packed, then move to their slots run by run while the page is in cache. Null slots are set to 0 (empty for strings, index -1 for dictionary strings), as `scatterLeaf` does now.
  - Level decoding records the positions of the nulls (32 levels at a time, visiting only the 0 bits), so neither step needs a branch per row.
  - Values and level entries then line up one to one, so `take` slices both by the same range.
- Assembly takes slotted data as is: the value array becomes the vector, and the null mask becomes its nulls. `scatterLeaf` stays for flat columns with deeper definition levels (inside optional structs).
- Level arrays (definition and repetition levels of other columns) and null positions come from per-thread pools, as page buffers do; a batch's levels go back once its vectors are built. A row slot column's mask is not pooled, because the vector keeps it.
- Tests compare slotted columns with the general path over: no nulls, some nulls, all nulls, nulls across page and batch boundaries, small batches, dictionary and PLAIN strings, INT32 decimals read as Long, and DATA_PAGE_V2.
- Benchmark: `ScanProbe` on TPC-H and TPC-DS SF1 files, alternating origin/main and this branch, with a digest of every value to show results are identical.

## Alternatives and Why Not?

- Branch-free `spread` in `scatterLeaf` only: a smaller win; it keeps the extra pass over the whole batch and the byte levels.
- Teach each value decoder (PLAIN, dictionary, delta) to write into row slots: avoids moving values once, but multiplies decoder code for every encoding; the per-page move is cache-hot and much cheaper than today's batch-wide pass.
