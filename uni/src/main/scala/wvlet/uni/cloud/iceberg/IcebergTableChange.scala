package wvlet.uni.cloud.iceberg

import wvlet.uni.cloud.parquet.*

/**
  * A change to an Apache Iceberg table, for [[Iceberg.alterTable]]. Each is a metadata-only
  * change: no data file is rewritten. Schema changes add a new current schema, and readers match
  * columns by field ID, so existing files read with the new schema.
  */
enum IcebergTableChange:
  /**
    * Adds an optional top-level column, which reads as null in the existing files. A required
    * column can't be added, as the existing rows have no value for it.
    */
  case AddColumn(column: ParquetColumn)

  /** Drops a top-level column; a partition source column of the current spec can't be dropped */
  case DropColumn(name: String)

  /** Renames a top-level column, keeping its field ID */
  case RenameColumn(from: String, to: String)

  /**
    * Widens a top-level primitive column as Iceberg allows: int to long, float to double, and a
    * decimal to a larger precision with the same scale
    */
  case WidenColumn(name: String, to: ParquetType.Primitive)

  /** Lets a required top-level column hold nulls */
  case MakeOptional(name: String)

  /**
    * Sets a table property, which other engines keep and Uni reads back from
    * [[ParquetDataset.tableProperties]]
    */
  case SetProperty(key: String, value: String)

  /** Removes a table property; removing one the table doesn't have is no change */
  case RemoveProperty(key: String)

end IcebergTableChange
