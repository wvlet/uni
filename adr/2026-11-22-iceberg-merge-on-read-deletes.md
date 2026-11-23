# Iceberg: merge-on-read row deletes written by Uni

- Status: Accepted
- Date: 2026-11-22

## Context

Uni's Iceberg writer removed rows only by copy-on-write (`withReplacedFiles`), which rewrites a whole data file to drop one row. The reader now applies position-delete files, deletion vectors, and equality deletes, so Uni can write deletes that every engine reads. DuckDB and Trino delete this way; Spark follows `write.delete.mode`, which is copy-on-write when unset.

## Decision

- **`Parquet.deleteIcebergRows(root, filter): Long`**, plus an `(store, root, filter)` overload, synchronous like the other table operations. The filter is a `ParquetFilter`, as reads take; there is no public call that takes row positions yet.
- **Always merge-on-read.** The call writes delete files whatever `write.delete.mode` says. Copy-on-write stays on `withReplacedFiles`. Following the property would make Uni's own tables, which don't set it, always copy-on-write.
- **The format follows the table version.**
  - v3: one Puffin file per commit, with one `deletion-vector-v1` blob per data file (a portable 64-bit roaring bitmap, no run containers, with its CRC-32). A data file has at most one vector, so a blob holds the new positions and every position the file's earlier deletes already remove. The entries it replaces are marked `DELETED` in a rewritten delete manifest: vectors and position-delete files scoped to that file (`referenced_data_file`, or equal `file_path` bounds, as iceberg-java decides). A partition-scoped position-delete file stays live; its positions are in the vector too.
  - v2: one position-delete Parquet file per data file, with the reserved field IDs, its rows sorted, `referenced_data_file` set (the optional field is added to Uni's v2 delete manifests), and `file_path` and `pos` bounds.
- **Finding rows.** `ParquetDataset.matchingPositions` reads the table at the commit's base snapshot with the filter: files and row groups are skipped by statistics, only the filter's and equality keys' columns are read, and the positions of rows already deleted (by position or equality) are left out. The row-delete step takes positions per data file, so a later merge-on-read update can reuse it.
- **One commit.** Delete manifests are written one per partition spec, with the `partition` record type and spec metadata copied from that spec's data manifests, so tables with transforms Uni can't write (bucket, day) take deletes too. Delete files go next to their data file when it is inside the table folder, else under `metadata/`. The snapshot is a `delete` with delete-file summary counts. Data manifests are kept as they are.
- **Races find positions again.** A delete that loses the metadata create doesn't rebase: another writer may have deleted rows of the same file, and a vector built from stale positions would undo them. It deletes its files, reloads, finds the positions again, and retries (`IcebergWrite.LostRace`, up to `MaxAttempts`).

## Consequences

- DuckDB reads Uni's deletion vectors and position-delete files with the expected rows, and pyiceberg reads the position deletes (`check_uni_tables.py`, tables `v3_dv` and `v2_posdel`).
- Every delete adds files that readers apply until the table is compacted. Update (2026-11-22): `Parquet.compactIcebergTable` rewrites the files and removes the deletes; see [`2026-11-22-iceberg-compaction.md`](2026-11-22-iceberg-compaction.md).
- Equality deletes are not written: every reader would pay a key lookup, and Uni has no streaming writer.
- Updates are still copy-on-write.
- Update (2026-11-22): `Parquet.updateIcebergRows` commits updates merge-on-read, reusing the delete step with the new rows' data files in one `overwrite` snapshot. See [`2026-11-22-iceberg-merge-on-read-updates.md`](2026-11-22-iceberg-merge-on-read-updates.md).
