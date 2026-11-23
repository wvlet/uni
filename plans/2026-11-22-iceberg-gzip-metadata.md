# Iceberg: read gzip-compressed metadata files

## Goals

- Read Iceberg tables whose metadata files are gzip-compressed. Engines built on iceberg-java write them when a table sets `write.metadata.compression-codec = gzip`, and today `Parquet.dataset` refuses them ("is gzip-compressed, which Uni doesn't read yet").
- Find these files wherever Uni looks for a table's current metadata: the version hint, the newest file in `metadata/`, catalog-named files, a metadata file passed as the root, and the writer's load before it commits.

## Background

- iceberg-java names a compressed file `v3.gz.metadata.json` (Hadoop tables) or `00003-<uuid>.gz.metadata.json` (catalog tables). Older releases wrote `v3.metadata.json.gz`, and iceberg-java still reads that name.
- Uni already decodes gzip in pure Scala on JVM, Scala.js, and Native (`wvlet.uni.io.codec.Inflate.gunzip`, used for Parquet GZIP pages).
- The reader's discovery only matched the `vN.metadata.json` file name. A `.gz.metadata.json` file got through `isMetadataFile` and was then refused. A legacy `.metadata.json.gz` file wasn't seen as a metadata file at all.

## Design

Lane: Express. This removes a documented read limitation with a known cause. The fix reuses an existing codec and adds no public API.

- One rule names metadata files: `.metadata.json` or `.metadata.json.gz`, with an optional `.gz` before `.metadata.json`. The reader, the writer's load, rename, and drop all use it.
- The reader decodes a metadata file by its content. Bytes that start with the gzip magic number are gunzipped first, whatever the file name says.
- Version hints resolve `vN.metadata.json`, `vN.gz.metadata.json`, and `vN.metadata.json.gz`. Rolling forward past a stale hint sees compressed versions too.
- The writer can commit after a compressed Hadoop version. It reads the newest version (compressed or not) and writes the next one uncompressed, which iceberg-java reads. Uni has no gzip compressor, and plain JSON reads everywhere.
- Tests use two real fixtures. `gzip_catalog` was made by DuckDB through Iceberg's REST catalog with gzip metadata. `gzip_hadoop` is the same table with Hadoop names, a legacy `.metadata.json.gz` file as the newest version, and a stale hint. The tests also cover an append to a compressed Hadoop table.

## Alternatives and Why Not?

- **Decide by file name only.** iceberg-java does this. Checking the magic bytes costs nothing, and it also reads a mislabeled file.
- **Write gzip metadata when the table property asks for it.** That needs a DEFLATE compressor, which Uni doesn't have. Readers accept plain files in any table, so this can wait until someone asks for it.
