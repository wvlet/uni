# Decode flat nullable Parquet columns into their row slots

## Goals

- Cut the CPU of scanning flat nullable columns. After #1174, a profile of the TPC-DS SF1 fact-table scan (every column OPTIONAL, PLAIN_DICTIONARY) shows `Assembly.scatterLeaf` at 17% self, `Arrays.fill` in `appendRepeated` at 8%, and 1-bit level expansion at 3%. Values are decoded compactly, then moved again to their rows.
- Allocate less: no Byte level array for flat columns with 1-bit definition levels.
- Keep every result identical, including default values in null slots, `nullFlags == null` for columns without nulls, and the errors on corrupt level and value counts.

## Background

- #1168 added the flat-column fast path: the cursor gives a flat nullable column of fixed-size values one slot per level entry, and `Assembly.scatterLeaf` spreads the values to their rows in place, back to front. Its plan deferred "specialize the decoders to write straight into the batch's output".
- `scatterLeaf` reads a Byte level per row, branches on it, and writes a null flag. With scattered nulls the branch mispredicts, and the whole batch passes through memory twice.
- A flat leaf's definition levels are needed outside the leaf only by a struct above it (`nullsOf`). With 1-bit levels (maxDef 1), the null mask says the same thing.

## Design

- Lane: Express. Internal performance change, no API change. The launch prompt says not to merge, so the PR stays open for the user.
- "Slotted" columns: flat (maxRep 0), nullable (maxDef > 0), fixed-size values (BOOLEAN, INT32, INT64, FLOAT, DOUBLE). BYTE_ARRAY, FIXED_LEN_BYTE_ARRAY, INT96, nested, and required columns keep today's path.
- For a slotted column the cursor keeps one value slot per level entry for every batch, not only for batches that take the whole chunk. Partial batches slice slots and mask as they slice levels today.
- The cursor keeps a null mask (`Array[Boolean]`) next to the levels. With 1-bit levels the level decoder writes the mask directly, from RLE runs with one fill and from bit-packed runs 8 bits at a time, and no Byte level array is kept. With deeper levels (a struct over an optional leaf) the Byte levels stay, and the mask is derived from them.
- Each page decodes into its rows:
  - No nulls in the page: today's bulk decode, unchanged.
  - All nulls: one fill with the default value.
  - Dictionary pages with some nulls: indices are read a block at a time, as today, and looked up straight into their rows. The row loop selects the value or the default without a branch on the null flag.
  - Other encodings (PLAIN, DELTA, BYTE_STREAM_SPLIT, RLE booleans): bulk decode into the page's first slots, then spread back to front within the page, with the same branch-free select.
- Assembly gets the slotted values, the mask, and a flag. It hands both to the ColumnVector, with no scatter pass. The mask is null when the batch has no nulls, as today. Struct null flags read the mask when there are no Byte levels.
- Tests compare the slotted path against the general path for required, optional with no/some/all nulls, dictionary and PLAIN pages, RLE and bit-packed (incl. V1 BIT_PACKED) levels, multi-page chunks, whole row groups and small batches, structs over optional leaves, and INT32 decimals as Long.

## Alternatives and Why Not?

- Only make `scatterLeaf` branch-free: removes the mispredicts but keeps the second pass over the batch and the compact-then-move of every value.
- Decode per run of non-null rows (one decoder call per run): with nulls a few rows apart, the per-call overhead outweighs the saved moves.
- Slot only batches that take the whole chunk: simpler cursor bookkeeping, but small-batch reads would keep the old path, and both paths would need tests anyway.

## Result

- A/B over the TPC-DS SF1 fact tables (3 alternating rounds, load ~7): thread CPU −1.4% in total (−3% on store_sales and catalog_sales, noise on the small files), allocation −6.4% on every file.
- The profile shows why the gain is small: placing values into rows (`placeFromDictionary`, `placeRepeated`, `spreadSlots`) costs about what `scatterLeaf` plus the compact appends did. The work moved more than it shrank.
- Next lever: hold 1-bit null flags as bit words (64 rows per Long), so placement can skip null-free words in bulk.
