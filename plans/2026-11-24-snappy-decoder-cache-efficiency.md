# Snappy decoder: what the PFOR-style ideas buy, and the one change worth making

## Goals

- Make uni's pure-Scala Snappy decoder faster, because Snappy is 27–31% of wvlet engine CPU in TPC-H SF10 q6, q17 and q21.
- Find which of the PFOR / cache-utilization ideas actually pay off, and record the ones that don't so nobody repeats them.

## Background

- Machine: Apple M5 Pro, JDK 25 (C2), Scala Native 0.5 with `releaseFull`. L1D is 128 KiB and L2 is 16 MiB per P-cluster. Pages are TPC-H SF1 pages, decoded in-process A/B. (The SF10 lineitem file is over 2 GiB, too big for one byte array; its pages have the same shape.)
- Baseline on the JVM, SF1 lineitem: uni 1.75 GB/s (221 ms), snappy-java 3.60 GB/s (108 ms). orders: 1.68 vs 3.33 GB/s.
- The pages carry about 60 M tags for 389 MB, about 6.5 output bytes per tag. Copy kinds are 29% literals, 43% 1-byte-offset copies and 28% 2-byte-offset copies, and 78% of tags change kind from the previous tag. The decoder is limited by tags per second, not bytes.
- Profiling: on macOS, async-profiler only has `cpu` (itimer). `cycles` and cache-miss events fail silently, and `lines` gave no line info for the inlined loop. Wall-clock samples put 85% of time in the inlined `decompressInto` body and 15% in `WideCopy` and `Word8`. Cache behaviour was therefore measured by ablation, not counters.
- Prior ADR (2026-11-23): on C2, branch-free tag tables and shared literal/copy steps were slower, and tag parsing alone sets the floor.

## Findings (throwaway experiments, JVM unless noted)

- **Cache is not the bottleneck.**
  - Reading a decoded page back costs 11 ms out of 221, about the same from L1-sized chunks, from L2, or streamed from DRAM (≈35 GB/s with the hardware prefetcher).
  - Pages are p50 44 KiB and p90 960 KiB, far below the 16 MiB L2.
  - So fused, chunked page consumption could save at most about 5%. It is not worth a new codec ↔ ColumnChunkCursor API.
- **Two-pass (tag scan into an L1 op array, then a copy pass): 24% slower** (273 vs 221 ms). The scan pass alone is 195 ms, 88% of today's whole decode.
- **Fewer checks (unconditional 16-byte moves in a slack region): no change** (−3% lineitem, +3% orders).
- **The floor is a serial latency chain plus kind mispredicts.**
  - A copy-free branchy scan alone is 147 ms (2.4 ns/tag), already slower than snappy-java's full decode.
  - Branch-free scans (google/snappy's dual next-tag load with select, table-driven, mask arithmetic) are 3.0–3.4 ns/tag on C2: about 14 cycles per tag on the load → ALU → load chain.
- **Independent lanes break the chain.** Each page is its own Snappy stream, so decoding 2 or 4 pages interleaved in one loop overlaps their chains.
  - The arithmetic scan goes from 1.87 GB/s on 1 lane to 3.35 GB/s on 2.
  - Full decoders (byte-identical to snappy-java):

| SF1 table | JVM base | JVM 1-lane branch-free | JVM 2 lanes | Native base | Native 1-lane branch-free | Native 2 lanes |
|---|---|---|---|---|---|---|
| lineitem | 1737 | 1496 (−14%) | 1766 (+2%) | 1792 | 1923 (+7%) | 2424 (+35%) |
| orders | 1613 | 1434 (−11%) | 2003 (+24%) | 1719 | 1877 (+9%) | 2463 (+43%) |
| partsupp | 1868* | 1734 | 2024 | 1927 | 2215 (+15%) | 2626 (+36%) |
| customer | 1750 | 1716 | 1809 (+3%) | 1884 | 2066 (+10%) | 2079 (+10%) |

  - MB/s, medians. *partsupp's JVM base swings 1188–1868 MB/s with page order during warm-up, so C2 profiles make small-file JVM numbers noisy.
  - JVM 4 lanes: +20% on lineitem but −45% on orders. Not robust.

## How other decoders do it

- **google/snappy, and DuckDB's vendored copy** (the same `DecompressBranchless` loop). It runs at about 8 cycles per tag because:
  - The next tag is picked without a branch: `csinc`/`csel` on ARM, `cmov` on x86. Both candidate next-tag bytes are loaded before the select; the code forces this with `volatile`.
  - `kLengthMinusOffset[tag]` folds long literals, copy-4, overlapping copies and `offset > op` into one predicted-false branch.
  - Copies are unconditional 32-byte moves, with 64 bytes behind a predicted-false branch. Literals up to 60 bytes and copies up to 64 bytes stay in the fast loop.
  - Each tag's copy runs one iteration later, and the loop is unrolled twice.
- **Rust `snap` crate** (DataFusion, via parquet-rs `SnappyCodec`). The same shape as uni's loop:
  - a branchy tag loop with 16-byte fast moves for literals ≤ 16 and copies with offset ≥ 8 and length ≤ 16;
  - a tag table for copy length and offset;
  - an overlapping-copy loop otherwise.

## What C2 does with those ideas (JDK 25, disassembled from PrintAssembly hex)

- C2 turns the select into a branch (`cbnz` / `b.ne`) and moves the two candidate loads into the branches, so the dual-load trick disappears.
  - `-XX:+UseCMoveUnconditionally` doesn't change this: branch-free `dual` went from 195 to 202 ms, and base from 211 to 229 ms.
- Each tag load also pays `ldrsb` + `and 0xff`, a sign-extend, a bounds check and a safepoint poll.
  - The branch-free chain (load → ALU → load) therefore costs 12–14 cycles per tag on C2, against about 8 in C++.
- The branchy loop is the best single-stream shape on C2: 2.4 ns/tag for the scan alone, already more than snappy-java's whole decode.
  - uni's loop is already that shape, the same as `snap`'s.
- Profile pollution isn't a factor.
  - Warm-up order (small pages first, big first, file order) leaves base within ±4%.
  - A string-copy-shaped warm-up of the shared `WideCopy.literal` (as `ValueBuffer` calls it) leaves base unchanged.
- Multiple streams are the only measured way past the chain on C2, but full decoders are erratic.
  - The arithmetic scan on 2 pages: 3.35 GB/s, against 2.67 for the branchy scan.
  - Full 2- and 4-lane decoders: −50% to +24% across tables and runs, depending on how C2 lays them out.

## Second round: single-stream designs in Java (JVM, JDK 25 C2, each decoder in its own JVM)

Goal for this round: snappy-java speed (about 3.5 GB/s on SF1 lineitem pages). Not reached. All variants are byte-identical to snappy-java on every SF1 page.

- **Tag shape of the pages.** 60.7 M tags for 389 MB. Literals are almost all 1-7 bytes (17.4 M of 17.5 M), 85% of copies are 4-8 bytes, 89% of offsets are over 64, and 3.6% of copies have an offset of 8 or less.
- **Results, MB/s (median of 40-60 rounds):**

| decoder | lineitem | orders | partsupp | customer | part |
|---|---|---|---|---|---|
| uni today (Scala) | 1716 | 1593 | 1800 | 1960 | 1789 |
| word load, copy kinds by arithmetic (`j2`) | 1913 | 1828 | 2183 | 2126 | 1875 |
| table + sliding window, VarHandle (`j14`) | 2173 | 2076 | 2450 | 2195 | 2148 |
| the same with `Unsafe`, no bounds checks (`j15`) | 2402 | 2224 | 2780 | 2512 | 2408 |
| aircompressor 2.0.2 (pure Java, `Unsafe`) | 1246 | | | | |
| snappy-java (JNI) | 3530 | 3233 | 4214 | 4109 | 3271 |

- **`j14`, the best that needs no `Unsafe`: +12% to +36%.**
  - The next 16 input bytes sit in two registers. A tag's fields come from the low register, and the registers shift by the tag's length, so the next tag does not wait on a memory load.
  - Literals of 1-6 bytes and copies of up to 16 bytes with an offset of 8 or more share one body with no branch on the tag kind. A 256-entry table gives the length and the offset mask.
  - Other tags (2% of them) take a checked path inside the loop.
- **Why parity is out of reach on one stream.**
  - The window update alone, with no table, no checks and no copies, takes 110 ms on lineitem: 6.9 cycles per tag. snappy-java's whole decode is 108 ms, 7.3 cycles per tag.
  - Shortening that chain on paper (4 operations deep) did not shorten it in practice: 110-118 ms for three rewrites.
  - The full loop then adds 50-70 ms. `Unsafe` removes the bounds checks and C2's register spills and still gets 162 ms.
- **Measured and rejected.**
  - Leaving the fast loop for rare tags is not the cost: handling them inside the loop saved 5 ms.
  - Store-to-load forwarding stalls on literals are not the cost: moving the harmless read elsewhere changed nothing.
  - Tag lengths for every input byte computed 8 at a time, then one byte load per tag (`j16`): 205 ms, slower.
  - A 24-byte window, a second tag from the same word, limits checked once per 16 tags: no gain.
- **C2 notes.** With the table added, C2 spills loop invariants (array bases, `length - 7`) to the stack inside the loop. `(x & 3) << 4` becomes one `ubfiz` only when `x & 3` has no other use. An `int` shift by 32 is a shift by 0, so tag-kind shifts of 16 bits per kind need a `long`.

## Design

Lane: Standard. The user picked "Ship the new loop" on 2026-11-24.

- JVM only: `SnappyLoop.run` (Java, `uni/.jvm`) is the fast loop: `j14` plus valid rare copies and literals decoded inside the loop.
- `Snappy.decompressInto` calls it and decodes each tag it stops at with the shared `checkedTag`. Scala.js and Scala Native keep their loop; `SnappyLoop.enabled` is false there.
- Integrated result, MB/s: lineitem 2302, orders 2206, partsupp 2637, customer 2485, part 2341 (+27% to +47% over today).
- Recorded in `adr/2026-11-24-snappy-jvm-window-loop.md`.

## Alternatives and Why Not?

- **Native-only branch-free loop.** Dropped: the JVM is the target.
- **Two-pass op array, fused page consumption, fewer checks.** Measured slower or within noise (see Findings).
- **google/snappy's branch-free loop on C2.** C2 can't compile it without branches; 11–14% slower.
