# Parquet datasets: write Apache Iceberg tables

- Status: Accepted
- Date: 2026-11-21
- Plan: `plans/2026-11-21-parquet-iceberg-write.md`

## Context

Uni reads Iceberg tables (`adr/2026-11-21-parquet-iceberg-tables.md`) but wrote only plain Hive
folders. An append to an Iceberg root added files that no Iceberg reader saw, because no snapshot
listed them. An Iceberg commit must swap the table's current metadata atomically. Catalog tables
do that in the catalog, and Hadoop tables do it by creating `metadata/vN+1.metadata.json`, which
iceberg-java does with a rename that isn't atomic on object stores.

## Decision

- **The dataset writer writes Iceberg.** `ParquetDatasetWriteOptions.withTableFormat` reuses the
  read-side `ParquetTableFormat`. Under `Auto` (the default), a root that already is an Iceberg
  table is written as one, and a Delta root fails. `Files` keeps plain files.
- **Hadoop tables only, committed with a conditional create.** `ObjectStore.putIfAbsent` (S3
  `If-None-Match: *`, GCS `ifGenerationMatch=0`, Azure `If-None-Match: *`, a local exclusive
  create) creates `vN+1.metadata.json`, so the commit is atomic on every store. After it,
  `version-hint.text` is rewritten. The reader now rolls forward past a stale hint, as
  iceberg-java does. Catalog-owned tables (`NNNNN-uuid.metadata.json`) are refused, because
  writing next to them would fork the table.
- **Lost races retry on the new parent**, up to 4 times, if the schema, spec, and format version
  are unchanged. The data files and the manifest are kept, and the manifest list and metadata are
  rewritten. A conflict whose metadata already holds this commit's snapshot ID counts as success,
  which covers a retried request whose first response was lost.
- **Data files keep the partition columns and carry field IDs.** `ParquetField` gained
  `fieldId`, which footers round-trip. A new table gets IDs in iceberg-java's order. An append
  binds by name to the current schema, needs the same Iceberg types, and refuses extra columns
  (no schema evolution yet).
- **A minimal Avro writer** (`Avro.write`, null codec, which every reader supports) writes
  format v2 manifests and manifest lists. Manifest entries carry counts and bounds from the
  Parquet row-group statistics, where the PLAIN bytes equal Iceberg's single-value serialization.
  Float, double, decimal, and UUID bounds are left out.
- **One commit routine for sync and async**: `IcebergWrite.Step` (list, fetch, put, create,
  delete) is driven by a blocking loop in `DatasetWriterCore` and by Rx in `AsyncDatasetDriver`.
- **Scope**: create (`ErrorIfExists`) and fast append (`Append`) to format v2 tables, with
  identity partitions on string, int, long, and date. Overwrite modes, other transforms, v1
  and v3 targets, and Delta writes fail by name.

## Consequences

- Tables Uni writes read in pyiceberg (including time travel and partition pruning), DuckDB's
  `iceberg_scan`, and `Parquet.dataset`; `check_uni_tables.py` in the Iceberg fixtures checks it.
- `ObjectStore` implementations must provide `putIfAbsent` and `putIfAbsentAsync`.
- Overwrites need DELETED manifest entries for expiry and incremental readers, so they come with
  their own change. So do schema evolution on write, other transforms, and catalog clients.
