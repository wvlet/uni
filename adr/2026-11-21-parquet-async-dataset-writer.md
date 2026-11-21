# Async Parquet dataset writer

## Context

The dataset writers do sync I/O: each object-store file streams through a multipart upload, and listing, deletes, and root files use sync calls. Browsers have only the stores' `...Async` methods, so datasets could be read there (`datasetAsync`) but not written. `ObjectStore` has no async multipart upload.

## Decision

- `Parquet.writeDatasetAsync`, `newDatasetWriterAsync[A]`, `newDatasetRecordWriterAsync`, and `newDatasetBatchWriterAsync` write to an object store with async calls only. Creating a writer lists the root, and reads an old `_common_metadata` for Append and OverwritePartitions, with `listAsync` and `getAsync`. An overwrite of a bucket root fails before anything is listed.
- Each file is built in memory and uploaded with one `putAsync`, the upload every store has. "No max file size" means 128 MiB in the async writer. `maxBufferedBytes` counts whole files, open and queued: over it, the largest open file is finished and queued.
- `write(row): Unit` queues the files it finishes. `flush()`, `writeAll`, and `close()` return `Rx` and upload them. `writeAll` uploads after each call that finishes files, in a loop rather than an `Rx` per row, which would overflow the stack. A `write` fails, without ruining the write, while the queued bytes exceed `maxBufferedBytes`.
- One core for both writers. `DatasetWriterCore` takes the prefetched listing and plans its I/O as `RootAction`s (puts and deletes):
  - The overwrite's `_SUCCESS` delete runs before any file is written.
  - Close runs the replaced-file deletes, then the root files.
  - Abort deletes the uploaded files.
  - The sync writers run these actions with sync calls. The async driver runs them with `putAsync` and `deleteAsync`. The mode and root-file rules stay in one place.
- Finished files upload four at a time with `RxParallel.parSequenceN`. Each put runs as a fiber, so a store whose puts complete at once doesn't grow the stack with every upload. A put that completes after `abort()` is deleted. A failed read of the old `_common_metadata` fails the write, as in the sync writer.
- A failure that ruins the write deletes every uploaded file, as in the sync writer. A failure after the data files are published (deletes or root files) keeps them and names the step.
- A local root uses the sync writer wrapped in `Rx`, as `datasetAsync` does for local reads.
- Tests run against an in-memory `ObjectStore` whose sync methods fail, so JS and JVM tests prove the async path makes no sync call. The S3 round trip runs on the JVM in CI.

Rejected alternatives:
- Async multipart uploads in `ObjectStore`: no size cap, but new upload code for three stores. It can come later behind the same API.
- `write(row): Rx[Unit]`: an `Rx` per row, chained by callers.
- A thread pool: there are none in browsers.
- Buffering the whole dataset until `close`: memory would grow with the dataset.

## Consequences

- Async files are at most `maxFileSize` (128 MiB by default) each, and peak memory is about `maxBufferedBytes` plus the file being uploaded.
- Browsers need bucket CORS rules that allow PUT and DELETE.
