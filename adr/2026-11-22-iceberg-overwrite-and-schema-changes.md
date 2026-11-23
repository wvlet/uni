# Iceberg tables: overwrites, copy-on-write file replacement, schema changes, and drops

- Status: Accepted
- Date: 2026-11-22

## Context

Uni created Iceberg tables and appended snapshots (`2026-11-21-parquet-iceberg-write.md`), which is
not enough for a table store. The wvlet engine keeps tables in an Iceberg warehouse and needs to
replace a table's rows (`save to t`), rewrite the rows that an update or delete touches, change a
schema (`alter table`), and drop a table. Uni's reader matched columns by name and so refused
tables with renamed columns.

## Decision

- **Overwrite is a snapshot, not a deletion.** `ParquetWriteMode.Overwrite` on an Iceberg table
  commits a snapshot whose manifests list every earlier live file as `DELETED`. No file is
  deleted, so older snapshots stay readable until snapshots are expired. Rows that don't bind to
  the current schema commit a new schema with fresh field IDs (from `last-column-id`), so a
  replace may change the table's shape as `create or replace` does.
- **Copy-on-write, not delete files.** `withReplacedFiles(paths)` removes exactly those data files
  in the same commit that adds the new ones. Manifests that hold a removed file are rewritten with
  their other files as `EXISTING` (inherited sequence numbers written out) and the removed ones as
  `DELETED`; other manifests are kept as they are. A rewritten manifest keeps its own Avro schema
  and metadata, so manifests from other writers (pyiceberg, Spark) round-trip. Position and
  equality delete files were rejected because Uni's reader doesn't read them, and every engine
  reads copy-on-write tables.
- Update (2026-11-22): the reader now applies delete files, and `Parquet.deleteIcebergRows` writes merge-on-read deletes; copy-on-write stays the way to rewrite files. See [`2026-11-22-iceberg-merge-on-read-deletes.md`](2026-11-22-iceberg-merge-on-read-deletes.md). `Parquet.updateIcebergRows` updates rows merge-on-read too ([`2026-11-22-iceberg-merge-on-read-updates.md`](2026-11-22-iceberg-merge-on-read-updates.md)).
- **A removed file must still be live.** If it isn't (on the first attempt, or after rebasing on
  another writer's commit), the commit fails, so a concurrent delete or rewrite never brings rows
  back.
- **Schema changes commit metadata only.** `Parquet.alterIcebergTable` applies
  `IcebergTableChange`s (add an optional column, drop, rename, widen, make optional, set or remove
  a property) as one new metadata version with one new schema, without a snapshot. It retries on
  a lost race by applying the changes to the new base. Adding a required column, dropping a column
  of the current partition spec, and narrowing types fail, as Iceberg forbids them.
- **Reads resolve columns by field ID.** When every top-level column of a data file carries a
  field ID that the table has known, the dataset renames the file's footer columns to the current
  names of those IDs (a dropped ID gets a name no column has). Column chunks follow the schema by
  position, so the footer names are the only change. Files without field IDs keep name matching,
  and a table whose history has a rename or a re-added name still fails for them.
- **The current snapshot reads with the current schema**, as iceberg-java does, so a schema
  change shows without a snapshot; time travel reads with the snapshot's schema.
- **Drop deletes metadata first.** `Parquet.dropIcebergTable` deletes `version-hint.text` and the
  `*.metadata.json` files before the rest, so a drop that fails partway leaves no readable table.

## Consequences

- The wvlet engine can keep `save to`, `delete`, keyed `append`, `truncate`, and `alter table` on
  Iceberg tables atomic, and other engines read the results.
- Overwrites and rewrites keep the old data files until snapshots are expired, which Uni doesn't
  do yet.
- Nested columns can't be added, renamed, or dropped by `alterIcebergTable` yet. Nested renames
  made by other tools read by field ID only at the top level.
