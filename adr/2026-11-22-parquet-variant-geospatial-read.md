# Reading Parquet VARIANT, GEOMETRY, and GEOGRAPHY columns

- Status: Accepted
- Date: 2026-11-22

## Context

Iceberg format version 3 adds the `variant`, `geometry`, and `geography` types, and Uni's v3 reader failed on them by name. The same Parquet types also appear in plain files: DuckDB 2.0 and Spark 4 write VARIANT groups, and DuckDB writes GEOMETRY. A VARIANT is a group of a `metadata` binary (a field-name dictionary) and a `value` binary in the Parquet Variant encoding. Writers may shred it: common fields and element types move into a `typed_value` column of Parquet types, and its layout can differ from file to file. DuckDB shreds by default.

## Decision

- **New logical types.** `LogicalType.Variant`, `Geometry(crs)`, and `Geography(crs, algorithm)` are parsed from the footer (Thrift union fields 16 to 18) and written back by the metadata encoder, so a copied schema keeps them.
- **A variant stays a `StructColumn`.** The reader assembles the group as any struct. `StructColumn.getValue` decodes a group whose logical type is VARIANT into a Scala value (`Map[String, Any]` in field-name order, `Seq[Any]`, or a primitive). Keeping the vector type means take, filter, and dataset code paths need no new case, and a batch writer copies the physical group as is. The shredded `typed_value` columns are decoded through their own vectors' `getValue`, so their logical types convert as any column does, and a partially shredded object merges its binary `value` fields back in.
- **Values follow Uni's column mapping.** Variant primitives map to the types `readRows` gives the matching Parquet columns: `Instant` for every timestamp, `LocalDate`, `LocalTime`, `BigDecimal`, `UUID`. The MessagePack path (`RowPacker`) packs decoded values with the same rules as columns.
- **Weaver goes through MessagePack for batches with variants.** `VectorUnpacker` streams typed vectors without boxing; a variant's type varies by row, so a batch holding one is packed by `RowPacker` and unpacked instead. Batches without variants keep the direct path.
- **Variants merge across files whatever their layout.** Dataset schema merging takes the first file's variant group, and conforming a file's vector keeps the file's own children under the dataset's field. An Iceberg `variant` maps to the unshredded group (`ParquetType.VariantGroup`, private to the package).
- **Geospatial values are WKB bytes.** Uni has no geometry model; `Array[Byte]` lets a geospatial library decode them, and `withBinaryAsString` never turns them into strings. Iceberg `geometry(crs)` and `geography(crs, algorithm)` map to annotated binary columns; manifest bounds for them are not used for pruning.
- **No writes.** The Iceberg writer refuses variant and geospatial columns by name, and the record writer refuses geospatial columns.

## Consequences

- Iceberg v3 tables with these types read, and so do plain Parquet files with them.
- Decoding happens per row in `getValue`, so a variant column costs more than a typed one. Metadata dictionaries are parsed per row, with names decoded lazily.
- On Scala.js, a decoded `Float` or `Double` with an integral value packs as an integer through MessagePack, as for other `Any` values there.
