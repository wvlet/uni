// Types for @wvlet/uni/parquet. Kept by hand next to the Scala.js facade
// (uni-npm/src/main/scala/wvlet/uni/parquet/npm/), and checked by tsc in CI.

/** A Parquet file to open: bytes, a browser Blob/File, a local path (Node.js), or an http(s) URL read with Range requests */
export type ParquetSource = Uint8Array | ArrayBuffer | Blob | string;

/**
 * A value read from a Parquet column.
 *
 * - INT64 and UINT64: `bigint`. INT32, UINT32, FLOAT16, FLOAT, and DOUBLE: `number`
 * - STRING, ENUM, JSON, UUID, DECIMAL (no precision loss), and TIME (`HH:mm:ss.fffffffff`): `string`
 * - BINARY: `Uint8Array`
 * - DATE: a `Date` at UTC midnight
 * - TIMESTAMP and INT96: a `Date` (milliseconds), or with `{ timestamps: "bigint" }`, epoch
 *   values in the column's unit (INT96: nanoseconds)
 * - LIST: an array. MAP and STRUCT: a plain object
 */
export type ParquetValue =
  | null
  | boolean
  | number
  | bigint
  | string
  | Date
  | Uint8Array
  | ParquetValue[]
  | { [key: string]: ParquetValue };

export type ParquetRow = { [column: string]: ParquetValue };

export interface ReadOptions {
  /** Read only these top-level columns */
  columns?: string[];
  /** How to return timestamps: `"date"` (default) or `"bigint"` epoch values without loss */
  timestamps?: "date" | "bigint";
}

/** A field of the file schema */
export interface ParquetFieldInfo {
  name: string;
  /**
   * The column type, for example `"int64"`, `"string"`, `"timestamp-micros"`, `"decimal(10,2)"`,
   * `"list"`, `"map"`, or `"struct"`
   */
  type: string;
  optional: boolean;
  repeated: boolean;
  /** The children of a list, map, or struct */
  fields?: ParquetFieldInfo[];
}

/** One column of a batch */
export interface ColumnData {
  name: string;
  /**
   * Plain numeric columns come as typed arrays (null rows hold 0). Other columns come as arrays of
   * values, mapped as rows are
   */
  values: Int32Array | Float32Array | Float64Array | BigInt64Array | ParquetValue[];
  /** 1 for a null row, or null when no row is null */
  nulls: Uint8Array | null;
}

/** The columns of one row group */
export interface ColumnBatch {
  numRows: number;
  columnNames: string[];
  /** Throws when the batch has no such column */
  column(name: string): ColumnData;
}

export interface ParquetFile {
  readonly numRows: number;
  readonly numRowGroups: number;
  /** The top-level fields */
  readonly schema: ParquetFieldInfo[];
  readonly keyValueMetadata: { [key: string]: string };
  readonly createdBy: string | null;
  /** Every row as a plain object. For large files, prefer `batches` */
  readRows(options?: ReadOptions): Promise<ParquetRow[]>;
  /** The selected row groups, one batch each, read as you iterate */
  batches(options?: ReadOptions): AsyncIterable<ColumnBatch>;
  /** Releases the file handle (local files) */
  close(): void;
}

/** Opens a Parquet file and reads its footer. Rejects with an Error for unreadable or corrupt files */
export function openParquet(source: ParquetSource): Promise<ParquetFile>;

export type PrimitiveTypeName =
  | "boolean"
  | "int32"
  | "int64"
  | "float"
  | "double"
  | "string"
  | "binary"
  | "json"
  | "uuid"
  | "date"
  | "timestamp-millis"
  | "timestamp-micros"
  | "timestamp-nanos";

export type ColumnType =
  | PrimitiveTypeName
  | { decimal: { precision: number; scale: number } }
  | { list: ColumnType }
  | { map: { key: ColumnType; value: ColumnType } }
  | { struct: ColumnSpec[] };

export interface ColumnSpec {
  name: string;
  type: ColumnType;
  /** Defaults to true: a missing or null field writes null */
  optional?: boolean;
}

export interface WriteOptions {
  /** `"snappy"` (default), `"none"`, `"lz4"`, or `"gzip"` (Node.js only) */
  compression?: "snappy" | "none" | "lz4" | "gzip";
}

/**
 * Writes rows to a Parquet file in memory. Rows use the read mapping: `bigint` or `number` for
 * integers, `Date` (or `bigint` epoch values in the column's unit) for timestamps, `Date` or
 * `"yyyy-mm-dd"` for dates, strings or numbers for decimals, and `Uint8Array` for binary.
 * Throws an Error when a value does not fit its column.
 */
export function writeParquet(
  rows: Array<{ [column: string]: unknown }>,
  schema: ColumnSpec[],
  options?: WriteOptions,
): Uint8Array;
