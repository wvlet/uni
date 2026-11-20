# Faster ZSTD sequences on the JVM and real 8-byte loads on Native for uni-parquet

## Goals

- Bring JVM ZSTD on `orders` from ~1.2x of parquet-java column time to ≤1.0x (Snappy is already ~1.0x).
- Make `Word8.le64` on Scala Native one unaligned 8-byte load instead of eight byte loads, so the ZSTD bit reader refills faster on Native.
- Keep decoded bytes, error checks, and the Scala.js path unchanged.

## Background

- After #1033, isolated decode of `orders.uncompressed` in 1 MiB chunks: uni ZSTD 83 ms vs zstd-jni 47 ms. About 90% is in `ZstdSequences.decode`, bound by the per-sequence chain: table lookup → bit read → next state.
- Tried in #1033 without gain: rep offsets as locals, merged refill checks per sequence, extracting the loop without local bit state (plus two Snappy ideas). This PR does not repeat them.
- `FrameDecoder.readTable` builds a fresh predefined (mode 0) `FseTable` every block, and then `SeqTables` packs it again, because the packed entries are cached per table object.
- The repeat-offset update has a three-way data-dependent branch (new offset / rep 1 / rep 2-3 or `rep0 - 1`), which mispredicts on mixed data.
- Native `Word8.le64` assembles bytes via two `Zstd.le32` calls. `Word8.copy` already uses a fixed 8-byte `memcpy`, which LLVM lowers to one unaligned move. A plain `Ptr[Long]` load may be emitted with 8-byte alignment, which LLVM is then free to rely on.
- The machine is shared with other benchmark sessions; timings need a quiet machine and 60 rounds (21 rounds gave ±10% noise).

## Design

Lane: Standard — changes the hot decode loop and adds a new Native load path, with no public API change.

- **Measure first.** Log, for `orders` in 1 MiB chunks, how many sequence blocks use each table mode (predefined / RLE / compressed / repeat). This decides whether caching the predefined tables matters at all.
- **Cache the predefined tables.** Build the three mode-0 `FseTable`s and their packed `SeqTables` entries once, in a shared immutable holder, and reuse them for every block and frame.
  - RLE (mode 1) tables are also rebuilt every block; cache them too (at most 36 + 32 + 53 tiny tables) only if the histogram shows RLE blocks matter.
  - Pack them eagerly at initialization so the shared arrays are safely published across threads; no lazy write to a shared table's `seqEntries` field.
- **Repeat-offset update off the bit-read chain.** Follow zstd's `ZSTD_decodeSequence`: branch on what the table entries already tell us (`ofBits > 1` means a new offset; the literal-length base tells `ll == 0`), so the branch resolves before any bit is read, and pick among rep 1/2/3 and `rep0 - 1` with selects instead of a match. Results stay the same.
  - Keep rep offsets in the `rep` array (locals were already tried); only the branching changes.
- **Native `le64` as one unaligned load.** First read the LLVM IR that Scala Native 0.5.12 emits for a `Ptr[Long]` load from `atUnsafe`, and pick by what it shows:
  - If the load is emitted with `align 1` (or without an alignment LLVM can exploit), use the plain pointer load.
  - Otherwise use a fixed 8-byte `memcpy` into a one-slot buffer owned by the bit reader (a Native-only `le64` overload that takes it), never a `stackalloc` inside the loop and never a shared global buffer.
  - Every `le64` caller (bit readers, Huffman, sequences) already guards that 8 bytes are inside the array, so there is no new bounds risk. Native tests build in debug mode; the release-mode benchmark run also checks its decoded bytes against parquet-java.
- **Decide by measurement.** Each change is kept only if it wins in an isolated A/B (60 rounds, quiet machine): `CodecBench` on the JVM for the first two; for `le64`, a Native copy of `CodecBench` in the bench example, built in release mode. Then a full `examples/parquet-bench` run for `orders` on JVM, JS, and Native. Changes that don't help are dropped and the PR reports their numbers.
- New code goes in new files so `Zstd.scala` (at scalafmt's search limit) changes only at its call sites.
- Tests: round-trip and corrupt-input tests for all four table modes and every repeat-offset case (offset value 1–3 with `ll == 0` and `ll > 0`), forced with `testOnly` on JVM, JS, and Native.

## Alternatives and Why Not?

- **Cache compressed (mode 2) tables by content hash.** Each block's table header differs, so hits would be rare and hashing costs as much as building.
- **Interleave the sequence decode and the copies in two passes** (decode all sequences first, then execute). zstd does this to hide latency, but it needs a sequence buffer per block; worth trying only if the two changes above leave a clear gap.
- **Native `le64` through `Long.reverseBytes` of a big-endian load.** Native is little-endian on every target we ship, so it adds work for nothing.

## Results

Isolated A/B, `orders.uncompressed` in 1 MiB chunks, zstd level 3, 60 rounds (median, alternating runs). Apple M5 Pro, JDK 25, Scala Native 0.5.12 releaseFull. Other sessions' benchmarks loaded the machine at times, so only alternating runs are compared.

- Table modes on `orders` (blocks: predefined / RLE / compressed / repeat): LL 32/0/600/0, OF 30/2/600/0, ML 29/4/599/0. Caching the predefined or RLE tables could save at most ~32 small table builds per run, so it was not implemented.
- JVM, `origin/main` 72–78 ms (zstd-jni 49–53):
  - zstd-style repeat-offset update (branch on table entries, selects): 81–88 ms. Dropped.
  - Only the outer branch keyed on the offset code: 80–87 ms. Dropped.
  - Two-pass decode (all sequences into arrays, then the copies): 88–93 ms. Dropped.
- Native `Word8.le64`:
  - Scala Native emits a `Ptr[Long]` load as `load i64, ptr` with no `align`, so LLVM assumes 8-byte alignment; a `stackalloc` + `memcpy` in `le64` emits its `alloca` inside the loop blocks.
  - Byte assembly (`origin/main`) 97–99 ms; two aligned words shifted together 74–78 ms; plain `Ptr[Long]` 70–75 ms. Shipped: the aligned pair, which has no alignment assumption and is within ~5% of the plain load.
  - Against a clean `origin/main` build: isolated decode 90–92 → 76–77 ms; `orders.zstd` column read (bench, median of 7) 252 → 226 ms; case-class read 1085 → 1045 ms.
- Tests: hand-built frames for the repeat-offset cases the fixtures never reach (rep 2 with literals, rep 1 minus 1, and the zero offset it can produce), and `le64` at every offset up to the array end.
