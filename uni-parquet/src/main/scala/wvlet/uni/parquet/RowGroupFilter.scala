package wvlet.uni.parquet

/** The old name of [[ParquetFilter]], from when filters only skipped row groups */
@deprecated("Use ParquetFilter", "2026.2.4")
type RowGroupFilter = ParquetFilter

/** The old name of [[ParquetFilter]]; `import RowGroupFilter.*` still finds `col` */
@deprecated("Use ParquetFilter", "2026.2.4")
val RowGroupFilter: ParquetFilter.type = ParquetFilter
