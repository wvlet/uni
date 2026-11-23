# Iceberg: compacting tables

## Goals

- Let Uni compact an Iceberg table: rewrite data files with their deletes applied, merge small files, and drop the delete files that no longer apply, in one `replace` snapshot.
- Today every `deleteIcebergRows` adds a deletion vector or a position-delete file, and streaming writers add equality deletes. Readers pay for each one until someone rewrites the files by hand with `withReplacedFiles`, which also leaves the old delete files live.
- Keep what other engines expect from a rewrite: row IDs in v3 tables, partition specs other than Uni's own, and safe commits next to concurrent writers.

## Background

- **What a rewrite is in Iceberg.** A `replace` snapshot adds and removes files without changing the table's rows. iceberg-java (`RewriteDataFiles`) bin-packs files per partition, and by default gives the new files the data sequence number of the snapshot it started from. Then equality deletes committed meanwhile still apply to the new files, and the rewrite need not fail on them.
- **Row lineage (v3).** A row's `_row_id` is its file's `first_row_id` plus its position, and `_last_updated_sequence_number` is its file's data sequence number, unless the file stores them as columns (reserved field IDs 2147483540 and 2147483539). A rewrite moves rows to new positions, so the spec says it must write both values as columns, copied from the source rows.
- **When a delete file stops applying.** A deletion vector or file-scoped position delete applies only to its data file. A partition-scoped position delete applies, by path, to the files of its partition and spec with a data sequence number not above its own. An equality delete applies to files with a lower data sequence number in its partition (or in the whole table, for an unpartitioned delete spec).
- **What Uni has.**
  - `CommitState` with `Removal.Files` rewrites the manifests of removed files and checks each one is still live, also after a rebase. It keeps delete manifests as they are, so a copy-on-write rewrite leaves dangling deletes behind.
  - `DeleteChange.replaces` marks file-scoped delete entries `DELETED`; `RowDeleteCommit` writes delete manifests per spec by copying the spec's `partition` record type, so it handles transforms Uni can't compute (bucket, day).
  - The dataset reader applies every kind of delete and maps old schemas to the current one by field ID. It doesn't read `_row_id` or `_last_updated_sequence_number` yet.
- **ADRs this touches.** `iceberg-merge-on-read-deletes` says Uni has no compaction yet; `iceberg-overwrite-and-schema-changes` says rewrites keep delete files. Both get an update pointing to a new ADR.

## Design

Lane: Standard. It adds public API and a new kind of commit.

User flow:

- A user has a table that `deleteIcebergRows`, DuckDB, or a streaming writer has filled with delete files and small files, and reads get slower.
- They call `Parquet.compactIcebergTable(root)`, or pass `IcebergCompactionOptions` (and an `ObjectStore` overload, as the other table calls have).
- Uni picks the files to rewrite, writes the new files, and commits one `replace` snapshot. The call returns what it did: data files removed and added, delete files removed, and rows written.
- Readers see the same rows, from fewer files with fewer deletes. Time travel still reads the old files until `expireIcebergSnapshots` removes them.
- Delete files that already apply to no live file (left by an earlier `withReplacedFiles`) count as work too: a compaction with no data file to rewrite still commits a `replace` that only removes them.
- With nothing to rewrite or remove, nothing is committed and the result is all zeros.

Decisions:

- **Which files.** Files are grouped by partition spec and partition value, and each group is bin-packed. A file is a candidate when deletes apply to it or when it is under the minimum size; a group is rewritten when it has a file with deletes, or at least `minInputFiles` small files. Options, with iceberg-java's defaults: `withTargetFileSize` (512 MiB, or the table's `write.target-file-size-bytes`), `withMinFileSize` (75% of the target), `withMinInputFiles` (5), `noFileMerging()` to apply deletes only (merging small files is optional), and `withFilter(ParquetFilter)` to compact only the files a filter can match (pruned by partition values and stats; matched files are rewritten whole).
- **Each file keeps its spec and partition.** New files are written under their group's spec, with the `partition` record copied from the source entries, one data manifest per spec, as delete manifests are written today. So tables with bucket or day partitions compact too. Moving old files into the current spec is out of scope.
- **Rows are read with the current schema.** Rows go through the dataset reader, so deletes, renames, and `initial-default` values apply, and new files carry the current schema's field IDs, as iceberg-java writes them.
- **Row lineage is kept.** The rewrite reads each source file on its own, yielding every surviving row with its position in the file before deletes and its stored lineage columns. In a v3 table each new file stores `_row_id` and `_last_updated_sequence_number` as columns: the source's stored value, or per row the inherited one (first row ID plus original position, and the file's data sequence number); a source without a `first_row_id` gives nulls. The reader hides those columns from the table's columns, and a later compaction reads them back. v2 tables write neither.
- **New files keep the base sequence number.** Their manifest entries store an explicit data sequence number, that of the snapshot the compaction first read (kept across rebases), with the commit's own as the file sequence number, as iceberg-java does; each spec gets its own new data manifest. Equality deletes committed after that snapshot keep applying to them.
- **Delete files that no longer apply are removed.** After the rewrite, a live delete file is marked `DELETED` when no remaining data file can match it by the rules above. That covers deletion vectors and file-scoped position deletes of rewritten files, partition-scoped position deletes whose partition was rewritten, and equality deletes once no older file is left in their scope. A delete that still applies to a file the compaction didn't rewrite stays. The planner computes this set and the commit marks it, a new removal kind next to `DeleteChange.replaces`, which matches only file-scoped deletes.
- **Concurrency: rebase when safe, otherwise start over.** On a lost race, the commit rebases (as copy-on-write does) when every rewritten file is still live, the schema, spec, and format version are unchanged, and no new deletion vector or position delete applies to a rewritten file. New equality deletes don't block it, because of the sequence numbers. This reads the delete manifests added since the base, which copy-on-write rebases skip today. On every rebase the delete files to remove are checked again against the new live data files, so an equality delete that a concurrent rewrite made live again (its files carry an older sequence number) is kept. Otherwise it deletes its files and plans again from the new snapshot, up to `MaxAttempts`.
- **Shared code, sync only.** Planning, the rewrite, and the commit go through the existing `Step` chain, so JVM, Scala.js, and Native share them; the call is synchronous, like the other table operations.
- **Docs and ADR.** A new ADR, `iceberg-compaction`, plus update notes on the two ADRs above and the index. A "Compacting Tables" section in `docs/text/parquet.md`, the table-operations entries in `for-ai-agents.md` and the skill's `references/parquet.md`, with a plugin version bump.
- **Tests.** Round-trips through Uni's reader (v2 and v3, partitioned, re-specced, with each kind of delete), row IDs kept across two compactions, the dangling rules, and lost-race rebase and restart. `check_uni_tables.py` gets a compacted Uni table that DuckDB (through the Java REST catalog fixture) must read with the same rows.

## Decisions on the questions

- Name: `compactIcebergTable` with `IcebergCompactionOptions`.
- Files with deletes are always rewritten; `withMinDeleteRatio` raises the bar.
- When a rebase isn't safe, the compaction plans and rewrites again, up to `MaxAttempts` (4).

## Alternatives and Why Not?

- **Compaction as an option on the dataset writer (`withReplacedFiles` plus a delete-removal flag).** Callers would still pick the files, read them, and keep row IDs by hand.
- **Give new files the commit's sequence number.** Simpler, but a concurrent equality delete would then have to fail the compaction, since it would no longer apply to the new files.
- **Rewrite into the current partition spec.** Needs every transform Uni can't compute today (bucket's Murmur3, truncate, dates); keeping each file's spec covers compaction, which is the need.
- **Leave dangling equality deletes for a separate cleanup call.** Iceberg-java splits it out (`RemoveDanglingDeleteFiles`), but the rewrite already knows which files remain, so one commit does both.
