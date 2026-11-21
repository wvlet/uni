# Parquet dataset writer: sorting within partitions

- Status: Accepted
- Date: 2026-11-21
- Plan: `plans/2026-11-21-parquet-dataset-sort-within-partitions.md`

## Context

The dataset writers stream each row into its partition's open file, so files keep arrival order. Range filters on columns like `ts` or `user_id` then skip few row groups, because every row group's min and max span most of the range. Spark offers `sortWithinPartitions` and DuckDB `ORDER BY` before `COPY` for this. Without a writer option, a caller had to sort the whole input in memory before the write.

## Decision

- `ParquetDatasetWriteOptions.withSortBy(columns*)` or `withSortBy(ParquetSortKey...)` sets one field, `sortBy`. `ParquetSortKey` has `withDescending` and `withNullsFirst`, plus `asc` and `desc` constructors. `noSortBy()` turns sorting off, which is the default.
- **Rows are buffered per partition, not per file.** When the writer closes, each partition's rows are sorted and written as one run through the normal file rolling. `maxRowsPerFile` and `maxFileSize` behave as before. A run's files cover consecutive key ranges, and the run's last file is finished when the run ends, so no file mixes runs. With enough memory, a partition is sorted end to end, as DuckDB's output is.
- **Memory.**
  - The held rows count against `maxBufferedBytes`. For the async writer, the files queued for upload count too.
  - Over the budget, the largest partition is written early as its own run. Each file stays sorted, but the partition's runs overlap, as Spark's per-task output does.
  - The first early run logs a warning that names `withMaxBufferedBytes` as the fix.
  - There is no spill to disk, because browsers and object-store-only setups have no temp storage.
  - `maxOpenFiles` has no effect while sorting, because a run writes one file at a time.
  - Async `flush()` keeps its meaning: it uploads finished files and does not end runs.
- **Rows are checked at `write`.** A MessagePack row is shredded against the file schema before it is held, and its sort keys are read from the shredder's staged values, so a row that doesn't fit still throws from `write` without stopping the writer. It is shredded again when its run is written. That costs CPU but keeps one validation path. Batches are checked by `BatchShredder`, copied (so callers can reuse their arrays), and joined with `VectorGather.concat` when the run is written.
- **Order is the statistics order** (`ColumnStats`), so min and max line up with the sort:
  - Integers, dates, timestamps, and decimals compare signed. UINT8 through UINT64 compare unsigned.
  - Strings and binary compare by their encoded bytes, unsigned. This differs from `String.compareTo` for characters outside the BMP.
  - False sorts before true.
  - Floats compare with `Double.compare`: NaN after every other value, and -0.0 before 0.0.
  - Nulls go last by default in either direction, as in DuckDB (Spark puts them first for ascending).
  - The sort is stable (TimSort), so equal keys keep arrival order.
- Sort columns are top-level primitive columns of the files. Partition columns are rejected because they are constant within a partition. INT96, INTERVAL, FLOAT16, and unknown logical types are rejected because they have no defined order.
- Every row group of a sorted file records Parquet's `sorting_columns`, as leaf indexes into the file schema (which leaves out the partition columns). `RowGroupMeta.sortingColumns` reads it back. Readers don't otherwise use it yet.

Rejected alternatives:
- Sorting per file: the files of a partition would overlap, so skipping whole files gains little, for the same memory.
- An external sort with spill files: it needs local temp storage, which the async writer's targets don't have.
- A global sort across partitions: partitions are folders, so an order across them adds nothing for readers.

## Consequences

- A sorted write holds up to `maxBufferedBytes` of rows (MessagePack bytes plus keys, or copied vectors), and writes nothing until close or memory pressure. A reader listing the root during the write sees fewer finished files than an unsorted write would show.
- Under memory pressure, a partition gets more files than its size alone would need.
- Not covered: spilling to disk for end-to-end order beyond memory, Z-order or Hilbert clustering, and using `sorting_columns` when reading.
