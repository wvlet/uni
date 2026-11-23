# Writing Iceberg format version 3 tables

- Status: Accepted
- Date: 2026-11-22

## Context

Uni read v3 tables (`2026-11-22-iceberg-v3-read.md`, `2026-11-22-iceberg-row-deletes.md`) but committed only to v2 tables. A table that another engine created as v3, or one that needs nanosecond timestamps, couldn't take Uni's appends, overwrites, or schema changes. Commits to a v3 table must keep row lineage: every row has an ID, assigned from the table's `next-row-id`.

## Decision

- **The `format-version` table property picks the version.** `withTableProperties(Map("format-version" -> "3"))` creates a v3 table, or upgrades a v2 table in that commit, as Iceberg's Java library and engines' `TBLPROPERTIES` do. `IcebergTableChange.SetProperty("format-version", "3")` upgrades in an alter. It is a metadata field, not a stored property, and a version never goes down. New tables stay v2 by default, since every engine reads v2.
- **Row IDs are assigned the way Java assigns them.** In a v3 commit, each data manifest in the new manifest list without a `first_row_id` gets the next IDs, one per added or existing row, in list order. The snapshot records `first-row-id` and `added-rows`, and the metadata records the new `next-row-id`. Manifests carried over from the parent keep their `first_row_id`, so after an upgrade, the v2 manifests get IDs in the first v3 commit. A rewritten manifest (a copy-on-write `withReplacedFiles`) writes out the IDs its entries inherited, so a kept file's rows keep their IDs, and the rewritten manifest's own list entry takes fresh IDs. A manifest written before an upgrade gets the v3 `data_file` fields when a v3 commit rewrites it, so its IDs can be written out too. That leaves gaps in the ID space, which the spec allows.
- **v3 manifests get the v3 fields.** Manifests add `first_row_id`, `referenced_data_file`, `content_offset`, and `content_size_in_bytes`, and manifest lists add `first_row_id`, all optional, with `format-version` 3 in their metadata. A rewritten manifest keeps the Avro schema it was written with.
- **Types are checked against the version.** Nanosecond timestamps map to `timestamp_ns` and `timestamptz_ns`, and `Plan.checkTypes` refuses them for a v2 table, with the property to set in the message. The plan is checked once the properties are known, before any data file is written, and `alterIcebergTable` checks the altered schema against the version too.
- **Rename handles v3 tables and deletes.** Relocation keeps the manifests' Avro schemas, so the v3 fields survive. It moves each deletion vector's `referenced_data_file` and drops the bounds of delete entries, which name the old path. It refuses tables with live position-delete files, whose Parquet rows name data files by the old paths.

## Consequences

- DuckDB reads the v3 tables Uni writes (`check_uni_tables.py`, table `sales_v3`).
- Uni doesn't write deletion vectors or position deletes. Row-level changes stay copy-on-write.
