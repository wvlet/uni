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

/** A value a filter compares with. A `Date` is a DATE for date columns and a timestamp otherwise */
export type FilterValue = string | number | bigint | boolean | Date;

/** One condition, such as `["dt", ">=", "2026-11-15"]` or `["country", "in", ["JP", "US"]]` */
export type FilterCondition =
  | [column: string, op: "=" | "==" | "!=" | "<" | "<=" | ">" | ">=", value: FilterValue]
  | [column: string, op: "in" | "not in", values: FilterValue[]]
  | [column: string, op: "is null" | "is not null"];

/**
 * Keeps the rows that match, with SQL semantics: a comparison with null matches nothing. Written
 * as in PyArrow: a condition, an array of conditions (AND), or an array of arrays of conditions
 * (OR of ANDs). Files and row groups that can't match are skipped before they are read.
 */
export type Filter = FilterCondition | FilterCondition[] | FilterCondition[][];

export interface ReadOptions {
  /** Read only these top-level columns */
  columns?: string[];
  /** Return only the rows that match */
  filter?: Filter;
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

/** The columns of a range of rows: a whole row group, or part of one too large for a single batch */
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
  /**
   * The selected row groups as batches, read as you iterate: one batch per row group, or several
   * when a column of a row group holds more than 2 GiB of values
   */
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
  /** `"snappy"` (default), `"none"`, `"lz4"`, `"zstd"`, or `"gzip"` (Node.js only) */
  compression?: "snappy" | "none" | "lz4" | "zstd" | "gzip";
  /** The ZSTD level, 1 (fastest) to 22 (smallest); 3 by default. Other codecs take no level. */
  compressionLevel?: number;
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

// ---- Datasets ----

/**
 * Object-store settings for a dataset read or write. Only the section of the source's scheme is
 * used. Without credentials, Node.js uses Uni's default chain (environment variables, profile
 * files, then cloud metadata endpoints, whose sync HTTP calls block the event loop while they
 * resolve). Browsers have no default chain, so pass credentials or `anonymous` there, and prefer
 * short-lived ones: values given to a page are visible to its users.
 */
export interface StorageOptions {
  /** For `s3://` URIs, including S3-compatible stores through `endpoint` */
  s3?: {
    region?: string;
    endpoint?: string;
    pathStyle?: boolean;
    credentials?: { accessKeyId: string; secretAccessKey: string; sessionToken?: string } | "anonymous";
  };
  /** For `gs://` URIs. Pass at most one of `accessToken`, `credentialsJson`, and `anonymous` */
  gcs?: {
    endpoint?: string;
    /** An OAuth access token, such as one a server hands to a browser */
    accessToken?: string;
    /** A service-account key or `authorized_user` JSON. Node.js only */
    credentialsJson?: string;
    anonymous?: boolean;
  };
  /** For `az://` and `azure://` URIs. Pass at most one of `sasToken`, `sharedKey`, and `anonymous` */
  azure?: {
    /** A connection string from the Azure portal, which may hold the account and a key or SAS */
    connectionString?: string;
    account?: string;
    endpoint?: string;
    sasToken?: string;
    sharedKey?: { accountName: string; accountKey: string };
    anonymous?: boolean;
  };
}

export type PartitionType = "string" | "int32" | "int64" | "date";

export interface DatasetOptions {
  /**
   * Partition key types. Partitions are strings unless declared here or recorded in
   * `_common_metadata` by the writer; a type given here wins. File lists don't read
   * `_common_metadata`, so declare their types here
   */
  partitionTypes?: { [key: string]: PartitionType };
  /** Read only files with this extension, such as `".parquet"` */
  fileExtension?: string;
  /** Apply partition types recorded in `_common_metadata` (default true) */
  readCommonMetadata?: boolean;
  /** How many file footers to fetch at once */
  prefetch?: number;
  storage?: StorageOptions;
}

/** A field of the dataset schema; partition columns come last, marked `partition: true` */
export interface DatasetFieldInfo extends ParquetFieldInfo {
  partition?: true;
}

export interface DatasetFileInfo {
  path: string;
  /** The size in bytes, or null for an object given in a list of files, which is not listed */
  size: number | null;
  /** The file's partition values: strings, `number` (int32), `bigint` (int64), or `Date` (date) */
  partitions: { [key: string]: ParquetValue };
}

export interface ParquetDataset {
  /** The listed directory or prefix, ending with `/` */
  readonly root: string;
  /** The union of the files' columns by name, then the partition columns */
  readonly schema: DatasetFieldInfo[];
  readonly partitionColumns: string[];
  readonly numFiles: number;
  /** The files a read with these options opens, after partition pruning. No file is read */
  files(options?: ReadOptions): Promise<DatasetFileInfo[]>;
  /** Every selected row as a plain object, partition columns included. For large datasets, prefer `batches` */
  readRows(options?: ReadOptions): Promise<ParquetRow[]>;
  /** Batches of the selected files in path order, read one row group at a time as you iterate */
  batches(options?: ReadOptions): AsyncIterable<ColumnBatch>;
  /** Closes the object store and any file an unfinished read left open */
  close(): void;
}

/**
 * Opens a dataset: a local directory (Node.js) or an `s3://`, `gs://`, or `az://` prefix, a glob
 * such as `s3://bucket/events/dt=2026-11-*`, or an array of files. Folders named `key=value`
 * become partition columns. Files and folders whose names start with `_` or `.` are skipped.
 * Rejects when no file is found
 */
export function openDataset(source: string | string[], options?: DatasetOptions): Promise<ParquetDataset>;

export interface DatasetWriteOptions extends WriteOptions {
  /** Partition columns, in folder order. Each must be a `string`, `int32`, `int64`, or `date` column of the schema */
  partitionBy?: string[];
  /**
   * What to do with existing files: fail (`"error-if-exists"`, the default), replace the dataset
   * (`"overwrite"`), add files (`"append"`), or replace only the partitions written
   * (`"overwrite-partitions"`)
   */
  mode?: "error-if-exists" | "overwrite" | "append" | "overwrite-partitions";
  maxRowsPerFile?: number;
  /** The target file size in bytes; object-store files are at most 128 MiB by default */
  maxFileSize?: number;
  /** How many files are open for writing at once */
  maxOpenFiles?: number;
  /** The memory budget for open and queued files, 256 MiB by default */
  maxBufferedBytes?: number;
  /** Write an empty `_SUCCESS` at the root on close (default true) */
  writeSuccessMarker?: boolean;
  /** Record the schema and partition types in `_common_metadata` on close (default true) */
  writeCommonMetadata?: boolean;
  storage?: StorageOptions;
}

/** A file a dataset write produced */
export interface WrittenFile {
  path: string;
  partitions: { [key: string]: ParquetValue };
  numRows: number;
  size: number;
}

export interface DatasetWriter {
  readonly root: string;
  readonly numRows: number;
  /**
   * Adds a row. Finished files are queued until `flush`, `writeAll`, or `close` uploads them.
   * Throws while the queued files exceed `maxBufferedBytes`, so await `flush()` regularly
   */
  write(row: { [column: string]: unknown }): void;
  /** Adds rows, uploading finished files as it goes */
  writeAll(rows: Iterable<{ [column: string]: unknown }>): Promise<void>;
  /** Uploads the queued files */
  flush(): Promise<void>;
  /** Finishes every file, uploads it, then writes `_common_metadata` and `_SUCCESS` */
  close(): Promise<WrittenFile[]>;
  /** Deletes the files this writer uploaded */
  abort(): Promise<void>;
}

/** Opens a writer for a partitioned dataset under a local directory (Node.js) or an object-store prefix */
export function newDatasetWriter(
  root: string,
  schema: ColumnSpec[],
  options?: DatasetWriteOptions,
): Promise<DatasetWriter>;

/** Writes rows as a dataset and resolves to the written files. A failed write deletes the files it uploaded */
export function writeDataset(
  root: string,
  rows: Iterable<{ [column: string]: unknown }>,
  schema: ColumnSpec[],
  options?: DatasetWriteOptions,
): Promise<WrittenFile[]>;
