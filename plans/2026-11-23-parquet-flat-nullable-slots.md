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
  - Each page's values decode into the buffer as today, then move to their row slots right away, back to front over that page only, while the page is in cache. Null slots are set to 0 (empty for strings, index -1 for dictionary strings), as `scatterLeaf` does now.
  - Values and level entries then line up one to one, so `take` slices both by the same range.
- Assembly takes slotted data as is: the value array becomes the vector, and the null mask becomes its nulls. `scatterLeaf` stays for flat columns with deeper definition levels (inside optional structs).
- Level arrays are not pooled: after this change a flat column's only level array is the null mask, which the vector keeps. Nested columns' level arrays could still be pooled; that is left out as they are rare in the target workloads.
- Tests compare slotted columns with the general path over: no nulls, some nulls, all nulls, nulls across page and batch boundaries, small batches, dictionary and PLAIN strings, INT32 decimals read as Long, and DATA_PAGE_V2.
- Benchmark: `ScanProbe` on TPC-H and TPC-DS SF1 files, alternating origin/main and this branch, with a digest of every value to show results are identical.

## Alternatives and Why Not?

- Branch-free `spread` in `scatterLeaf` only: a smaller win; it keeps the extra pass over the whole batch and the byte levels.
- Teach each value decoder (PLAIN, dictionary, delta) to write into row slots: avoids moving values once, but multiplies decoder code for every encoding; the per-page move is cache-hot and much cheaper than today's batch-wide pass.
