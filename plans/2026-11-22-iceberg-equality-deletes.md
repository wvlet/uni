# Iceberg equality deletes

## Goals

- Read Iceberg tables that carry equality delete files. Flink and other streaming upsert writers delete rows by key value instead of by position, and today `Parquet.dataset` refuses those tables when it opens them.
- With this, Uni applies every row-delete kind of format versions 2 and 3: position deletes, deletion vectors, and equality deletes.

## Background

- An equality delete file (manifest `content` = 2) is a Parquet file of key values. `equality_ids` names the key columns by field ID; the file's columns carry the same field IDs.
- It deletes a row of a data file when every key column equals the delete row's value, with null equal to null.
- Scope, which differs from position deletes:
  - Only data files whose data sequence number is strictly less than the delete's (a position delete also applies at an equal number).
  - A partitioned delete applies only to data files of the same partition spec with the same partition values; a delete with an unpartitioned spec applies to every data file.
- Position deletes and deletion vectors already load when the dataset opens and drop rows per row group before row filters (ADR 2026-11-22-iceberg-row-deletes).
- DuckDB and pyiceberg 0.12 don't write equality deletes, so the current `equality_deletes` fixture is position-delete files relabeled; it is not a valid equality delete.

## Design

Lane: Standard. New read behavior; the user set the direction and authorized auto-merge, so it goes ahead without a design card.

- **Load key sets when the dataset opens.** The delete files are fetched in the same step chain as position deletes (sync and async). Each equality delete becomes a set of key tuples, shared by every data file it applies to; the per-file list follows the sequence-number and partition rules above.
- **Match on canonical values.** Keys compare by their stored value, not by how a read option decodes it: integers as longs, floats as doubles, decimals by unscaled value, strings and binary by bytes. A data file written before an `int` to `long` or decimal widening still matches.
- **Match after the row is assembled, before row filters.** Key columns are read even when the caller doesn't project them, and dropped from the output. Matching uses the dataset's view of the row, so renamed columns (by field ID), partition values, and `initial-default` fill-ins all count.
- **Fail by name what isn't supported.** A key on a nested or non-primitive column, or a field ID the read schema lacks.
- **A real fixture.** `make_iceberg_v3.py` builds `equality_deletes` from a DuckDB v2 table (with a position delete), adding a delete snapshot with pyarrow (Parquet with field IDs) and fastavro (manifests). It covers a partitioned delete, a global multi-column delete, nulls, and a delete whose sequence number leaves a later data file alone.

## Alternatives and Why Not?

- **Turn deletes into an anti-join filter (`NOT IN`).** Filters skip row groups and files by statistics and run after projection; deletes must see every row first.
- **Match on decoded values (`getValue`).** Results would change with read options such as `binaryAsString` and `int32DecimalAsLong`.
