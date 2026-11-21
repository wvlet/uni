# Parquet datasets in the npm package

- Status: Accepted
- Date: 2026-11-21
- Plan: `plans/2026-11-21-parquet-npm-datasets.md`

## Context

`@wvlet/uni/parquet` read and wrote single files ([npm ADR](2026-11-20-npm-package.md)). The Scala
API reads and writes partitioned datasets on local disks and object stores
([datasets](2026-11-21-parquet-datasets.md), [async writer](2026-11-21-parquet-async-dataset-writer.md)),
but JS users had no way to pass store settings or credentials, and the async reads push batches
with no backpressure, which an `AsyncIterable` needs.

## Decision

- **Entry points.** `openDataset(root | glob | files[], options)`, `writeDataset(root, rows, schema, options)`,
  and `newDatasetWriter(root, schema, options)` join the `parquet` subpath, in the same link and
  `.d.ts`. Object-store sources use only async store calls (`datasetAsync`, the file-list dataset,
  which makes no request when built, and the async dataset writer), so they work in browsers.
  Local paths (Node.js) use the local dataset and the sync local writer.
- **No sync footer reads.** `openDataset` awaits `schemaAsync` before it resolves, so the JS
  `schema` is a plain property and no later call reads footers synchronously.
- **Pull-based reads.** `batches()` and `readRows()` pull one row group at a time from a
  package-private `ParquetDataset.batchReaderAsync`, with a Future per row group, so a slow
  consumer never buffers the dataset and long runs of filtered-out row groups don't deepen the
  stack. Early `break` closes the open file.
- **Filters** use PyArrow's shape: a `[column, op, value]` condition, an array of them (AND), or an
  array of arrays (OR of ANDs). They map onto `ParquetFilter` and `withFilter`, so they are exact
  and also prune files and row groups. `in`, `not in`, and `!=` expand into ORs and ANDs of
  comparisons (`!=` as `<` or `>`, which keeps SQL null semantics). A `Date` is a DATE literal on a
  date column and an `Instant` otherwise. Single-file reads take the same `filter`.
- **Storage settings are per call.** A `storage` option with `s3`, `gcs`, and `azure` sections
  builds the store, which the dataset or writer owns and closes. Nothing is held globally.
  Credentials are static values (access keys and session token, OAuth access token, service-account
  JSON, SAS token, shared key, or connection string) or anonymous. Without them, Node.js uses Uni's
  default chains. Browsers have none, so a missing credential is an error that names the options,
  and `credentialsJson` is rejected there: its token exchange is sync HTTP, and the page would
  expose a private key.
- **Partition values** use the read mapping (INT32 `number`, INT64 `bigint`, DATE a UTC-midnight
  `Date`), in rows, `files()`, and written files.

Rejected alternatives:
- Process-wide settings (`configureStorage`): hidden state, and one user's token shared by every
  dataset in a tab.
- A JS object-store interface: users would write request signing that Uni already has. It can
  come later as a `store` option.
- Credential-refresh callbacks: the providers are sync, so a JS callback could not await a token.
  A token that expires needs a new dataset or writer.
- Pushing `readBatchesAsync` into a queue: no backpressure, so memory grows with the dataset.
- A pruning-only `filter` until exact filtering landed: the same call would return other rows
  after an upgrade.
- A separate `@wvlet/uni/parquet-dataset` subpath: it would share the runtime anyway, and the
  bundle split belongs to the lazy-loading follow-up.

## Consequences

- The package grows from about 613 KB to 858 KB gzipped (both modules), mostly the S3, GCS, and
  Azure clients and their credential chains, which every importer now loads. Lazy loading stays
  the follow-up the npm ADR names.
- In Node.js, the default chains' metadata-endpoint lookups block the event loop while they
  resolve, as the sync HTTP bridge does; the docs recommend explicit credentials for servers.
- Values in `storage` are visible to a browser page's users. The docs recommend short-lived,
  narrowly scoped credentials and note the CORS rules buckets need.
- File lists don't read `_common_metadata`, as in Scala, so their partition types must be declared.
- Not covered: browser `File`/`Blob` collections as a dataset, presigned URLs, and sorting options.
