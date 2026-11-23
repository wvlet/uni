# Iceberg: rename a table with position-delete files

## Goals

- Let `Parquet.renameIcebergTable` move tables that have v2 position-delete files. DuckDB, Spark, and Trino leave these files after merge-on-read deletes, and today rename refuses such tables and asks for a compaction first.

## Background

- Rename moves the local folder. It then writes the current snapshot's manifests and manifest list again with the new paths, and commits a metadata version with the new `location`. The replaced files and older snapshots' files are deleted as unreferenced.
- A position-delete file is a Parquet file of `file_path` and `pos` rows. Its rows name data files by absolute path, so after a move they point at the old folder, and readers would miss the deletes. Deletion vectors name their data file in the manifest, which rename already moves.
- Rename checks everything it can before it moves the folder, because a failure after the move leaves the metadata pointing at the old location.

## Design

Lane: Express. This removes a documented refusal using the relocation design that already exists. It adds no public API.

- During relocation, each live position-delete file of the current snapshot is read. Its `file_path` values are moved the same way manifest paths are, and the rows are written to a new file, sorted by path and position, in the old file's schema so the reserved field IDs stay.
- The new file goes next to the old one. Its manifest entry gets the new path and size, and drops the column sizes, bounds, and split offsets, which described the old file.
- The old delete file becomes unreferenced and is deleted with the other replaced files. A retried commit deletes the files its failed attempt wrote, as it does for manifests.
- Rename still refuses before moving when a rewrite can't be faithful: a delete file that isn't Parquet, or one with the optional `row` column. The check reads each delete file, which a local rename can afford.
- Tests rename the DuckDB `v2_deletes` and `equality_deletes` fixtures and check the rows, the rewritten files' paths, and that the old files are gone. A refusal test marks delete files as ORC. DuckDB reads a Uni-renamed table with the same rows.

## Alternatives and Why Not?

- **Keep refusing, and ask for compaction.** Uni can't compact Iceberg tables yet, so users would have no way to move these tables with Uni.
- **Convert position deletes to deletion vectors.** That is only valid in v3 tables, and it's a format change that a rename shouldn't make.
- **Carry the `row` column through.** Writers rarely set it, and round-tripping arbitrary row values risks changing them. Refusing it is safer until someone needs it.
