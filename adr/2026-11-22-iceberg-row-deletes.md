# Applying Iceberg row deletes

- Status: Accepted
- Date: 2026-11-22

## Context

Engines that update or delete rows in place (DuckDB, Spark with merge-on-read, Trino, Flink) leave delete files instead of rewriting data files. Format version 2 uses position-delete files (Parquet rows of `file_path` and `pos`), and format version 3 replaces them with deletion vectors: a roaring bitmap of row positions stored as a blob in a Puffin file. Uni refused any table with delete files, so most tables those engines had updated could not be read.

## Decision

- **Load deletes when the dataset opens.** `IcebergTable` collects the live entries of delete manifests, fetches their files in the same step chain as the manifests (sync and async), and turns them into a sorted array of deleted positions per data file (`Resolved.deletes`). A delete applies to a data file whose data sequence number is not above its own; an entry added by its manifest's snapshot inherits the manifest's sequence number. The manifest list's `content` marks a delete manifest, and so does the manifest's own metadata (for v1 lists).
- **Decode deletion vectors in Uni.** The `deletion-vector-v1` blob (length, magic, CRC-32) and the portable 64-bit roaring format (array, bitmap, and run containers) are decoded in shared Scala, with the CRC checked, so every platform reads them. Only the blob range named by `content_offset` and `content_size_in_bytes` is decoded; the Puffin footer is not needed.
- **Drop deleted rows per row group, before any row filter.** Deletes name rows by position in the file. Files with deletes are read row group by row group: the first row's position is the sum of the earlier row groups' row counts, and a batch keeps the rows whose positions are not in the array. Row-group skipping by statistics still works, because no page-level skipping changes positions. Row filters, defaults, and partition values apply after.
- **Equality deletes keep failing by name.** They need value matching against the delete rows, and sequence numbers ordered strictly, and DuckDB doesn't write them. A later change can add them.
- **Fixtures come from DuckDB.** `v3_deletes` and `v3_dv_large` carry DuckDB's deletion vectors, `v2_deletes` its position-delete files, and `equality_deletes` is `v2_deletes` with its delete entries marked as equality deletes. A test builds a bitmap with run containers and two buckets by hand, since DuckDB writes neither.

## Consequences

- Tables updated in place by other engines read as those engines read them.
- Deleted positions are held in memory per data file: 8 bytes per deleted row.
