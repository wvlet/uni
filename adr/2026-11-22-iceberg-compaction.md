# Iceberg: compacting tables

- Status: Accepted
- Date: 2026-11-22

## Context

`Parquet.deleteIcebergRows` adds a deletion vector or position-delete file on every call, streaming writers add equality deletes, and small appends add small data files. Readers pay for each of them. Until now, the only way to rewrite a file was `withReplacedFiles`, and the caller had to pick the files, read them, and write them back. That path keeps the delete files live, so their vectors and position deletes stay in the table, dangling.

## Decision

- **`Parquet.compactIcebergTable(root, IcebergCompactionOptions): IcebergCompaction`**, plus `ObjectStore` overloads. It is synchronous, like the other table operations, and returns the counts of data files removed and added, delete files removed, and rows written. When there is nothing to do, nothing is committed.
- **Which files.** Files are grouped by spec ID and partition record. In a group, every file that a live delete applies to is rewritten (`withMinDeleteRatio` raises the bar), together with the group's small files. A group without deletes is merged only when it has `minInputFiles` small files, and never fewer than two, so a file is never rewritten into itself. The defaults follow iceberg-java: a 512 MiB target (or `write.target-file-size-bytes`), small below 75% of it, and 5 input files. Output is cut at the target size, with the size checked every 1024 rows. `withFilter` prunes by partition values and column statistics, as reads do.
- **Each file keeps its spec and partition.** New entries copy the source manifest's `partition` record and Avro type, in one data manifest per spec, as delete manifests already did. So bucket, day, and other transforms compact without Uni computing them. The manifest's schema metadata is the current schema, which the rows are read and written with.
- **One `replace` snapshot.** `CommitState` takes a `Rewrite`: data manifests of the new files, the delete entries to remove (keyed by path, plus `content_offset` for vectors that share a Puffin file), and the planned snapshot's sequence number. The removed data files go through `Removal.Files`, which rewrites their manifests, as copy-on-write does. The summary counts removed equality deletes apart from position deletes.
- **New files keep the planned snapshot's data sequence number** (their entries store it; the file sequence number is the commit's), as iceberg-java's `RewriteDataFiles` does by default. Equality deletes committed after that snapshot still apply to the new files, so they don't block the commit.
- **Delete removal is one rule.** A delete file is removed when it applies to no remaining data file: file-scoped deletes by target, partition-scoped position deletes by partition with a sequence number not above theirs, and equality deletes by a strictly lower sequence number in their partition (or anywhere, for an unpartitioned spec). New files never match old deletes: they have new paths, and their sequence number is not below any old delete's. Dangling deletes that copy-on-write left behind count as work, so a commit may only remove deletes.
- **Row lineage is written out.** In v3, the new files carry `_row_id` and `_last_updated_sequence_number` with the reserved field IDs. Each value is the source's stored one, or else the inherited one: `first_row_id` plus the position before deletes, and the data sequence number. So the rewrite reads each source file on its own: `foreachLiveBatch` gives each batch its rows' lineage values, stored or inherited, from the same row-group-at-a-time helper that updates use for row IDs, so memory doesn't grow with the file. The reader treats reserved field IDs as metadata columns, hidden like dropped ones, so a table read never shows them.
- **Races rebase when safe, otherwise plan again.** On a lost metadata create, the commit loads the newer snapshot. It rebases when the format version and schema are unchanged, every rewritten file is still live, no new position delete or vector applies to one, and every removed delete still applies to no remaining file. That last check keeps an equality delete that a concurrent rewrite made live again. Otherwise it throws `LostRace`, deletes the files it wrote, and plans again, up to `MaxAttempts`. A commit that has used up its rebases fails instead of planning again, so retries stay bounded. `deleteIcebergRows` and compaction share one commit, retry, and verify helper.

## Consequences

- DuckDB and pyiceberg read compacted Uni tables with the same rows, and DuckDB reads the same `_row_id` values before and after (`check_uni_tables.py`, tables `v3_compacted` and `v2_compacted`). Compaction also works on DuckDB's own deletion vectors and position deletes, and on the pyarrow-written equality-delete fixture.
- A restart rewrites the data again. That happens only when a concurrent delete hits a rewritten file, or a rewrite conflicts with it.
- Files stay in their spec; rewriting old files into the current spec would need transforms Uni can't compute. Sort orders are not applied.
- A copy-on-write `withReplacedFiles` still leaves the deletes of the files it removes; a later compaction removes them.
