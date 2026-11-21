# Parquet datasets from TypeScript (`@wvlet/uni/parquet`)

## Goals

- TypeScript and JavaScript users can read and write partitioned Parquet datasets, as they can from Scala with `Parquet.dataset` and `writeDatasetAsync`, in Node.js and in browsers.
- Reads take a root, a glob, or a file list; skip partitions and row groups with a filter; and return partition columns typed as the writer recorded them in `_common_metadata`.
- Settle how S3, GCS, and Azure settings and credentials reach the facade from JS.
- Out of scope: browser `File`/`Blob` collections (for example from a folder picker) as a dataset source. `openParquet` already reads single Blobs.

## Background

- `@wvlet/uni/parquet` exposes `openParquet` and `writeParquet` for single files only ([npm ADR](../adr/2026-11-20-npm-package.md)). Values follow a JS mapping (`bigint`, `Date`, typed arrays), results are Promises and AsyncIterables, and `parquet.d.ts` is hand-written and checked by `tsc` in CI.
- Browsers can do only async I/O. `datasetAsync` (root and glob) and the async dataset writers from #1070 make no sync calls on object stores. A file-list dataset does no I/O when it is built, so its async reads also work in browsers.
- Store configs (`S3Config`, `GcsConfig`, `AzureConfig`) resolve credentials through sync providers. In Node.js the default chains read environment variables and profile files, and some sources (web identity, container, EC2 metadata, Azure managed identity, Google token exchange) make sync HTTP calls that block the event loop. Browsers have no environment or files and no sync HTTP.
- `datasetAsync(root)` and the async writers' URI overloads build a store from the scheme's default config. The `(store, root)` and `(store, paths)` overloads take a store and leave it open.
- `ParquetDataset.schema` reads footers synchronously; `schemaAsync` is the browser-safe form.
- #1086 (open) adds exact row filtering (`withFilter`). Before it, `withRowGroupFilter` prunes only files and row groups.

## Design

Lane: Standard. It adds new public API to the npm package, and credentials are an always-gate area.

User flow (reads):

- `const ds = await openDataset("s3://bucket/events/", { storage })`. The source is a root, a glob such as `.../dt=2026-11-*`, or an array of files. Paths can be local (Node.js) or `s3://`, `gs://`, or `az://` URIs.
- `openDataset` lists the files and awaits the async schema before it resolves, so no sync read happens later. `ds.schema` lists the file columns and then the partition columns (marked `partition: true`). `ds.partitionColumns` names the keys.
- `await ds.files({ filter })` returns the files a filter keeps, each with its path, size (`null` for a file list, which makes no listing request), and partition values. Use it to check pruning before a large read.
- `await ds.readRows({ columns, filter })` or `for await (const b of ds.batches({ ... }))`, with the same options as `ParquetFile`. Files are read one at a time.
- `ds.close()` closes the store. The facade builds the store from `storage` and calls the `(store, ...)` overloads, so it owns and closes the store itself.
- File lists have no root, so they don't read `_common_metadata`, as in Scala. Their partitions are strings unless `partitionTypes` declares them.

User flow (writes):

- `await writeDataset(root, rows, schema, { partitionBy: ["dt"], mode: "overwrite-partitions", storage })` writes rows and resolves to the written files (path, rows, bytes, partition values).
- For streams: `const w = await newDatasetWriter(root, schema, options)`, then `w.write(row)`, `await w.flush()` (`write` throws while queued files exceed `maxBufferedBytes`, so stream writers await `flush()` regularly; `writeDataset` does this itself), and `await w.close()` (or `await w.abort()`).
- `schema` is the `ColumnSpec[]` that `writeParquet` takes. Partition columns are columns of that schema typed `string`, `int32`, `int64`, or `date`. The writer records them in `_common_metadata`, so a later `openDataset` reads them typed with no options.
- Object-store roots use the async writer, which works in browsers. Local roots in Node.js use the sync writer, as `datasetAsync` does for local reads.

Filters:

- A plain JSON shape in the style of PyArrow's `filters`: `["dt", ">=", "2026-11-15"]`, an array of such conditions (AND), or an array of arrays (OR of ANDs).
- Operators: `=`, `!=`, `<`, `<=`, `>`, `>=`, `in`, `is null`, `is not null`. `in` and `!=` expand to ORs of comparisons, because the Scala filter has no `In` or `Not` yet.
- Literals: string, number, bigint, boolean, and `Date`. A `Date` means a DATE for date columns and a timestamp otherwise.
- The filter is exact, built on `withFilter` from #1086, so `filter` means what it means in PyArrow and DuckDB. This PR waits for #1086 and rebases onto it. It does not ship a pruning-only `filter`, because the same call would return different rows after an upgrade.

Options:

- Dataset options: `partitionTypes` (`{ hour: "int32" }`, which overrides recorded types), `fileExtension`, `readCommonMetadata` (default true), and `prefetch`.
- Write options: `partitionBy`, `mode` (`"error-if-exists"`, the default, or `"overwrite"`, `"append"`, `"overwrite-partitions"`), `maxRowsPerFile`, `maxFileSize`, `maxBufferedBytes`, `compression`, `compressionLevel`, `writeSuccessMarker`, and `writeCommonMetadata`.
- Values map as in single-file reads. Partition values follow the same mapping: DATE as a UTC-midnight `Date`, INT64 as `bigint`, INT32 as `number`.

Storage settings and credentials (always-gate):

- A `storage` option on `openDataset`, `writeDataset`, and `newDatasetWriter`. It is passed on each call; nothing is held globally.
  - `s3`: `region`, `endpoint`, `pathStyle`, and `credentials`, which is `{ accessKeyId, secretAccessKey, sessionToken? }` or `"anonymous"`.
  - `gcs`: `endpoint`, plus `accessToken`, `credentialsJson` (a service-account key; Node.js only, and rejected in browsers, since its token exchange is sync HTTP and a page would expose the private key), or `anonymous: true`.
  - `azure`: `account`, `endpoint`, plus `sasToken`, `sharedKey: { accountName, accountKey }`, `connectionString`, or `anonymous: true`.
- When credentials are left out, Uni's default chain applies, as in the Scala API. In Node.js it reads the environment and profile files, and its HTTP sources block the event loop while they resolve; the docs say so and recommend explicit credentials in servers. Browsers have none of these sources, so the error says to pass credentials or `anonymous`.
- Credentials stay in the dataset's or writer's store and are dropped when it closes. Errors and `toString` keep masking them, as the Scala configs already do.
- The docs say that values given to a browser page are visible to its users. In browsers, use short-lived credentials: STS session credentials, a SAS token, or an OAuth access token. Buckets need CORS rules, and writes also need PUT and DELETE.
- Not in this PR: refreshing credentials through a JS callback, and presigned URLs. A token that expires needs a new dataset or writer.

Packaging and tests:

- Packaging (always-gate): the dataset API joins the `parquet` subpath (one `.d.ts`, one module), and the bundle grows with the S3, GCS, and Azure clients, which the facade does not link today (about 360 KB gzipped now). The PR reports the new gzipped size; lazy loading stays the follow-up the npm ADR names.
- Docs: the "From TypeScript" sections of `docs/text/parquet.md` and the skill's `references/parquet.md`, `docs/guide/for-ai-agents.md`, a plugin version bump, and an ADR.
- The Node smoke test writes a partitioned local dataset, then reads it back by root, glob, and file list, with typed partitions and a pruning filter. `tsc` checks a consumer that uses the new types.
- The object-store path is tested in Scala.js against the async-only in-memory store, which also proves that no sync call is made. A unit test checks how `storage` maps to the store configs.

## Alternatives and Why Not?

- **A separate `@wvlet/uni/parquet-dataset` subpath.** It would share one runtime anyway, and users would have to know which entry point a file reader belongs to. A later lazy split can keep the import path.
- **Passing a JS object-store implementation.** It is flexible, but users would write signing code that Uni already has. It can be added later as a `store` option.
- **Process-wide storage settings (`configureStorage(...)`).** Hidden global state, and in a browser tab one user's token would be shared by every dataset.
- **A builder API for filters (`col("dt").gte(...)`).** Not serializable, and PyArrow-style arrays are familiar to data users.
- **Guessing partition types from values.** Rejected in the datasets ADR. Recorded or declared types are used instead.
