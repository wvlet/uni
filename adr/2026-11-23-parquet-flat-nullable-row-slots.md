# Flat nullable Parquet columns decode into row slots

- Status: accepted
- Date: 2026-11-23

## Context

DuckDB writes every column of TPC-H and TPC-DS as OPTIONAL, so a scan of its files reads every column through the nullable path. Before this decision, `ColumnChunkCursor` decoded definition levels into a byte array and the non-null values into a packed buffer, and `Assembly.scatterLeaf` then allocated a Boolean null mask and moved every value to its row, one branch per row. That pass took 17–19% of the CPU of a TPC-DS fact-table scan.

For a flat column with one definition level (maxDefinitionLevel 1, no repetition, which covers every top-level OPTIONAL column), the level says only whether the row is null.

## Decision

- Such columns are "row slot" columns in the cursor. Their 1-bit levels decode straight into the Boolean null mask (`RleBitPackedDecoder.readNulls`), and nothing else. A run of 1s writes nothing, and the mask is allocated at the first null, so a column with no nulls carries no mask and its vector gets `null` null flags, as before.
- Level decoding also records the positions of the page's nulls. Bit-packed levels are read 32 at a time, visiting only the 0 bits with `numberOfTrailingZeros`, so rows with values cost no branch.
- Values reach their row slots per page, while the page is in cache:
  - Dictionary pages of fixed-size values write each value to its slot as they decode (`ValueBuffer.beginSlots` / `endSlots`), run by run between nulls.
  - Other pages (PLAIN, delta, booleans, strings) decode packed and then move to their slots run by run (`spreadToRows`). PLAIN numbers decode in bulk through a `ByteBuffer` view, which costs more per call than moving them afterwards, so they don't write slots directly.
- `Assembly` hands the slot arrays and the mask over as the vector. `scatterLeaf` stays for flat columns with deeper levels (leaves inside optional structs).
- Definition and repetition level arrays and the null positions come from per-thread pools (`LevelBuffers`, next to `PageBuffers`). `RowGroupBatches` releases a batch's levels once its vectors are built, since no vector keeps them. A row slot column's mask is not pooled, because the vector keeps it.

## Measurements

Moving values out per page with a branch per row measured no faster than `scatterLeaf`: the cost is branch mispredictions on random nulls (4.5% in TPC-DS), not cache misses. A microbenchmark (100k Longs, 4.5% random nulls) gave, in ns per row: a branch per row 0.46, run-wise loop 0.30, run-wise `System.arraycopy` 0.23, writing slots during decode 0.21 including the decode's own store, and a branch-free bitmap select 0.82. Runs shorter than 8 rows move or fill in a loop, since `System.arraycopy` and `Arrays.fill` have a fixed cost per call.

## Consequences

- TPC-DS fact tables with mostly dictionary-encoded keys read 13% faster (catalog_sales, web_sales). store_sales stays flat: most of its columns repeat per ticket, so they decode as short RLE runs that nulls split, and the per-run cost dominates both before and after.
- `ColumnData` carries either definition levels or a null mask with slotted values (`rowSlots`).
- Engine-owned output arrays, which would remove most remaining zeroing, need a way for the caller to recycle vectors and are left for later.
