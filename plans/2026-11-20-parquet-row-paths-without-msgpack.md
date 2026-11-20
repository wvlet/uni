# uni-parquet: case-class reads and writes without the MessagePack round trip

PR 2 of [the read/write performance plan](2026-11-20-parquet-read-write-performance-and-batch-api.md).
The user approved that plan's direction on 2026-11-20; this note only narrows it to PR 2.

## Goals

- Case-class reads (`readAs`) at ≤1.5x parquet-java's Group reader on the JVM.
- Case-class writes (`Parquet.newWriter[A]`) at ≤1.5x parquet-java's Group writer on the JVM.
- Native within 2x of JVM uni for both, Node.js within 4x.
- No change in public API or in which values read and write.

## Background

- Baselines at #1023 (median of 7 after 3 warm-ups): reads 1.6–2.7x the Group reader, writes
  2.1–2.3x the Group writer; Native writes 2.9–4.0x JVM uni, Node.js 5.6–8.0x.
- Reads pack every row of a batch into MessagePack (`RowPacker`) and Weaver unpacks it again.
- Writes go Weaver → MessagePack bytes → `Value` tree (every map a `ListMap`) → shredder with
  name lookups → boxed `ColumnWriter.add`. The profile splits about 18% / 23% / 26% / 21%.

## Design

Lane: Standard (pre-approved in the parent plan). Touches both row paths, not the public API.

### Reads

- A private `Unpacker` that walks a batch's vectors and hands Weaver the same tokens `RowPacker`
  would have written: same value mapping (timestamps as Instant, DATE as ISO strings, and so on),
  so `readAs` decodes exactly as before.
- Column names are turned into Strings once per batch, not per row.
- Dictionary-encoded string columns: each distinct String is built once per column chunk for
  case classes. Decided by measurement: carry dictionary indices beside `BinaryColumn` (its public
  `offsets` and `data` stay), or a per-column String cache if that is as fast and simpler.
- `RowPacker` stays only if another path still needs it.

### Writes

- One streaming shredder reads MessagePack bytes directly: no `Value` tree, no `ListMap`.
- Struct fields are matched by position in schema order first; a key that does not match falls
  back to the existing exact-then-loose name lookup, so JSON in any key order still works.
- Every input becomes bytes: case classes through Weaver, JSON through the existing converter,
  `Map`/`Seq`/Scala values through a small packer that replaces the `Value` converter.
- Rows are still staged before commit, so a rejected row leaves no partial values. Staging
  buffers are typed per column (longs, ints, doubles, bytes).
- `ColumnWriter` gets typed appends with typed statistics and open-addressing dictionaries, so no
  value is boxed from the stream to the page.
- Rare value forms (decimal from a string, UUID, timestamps from strings) keep today's
  converters and error messages; only the common forms get a fast path.
- The writer ADR's "one value path" is amended: still one path, now a byte stream.
- If the streaming shredder misses 1.5x, the fallback is a `Packer` that shreds as Weaver packs.

### Checks

- Existing reader and writer tests pass on JVM, JS, and Native (forced with `testOnly`), plus
  tests for out-of-order keys, loose names, duplicate keys, and rejected rows.
- `examples/parquet-bench` is re-run and the numbers are recorded in the parent plan.

## Alternatives and Why Not?

- **A `Packer` that shreds as Weaver packs, first.** Kept as the fallback, as the parent plan says:
  the `Packer` SPI is wide and push-style.
- **Read case-class fields through Surface getters.** Needs a second path for maps and JSON and
  duplicates Weaver's type handling (writer ADR).
