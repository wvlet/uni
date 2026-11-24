# Snappy decoding and Parquet string buffers

## Goals

- Cut the CPU that wvlet's Parquet scans spend in Snappy decoding and Parquet decoding. On TPC-H SF1 the scan is 47% of engine CPU: about 40% of the scan is `Snappy.decompressInto` and its copy helpers, and about 50% is Parquet decoding.
- Keep the code pure Scala and shared across JVM, Scala.js, and Scala Native, with the same output and the same errors on corrupt input.

## Background

- On the real pages of TPC-H SF1 `lineitem.parquet` (1,372 pages, 389 MB raw), uni decodes at 1.77 GB/s (220 ms) and snappy-java at 3.6 GB/s (108 ms).
- The pages hold about 60 M tags, about 6.5 output bytes per tag. Most copies and most literals are 1 to 8 bytes long, and most copy offsets are over 64. So the cost is per tag, not per byte.
- A lineitem scan allocates 1.84 GB for 945 MB of output vectors. Most of the gap is string buffers that double as values arrive: each doubling zero-fills a new array and copies the old one. DuckDB-written chunks hold about one data page each, so the cursor's per-page projection never kicks in.

## Design

- Lane: **Standard** after the user chose to add the buffer fix to this PR. The launch prompt authorizes auto-merge once CI is green.
- Snappy: keep the decoder loop as it is. Every rewrite measured slower on C2 or no faster (details below), so this PR changes only correctness and tooling there.
- Snappy: never write past `expectedSize`, as the `decompressInto` doc promises for reused page buffers. The 16-byte moves checked only `out.length`, so they could spill past it. `WideCopy` now takes the write limit. ZSTD and the Snappy compressor pass `out.length`, since their contracts allow the spill.
- String data: reserve it once, from what the page says, instead of doubling.
  - A PLAIN page reserves its values' bytes (the page bytes less 4 per value) up front.
  - A dictionary block sums its entries' lengths and grows once for the values the cursor reserved, at that block's bytes per value.
  - On later pages, the cursor projects the data from the bytes per value so far whenever the next page may not fit. Before, it did this only when the value capacity grew, which happened once.
- Dictionary indices: allocate them at the reserved value capacity instead of doubling, and hand the array to the vector when it fits exactly, instead of copying it.
- Dictionary appends copy values in one loop with no per-value capacity checks, through `WideCopy.literal` (two 8-byte moves for values of up to 16 bytes).
- Add `SnappyPageBench` to `examples/parquet-bench/.jvm`: it decodes the compressed pages of given Parquet files against snappy-java, and `--stats` prints the tag histogram.
- Tests: round trips for every offset from 1 to 64 at every copy length in each copy form, every literal length form, streams at the end of their array and inside a longer one, a sentinel check past `expectedSize`, and corrupt streams.
- wvlet: the scan gain is a few percent on string-heavy tables, under the TPC-H suite's noise. The `UNI_VERSION` bump can ride the next release that wvlet picks up.

## Alternatives and Why Not?

Each was measured A/B against origin/main on lineitem pages (decoder alone, origin/main at about 220 ms):

- A 256-entry tag table, like the reference `char_table`: 370 ms. The table load sits on the tag-to-tag dependency chain, while branches let the CPU run ahead.
- One branch-free step for literals and copies: 345 ms.
- A port of google/snappy's `kLengthMinusOffset` loop: 266 ms.
- Parsing tags without branches, copies stubbed out: 223 ms; with branches: 180 ms. Forcing C2's conditional moves didn't help.
- Pattern doubling for offsets under 8, 64-byte copies, and 16-byte moves that load before they store: equal on lineitem, but 35% slower on `orders` pages.
- Write the JVM decoder loop in Java: the loop compiles to the same bytecode, so there's nothing to gain.
- A 32-byte step in `WideCopy.literal` for strings: no gain on lineitem, and a bigger method on Snappy's hot path.
