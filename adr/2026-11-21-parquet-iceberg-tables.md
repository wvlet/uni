# Parquet datasets: read Apache Iceberg tables

- Status: Accepted
- Date: 2026-11-21
- Plan: `plans/2026-11-21-parquet-iceberg-tables.md`

## Context

An Iceberg table keeps `metadata/*.metadata.json` (schemas, partition specs, snapshots) and Avro
manifests that list each snapshot's data files. Deletes and overwrites leave old files on disk
until snapshot expiry, so `Parquet.dataset` returned removed rows, and it read the `data/dt=.../`
folders that Iceberg writers create as `key=value` partitions. Spark (`format("iceberg")`) and
DuckDB (`iceberg_scan`) read the metadata instead. The Delta ADR set up the table-format seam
(`withTableFormat`, detection under `Auto`, refusing what changes meaning).

## Decision

- **Detect by default.** Under `Auto`, a plain root with `.metadata.json` files directly under
  `metadata/` is an Iceberg table; `_delta_log/` wins when both exist (Delta UniForm). A root that
  is a `.metadata.json` file reads that version, which is how a catalog's answer is passed.
  `ParquetTableFormat.Iceberg` lists only `metadata/`.
- **Guess the newest metadata file** when there is no `version-hint.text`, unlike DuckDB, which
  refuses. The catalog is the authority, but refusing would make every catalog table need an
  explicit path. Two files for the newest version fail instead of picking one.
- **Snapshot**: `current-snapshot-id`, or `withSnapshotId(id)`, read with that snapshot's own
  `schema-id`. `withVersion` stays Delta-only, and each option fails on the other format.
- **Staged resolver.** Metadata, manifest list, and manifests are three dependent fetch levels,
  so `IcebergTable` returns `Step.Fetch(paths, next)` until `Step.Done`. A blocking loop runs it
  for `dataset`, Rx for `datasetAsync` (batches of 64, each after `Rx.delay(0)`, so thousands of
  manifests don't grow the stack). Every platform shares the parser.
- **A minimal Avro reader** (`Avro`) decodes object container files with the writer schema into a
  generic tree. Codecs: null, deflate (raw `Inflate`), snappy (with the CRC32 check), and
  zstandard (`Zstd.decompressUnknownSize`, since Avro blocks don't record their decoded size).
- **Columns from the table schema.** The dataset gains a table-schema projection: an Iceberg
  dataset lists the snapshot schema's top-level columns in table order, with types from the
  footer union, so dropped columns are hidden. Delta and plain datasets keep the plain union
  (a Delta drop needs column mapping, which is refused).
- **Identity partitions** on string, int, long, and date columns, present in every spec the
  snapshot's files use, are partition columns with values from the manifests, so Hive-imported
  files without the column read correctly and filters prune files. Other identity columns must be
  in every file, which is checked when footers are read.
- **Paths.** A path under the recorded `location` is resolved against the actual root, so moved or
  copied tables read. `s3a://` and `s3n://` count as `s3://`, and `file:` URIs as local paths.
  Other paths must be in the root's bucket, or local for a local table.
- **Refuse what changes meaning**: format version 3 or later, live delete files (a delete
  manifest with added or existing files, or delete content in a data manifest), non-Parquet data
  files, and a schema history where a field id changes its name or a name changes its field id
  (the dataset matches by name).

## Consequences

- An Iceberg read fetches the metadata JSON, the manifest list, and every manifest of the
  snapshot before any footer, `withPrefetch` at a time.
- Decimal precision promotion fails as a type conflict, and a dropped struct field still shows.
- Not covered: delete files (row masking), catalog clients (REST, Glue, Hive), snapshot as of a
  timestamp, branches and tags, pruning by manifest column bounds, gzip-compressed metadata,
  format v3, and writing Iceberg tables.
