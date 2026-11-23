# Iceberg: merge-on-read row updates written by Uni

## Goals

- Let Uni update rows of an Iceberg table without rewriting the data files that hold them. Today an update is copy-on-write: the caller reads each touched file with `ds.withFiles`, writes every row of it again, and commits with `withReplacedFiles`. Changing one row rewrites a whole file.
- Commit an update the way DuckDB, Trino, and Spark (`write.update.mode = merge-on-read`) do: new data files with the new versions of the matching rows, and delete files that remove the old versions, in one `overwrite` snapshot.
- Keep row lineage on version 3 tables: an updated row keeps its `_row_id`, so change tracking downstream sees an update, not a delete plus an insert.

## Background

- **Deletes are in place (PR #1145).** `Parquet.deleteIcebergRows(root, filter)` finds the positions of matching rows (`ParquetDataset.matchingPositions`, reading only the filter's columns), writes deletion vectors (v3) or position-delete files (v2) through `RowDeleteCommit`, and commits a `delete` snapshot. On a lost race it finds the positions again and retries (`LostRace`), since a vector built on stale positions would undo another writer's deletes.
- **The commit already takes both halves; its callers don't.** `CommitState(plan, files, deletes)` writes a data manifest and delete manifests in one snapshot and names it `overwrite` when both are present, and throws `LostRace` whenever deletes are present. But `RowDeleteCommit` builds its own `CommitState` with an empty schema and no data files, and the dataset writer commits by itself on `close()` with no deletes.
- **`matchingPositions` returns positions only.** It reads the filter's columns, not whole rows.
- **Data files of one commit don't see its position deletes as a problem.** Position deletes and vectors name their data file by path, so new data files in the same snapshot (same sequence number) are never hit by them. Equality deletes would be, which is one reason Uni doesn't write them.
- **Row lineage (v3 spec).** A row's `_row_id` is the data file's `first_row_id` plus its position, unless the file stores a non-null `_row_id` column (reserved field ID 2147483540). A writer that moves a row to a new file should write the original `_row_id`, and write `_last_updated_sequence_number` (2147483539) as null so the row takes the new commit's sequence number. Uni assigns `first_row_id`s on commit today but its reader neither computes nor exposes `_row_id`, and copy-on-write doesn't keep it for rewritten rows.
- **ADRs this changes.** `iceberg-merge-on-read-deletes` ("Updates are still copy-on-write"), `iceberg-overwrite-and-schema-changes`, `iceberg-v3-write` (consequences line), and `iceberg-v3-read` ("Row lineage is ignored on read") get update notes pointing to a new ADR.
- **Parallel work.** Another session is adding Iceberg compaction, which also touches `IcebergWrite`. This branch rebases on `origin/main` if that merges first.

## Design

Lane: Standard. This adds public API and a new kind of commit.

User flow:

- A user (or an engine built on Uni, such as wvlet's `update ... set`) wants to change some rows of an Iceberg table: fix a bad value, mask a column, move rows to another partition.
- They call `Parquet.updateIcebergRows(root, filter)(row => row.updated("status", "masked"))`, or the `(store, root, filter)` overload, with the same `ParquetFilter` that reads and deletes take. Rows are `Map[String, Any]`, as `Parquet.readRows` gives them.
- Uni prunes files by partition values and column statistics, reads the matching rows that aren't deleted yet (all columns), and calls the function on each.
- One `overwrite` snapshot adds data files with the returned rows and delete files for the old positions. The call returns the number of rows updated; when nothing matches, nothing is committed.
- Readers see the new values; time travel still shows the old ones until `expireIcebergSnapshots`. A failed update removes every file it wrote, and the table keeps its previous snapshot.

Decisions:

- **Always merge-on-read.** As for deletes, the call writes delete files whatever `write.update.mode` says. Copy-on-write stays on `withReplacedFiles`.
- **Rows in, rows out.** The function gets the full row and returns the new row. The result must fit the table's current schema, as an append's rows must; extra columns or wrong types fail before anything is written. A typed variant can come later on top of this. (Question 1.)
- **One table version for both halves.** Each attempt loads the table once; the rows are read, the data files written, and the delete files planned all against that version `t`, and one `CommitState` gets the writer's `Plan`, its data files, and the `DeleteChange`.
- **Read rows with their positions.** A sibling of `matchingPositions` reads all columns of the matching rows and yields each row with its position (and, on v3, its row ID), file by file. `matchingPositions` stays for deletes, which need only the filter's columns; both share the pruning and delete-skipping code.
- **Delete half: `RowDeleteCommit` minus its commit.** Its planning (one Puffin file with merged vectors for v3, one position-delete file per data file for v2, the same entry replacement rules) is split out so it returns the `DeleteChange` and its written files; `deleteIcebergRows` commits that alone, the update commits it with data files.
- **Insert half: the dataset writer without its commit.** An internal close path writes the data files and returns the `CommitState` inputs instead of committing; the update deletes those files on a lost race or failure. The rows are written with the table's current schema and default spec, so a row whose partition column changes lands in its new partition. Tables whose spec Uni can't write (bucket, day) fail, as appends do; deletes still work on them.
- **Row lineage on v3: updated rows keep `_row_id`.**
  - The reader learns each data file's `first_row_id` (from its manifest entry, or inherited from the manifest list's `first_row_id` plus the record counts before it), so the new read path can give a row's ID: the file's stored `_row_id` when non-null, else `first_row_id` plus position. It stays internal; reads still expose no metadata columns.
  - The writer accepts the two reserved columns only on this internal path: `_row_id` (field ID 2147483540) filled with the old IDs, and `_last_updated_sequence_number` (2147483539) all null, so the rows take the new commit's sequence number. They are not added to the table schema, and the user's function never sees them.
  - The manifest still reserves fresh IDs for the added rows, as Java does; the unused ones are a gap the spec allows.
  - When the base snapshot has no row IDs yet (upgraded to v3 by alter, no v3 commit since), there is nothing to keep, and the rows get fresh IDs.
- **Races redo everything.** Losing the metadata create throws `LostRace`, as for deletes: the update deletes its data and delete files, reloads, reads the rows again, calls the function again, and retries up to `MaxAttempts`. The function must therefore be pure; docs say so. Reusing the written data files when the touched files didn't change is an optimization left out for now.
- **Memory.** Matching rows are read and written file by file and streamed into the writer, so an update holds one file's matches at a time, not the whole result.
- **Pure Scala, sync only.** Shared code for JVM, Scala.js, and Native, synchronous like `deleteIcebergRows`.
- **Docs.** A "Updating Rows (Merge-on-Read)" section after "Deleting Rows" in `docs/text/parquet.md`, plus `for-ai-agents.md`, the skill's `references/parquet.md`, and a plugin version bump above origin/main's at rebase time. A new ADR `iceberg-merge-on-read-updates`, with update notes on the four ADRs above and the README index.
- **Tests.** Round trips through Uni's reader for v2 and v3, partitioned and re-specced tables, partition moves, updates on top of DuckDB's vectors, position deletes, and equality deletes, `_row_id` values in the new files, lost-race retries, and `check_uni_tables.py` gaining Uni-updated `v3_update` and `v2_update` tables that DuckDB must read with the expected rows.
  - New DuckDB fixtures from `make_iceberg_v3.py` (DuckDB through Iceberg's Java REST catalog on JDK 21) where DuckDB ran `UPDATE` on a v3 and a v2 table: Uni reads them as DuckDB does, and Uni's update runs on top of them.

## Questions

1. Is a `Map[String, Any] => Map[String, Any]` function the right shape, or should the first version also take case classes (`updateIcebergRows[A](root, filter)(f: A => A)`)? Recommended: maps only now; engines build on maps, and a typed overload is a small follow-up.

## Alternatives and Why Not?

- **Delete with `deleteIcebergRows`, then append.** Two snapshots: readers between them see the rows missing, and lineage is lost.
- **Equality deletes plus inserts (upsert).** No read of the old rows needed, but every reader pays a key lookup, and Uni has no streaming writer that needs it.
- **Column assignments (`Map("status" -> "masked")`) instead of a function.** Simpler to call, but can't compute a value from the row (`price * 1.1`); a function covers both.
- **A write option (`withUpdatedRows`) on `writeDataset`.** The caller would have to read the old rows and their positions first, which no public API exposes.
