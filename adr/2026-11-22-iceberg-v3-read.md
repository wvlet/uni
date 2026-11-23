# Reading Iceberg format version 3 tables

- Status: Accepted
- Date: 2026-11-22

## Context

DuckDB, Spark, Trino, and Snowflake now write Iceberg format version 3 tables, and Uni refused them. Version 3 adds nanosecond timestamps, an `unknown` type, `initial-default` values, deletion vectors, row lineage, and the `variant` and geospatial types. Reading a v3 table as if it were v2 would return wrong rows: deleted rows would come back, and defaulted columns would read as null.

## Decision

- **Read v3 per feature, failing by name on the rest.** `timestamp_ns`/`timestamptz_ns` read as INT64 NANOS timestamps with manifest bounds. `unknown` reads as nulls. Delete files, deletion vectors, `variant`, `geometry`, `geography`, defaults inside structs, and encrypted tables fail when the dataset opens. Each later PR turns one of these failures into a supported read.
- **Defaults are decoded once, from the metadata.** `IcebergTable.Resolved.defaults` holds each top-level column's `initial-default` as a physical value (Boolean, Int, Long, Float, Double, BigInt for a decimal's unscaled value, Array[Byte] for strings, binary, fixed, and UUIDs). `ParquetDataset` fills a column a file lacks with that value, converted to the vector type of the column's physical type. Row filters run after the fill, so they see the default. Stats pruning never skips such a file, since its manifest entry has no bounds for the column.
- **A defaulted column is not required in files.** Without a default, an identity partition source that a file lacks can't be filled; with one, it can.
- **Row lineage is ignored on read.** Uni exposes no metadata columns such as `_row_id`.
- **Fixtures come from DuckDB.** pyiceberg can't write v3, so `make_iceberg_v3.py` writes the v3 fixtures with DuckDB through Iceberg's Java REST catalog fixture. Real output pins down layout details (Puffin offsets, manifest fields) that hand-edited fixtures would only guess.
- **Relocation refuses v3 for now.** `renameIcebergTable` rewrites manifests in the v2 layout, which would drop the v3 fields.

## Consequences

- v3 tables without deletes read as they do in other engines.
- Tables with deletion vectors still fail until delete support lands. `variant` and the geospatial types read since [2026-11-22-parquet-variant-geospatial-read.md](2026-11-22-parquet-variant-geospatial-read.md).
