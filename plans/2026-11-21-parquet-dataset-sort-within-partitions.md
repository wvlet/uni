# Sort rows within dataset partitions

## Goals

- A dataset write can sort the rows of each partition by chosen columns before writing, like Spark's `sortWithinPartitions` or DuckDB's `ORDER BY` before `COPY ... PARTITION_BY`.
- Sorted files have tight row-group min/max statistics, so `RowGroupFilter` (and Spark, DuckDB, Trino) skip far more row groups on range filters over the sort columns (`ts`, `user_id`).
- Readers get rows in sort order within each file without sorting again.

## Background

- The dataset writer streams each row straight into its partition's open file, so the file keeps arrival order. Callers who want sorted files must sort the whole input first, which needs the entire dataset in memory.
- Memory is already bounded by `maxBufferedBytes` (256 MiB). Sorting needs rows held until they are written, so it must live inside that same budget.
- The sync and async writers share `DatasetWriterCore`. Anything added there works for both, and for all three row shapes (case classes, records, batches).
- Parquet's `RowGroup` has a standard `sorting_columns` field (column index, descending, nulls first). parquet-mr and Arrow write it, and some engines use it to skip a sort. uni-parquet neither writes nor reads it today.
- Defaults differ between engines: Spark sorts ascending with nulls first, DuckDB with nulls last.

## Design

Lane: Standard. New writer option that changes the order and number of files a write produces.

- User flow:
  - Write: `options.withPartitionBy("dt").withSortBy("user_id", "ts")` sorts each partition's rows by `user_id`, then `ts`, ascending.
  - Direction and nulls: `withSortBy(ParquetSortKey.desc("ts"), ParquetSortKey.asc("id").withNullsFirst(true))`. Both `withSortBy` overloads set one field, `sortBy: Seq[ParquetSortKey]`.
  - Turn off: `noSortBy()`, the default.
  - Check: each file's row groups carry `sorting_columns`, visible through `ParquetFile`, and the docs show a filter skipping row groups on a sorted dataset.
  - Fragmented runs: when memory pressure ends a run early, the writer logs it once per write (partition and run count), and the docs name `withMaxBufferedBytes` as the remedy.
  - Recover: an unknown, nested, or partition column fails when the writer is created, naming the column. So do types with no defined order: INT96, INTERVAL, and FLOAT16.
- Sorting is buffered per partition, not per file. Rows of a partition are held in memory, then sorted and written as one run through the usual file rolling, so `maxRowsPerFile` and `maxFileSize` behave as today. A run's files cover consecutive key ranges, and its last file is finished at the end of the run, so no file mixes two runs.
- When runs are written:
  - On `close()`: every partition's buffer. The async `flush()` keeps its meaning (upload files already finished) and does not end runs.
  - When memory exceeds `maxBufferedBytes`: the largest partition's buffer is written early as its own run. The partition's later rows start a new run in new files. Memory counts the sort buffers plus what the writer counts today: row-group buffers (sync), or open and queued files (async). The async trigger leaves room for the run's files, so `write` does not hit the queued-bytes error.
  - With enough memory, each partition gets one run and its files are sorted end to end, as DuckDB's output is. Under memory pressure a partition gets several sorted runs, and each file is still fully sorted, as Spark's per-task output is. There is no spill to disk: the budget sets the run size.
- `maxOpenFiles` has no effect while sorting: a run opens one file at a time and finishes it before the next run starts.
- Sort order is the Parquet column order, the same order the statistics use, so min/max line up with the sort:
  - Signed order for integers, dates, timestamps, and decimals; unsigned for UINT8/16/32/64; strings and binary by their encoded (UTF-8) bytes, unsigned; false before true.
  - NaN after every other float, as in Spark and DuckDB.
  - Nulls last by default, as in DuckDB (Spark puts them first for ascending); `withNullsFirst(true)` per key.
  - The sort is stable: rows with equal keys keep their arrival order.
- Sort columns are top-level primitive columns other than the partition columns, which are constant within a partition.
- Every row group of a sorted file records `sorting_columns`, with leaf indexes in the file's schema (partition columns removed). The thrift encoder, parser, and `RowGroupMeta` gain the field. The reader parses it and exposes it on row-group metadata; it does not otherwise change read behaviour.
- Docs: `docs/text/parquet.md` (compiled snippet), `for-ai-agents.md`, the skill's `references/parquet.md`, a plugin bump, a new ADR, and the "Not covered" list of the writer ADR.
- Tests cover the three writer shapes, sync and async, asc/desc/nulls, multi-key ties, runs under a small `maxBufferedBytes`, file rolling within a run, `sorting_columns` round-trips, and bad sort columns.

## Alternatives and Why Not?

- **Sort per file instead of per partition:** each file sorted, but files of one partition overlap, so file-level skipping gains little; per-partition runs cost the same memory.
- **External sort with spill files:** sorts any size end to end, but needs temp storage, which browsers and object-store-only setups lack. Runs within the budget cover the skipping goal.
- **Global sort across partitions (Spark `orderBy`):** partitions are folders, so ordering across them adds nothing to reads.
- **Leave sorting to the caller:** forces the whole input into memory before the write, and the caller cannot reuse the writer's budget.
