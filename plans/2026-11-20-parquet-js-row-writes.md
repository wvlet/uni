# uni-parquet: faster case-class writes on Node.js

A follow-up to PR 2 ([design note](2026-11-20-parquet-row-paths-without-msgpack.md)) of
[the read/write performance plan](2026-11-20-parquet-read-write-performance-and-batch-api.md).
The user approved that plan's direction and targets on 2026-11-20; this note narrows it to the
Node.js write gap.

## Goals

- Node.js case-class writes (`Parquet.newWriter[A]`) within 4x of JVM uni on lineitem and orders,
  with margin, since PR 2 left them at the edge (3.6–4.1x on a quiet machine).
- JVM writes stay within 1.5x of parquet-java's Group writer; Native stays within 2x of JVM uni.
- No change in public API, in the written files, or in `ColumnWriter`'s typed appends, which the
  batch writer (PR 3) also uses.

## Background

- Baseline on this branch (median of 5 after 2 warm-ups): JVM uni 1,123 / 1,294 ms on lineitem
  (uncompressed / snappy), 843 / 1,232 ms on orders; Node.js 4,564 / 4,659 and 3,421 / 4,610;
  Native 2,531 / 1,905 and 1,509 / 1,809.
- A `node --cpu-prof` profile of the write cases puts about 20% of the time in byte-array copies
  and allocation. On Scala.js a `System.arraycopy` of bytes is a typed-array `subarray` plus `set`,
  which costs an object per call, so it dominates when the copies are a few bytes long.
- The copies of each row: the packer stages an ASCII string and copies it into its buffer, the
  writer copies the packed row out (`toByteArray`), the shredder copies each string into a
  per-column stage, and the column writer copies it again into its page or dictionary.
- Smaller costs: the per-value physical-type dispatch when a staged row is committed, and the
  64-bit multiply in the numeric dictionary's hash, which Scala.js emulates.

## Design

Lane: Express — an internal performance change with no change in behaviour or API, inside the
approved plan's direction.

- Remove copies rather than speed them up where we can:
  - The writer reads the packed row in the packer's buffer, in place, instead of copying it out.
  - The shredder's stage keeps where a string lies in the row's bytes, not a copy of it; only
    values converted from another form (`readValue` and `ValueConverter`) keep their own bytes.
- Copy short byte runs with a loop instead of `System.arraycopy`, in the packer's buffer and in the
  Parquet byte buffer. Long runs keep `System.arraycopy`.
- Commit a staged row with one dispatch per column instead of one per value.
- Hash dictionary keys with 32-bit multiplies, which Scala.js runs natively.
- Measure each step with the profile, and keep only what moves Node.js without slowing the JVM.
- The existing writer tests cover the paths; no new behaviour needs new tests beyond a stage that
  mixes in-place and converted strings.

## Alternatives and Why Not?

- **A `Packer` that shreds as Weaver packs, with no bytes in between** (the plan's named fallback).
  It removes the packing too, but the `Packer` SPI is wide. Keep it for when these fixes miss the
  target.
- **Platform-specific copy helpers (`.js` sources).** uni-parquet is a pure cross project; a short
  loop is as fast as `System.arraycopy` for a few bytes on the JVM and Native too.
