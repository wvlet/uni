# uni-parquet: read and write speed, and a columnar batch writer

## Goals

- Reach the approved read-speed targets from
  [the large-chunks plan](2026-11-20-parquet-large-chunks-and-performance.md), and fix the Scala
  Native case-class collapse.
- Measure the writer against parquet-java, set write targets, and reach them.
- Decide, with measured trade-offs, whether to add a columnar batch writer, and whether the reader
  needs a public batch API beyond `readBatches` / `ColumnBatch`.

## Background

### Read baseline and targets (approved in #1018)

- Baseline tables are in the large-chunks plan (Apple M5 Pro, JDK 25, 1M-row `lineitem`, 250k-row
  `orders`, parquet-java 1.16 files).
- Targets, as uni time / parquet-java time on the JVM: columns ≤1.0x (today 0.47–2.6x; ZSTD and
  nested data are slow); case classes ≤1.5x of the Group reader (today 2.0–2.8x); Native columns
  ≤1.5x of JVM uni and case classes ≤2x; Node.js ≤3x / ≤4x.
- Profiles:
  - ZSTD: `BackwardBitReader.bitsAt` assembles FSE and Huffman bits one byte at a time.
  - Nested columns (new profile, `orders` uncompressed): 53% of the time is in `Assembly.build`.
    It rebuilds offsets through growing `ArrayBuilder`s and looks up level arrays in `Map`s per
    field. Page decoding is only about 40%.
  - Case classes: building Strings, packing each row into MessagePack (`RowPacker`) before
    Weaver reads it, `CaseClassWeaver.unpackFromMap` name lookups, and buffer growth.
  - Native case classes on uncompressed and snappy `lineitem`: 1.3k–4k rows/s, against 184k on
    zstd. The row path is the same for all three files, so the cause is not known yet.

### Write baseline (new)

- `examples/parquet-bench` now times writes too. Each table is read into memory once as case
  classes and written again: uni's `Parquet.newWriter[A]` against parquet-java's
  `ExampleParquetWriter`, with Groups built from the same case classes, the same schema (camelCase
  names, dates as strings), and the same codec. The uni writer has no ZSTD, so the write cases use
  uncompressed and snappy only.
- JVM, median of 5 runs after 2 warm-ups:

| file | uni case class | parquet-java groups | ratio |
|---|---|---|---|
| lineitem uncompressed | 2,449 ms · 408k rows/s · 26 MB/s | 1,246 ms · 802k rows/s · 50 MB/s | 1.97x |
| lineitem snappy | 2,551 ms · 392k rows/s · 25 MB/s | 1,080 ms · 926k rows/s · 58 MB/s | 2.36x |
| orders uncompressed | 2,038 ms · 123k rows/s · 35 MB/s | 984 ms · 254k rows/s · 73 MB/s | 2.07x |
| orders snappy | 1,962 ms · 127k rows/s · 37 MB/s | 785 ms · 318k rows/s · 92 MB/s | 2.50x |

- Scala Native and Node.js, uni only (median of 3 after 1 warm-up), with the ratio to JVM uni:

| file | Native case class | Node case class |
|---|---|---|
| lineitem uncompressed | 11,595 ms · 86k rows/s · 4.7x | 17,511 ms · 57k rows/s · 7.2x |
| lineitem snappy | 10,085 ms · 99k rows/s · 4.0x | 21,884 ms · 46k rows/s · 8.6x |
| orders uncompressed | 5,378 ms · 46k rows/s · 2.6x | 9,360 ms · 27k rows/s · 4.6x |
| orders snappy | 9,899 ms · 25k rows/s · 5.0x | 15,475 ms · 16k rows/s · 7.9x |

- Where the JVM write time goes (JFR, all four files):
  - 18%: Weaver packs the case class into MessagePack bytes.
  - 23%: those bytes are unpacked again into a `Value` tree. Every map is built as a `ListMap`,
    which compares keys one by one on insert.
  - 26%: the shredder walks the tree, looking up each field by name in those `ListMap`s.
  - 21%: `ColumnWriter.add` takes boxed values: generic statistics, a `HashMap[Any, Int]`
    dictionary, and boxed equality.
  - The rest: page encoding and Snappy.
- A quick prototype fed today's `ColumnWriter` straight from the `ColumnBatch` arrays of the
  `lineitem` file, with no MessagePack and no shredding: 345 ms uncompressed and 483 ms snappy.
  That is 0.28x / 0.45x parquet-java's time and 7x / 5x faster than uni's row path. It is an
  estimate: flat data only, and its dates are INT32 rather than strings. PR 3 adds a
  "write uni batch" case for both tables to the benchmark.

## Design

Lane: Standard. It adds a public writer API, sets new targets, and touches every read and write
path.

### Write targets

- Case-class writer: ≤1.5x of parquet-java's Group writer on the JVM (today 2.0–2.5x).
- Batch writer: ≤0.5x of parquet-java's Group writer on the JVM. Provisional: it comes from the
  flat prototype, and PR 3 confirms it on both tables.
- Native: within 2x of JVM uni for both writers (today 2.6–5.0x). Node.js: within 4x (today
  4.6–8.6x).

### Read work (toward the approved targets)

- ZSTD: read the backward bit stream a 64-bit word at a time; decode Huffman literals and FSE
  sequences from tables.
- Nested columns: assemble lists, maps, and structs in one pass per column with presized arrays,
  and resolve levels once per field rather than per lookup.
- Dictionary strings: keep indices internally so each distinct String is built once for case
  classes. `BinaryColumn` still exposes `offsets` and `data`.
- Bit unpacking specialized per bit width; table-driven Snappy and Brotli/Inflate prefix decoding,
  and `BigInt64Array` on Node.js, only where the profile shows them.
- Case classes without MessagePack: Weaver reads each row through an `Unpacker` that walks the
  batch's vectors directly, with field names resolved once per batch.
- Native collapse: profile the `lineitem` uncompressed and snappy runs first; fix it before the
  other Native work, since it may be a GC or allocation pattern that also slows the rest.

### Case-class writer: same API, no MessagePack round trip

- `ParquetWriter[A]` and `ParquetRecordWriter` keep their API. Rows are still converted by Weaver,
  so Options, enums, `Instant`, and nested case classes keep one behaviour for reading and
  writing.
- The shredder reads the Weaver's MessagePack bytes as a stream, instead of building a `Value`
  tree. Fields are matched by position in schema order, with the name lookup only as a fallback,
  so case classes need no map lookups.
- One shredder for every input: JSON records already become MessagePack bytes, and `Map`/`Seq`
  records are packed to bytes too, instead of building a `Value` tree. This amends the writer ADR's
  "one value path" decision: the path stays single but becomes a byte stream. PR 2 updates that
  ADR.
- `ColumnWriter` gets typed appends (`Long`, `Int`, `Double`, bytes) with typed statistics and
  open-addressing dictionaries per physical type, so values are never boxed.
- A rejected row still leaves no partial values: rows are staged before they are committed. The
  staging buffers become typed per column (longs, ints, doubles, bytes), so values stay unboxed
  from the stream to the page.
- If the streaming shredder still misses 1.5x, the next step is a `Packer` that shreds as Weaver
  packs, with no bytes in between.

### New: `ParquetBatchWriter` for columnar data

- Who needs it: code that already holds columns. For example, copying or projecting files
  (`readBatches` → select columns → write), exporting analytics results, or generated data. Rows would
  only cost them a conversion each way.
- Flow:
  - Open it with `Parquet.newBatchWriter(path, schema, options)` (also an `OutputStream`
    overload), with the same `ParquetWriteOptions` as the row writers.
  - Call `write(batch: ColumnBatch)` for each batch. A batch takes the same vector types the reader
    returns, so a batch read from one file can be written to another unchanged.
  - Call `close()` to write the footer. `numRows` reports progress.
- Decisions:
  - Columns are matched to the schema's top-level fields by name. A missing or extra column, a
    vector of the wrong type, mismatched sizes, or a null in a required column throws
    `ParquetException` before anything from the batch is buffered, so the writer stays usable.
  - Nested columns (`ListColumn`, `MapColumn`, `StructColumn`) are supported. Their offsets and
    nulls are turned into levels directly, without building rows.
  - Values are physical, as when reading: DATE is days in an `IntColumn`, strings are UTF-8 in a
    `BinaryColumn`. A Scaladoc table lists each logical type's vector.
  - Row groups still close at `rowGroupSize`, at a row boundary inside a batch if needed. Batch
    boundaries do not show up in the file.
  - Vectors are built with their existing public constructors and `schema.field(name)`. No new
    vector builder in this work.
  - Projection: `ParquetSchema.select(names)` and `ColumnBatch.select(names)`, so a copy can keep
    a subset of columns. Row filtering stays with the caller, who rebuilds the arrays; a nested
    `filter` needs offset rewriting and is left for later (decided with the user: projection only).
- Trade-offs:
  - For: 5–7x faster than the row path today in the prototype, and ahead of parquet-java's Group
    writer. Memory is proportional to the batch rather than to object graphs.
  - Against: callers must build offsets and null arrays for nested data, which is easy to get
    wrong, so every batch is validated. It is a second public writer to document and test on
    three platforms.
  - Recommendation: add it. The read side is already columnar, and copying or transforming files is
    where the gap matters most.

### Reader: no new public batch API

- **Reusable buffers** (an option to refill one batch's arrays in place): no, for now. The column
  path's measured gaps are ZSTD and nested assembly, not allocation; reuse would break anyone who
  keeps a batch from the iterator or from `readBatchesAsync`. PR 1 records the GC share of the
  column path, and we revisit this if it is significant after the decoding work.
- **Typed batch-to-case-class decoding** (for example `ColumnBatch.as[A]`): no. `readAs` already
  decodes each batch to case classes, and the direct vector `Unpacker` speeds it up without new
  API. PR 2 measures it against the column path; we can add `ColumnBatch.as[A]` later if a caller of
  `readBatchesAsync` needs typed rows per batch.
- `readBatches` and `ColumnBatch` stay the public columnar API; `withBatchSize` already bounds the
  batch size.

### PRs and records

- PR 1 (this branch): the write benchmark, the reader's decoding work (ZSTD, nested assembly,
  dictionary, bit unpacking), and the Native collapse fix. Targets: the column ones.
- PR 2: row paths without MessagePack: the vector `Unpacker` for `readAs`, the streaming shredder,
  and typed `ColumnWriter` appends. Targets: case classes, read and write.
- PR 3: `ParquetBatchWriter`, with an ADR, `docs/text/parquet.md`, `for-ai-agents.md`, and the
  `uni` skill's `references/parquet.md` (with a version bump).
- Each PR re-runs the benchmark (more iterations, since JVM runs vary by ±30%) and updates the
  numbers in this note. Tests cover every platform, forced with `testOnly`, since sbt 2 caches test
  runs.

## PR 2 results (row paths without MessagePack)

- Design note: [2026-11-20-parquet-row-paths-without-msgpack.md](2026-11-20-parquet-row-paths-without-msgpack.md).
- JVM, median of 7 after 3 warm-ups, uni and parquet-java in the same run:

| file | read uni case class | parquet-java groups | ratio | write uni case class | parquet-java groups | ratio |
|---|---|---|---|---|---|---|
| lineitem uncompressed | 618 ms | 497 ms | 1.24x | 1,062 ms | 1,051 ms | 1.01x |
| lineitem snappy | 641 ms | 520 ms | 1.23x | 1,256 ms | 1,011 ms | 1.24x |
| lineitem zstd | 659 ms | 530 ms | 1.24x | | | |
| orders uncompressed | 539 ms | 506 ms | 1.07x | 723 ms | 777 ms | 0.93x |
| orders snappy | 611 ms | 511 ms | 1.20x | 961 ms | 776 ms | 1.24x |
| orders zstd | 693 ms | 533 ms | 1.30x | | | |

- Before PR 2, in the same setup: reads 1.9–2.7x, writes 1.9–2.1x.
- Scala Native and Node.js (median of 3 after 1 warm-up) were measured while another benchmark
  and system scanners loaded the machine, so the ratios to JVM uni are approximate: Native reads
  about 2.2x (lineitem had been 7.2–7.7x), Native writes 1.5–2.9x, Node.js reads 2.8–3.7x, and
  Node.js writes 4.5–5x (still over the 4x target).
- On Native, `LocalDate` formatting and Weaver's canonical-name lookup per key dominated
  case-class reads. DATE strings are now formatted directly, and `CaseClassWeaver` matches keys
  by position and caches loose matches by key identity, which helps every Weaver user.

## Follow-up: Node.js case-class writes

- Design note: [2026-11-20-parquet-js-row-writes.md](2026-11-20-parquet-js-row-writes.md).
- A `node --cpu-prof` profile put about 20% of the write time in short byte-array copies
  (`System.arraycopy` is a typed-array `subarray` plus `set` on Scala.js) and per-row allocation.
  The writer now reads the packed row in the packer's buffer, the shredder stages strings as offsets
  into the row, short copies take a loop, a staged row is committed with one dispatch per column,
  and the numeric dictionary hashes with 32-bit multiplies.
- Write medians (ms) of 5 after 2 warm-ups, all platforms in one run. Other sessions' builds kept
  the load average at 5–15, so take the ratios rather than the absolute times:

| file | JVM uni | parquet-java | Node.js before | Node.js after | ratio to JVM | Native before | Native after | ratio to JVM |
|---|---|---|---|---|---|---|---|---|
| lineitem uncompressed | 1,105 | 1,185 | 4,564 | 3,074 | 2.78x | 2,531 | 1,490 | 1.35x |
| lineitem snappy | 1,260 | 1,244 | 4,659 | 3,420 | 2.71x | 1,905 | 1,808 | 1.43x |
| orders uncompressed | 754 | 853 | 3,421 | 2,094 | 2.78x | 1,509 | 1,241 | 1.65x |
| orders snappy | 943 | 870 | 4,610 | 2,252 | 2.39x | 1,809 | 1,305 | 1.38x |

- The "before" columns come from the same machine an hour earlier (JVM uni then 1,123 / 1,294 /
  843 / 1,232 ms). JVM uni stays at 0.88–1.08x parquet-java's Group writer.

## Alternatives and Why Not?

- **Write case classes through the batch writer** (collect rows into vectors, then write columns).
  It adds a full copy and still needs Weaver for each field, so it saves less than the prototype
  suggests. The streaming shredder removes the two costliest steps (the `Value` tree and the name
  lookups, about half the time) without a copy.
- **A `Packer` that shreds directly, first.** It is faster in theory, but the `Packer` SPI is wide
  and its push-style state machine is harder to get right. We keep it as the fallback if the
  streaming shredder misses the target.
- **Arrow-style builders for vectors.** Useful, but a separate API decision. Plain arrays already
  work, and a builder can come later without changing the writer.
- **Compare against parquet-java's Avro or Protobuf writers.** They need Avro or Protobuf; the Group
  writer is what a parquet-java user without them has, as on the read side.

## Follow-ups

- Wide copies for ZSTD and Snappy (plans/2026-11-20-parquet-wide-copy.md): 16-byte wild copies
  (VarHandle on the JVM, pointers on Native), packed ZSTD sequence tables, and bit state in locals.
  Isolated A/B on `orders.uncompressed` in 1 MiB chunks: ZSTD 140 → 83 ms (zstd-jni 47), Snappy
  60 → 43 ms (snappy-java 23). Still open: ZSTD at ~1.2x of parquet-java on `orders`; the rest is
  the per-sequence dependency chain (table lookup → bit read → next state). Tried without gain:
  a table-driven Snappy tag decoder, VarHandle int loads in Snappy, and rep offsets in locals.
- ZSTD sequences and Native 8-byte loads (plans/2026-11-20-parquet-zstd-sequences-and-native-le64.md):
  Native `le64` now reads two aligned words (isolated ZSTD 90 → 76 ms, `orders.zstd` columns
  252 → 226 ms). JVM ZSTD stays at ~1.2x of parquet-java: caching predefined tables (≤5% of
  blocks use them), a zstd-style repeat-offset update, and a two-pass sequence decode were all
  slower or no faster.
