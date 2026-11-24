# Faster Snappy decoding for Parquet pages

## Goals

- Cut the CPU that wvlet's Parquet scans spend in Snappy decoding. On TPC-H SF1 the scan is 47% of engine CPU, and about 40% of the scan is `Snappy.decompressInto` and its copy helpers.
- Keep the decoder pure Scala and shared across JVM, Scala.js, and Scala Native, with the same output and the same errors on corrupt input.

## Background

- Measured on the real pages of TPC-H SF1 `lineitem.parquet` (1,372 pages, 389 MB raw): uni decodes at 1.77 GB/s (220 ms), and snappy-java (native C++) at 3.6 GB/s (108 ms). TPC-DS `store_sales` shows the same 2x gap.
- The pages hold about 60 M tags for 389 MB, about 6.5 output bytes per tag. Most copies are 1 to 8 bytes long (37 M of 43 M), most literals are 1 to 8 bytes long (17 M, 1.6 bytes on average), and most copy offsets are over 64. A few 65+ byte literals hold 14% of the bytes.
- So the cost is per tag (decode, checks, call dispatch), not per byte. The 16-byte wide copies from earlier PRs already cover short runs. Copies with offsets under 8 still go byte by byte, and copies of 17 to 64 bytes go through `System.arraycopy` or byte loops.

## Design

- Lane: **Express**. An internal speedup with unchanged output and error behaviour; it touches no always-gate area. The launch prompt authorizes auto-merge once CI is green.
- Restructure the fast loop around one guard: it runs while the tag and its extra bytes fit in the input and at least 80 bytes remain before `expectedSize`. Inside it, every copy can write whole 8-byte words with no per-copy space checks. The checked tail loop handles the rest, as it does today.
- Never write past `expectedSize`, as the `decompressInto` doc promises for reused page buffers. Today's 16-byte moves check only `out.length`, so they can spill past it; `WideCopy` takes the write limit as a parameter. ZSTD and the Snappy compressor keep `out.length`, since their contracts allow the spill.
- The new copy paths (64-byte matches, short-offset doubling) stay in Snappy's fast loop; ZSTD keeps its current copies, so its output and speed don't change.
- Decode a tag's length, offset high bits, and extra-byte count from one 256-entry table, like the reference Snappy's `char_table`, so literals and the three copy kinds share one branch-light path.
- Fold each tag's corruption checks (offset range, input and output overrun) into one sign test, so the common case costs one predictable branch.
- Copy lengths of up to 64 bytes as 8-byte words. For offsets under 8, expand the pattern by doubling (each 8-byte move doubles the repeated span) instead of copying byte by byte.
- The JVM keeps `Word8`'s VarHandle loads, as ADR 2026-11-20-pure-scala-parquet-reader decided: no `Unsafe`, no native code.
- Scala.js keeps `System.arraycopy` and byte loops through its own `WideCopy`; the shared loop shape still helps it.
- Add `SnappyPageBench` to `examples/parquet-bench/.jvm` (snappy-java is a JVM-only dependency), which decodes the compressed pages of given Parquet files and compares against snappy-java, plus a `--stats` mode that prints the tag histogram above.
- Tests: round trips for every offset from 1 to 64 at every copy length, long literals, and copies and literals that end at the last bytes of the output and the input.
- Compare before and after on the decoder alone and on full scans of `lineitem` and `store_sales` (`ScanProbe`).
- After the uni release, bump wvlet's `UNI_VERSION` if its process allows it, otherwise report the version it needs; then measure `EngineTPCHBench` at SF1 against wvlet's origin/main in alternating rounds.

## Alternatives and Why Not?

- Write the JVM decoder loop in Java with VarHandles: faster loads, but a second copy of the decoder per platform. Keep it in Scala unless the Scala loop stalls well short of the target.
- Call snappy-java on the JVM: Uni avoids native dependencies.
