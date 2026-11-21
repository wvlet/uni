# Async Parquet dataset writer

## Goals

- Write partitioned datasets to S3, GCS, or Azure from browsers and other non-blocking code, as `datasetAsync` already lets them read. DuckDB-Wasm users can `COPY ... TO` from a browser, and Uni datasets should be writable there too.
- Keep everything the sync writer guarantees: partition folders, write modes, the root files (`_common_metadata`, `_SUCCESS`), cleanup on failure, and the same `WrittenFile` results.

## Background

- The dataset writers are synchronous. On an object store, each file streams through `ObjectStore.newOutputStream` (a multipart upload), and listing, deletes, and root files use sync calls. Browsers have none of these. They only have the `...Async` methods (`listAsync`, `getAsync`, `putAsync`, `deleteAsync`).
- `ObjectStore` has no async multipart upload. Adding one means new upload code for S3, GCS, and Azure.
- Encoding rows into Parquet is CPU work. Only the I/O needs to be async. `DatasetWriterCore` already keeps partition routing, file rolling, and the mode rules separate from the files' I/O.
- Spark and DuckDB write files of about 100 MB. Without a memory budget for whole files, 32 open files × 128 MiB would be 4 GiB, so the async writer needs its own bound (see Design).
- In the sync core, one `write` can finish several files: a batch spanning partitions, an eviction at the open-file limit, or the row or size limit.

## Design

- Lane: Standard. New public API.
- User flow:
  - Start: `Parquet.newDatasetWriterAsync[A](store, root, options)` returns an `Rx` of a writer. Creating it lists the root and reads an old `_common_metadata` with async calls, and runs the same mode checks as the sync writer.
  - Write:
    - `writer.write(row): Unit` encodes a row. Files it finishes are queued, and `writer.flush(): Rx[Unit]` uploads the queue with `putAsync`.
    - `writer.writeAll(rows): Rx[Unit]` writes and flushes in a loop, uploading after each call that finishes files. A loop rather than chained `Rx` per row, so long inputs don't overflow the stack.
    - A `write` that would push the queued bytes past `maxBufferedBytes` fails and asks for `flush()`. Memory stays bounded even when the caller never flushes.
  - Finish: `writer.close(): Rx[Seq[WrittenFile]]` uploads the remaining files, deletes the files an overwrite replaces, then writes `_common_metadata` and `_SUCCESS`.
  - One call: `Parquet.writeDatasetAsync(store, root, rows, options): Rx[Seq[WrittenFile]]` for rows already in hand, like `writeDataset`.
  - Failure: an error or `abort(): Rx[Unit]` deletes every file this write uploaded, and no `_SUCCESS` is written, as in the sync writer.
- Record and batch variants (`newDatasetRecordWriterAsync`, `newDatasetBatchWriterAsync`) share the same core, as the sync variants do.
- Each file is built in memory and uploaded with one `putAsync`, the upload shape every store has today. 128 MiB is far below the single-upload limits of S3 (5 GiB), Azure, and GCS.
- Memory:
  - In the async writer, "no max file size" means 128 MiB. A larger cap can be set explicitly.
  - `maxBufferedBytes` (256 MiB by default) counts the whole in-memory files, open and queued. Over it, the largest open file is finished and queued, instead of only flushing a row group.
  - Peak memory is about `maxBufferedBytes` plus the file being uploaded.
- Object stores are the async case. With an `s3://`, `gs://`, or `az://` root, `Parquet.writeDatasetAsync(root: String, ...)` creates and closes the store, as `datasetAsync` does. A local root falls back to the sync writer inside `Rx`, as `datasetAsync` does for reads.
- Browsers also need the bucket's CORS rules to allow PUT and DELETE. The docs say so.
- The core is reused, not copied. It plans I/O instead of running it, so the write-mode and root-file rules stay in one place:
  - It takes a prefetched listing and old-metadata bytes.
  - Finished files go to an in-memory sink.
  - Opening, close, abort, and overwrite return pending actions: puts of finished files and root files, and deletes of replaced files, old root files, and uploaded files on abort.
  - The sync writers run those actions with sync calls, as today. The async writer runs them with `putAsync` and `deleteAsync`.
- Docs: `docs/text/parquet.md`, `for-ai-agents.md`, the skill's parquet reference, a plugin bump, and an ADR update for the writer.
- Tests:
  - JS and JVM against an in-memory `ObjectStore` test fixture: a working store, not a mock. They cover typed round trips through `datasetAsync`, each write mode, root files, abort and failure cleanup, file rolling, the memory budget, and the flush-needed error.
  - An S3 round trip on the JVM in CI. The S3 server runs only for the JVM job.

## Alternatives and Why Not?

- Add async multipart uploads to `ObjectStore` and stream files: no file-size cap, but new upload code for three stores. It can come later behind the same writer API.
- `write(row): Rx[Unit]`, which completes at once when no file finished: callers would chain an `Rx` per row, and long synchronous `Rx` chains overflow the stack.
- Run the sync writer on a thread pool: there are no threads in browsers, which are the main reason for this.
- Buffer the whole dataset and upload at `close`: memory grows with the dataset instead of with file size.
