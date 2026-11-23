# Iceberg: merge-on-read row deletes written by Uni

## Goals

- Let Uni delete rows from an Iceberg table by committing delete files, not by rewriting the data files that hold them. Today a delete is copy-on-write: the caller reads each touched file, writes the kept rows, and commits with `withReplacedFiles`. That rewrites a whole file to drop one row.
- Write what other engines read: deletion vectors in format version 3 tables, and position-delete files in version 2 tables. Uni already applies both on read.
- Give callers a short way to say which rows go, so they don't read and rewrite files by hand.

## Background

- **Other engines.** DuckDB and Trino write deletes as delete files. Spark follows `write.delete.mode` (copy-on-write unless the table says `merge-on-read`). Either way the snapshot adds delete files and keeps the data files.
- **Version 2.** Position-delete files are Parquet `(file_path, pos)` rows, sorted, with reserved field IDs, listed in delete manifests under the data file's spec and partition. Newer writers make one file per data file and set `referenced_data_file`, which is optional in v2 manifests.
- **Version 3.** A deletion vector is a roaring bitmap of positions in a Puffin `deletion-vector-v1` blob. The manifest entry names the data file and the blob's byte range. A data file has at most one vector, so a new delete merges the old vector and any v2 position deletes for that file, and removes the entries it replaced.
- **What Uni has.**
  - The reader decodes vectors (portable roaring, CRC-32) and position deletes, matched by sequence number (`IcebergDeletes`).
  - Commits write manifests, manifest lists, and row lineage, and they retry a lost race.
  - Rename already writes position-delete Parquet files with the reserved field IDs (`relocatedPositionDeletes`), and it already rewrites delete-manifest entries.
  - New pieces: a roaring encoder, a Puffin writer, delete manifests for new entries, and a scan that finds the positions of matching rows.
- **ADRs this changes.** `iceberg-overwrite-and-schema-changes` chose copy-on-write because the reader couldn't read delete files then. `iceberg-v3-write` says Uni doesn't write vectors or position deletes. Both get an update pointing to a new ADR.

## Design

Lane: Standard. This adds public API and a new kind of commit.

User flow:

- A user has an Iceberg table, written by Uni or another engine, and wants to drop rows (GDPR erasure, a bad load) without rewriting large files.
- They call `Parquet.deleteIcebergRows(root, filter): Long`, or `deleteIcebergRows(store, root, filter)`, with the same `ParquetFilter` that reads use.
- Uni prunes files by partition values and column stats, and reads only the filter's columns from the remaining files. It finds the positions of matching rows that aren't deleted yet.
- One commit (a `delete` snapshot) adds the delete files, and the call returns the number of rows deleted. If nothing matches, nothing is committed.
- After that, Uni and other engines no longer see the rows. Time travel to the earlier snapshot still shows them, and `expireIcebergSnapshots` later removes the files only that snapshot needed. Copy-on-write stays available through `withReplacedFiles`.

Decisions:

- **Always merge-on-read.** The new call always writes delete files, as DuckDB and Trino do, whatever `write.delete.mode` says. Copy-on-write remains the `withReplacedFiles` path. (Question 1 below.)
- **Format follows the table version.**
  - **v3:** one Puffin file per commit, with one blob per touched data file. Each blob is merged with that file's existing vector and its position deletes. The vector entries it replaces are marked `DELETED`. So are position-delete entries scoped to that one file (`referenced_data_file` set, or every row naming it). A partition-scoped position-delete file stays live; that is harmless, since the vector already holds its positions.
  - **v2:** one file-scoped position-delete file per touched data file, written with the rename's writer. Uni's v2 delete manifests gain the optional `referenced_data_file` field.
- **Partitions and placement.** Uni writes one delete manifest per partition spec ID, and each entry copies its data file's partition. A new delete file goes next to its data file when that file is inside the table folder. Otherwise it goes in `metadata/`, where cleanup can reach it. The Puffin file goes next to the first touched data file under the same rule.
- **Concurrency.** Positions are found in the snapshot the commit builds on, and the metadata create is atomic, so any commit by another writer in between makes it lose the race. Then it deletes its files, reloads, finds the positions again, and retries, up to the usual limit, rather than rebasing. It never commits a vector that drops another writer's deletes.
- **Pure Scala, sync only.** The encoder and writer sit next to the decoder, shared by JVM, Scala.js, and Native. Like `alterIcebergTable` and the other table operations, the call is synchronous for now.
- **Room for updates.** Internally, a delete is two steps: find positions per data file, then plan the delete entries. A later merge-on-read update can join that plan to an append's plan in one snapshot. This PR ships deletes only.
- **Docs and ADR.** A new ADR, `iceberg-merge-on-read-deletes`, with update notes on the two ADRs above and the README index. Docs change in `docs/text/parquet.md` (a new "Deleting Rows" section next to Copy-on-Write), `for-ai-agents.md`, and the skill's `references/parquet.md`, with a plugin version bump.
- **Tests and fixtures.**
  - Round-trips through Uni's reader, including partitioned and re-specced tables.
  - Deletes on top of DuckDB's deletion vectors, position-delete files, and equality deletes (existing fixtures), and on a copy of `v2_deletes` whose two delete files are merged into one partition-scoped file, upgraded to v3. DuckDB doesn't write partition-scoped deletes, so the test builds one.
  - `check_uni_tables.py` gains Uni-written `v3_dv` and `v2_posdel` tables that DuckDB must read with the same rows.
  - Lost-race retries.

## Questions

1. Should `deleteIcebergRows` honor `write.delete.mode = copy-on-write` (Spark's default when the property is unset), or always write delete files? Recommended: always write delete files, and keep copy-on-write on `withReplacedFiles`.
2. Is a `ParquetFilter` enough, or do engines built on Uni (keyed deletes, keys from another relation) also need a lower-level call that takes positions per data file, plus a way to read row positions? Recommended: filter only now, with the position step kept internal so it can be exposed later.

## Alternatives and Why Not?

- **Follow `write.delete.mode`, defaulting to copy-on-write.** Tables Uni creates don't set the property, so the new call would almost never write delete files.
- **Positions-only API (`withDeletedPositions`).** Callers can't see row positions today, so this needs a positions API first.
- **Equality deletes.** They suit streaming upserts, but every reader then pays a key lookup, and Uni has no streaming writer.
