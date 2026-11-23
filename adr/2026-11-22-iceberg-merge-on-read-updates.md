# Iceberg: merge-on-read row updates written by Uni

- Status: Accepted
- Date: 2026-11-22

## Context

Uni deleted rows merge-on-read (`2026-11-22-iceberg-merge-on-read-deletes.md`), but an update was still copy-on-write: the caller read every touched file with `ds.withFiles`, wrote all of its rows again, and committed with `withReplacedFiles`. DuckDB, Trino, and Spark (with `write.update.mode = merge-on-read`) commit an update as new data files plus delete files for the old rows. In format version 3, the spec asks a writer that moves a row to keep its `_row_id`, so lineage readers see an update, not a delete and an insert.

## Decision

- **`Parquet.updateIcebergRows(root, filter)(update): Long`**, plus an `(store, root, filter)` overload, synchronous like the other table operations. `update` maps each matching row, a `Map[String, Any]` as `readRows` gives it, to its new version. Maps only: engines built on Uni work with them, and a typed overload can come later.
- **Always merge-on-read**, whatever `write.update.mode` says, as for deletes. Copy-on-write stays on `withReplacedFiles`.
- **One snapshot, built on one table version.** Each attempt loads the table once. It reads the matching rows of that version, writes the new rows, and plans the delete files, then commits them all as one `overwrite` snapshot. `RowDeleteCommit` takes the writer's plan and data files (`added`) into its `CommitState`, which already named a commit with both data and delete files `overwrite`. The dataset writer gets an update mode (`DatasetWriterCore.UpdateBase`) that binds to the given table instead of loading one, and `closeUncommitted`, which returns the files without committing.
- **Rows with their positions.** `ParquetDataset.foreachMatchingRows` reads every column of the matching rows, file by file, with their positions and row IDs. `matchingPositions` uses the same scan and still reads only the filter's columns for deletes. The update streams each file's rows into the writer, so it holds one file's matches at a time.
- **New rows are appended rows.** They are written with the table's current schema and default spec, so a row whose partition column changes moves to its new partition. They must fit the schema as an append's rows must, and tables whose spec Uni can't write fail as appends do.
- **Row lineage (v3).** The reader computes each data file's first row ID: the entry's `first_row_id`, or one inherited from the manifest list entry's, in entry order, skipping deleted entries, as iceberg-java's `ManifestReader` assigns them. A row's ID is a stored non-null `_row_id`, else the file's first row ID plus its position. The update writes `_row_id` (field ID 2147483540) with those IDs and `_last_updated_sequence_number` (2147483539) as null, after the table's columns. Neither column is added to the table schema. A file column with a reserved field ID no longer keeps a table from being read by field ID, and such columns stay hidden from reads. When the base snapshot has no row IDs (a table upgraded by alter, with no v3 commit since), the column is null and the rows get fresh IDs. The manifest still reserves fresh IDs for every added row, as Java does, leaving gaps the spec allows.
- **Races redo the whole update.** A lost metadata create throws `LostRace`, as for deletes. The update deletes its data and delete files, reloads, reads the rows again, calls `update` again, and retries up to `MaxAttempts`. `update` must have no side effects. Reusing the written data files when the touched files didn't change is left out.

## Consequences

- DuckDB reads Uni's updated v3 and v2 tables with the expected rows and the old row IDs, and pyiceberg reads the v2 table (`check_uni_tables.py`, tables `v3_update` and `v2_update`). Uni reads DuckDB's updates (fixtures `v3_update` and `v2_update`, where DuckDB stores `_row_id` and no last-updated column) and updates on top of them, keeping the IDs.
- Each update adds data files and delete files that readers apply until the table is compacted.
- A large update whose commit races pays the read and the write again on every attempt.
