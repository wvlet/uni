# Parquet datasets: read Apache Iceberg tables

## Goals

- Read an Iceberg table as a `ParquetDataset`, as Spark (`spark.read.format("iceberg")`) and DuckDB (`iceberg_scan`) do: only the data files of the current snapshot (or a chosen one), with the table's columns.
- Stop the silent wrong answer we have today: `Parquet.dataset(icebergRoot)` skips nothing under `data/`, so it reads files that later snapshots removed (a copy-on-write delete or an overwrite leaves the old files until snapshot expiry). It also reads the `data/dt=a/` folders that Iceberg writers create as Hive partitions. The folder's STRING value then overrides the file's typed `dt` column, and transform folders (`ts_day=`, `id_bucket=`) add columns the table doesn't have.
- Build on the table-format seam from the Delta PR (#1091): `withTableFormat`, detection under `Auto`, and the "refuse what changes meaning" rule.

## Background

- An Iceberg table root holds `metadata/` and (usually) `data/`. Each commit writes a new `metadata/NNNNN-<uuid>.metadata.json` (catalog tables) or `metadata/vN.metadata.json` plus `metadata/version-hint.text` (Hadoop tables). The JSON holds the schemas, partition specs, snapshots, and `current-snapshot-id`.
- A snapshot points to a manifest list (Avro), which points to manifests (Avro), which list data files with a status (added, existing, deleted). Format v2 adds delete manifests (position and equality deletes, which mask rows). Format v3 adds deletion vectors, default values, row lineage, and new types.
- Which metadata file is current is decided by the catalog (Glue, REST, Hive, SQL), not by the folder. A listing can contain a newer metadata file that the catalog doesn't consider current: a commit that lost a race and wasn't cleaned up, a commit whose outcome is unknown, or a catalog rollback. Usually the newest file is current. DuckDB reads `version-hint.text`. Without the hint, DuckDB needs an explicit metadata file or an opt-in to guessing. Spark and pyiceberg go through a catalog or take a metadata file path.
- Every path in the metadata is an absolute URI (`s3://`, `s3a://`, `file:///…`) under the table's `location`. A copied or moved table still records the old location.
- Data files that Iceberg writers produce contain every column, including partition source columns, and tag each with a field ID (`PARQUET:field_id`). Files imported from Hive (`add_files`, `migrate`) may lack the identity-partition columns. For those, the spec says readers take the value from the manifest's partition struct. Iceberg matches columns by ID, so renames and drop-then-re-add are safe for Iceberg. Our dataset matches columns by name.
- Manifests are Avro object container files. The Avro codecs in use are `null`, `deflate` (pyiceberg's and Java's default, and what Iceberg's `gzip` setting writes), `snappy` (with a trailing CRC32 per block), and `zstandard`. In format v1, a snapshot may list its manifests inline instead of in a manifest list. uni-parquet has Inflate, Snappy, and Zstd decoders but no Avro reader. Iceberg's Java library is JVM-only and heavy, so we add a small pure-Scala reader.

## Design

Lane: Standard. It adds public API (`ParquetTableFormat.Iceberg`, `withSnapshotId`), changes what `Parquet.dataset` returns for an Iceberg root, and updates the docs and the uni skill.

User flow:

- A user has an Iceberg table at `s3://lake/events/` written by Spark, Trino, or pyiceberg. `Parquet.dataset("s3://lake/events/")` sees `metadata/*.metadata.json` at the root and reads the current snapshot's data files with the current schema's columns. `readAs`, `readRows`, filters, and the async variants work as for any dataset. `dataset.files` lists the snapshot's files.
- A user who gets a metadata location from a catalog (Glue, REST) passes that file instead: `Parquet.dataset("s3://lake/events/metadata/00042-….metadata.json")` reads exactly that table version, the catalog's answer.
- Time travel: `ParquetDatasetOptions.default.withSnapshotId(id)` reads an older snapshot that is still in the metadata. An unknown ID fails and lists the snapshot IDs with their timestamps. `withSnapshotId` on a non-Iceberg dataset fails, as `withVersion` does on a non-Delta one.
- A table that uses a feature we can't honor fails when the dataset opens, naming it, instead of returning wrong rows. `withTableFormat(ParquetTableFormat.Files)` still reads the raw files.

Decisions:

- **Detection** under `Auto`, for a plain root: a `metadata/` folder directly under the root with a `*.metadata.json` file in it makes an Iceberg table. `_delta_log/` takes precedence if both exist (Delta's UniForm writes both, and Delta is the source of truth there). `ParquetTableFormat.Iceberg` fails without metadata and lists only `metadata/`. A root that is a `.metadata.json` file reads as that version, under `Auto` or `Iceberg`.
- **Current metadata file**: `version-hint.text` when present (Hadoop tables), otherwise the newest by version number (`NNNNN-` prefix or `vN`). Unlike DuckDB we guess by default, because refusing would make every catalog table need an explicit path. The docs say that the catalog is authoritative and how to pass its metadata file.
- **Snapshot**: `current-snapshot-id` by default, or `withSnapshotId`. The read uses the selected snapshot's own `schema-id`, as Spark and pyiceberg do, so time travel shows the columns as they were. A v1 snapshot's inline `manifests` list is read too. No snapshot (`null`/`-1`, a new table) reads as an empty dataset with the schema converted from Iceberg types. Snapshot-as-of-timestamp is left for later.
- **Planning**: metadata JSON, then the manifest list, then the manifests (`withPrefetch` at a time). A file is live when its entry status is not deleted. These are three dependent fetch levels, where Delta has one flat batch, so this adds a small staged resolver: pure steps over fetched bytes, driven by a blocking loop for `dataset` and by Rx for `datasetAsync`. Scala.js and Native work too. Manifests are fetched in small batches, each after `Rx.delay(0)`, so a snapshot with thousands of manifests doesn't overflow the stack.
- **Columns**: an Iceberg dataset projects the footer union onto the snapshot schema's top-level columns, in table order. This is new in `buildPlan`, because Delta and plain datasets keep the plain union. Delta doesn't need it, since a Delta drop needs column mapping, which we refuse. Columns that the table dropped but old files still hold are hidden. This works at the top level only: a dropped struct field still shows (Not covered). Columns added later read as null in older files. The int→long and float→double promotions go through the existing widening. Decimal precision promotion isn't covered by that widening, so it fails as a type conflict that names the column (Not covered).
- **Partitions**: an identity-partitioned column whose type is string, int, long, or date, and which is an identity field in every partition spec the snapshot's files use, becomes a partition column. Its value comes from the manifest, so imported files without the column read correctly and partition filters prune files as for Delta. Every other column, including other transforms' source columns, is read from the files. A file that lacks an identity column we can't fill fails when its footer is read. The `data/dt=a/` folders are never read as Hive partitions. Manifest column bounds aren't used for pruning in this PR.
- **Paths**: an absolute path under the table's recorded `location` is resolved against the actual root, so a copied or moved table reads (DuckDB needs `allow_moved_paths` for this). Other paths must be in the root's bucket (`s3a://` and `s3n://` count as `s3://`) or, for a local table, on the local file system. Anything else is refused.
- **Refusals**, each by name:
  - Format version 3 and later.
  - A snapshot with live delete files (a delete manifest whose added or existing file count is above 0).
  - Data files that aren't Parquet (Avro, ORC).
  - Schema changes that break matching by name: a field ID whose name differs across the table's schemas (a rename), or a name that maps to different IDs (dropped and re-added). Nested fields are checked too.
- **Avro reader**: a private, minimal object-container reader, covering only what manifests need. Codecs are null, deflate, snappy, and zstd. The reader decodes records, arrays, maps, unions, enums, fixed, and primitives into a generic tree, with the writer's schema from the file header.
- **Tests**: tables made by pyiceberg (`uv run --with pyiceberg --with pyarrow`, SQL catalog on a local warehouse), checked in with the script. The tables cover partitioned appends with a copy-on-write delete, several snapshots for time travel, an empty table, schema evolution (an added column, a dropped column, a rename for the refusal), and a merge-on-read delete for the delete-file refusal. Hand-made metadata covers version 3, ORC files, and path errors. The fixtures' absolute `file://` paths exercise the moved-table rule. Async tests run on `MemoryObjectStore`.
- **Docs**: an "Apache Iceberg tables" section in `docs/text/parquet.md`, the AI-agent page row, the skill reference, an ADR, and the plugin version bump.

## Not covered (later)

- Delete files (merge-on-read position and equality deletes, v3 deletion vectors). They need row masking, as Delta's deletion vectors do.
- Catalog clients (REST, Glue, Hive), snapshot-as-of-timestamp, branches and tags, and manifest-based file pruning (partition values and column bounds).
- Gzip-compressed metadata JSON, format v3, decimal precision promotion, hiding dropped struct fields, and writing Iceberg tables.

## Alternatives and Why Not?

- **Refuse to guess the metadata file without `version-hint.text`** (DuckDB's default): safe against an uncommitted file but fails for most catalog tables at their root. Passing the metadata file covers the strict case.
- **Match columns by field ID**: Iceberg's semantics, and renames would read. But the dataset reader, filters, and the union schema are name-based. Refusing renames keeps this PR additive, and ID matching can follow.
- **No partition columns, values from the files only**: simpler, but imported Hive files would read the identity columns as null, and partition filters couldn't prune files.
- **Depend on iceberg-java or an Avro library**: JVM only and many dependencies. uni-parquet stays pure Scala on every platform.
