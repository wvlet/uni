# Applying Iceberg row deletes

- Status: Accepted
- Date: 2026-11-22

## Context

Engines that update or delete rows in place (DuckDB, Spark with merge-on-read, Trino, Flink) leave delete files instead of rewriting data files. Format version 2 uses position-delete files (Parquet rows of `file_path` and `pos`), and format version 3 replaces them with deletion vectors: a roaring bitmap of row positions stored as a blob in a Puffin file. Streaming writers (Flink upserts) leave equality-delete files instead: Parquet rows of key values that delete every older row with the same key. Uni refused any table with delete files, so most tables those engines had updated could not be read.

## Decision

- **Load deletes when the dataset opens.** `IcebergTable` collects the live entries of delete manifests, fetches their files in the same step chain as the manifests (sync and async), and turns them into a sorted array of deleted positions per data file (`Resolved.deletes`). A delete applies to a data file whose data sequence number is not above its own; an entry added by its manifest's snapshot inherits the manifest's sequence number. The manifest list's `content` marks a delete manifest, and so does the manifest's own metadata (for v1 lists).
- **Decode deletion vectors in Uni.** The `deletion-vector-v1` blob (length, magic, CRC-32) and the portable 64-bit roaring format (array, bitmap, and run containers) are decoded in shared Scala, with the CRC checked, so every platform reads them. Only the blob range named by `content_offset` and `content_size_in_bytes` is decoded; the Puffin footer is not needed.
- **Drop deleted rows per row group, before any row filter.** Deletes name rows by position in the file. Files with deletes are read row group by row group: the first row's position is the sum of the earlier row groups' row counts, and a batch keeps the rows whose positions are not in the array. Row-group skipping by statistics still works, because no page-level skipping changes positions. Row filters, defaults, and partition values apply after.
- **Equality deletes load as key sets.** Each equality-delete file becomes one set of keys, shared by the data files it applies to: those whose data sequence number is strictly below the delete's and, unless the delete's spec is unpartitioned (no fields, or only `void` ones), that have the same spec and partition values. `equality_ids` name the keys by field ID; the delete file's columns are found by field ID (or by the current name for a file without IDs), and the keys map to the current column names. A key on a nested or non-primitive column, or on a field ID the read schema lacks, fails by name.
- **Keys match on stored values after the row is assembled.** Matching runs on the dataset's view of the row (the plan's columns: renamed by field ID, with partition values and `initial-default` fill-ins), before the row filter, and the key columns join the read even when the caller doesn't select them. Values compare as stored, not as decoded: integers as longs, floats and doubles by their double bits (as Iceberg's `Double.compare`: NaN equals NaN, -0.0 differs from 0.0), decimals by unscaled value, and bytes for strings, binary, and fixed. Deletes on the same key columns share one key per row. A file written before a widening (`int` to `long`, a wider decimal) still matches, and read options such as `binaryAsString` and `int32DecimalAsLong` can't change the result. A filter that names a key column can still skip row groups by statistics, since deletes only remove rows.
- **Fixtures come from DuckDB.** `v3_deletes` and `v3_dv_large` carry DuckDB's deletion vectors, and `v2_deletes` its position-delete files. A test builds a bitmap with run containers and two buckets by hand, since DuckDB writes neither. DuckDB doesn't write equality deletes, so `equality_deletes` is a DuckDB v2 table (with a position delete) plus a snapshot of two equality-delete files written with pyarrow and fastavro: a partitioned one, and an unpartitioned two-column one whose explicit data sequence number reaches only the first commit. DuckDB's own reader returns the same rows for it.

## Consequences

- Tables updated in place by other engines read as those engines read them.
- Deleted positions are held in memory per data file: 8 bytes per deleted row. Equality keys are held once per delete file, as boxed values in a hash set.
- Every row of a file with equality deletes is checked against each delete that applies to it, so many small delete files (one per streaming commit) cost a lookup each; compaction removes them.
