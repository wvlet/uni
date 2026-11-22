# Iceberg writer: performance against DuckDB, and correctness checks

Follow-up to [2026-11-21-parquet-iceberg-write.md](2026-11-21-parquet-iceberg-write.md) (#1115).

## Goals

- Measure the Iceberg dataset writer against DuckDB's Iceberg writer on the same rows, and close
  the gaps that are Uni's own.
- Check that the tables are right with readers other than Uni: every row, every manifest entry,
  and partition pruning.

## Background

- DuckDB writes Iceberg only through a catalog. The comparison runs it against a local Iceberg
  REST catalog (iceberg-java's REST test fixture), with the rows loaded into a DuckDB table first,
  so both sides time the write alone.
- Uni's cases write the benchmark's lineitem table (1M rows, 16 columns) held in memory, as case
  classes and as column batches, each creating a new table: unpartitioned, and partitioned by ship
  mode (7 values).
- First measurements (JVM 25, Apple M-series, one thread, Snappy):

  | case | Uni case class | Uni batch | DuckDB, 1 thread | DuckDB, 18 threads |
  |---|---:|---:|---:|---:|
  | unpartitioned | 1032 ms | 272 ms | 315 ms | 276 ms |
  | by ship mode | 1828 ms | 473 ms | 457 ms | 157 ms |

- The Iceberg layer costs little: an unpartitioned table writes within ~4% of a plain single file
  (case class 995 ms, batch 270 ms). The case-class time is the row writer's, on par with
  parquet-java (1016 ms). DuckDB has no row API, so batches are the like-for-like comparison.
- Partitioning was the gap. Profiles showed, per row:
  - `currentDir` rebuilt the folder name (StringBuilder, escaping) whenever a row's partition
    differed from the previous row's, which for unsorted data is nearly every row (54 ms of 473).
  - On the row path, every map key was decoded to a `String` to find the partition columns, a
    string partition value went through `readValue` (a copy, a new unpacker, a boxed `Value`, then
    a `String`), and the `(bytes, len)` tuple returned per row boxed its length.
  - The rest is the data: each partition file holds ~143k rows, so high-cardinality columns
    (`lOrderkey`, `lPartkey`) stay under the dictionary limit and keep dictionary encoding, which
    costs lookups that miss cache. parquet-java makes the same choice. DuckDB drops such
    dictionaries early.

## Design

Lane: Express. Internal changes to the dataset writer's partition handling, with the same output,
plus benchmark and check scripts.

- **Folder cache.** `currentDir` looks the folder up in a map keyed by the partition values (a
  wrapper with `Arrays.equals` and `hashCode`, probed with the writer's own array), built once per
  partition. The cache starts over past 4096 partitions.
- **Interned partition strings.** Each STRING partition column keeps a small table from UTF-8
  bytes to the decoded `String`, used by the row path (straight from the MessagePack payload) and
  the batch path (from the `BinaryColumn` slice), so repeated values are neither decoded nor
  allocated. Past 4096 distinct values, strings decode as they come.
- **Row-path fast paths.** Map keys are matched by position against the schema's UTF-8 names, as
  the shredder does, falling back to by-name lookup (with loose matching) at the first key out of
  order. STRING values that are MessagePack strings and INT32/INT64 values that are integers skip
  the generic converter; anything else (dates, numbers for strings, out-of-range ints) goes
  through it as before, with the same errors. Iceberg rows keep the partition columns, so the scan
  stops once every partition value is read. The row bytes come back through two fields instead of
  a tuple.
- **Benchmark.** `examples/parquet-bench` gets `iceberg` cases (`--filter iceberg`) and
  `duckdb_iceberg.py`, both with Snappy and ZSTD, since DuckDB writes ZSTD by default. `--filter`
  takes `=name` for one exact case, which profiling needs.
- **Correctness check.** `check_iceberg.py` reads the benchmark's tables with DuckDB
  (`iceberg_scan`) and pyiceberg and compares them with the source rows as a multiset (row hashes
  with counts). For each manifest entry it checks the record count, file size, partition value,
  and per-column value counts, null counts, and lower and upper bounds against the data file. It
  also checks that pyiceberg plans one file for a ship-mode filter.
- **Tests.** Rows with keys out of order, a loose key name, a number for a string partition, an
  out-of-range INT32, and rows that alternate partitions, written as plain files and as an Iceberg
  table. The interner past its entry limit.

## Results

JVM 25, one thread, 1M lineitem rows, median of 7 after 3 warmups. Uni before and after ran
alternately, twice, from two jar sets (machine load 5-6); the numbers are the mean of the two
rounds. DuckDB 2.0.1 ran in the same session.

| case | Uni before | Uni after | DuckDB, 1 thread | DuckDB, 18 threads |
|---|---:|---:|---:|---:|
| case class, unpartitioned, Snappy | 1060 ms | 1058 ms | | |
| case class, by ship mode, Snappy | 1920 ms | 1628 ms (-15%) | | |
| batch, unpartitioned, Snappy | 269 ms | 270 ms | 269 ms | 217 ms |
| batch, by ship mode, Snappy | 486 ms | 434 ms (-11%) | 391 ms | 136 ms |
| batch, unpartitioned, ZSTD | 468 ms | 463 ms | 313 ms | 270 ms |
| batch, by ship mode, ZSTD | 700 ms | 647 ms (-8%) | 453 ms | 143 ms |

- With Snappy (Uni's default), an unpartitioned table writes as fast as DuckDB's on one thread,
  and a partitioned one is 11% behind, down from 24%.
- ZSTD is the larger gap: Uni's pure-Scala compressor spends ~290 ms on the pages DuckDB's libzstd
  compresses in ~45 ms more than Snappy, and writes 31 MB to DuckDB's 25 MB for the partitioned
  table (DuckDB also drops the dictionaries of high-cardinality columns).
- `check_iceberg.py` passes on all six tables: DuckDB and pyiceberg read back exactly the source
  rows, every manifest entry matches its data file, and a ship-mode filter plans one file. Removing
  one row or changing one value makes the row check fail.

## Follow-up: drop dictionaries that don't pay off

- High-cardinality columns kept dictionary encoding until the dictionary reached 1 MiB
  (`dictionaryPageSize`), about 131k longs. A partition file of ~143k rows rarely got there, so
  `lOrderkey`, `lPartkey`, `lExtendedprice`, and `lComment` paid a cache-missing lookup per value
  and wrote larger files. DuckDB writes those columns as PLAIN.
- The column writer now checks once per row group, after 20,000 values, whether the dictionary
  page plus bit-packed indices is smaller than the PLAIN values. If not, it drops the dictionary,
  as parquet-java does at its first page. When no dictionary-encoded page has been closed yet (the
  usual case, since a page holds 1 MiB), the buffered values are rewritten as PLAIN, so the chunk
  has no dictionary page. Otherwise the closed pages stay and the rest is PLAIN, as before.
- The same rewrite applies to the old 1 MiB fallback when it fires before the first page closes.
- Results, same setup (load ~4), against the first change:

  | case | before | after | DuckDB, 1 thread |
  |---|---:|---:|---:|
  | case class, by ship mode, Snappy | 1527 ms | 1315 ms (-14%) | |
  | batch, unpartitioned, Snappy | 258 ms | 252 ms | 269 ms |
  | batch, by ship mode, Snappy | 411 ms | 344 ms (-16%) | 391 ms |
  | batch, by ship mode, ZSTD | 621 ms | 549 ms (-12%) | 453 ms |

  | table data | before | after | DuckDB |
  |---|---:|---:|---:|
  | by ship mode, Snappy | 39.4 MB | 34.3 MB (-13%) | |
  | by ship mode, ZSTD | 31.1 MB | 25.5 MB (-18%) | 25.1 MB |
  | unpartitioned, Snappy | 33.3 MB | 33.2 MB | |

- A column whose first 20,000 values are mostly distinct but repeat later loses its dictionary for
  that row group. parquet-java's first-page check has the same trade-off, at a later point.

## Follow-up: write on several threads (JVM)

- DuckDB's partitioned write scales to 136 ms on 18 threads; Uni's was single-threaded.
- `ParquetWriteOptions.withParallelism(n)`, by default the available processors up to 8 on the JVM
  and 1 on Scala.js and Scala Native (whose Immix GC can still crash under concurrent allocation):
  - The batch shredder writes the top-level fields of a slice of 1024 rows or more in parallel.
    Each field writes only its own column writers, which share no state.
  - A row group's columns finish their last pages and compress their dictionary pages in
    parallel when it closes, then the chunks are written in order.
  - The dataset batch writer writes the partitions of a batch to their files in parallel, each
    task gathering its own rows with its own shredder, when there's no sort and no file limit
    that could roll a file mid-batch. Files are opened before and bookkeeping runs after, on the
    calling thread.
- `WriteParallelism.run` uses a shared pool of daemon threads. The caller takes tasks too, and a
  helper that hasn't started when the tasks run out is skipped, so runs nest without waiting for a
  free thread. (`Future.cancel` can't be used for that: it also succeeds on a running task.)
- The output is the same bytes at every setting (tested for files and partitioned datasets).
- Results (load 6-8), Uni with 8 threads:

  | case | Uni, 1 thread | Uni, 8 threads | DuckDB, 18 threads |
  |---|---:|---:|---:|
  | batch, unpartitioned, Snappy | 252 ms | 120 ms | 217 ms |
  | batch, by ship mode, Snappy | 344 ms | 97 ms | 136 ms |
  | batch, unpartitioned, ZSTD | 452 ms | 256 ms | 270 ms |
  | batch, by ship mode, ZSTD | 549 ms | 122 ms | 143 ms |

- Case-class and record writes still shred rows on the calling thread; only their row-group
  compression is parallel.

## Follow-up: double-fast ZSTD at levels 1-3

- Single-threaded ZSTD writes were the gap left against DuckDB (452 vs 313 ms unpartitioned).
  Uni's compressor took 296 ms over the 60 MiB of lineitem pages that zstd-jni (libzstd) compresses
  in 112 ms (`CodecBench`, `compress` filter).
- Levels 1-3 now use zstd's double-fast strategy, as libzstd's level 3 does. A table of 8-byte
  hashes beside the 4-byte one finds long matches at once. A short match is taken only after
  checking for a long one a position later, and there is no lazy search. Levels 4-9 keep their
  hash chains.
- The Huffman code builder sorted symbols through boxed tuples; it now sorts packed longs.
- `CodecBench`: 296 ms to 214 ms, and 26.18 MB to 25.59 MB (libzstd: 25.53 MB). zstd-jni decodes
  every chunk. Dropping the lazy step alone was 20% faster but 2.4% larger, and skipping the 2nd
  and 3rd repeat offsets was 15% faster but 0.5% larger, so neither shipped.
- Iceberg writes on one thread: ZSTD 452 to 403 ms unpartitioned, 549 to 518 ms by ship mode, and
  the partitioned table is 24.95 MB (DuckDB: 25.1 MB). DuckDB on one thread is still faster (313
  and 453 ms): libzstd compresses about 2x as fast as Uni's pure-Scala code.

## Not covered (later)
- ZSTD compression speed and ratio against libzstd at level 3.

## Alternatives and Why Not?

- **Keep a one-entry cache and ask callers to sort by partition.** Unsorted input is the common
  case, and sorting the input only saves ~10% on the row path.
- **Have the shredder report partition values while it walks the row.** It would save the
  partition scan, but ties the shredder to the dataset writer for a cost that is now small.
