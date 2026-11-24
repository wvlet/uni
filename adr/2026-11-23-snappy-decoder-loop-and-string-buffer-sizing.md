# Snappy decoder loop shape and Parquet string-buffer sizing

## Status

Accepted (2026-11-23, PR #1175)

## Context

Snappy decoding is about 40% of a wvlet Parquet scan's CPU, and uni's decoder runs at half of snappy-java's speed on the JVM (TPC-H SF1 lineitem pages: 220 ms against 108 ms). A lineitem scan also allocated 1.84 GB for 945 MB of output vectors. DuckDB-written column chunks hold about one data page each, so string data and dictionary indices doubled page by page and block by block, and each doubling zero-filled a new array and copied the old one.

## Decision

- `Snappy.decompressInto` keeps its branchy tag loop: one branch for literal or copy, then one per copy kind, with 16-byte `WideCopy` moves. Branch-free designs were measured on C2 (Apple Silicon, JDK 25) and were slower: a 256-entry tag table took 370 ms, one shared literal/copy step 345 ms, and a port of google/snappy's `kLengthMinusOffset` loop 266 ms. With branches the CPU can run ahead past predicted tags; without them, each tag's position waits on the previous tag's load and arithmetic. Parsing the tags alone takes 180 ms with branches and 223 ms without, which sets the floor. Copy variants (64-byte copies, pattern doubling for offsets under 8, 16-byte moves that load before they store) were no faster on lineitem and 35% slower on `orders`.
- `WideCopy.literal` and `matchCopy` take the write `limit`. Snappy passes `expectedSize`, since `decompressInto` promises to leave a reused buffer's bytes past the output as they were; ZSTD and the Snappy compressor pass `out.length`, since their contracts allow writes into the slack.
- `BinaryBuffer` data is reserved from what each page tells: a PLAIN page reserves its values' bytes (the page bytes less 4 per value), a dictionary block grows once for the values the cursor reserved at that block's bytes per value, and the cursor projects later pages from the bytes per value so far. Projections stay bounded by the cursor's reserved value capacity, which comes from the bytes read so far, so a corrupt value count alone cannot force a huge allocation.
- Dictionary indices are allocated at the reserved value capacity, and `Assembly.directLeaf` hands the array to the `BinaryColumn` when it fits exactly and the offsets are handed over too.

## Consequences

- lineitem scans allocate 32% less (1,837 → 1,242 MB) and run about 4% faster; orders allocates 37% less. Tables without string columns are unchanged.
- Before trying another Snappy loop shape on the JVM, run `SnappyPageBench` (in `examples/parquet-bench/.jvm`) on real pages from more than one table, alternating with origin/main: lineitem alone hid the `orders` regression.
- Writing the decoder loop in Java would not help: it compiles to the same bytecode. Only `Word8` needs Java, for its VarHandles.
