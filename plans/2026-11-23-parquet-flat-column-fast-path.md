# Faster Parquet reads of flat nullable columns

## Goals

- Cut the CPU that column assembly takes when Wvlet's engine scans TPC-DS tables, which DuckDB writes with every column OPTIONAL.
- Cut the allocations per batch, which show up as GC time in the same profiles.
- Keep every result identical, and keep the nested (list, map, struct) path unchanged.

## Background

- Profiles of TPC-DS SF1 on the Wvlet engine put uni's Parquet code at 24–74% of engine CPU; `Assembly.gatherLeaf`, `rowRanges`, and `Leaf.valueIndex` are the top frames.
- `Assembly` was written for the Dremel record-shredding general case. Flat columns (repetition level 0) reach it too: each gets two identity range arrays of numRows, and any column with one null leaves the direct path for `gatherLeaf`, which allocates `valueIndex`, `pick`, the nulls array, and the output, in several passes.
- `directLeaf` copies the decoded buffer even when its array is already exactly the vector (true whenever the cursor hands out a slice).
- When `ColumnChunkCursor.take` hands out its whole buffer, it restarts the next buffer at 1,024 entries, so every batch regrows by doubling from there (`IntBuffer.ensure`, `LongBuffer.ensure`, `ensureLevels` in the profiles).

## Design

- Lane: Express — an internal performance change with no change in behaviour or API, outside every always-gate area. The launch prompt says not to merge, so the PR stays open for review.
- Flat leaves skip the range machinery: `rowRanges` records an identity range without arrays, and structs reading null flags through it see the same starts.
- A flat leaf with no nulls hands over the decoded array when its length is exactly the row count, and copies it once otherwise.
- A flat leaf with nulls is filled in one pass over the definition levels, writing values and the null mask together; binary data stays shared, and dictionary indices come from the same pass.
- Leaves under lists and maps keep `gatherLeaf` as it is today.
- After handing out its whole buffer, the cursor sizes the next value and level buffers from the batch it just handed out, capped by the values left in the chunk, so buffers stop regrowing from 1,024 every batch. Sizing follows what was actually read, so a corrupt value count still cannot force a huge allocation.
- Tests compare the new path against the general path for: flat required, optional with no nulls, some nulls, all nulls, dictionary-encoded strings with nulls, structs over optional leaves, and multi-page chunks over several batches.
- A timing probe on TPC-DS SF1 `store_sales` (2.9M rows, 23 optional columns) measures before and after; the numbers go in the PR.

## Alternatives and Why Not?

- Let column vectors carry a length shorter than their arrays: avoids the remaining copy, but changes the public `ColumnVector` contract that every caller reads through `values.length`.
- Specialize the decoders to write straight into the batch's output: a larger rewrite of `ValueDecoder`; worth it only after this change shows what is left.
