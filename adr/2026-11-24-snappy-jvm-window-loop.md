# Snappy on the JVM: a two-register input window and one body for short tags

## Status

Accepted (2026-11-24)

Supersedes the first decision of [`2026-11-23-snappy-decoder-loop-and-string-buffer-sizing.md`](2026-11-23-snappy-decoder-loop-and-string-buffer-sizing.md) for the JVM. Scala.js and Scala Native keep the branchy loop that ADR describes.

## Context

Snappy is 27-31% of wvlet engine CPU in TPC-H SF10 q6, q17 and q21, and uni's decoder ran at half of snappy-java's speed on the JVM. The goal of this round was snappy-java's speed (about 3.5 GB/s on TPC-H SF1 lineitem pages, Apple M5 Pro, JDK 25 C2).

The pages hold about 6.5 output bytes per tag: literals are almost all 1-7 bytes, 85% of copies are 4-8 bytes, 89% of offsets are over 64, and 78% of tags differ in kind from the tag before. The decoder is limited by tags per second, not by bytes.

## Decision

- On the JVM, `Snappy.decompressInto` runs `SnappyLoop.run` (Java, in `uni/.jvm`) and decodes each tag that loop stops at with the shared, fully checked `checkedTag`. `SnappyLoop.enabled` is a compile-time constant, false on Scala.js and Scala Native, where the earlier branchy loop still runs.
- `SnappyLoop` keeps the next 16 input bytes in two registers. A tag's fields come from the low register, and both registers shift by the tag's length. Finding the next tag then waits on shifts, not on a load at an address that depends on this tag.
- Literals of 1-6 bytes and copies of up to 16 bytes with an offset of 8 or more share one body with no branch on the tag kind:
  - a 16-byte move from `op - offset`, where a 256-entry table gives a literal an offset of 8, so its move is harmless;
  - an 8-byte store of the literal's bytes from the low register, which a copy sends 16 bytes ahead.
  Both write up to 24 bytes past the output position, into bytes that later tags overwrite, so the loop stops 24 bytes before the output limit.
- A table entry of 0 marks the tags the body does not take. It gives an offset of 0, so one test covers those tags, offsets below 8, and offsets before the start of the output. `rareTag` then decodes valid copies and literals of up to 60 bytes inside the loop; anything else returns to `checkedTag`, which decodes or rejects it.
- The loop is written in Java and uses a byte-array view `VarHandle`, as `Word8` does. `Unsafe` was measured (+10%) and not used.

## Consequences

- SF1 pages, MB/s, each decoder in its own JVM: lineitem 1716 → 2302, orders 1593 → 2206, partsupp 1800 → 2637, customer 1960 → 2485, part 1789 → 2341 (+27% to +47%). snappy-java runs these at 3245-4183.
- Parity with snappy-java is not reachable on one stream with C2, by anything measured here. The window update alone, with no table, no checks and no copies, takes 110 ms on lineitem (6.9 cycles per tag); snappy-java's whole decode takes 108 ms. Rewriting that update to be 4 operations deep did not shorten it.
- Measured and not worth repeating:
  - Leaving the loop for rare tags is not the cost: decoding them inside the loop saved 5 ms of 180.
  - Store-to-load forwarding stalls on the literal's harmless move are not the cost.
  - Tag lengths computed for every input byte, 8 at a time, then one byte load per tag: slower (205 ms).
  - A 24-byte window, a second tag decoded from the same word, and limits checked once per 16 tags: no gain.
  - aircompressor 2.0.2's pure-Java decoder (`Unsafe`): 1246 MB/s on lineitem.
  - Two pages decoded interleaved in one loop, each with its own register window (pages paired by compressed size): 25-45% slower than one page at a time on lineitem, orders, partsupp and customer (lineitem 2378 → 1306 MB/s). The single loop is limited by instruction throughput, not by the tag chain, so a second lane only adds register pressure.
- The 2026-11-23 ADR found table-driven and shared-step loops slower. Those loaded each tag from memory, so the next tag waited on a load; the register window is what makes the branch-free body pay.
- C2 notes for whoever edits the loop: it spills loop invariants once the body grows, so look at the disassembly after a change; an `int` shift by 32 is a shift by 0, so the literal-length shift is a `long`; time each variant in its own JVM, on more than one table.
- `SnappyDecodeTest` covers the loop with random runs of short tags decoded into clean and dirty buffers, and with bad copies at every position among short tags.
