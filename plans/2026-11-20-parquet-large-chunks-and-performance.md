# uni-parquet: column chunks beyond 2 GiB, and read speed measured against parquet-java

## Goals

- Read files whose column chunks hold more than 2 GiB of values, such as parquet-testing's
  `large_string_map.brotli.parquet`, on every platform. Today they fail with "column chunks with
  more than 2 GiB of values".
- Add a benchmark that compares uni-parquet with parquet-java on the same files. Set read-speed
  targets from measured numbers, then reach them by profiling.

## Background

- A row group is decoded in one piece. All values of a column chunk go into one `Array` (strings
  into one `Array[Byte]` in `BinaryBuffer`), and the compressed chunk is fetched into one
  `Array[Byte]`. Both cap a chunk at 2^31 bytes. Row and value counts are `Int` too, and counts
  above that are reported as corrupt.
- Dictionary-encoded strings are copied per value when decoded, so a chunk that is small on disk
  can still decode to more than 2 GiB.
- The limit applies only to the whole chunk. A page's size is an `i32` in the format, and so is a
  BYTE_ARRAY value's length. `large_string_map.brotli.parquet` is 2 rows of one 1 GiB string each
  (2,147,483,749 bytes uncompressed, 3 KiB compressed).
- `ColumnVector` exposes plain arrays (`values: Array[Long]`, `offsets: Array[Int]`,
  `data: Array[Byte]`). That keeps access fast and simple; the column path's speed below depends
  on it.
- `Canvas` has `Long` offsets, but off-heap memory needs Java 22+ (Uni supports Java 17), heap
  canvases are still limited to 2 GiB, and Scala.js `ArrayBuffer`s are limited by the engine.

### Baseline

- Apple M5 Pro, JDK 25, Node.js 24, Scala Native 0.5 (release-full, immix GC). Median of 5 runs
  after 3 warm-up runs (Native and Node: 3 after 1–2). Repeated JVM runs vary by up to 30%.
- The files are written by parquet-java 1.16 with default settings: one row group, 1 MiB pages,
  dictionary encoding. `lineitem` has 1M rows in 16 flat columns (63 MB uncompressed). `orders`
  has 250k orders with a `LIST<STRUCT>` of their lines and a `MAP<STRING, STRING>` (72 MB).
- MB/s is the footer's uncompressed column size divided by the time, so codecs compare.
- Both column paths read every value. parquet-java's is `ColumnReader` value by value: it has no
  public vectorized reader (Spark and Arrow ship their own). Both row paths read every field and
  build every String: uni builds case classes, parquet-java the example `Group` reader.
- JVM:

| file | uni columns | parquet-java columns | uni case class | parquet-java groups |
|---|---|---|---|---|
| lineitem uncompressed | 83 ms · 12.0M rows/s · 757 MB/s | 178 ms · 5.6M rows/s · 353 MB/s | 1,280 ms · 781k rows/s · 49 MB/s | 646 ms · 1.5M rows/s · 97 MB/s |
| lineitem snappy | 146 ms · 6.8M rows/s · 430 MB/s | 225 ms · 4.4M rows/s · 279 MB/s | 1,628 ms · 614k rows/s · 39 MB/s | 617 ms · 1.6M rows/s · 102 MB/s |
| lineitem zstd | 294 ms · 3.4M rows/s · 214 MB/s | 191 ms · 5.2M rows/s · 329 MB/s | 1,547 ms · 646k rows/s · 41 MB/s | 558 ms · 1.8M rows/s · 113 MB/s |
| orders uncompressed | 148 ms · 1.7M rows/s · 486 MB/s | 134 ms · 1.9M rows/s · 537 MB/s | 1,375 ms · 182k rows/s · 52 MB/s | 516 ms · 484k rows/s · 139 MB/s |
| orders snappy | 256 ms · 976k rows/s · 281 MB/s | 149 ms · 1.7M rows/s · 483 MB/s | 1,424 ms · 175k rows/s · 50 MB/s | 628 ms · 398k rows/s · 114 MB/s |
| orders zstd | 484 ms · 516k rows/s · 149 MB/s | 185 ms · 1.4M rows/s · 389 MB/s | 1,541 ms · 162k rows/s · 47 MB/s | 789 ms · 317k rows/s · 91 MB/s |

- Scala Native and Node.js, uni-parquet only:

| file | Native columns | Native case class | Node columns | Node case class |
|---|---|---|---|---|
| lineitem uncompressed | 256 ms · 3.9M rows/s · 245 MB/s | 240,992 ms · 4.1k rows/s · 0.26 MB/s | 397 ms · 2.5M rows/s · 158 MB/s | 9,326 ms · 107k rows/s · 7 MB/s |
| lineitem snappy | 286 ms · 3.5M rows/s · 220 MB/s | 773,782 ms · 1.3k rows/s · 0.08 MB/s | 708 ms · 1.4M rows/s · 89 MB/s | 9,880 ms · 101k rows/s · 6 MB/s |
| lineitem zstd | 577 ms · 1.7M rows/s · 109 MB/s | 5,438 ms · 184k rows/s · 12 MB/s | 1,157 ms · 864k rows/s · 54 MB/s | 10,202 ms · 98k rows/s · 6 MB/s |
| orders uncompressed | 287 ms · 870k rows/s · 251 MB/s | 2,045 ms · 122k rows/s · 35 MB/s | 752 ms · 332k rows/s · 96 MB/s | 4,833 ms · 52k rows/s · 15 MB/s |
| orders snappy | 367 ms · 681k rows/s · 196 MB/s | 2,095 ms · 119k rows/s · 34 MB/s | 965 ms · 259k rows/s · 75 MB/s | 5,297 ms · 47k rows/s · 14 MB/s |
| orders zstd | 525 ms · 476k rows/s · 137 MB/s | 2,307 ms · 108k rows/s · 31 MB/s | 1,744 ms · 143k rows/s · 41 MB/s | 6,196 ms · 40k rows/s · 12 MB/s |

- What the numbers say:
  - The JVM column path beats parquet-java on flat uncompressed and snappy data (0.47–0.65x the
    time) and is 1.1–2.6x slower on nested data and ZSTD. JFR puts ZSTD's time in
    `BackwardBitReader.bitsAt`, which assembles FSE and Huffman bits one byte at a time.
  - The case-class path takes 2.0–2.8x the Group reader's time. The profile shows building Strings,
    packing every row into MessagePack, `CaseClassWeaver.unpackFromMap`'s name lookups, and
    buffer growth.
  - Scala Native's case-class path collapses on the uncompressed and snappy lineitem files (1.3k–4k
    rows/s) but not on zstd (184k rows/s), though all three take the same row path. The cause is
    not known yet; it is a bug to profile and fix in PR 2, not a target.
  - Native's column path takes 1.1–3.1x the JVM's time; Node.js 3.6–5.1x on columns and 3.5–7.3x
    on case classes.

## Design

Lane: Standard. It changes a documented limit and how batches are cut, adds a read option, and the
targets need agreement.

### Part 1: batches within a row group

- `readBatches`, `readAs`, `readRows`, `readJson`, and their `...Async` forms read a row group
  in batches of rows. Each batch holds less than 2 GiB per column, so vectors stay plain arrays and
  the public vector API does not change.
- New option `ParquetReadOptions.withBatchSize(rows)` / `noBatchSize()`. Without it a row group is
  still one batch, unless it would not fit: a batch then ends at the next row boundary once any
  column's decoded values pass 1 GiB. The footer's sizes only pick the first guess, so dictionary
  strings and skewed rows are covered. `large_string_map` reads one row per batch without
  settings, and ordinary files give the same batches as today.
- Column readers stream pages: each keeps its page cursor and decoded tail between batches, and a
  batch ends at the same row (repetition level 0) in every column.
- Counts become `Long` per row group and chunk, so row groups and chunks with more than 2^31 rows
  or values read in batches instead of failing as corrupt.
- Compressed chunks over 256 MiB are fetched in windows of whole pages, not in one array. That
  removes the 2 GiB limit on compressed chunks and bounds peak memory. Smaller chunks keep today's
  merged range reads, so HTTP round trips don't change.
- `readRowGroup(index)` keeps returning the whole row group. A row group too large for one batch
  fails with a message that points to `readBatches` and `withBatchSize`.
- Still unsupported, with an "unsupported" error: one row whose values in one column exceed 2 GiB
  (for example a list of three 1 GiB strings).
- Tests use a small internal limit to force splitting and windowing on every platform, including
  nested columns whose pages end mid-row. Reading `large_string_map` itself needs over 3 GiB of
  memory, so that test runs on the JVM only when an environment variable asks for it.
- Docs and records: a new ADR for batches within row groups and the option; `docs/text/parquet.md`
  (its "a `ColumnBatch` per row group" line, the limit, the option); the reader ADR's
  "Not supported"; `for-ai-agents.md`; the `uni` skill's `references/parquet.md` with a version
  bump.

### Part 2: benchmark and performance

- `examples/parquet-bench` (cross-built, not published, outside the CI aggregates, so parquet-java
  and Hadoop's client jars never reach uni-parquet or CI). `run.sh` builds all three platforms,
  writes the six files with parquet-java, and prints the tables above.
- Targets, as uni time over parquet-java time on the JVM, for every file above:
  - Column path: at most 1.0x (today 0.47–2.62x).
  - Case-class path, against the Group reader: at most 1.5x (today 1.95–2.77x).
  - Scala Native: column path within 1.5x of uni on the JVM (today 1.1–3.1x); case-class path
    within 2x (fixes the collapse).
  - Node.js: column path within 3x of uni on the JVM (today 3.6–5.1x); case-class path within 4x
    (today 3.5–7.3x).
- Planned work, in profile order, re-measured after each step:
  - ZSTD: read the backward bit stream a word at a time, and decode Huffman literals and FSE
    sequences from tables.
  - Rows without MessagePack: an `Unpacker` that walks the vectors directly, so Weaver reads
    values without the packed buffer, with field names resolved once per batch.
  - Dictionary strings: decoding keeps dictionary indices internally and builds each distinct
    String once for case classes; `BinaryColumn` still exposes `offsets` and `data`.
  - Bit unpacking specialized per bit width; Snappy and Brotli/Inflate table-driven prefix
    decoding where the profile shows them.
  - Scala.js: INT64 decoding through `BigInt64Array` internally if the Node profile shows
    emulated `Long`; vectors keep `Array[Long]`.
- Page-index skipping stays future work. It speeds filtered reads, not the full scans measured here.

### PRs

- PR 1 (this branch): the benchmark and Part 1.
- PR 2: Part 2's optimizations, each measured with the benchmark, until the targets hold.

## Alternatives and Why Not?

- **Canvas-backed vectors with `Long` offsets.** Off-heap needs Java 22+, so Java 17–21 keep the
  limit; it changes the public vector API, slows every value access with bounds-checked reads, and
  still holds the whole 2+ GiB chunk in memory.
- **Split only at page boundaries.** Pages of different columns end at different rows, so batches
  would not line up across columns.
- **Default to a fixed batch size (for example 64k rows).** Changes how many batches existing
  readers see for ordinary files; splitting only when needed keeps today's output.
- **Compare against Spark's or Arrow's vectorized reader.** They bring Spark or Arrow; parquet-java's
  own readers are what a JVM user without those has.
