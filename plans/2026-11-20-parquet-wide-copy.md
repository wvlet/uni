# Wide copies for uni-parquet's ZSTD and Snappy decoders

## Goals

- Bring the JVM column-read ratio (uni / parquet-java time) for `orders` to ≤1.0x with Snappy (1.24x today) and ZSTD (1.52x today).
- Keep the decoded bytes, the error checks, and the Scala.js code path exactly as they are.

## Background

- #1023 found both decoders bound by many short match and literal copies. An isolated A/B on `orders.uncompressed` in 1 MiB chunks: uni ZSTD 127 ms vs zstd-jni 47 ms; uni Snappy 61 ms.
- `System.arraycopy` has a fixed setup cost that dominates copies of a few bytes. Plain Scala byte loops and unrolled 8-byte copies were slower still, because the JIT does not merge byte stores into word stores.
- Native decoders (zstd, snappy) get their speed from a "wild copy": copy in 8-byte words and allow writing a few bytes past the end, which removes the length-dependent branching.
- Decoders return the output array as-is, so it must be exactly `uncompressedSize` long. Slack after the end would need either a trimming copy or a bigger allocation.
- uni-parquet has `.js` and `.jvm-native` source directories today, but no JVM-only or Native-only directory.

## Design

Lane: Standard — adds new platform-specific code paths (JVM-only and Native-only sources) to every Snappy and ZSTD read.

- Add one small platform object, `WideCopy`, with a literal copy (`src` → `out`) and a match copy (inside `out`, possibly overlapping). Snappy and ZSTD call it where they call `System.arraycopy` / `Lz77.copyMatch` today.
  - JVM: 8-byte loads and stores through `MethodHandles.byteArrayViewVarHandle(long[], LITTLE_ENDIAN)`.
  - Scala Native: 8-byte loads and stores through pointers into the array data, as unaligned-safe moves (an 8-byte `memcpy`, which LLVM lowers to one unaligned move), since a plain `Ptr[Long]` load assumes 8-byte alignment. Native has no bounds check, so the tail rule below is the only guard; its tests also run in release mode.
  - Scala.js: today's code (`System.arraycopy` and the overlap loop), inlined, so JS output and speed don't change.
- No slack in the returned array. The wide path runs only when the copy, rounded up to 8 bytes, stays inside both arrays. Copies near the end of the output or input fall back to today's code. So the overshoot never leaves the arrays, and no extra trimming copy is needed.
  - ZSTD's internal literals buffer is reused across blocks, so it gets 16 bytes of slack. That removes only the source-side check; the `out`-side tail check still applies to every literal copy.
  - Overshoot is safe because bytes below the write position are final: a copy may write garbage only into the next 7 bytes past its end, and the next copy starts there and overwrites them. A wide match also never reads bytes it has not written yet (offset ≥ 8).
- First step: log a histogram of match offsets and lengths, and literal lengths, for both codecs on `orders` in 1 MiB chunks. If offsets 1–7 carry much of the output, pattern widening (repeat the first `offset` bytes to fill 8) goes in the first cut; otherwise those matches keep today's byte loop.
- Long copies (over ~32 bytes) keep `System.arraycopy`, which is already fast for those.
- Bounds checks before each copy stay as they are, so corrupt input still fails with `ParquetException`, never with an out-of-bounds write.
- New code goes in new files (`.jvm`, `.native`, `.js` sources plus a shared helper), so `Zstd.scala` changes only at its call sites and scalafmt does not rewrap it.
- Decide by measurement: an isolated A/B microbenchmark on 1 MiB chunks, then `examples/parquet-bench` comparing against parquet-java in the same run. If a platform path is not faster, drop it and keep today's code there, and the PR reports the numbers.
- Tests: round-trip and corrupt-input tests for both codecs across copy lengths 0–64 and offsets 1–16 around the array tail, including a short copy right after a near-tail wide copy. Output must be byte-identical to today's path. Forced with `testOnly` on JVM, JS, and Native.

## Alternatives and Why Not?

- **`sun.misc.Unsafe` on the JVM.** Faster on older JVMs, but deprecated for removal and warns on JDK 23+; VarHandle compiles to the same moves.
- **Allocate `size + 16` and trim at the end.** Simplest wild copy, but adds a full copy of every page; the tail fallback avoids it.
- **zstd-jni / native libsnappy.** Rejected in the ADR: different behavior per platform and none in browsers.
