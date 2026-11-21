# Parquet datasets: glob patterns, explicit file lists, and parallel sync footers

## Goals

- Read part of a dataset by pattern, as DuckDB's `read_parquet('s3://b/events/dt=2026-11-*/*.parquet')` and Spark's path globs do, without listing and filtering by hand.
- Read an explicit list of files as one dataset, as `read_parquet(['a.parquet', 'b.parquet'])` and `spark.read.parquet(p1, p2)` do. For example, the files a job just wrote (`writtenFiles`), or a list from a catalog.
- Make sync reads of a many-file object-store dataset as fast to start as async ones. Today the sync path fetches footers one after another, so 1,000 files on S3 means 1,000 sequential round trips before the first row.
- The second step toward a dataset API on par with Spark and DuckDB, after the writer (#1054).

## Background

- `Parquet.dataset(root)` lists everything below a root. `build` then applies the `_`/`.` skip rule and parses `key=value` segments below the root into partition columns.
- Async reads already prefetch footers with `RxParallel.parSequenceN(prefetch)`. The sync `prepare` calls `footer(file)` in a loop.
- The JVM and Scala Native have threads, and the sync `ObjectStore` and local file reads are thread-safe. Scala.js has no threads, and in browsers a dataset is read through the async API anyway.

## Design

Lane: Standard. It adds new public API (`Parquet.dataset` accepting patterns and file lists) and changes the docs and the uni skill.

User flow:

- A user wants this month's JP events: `Parquet.dataset("s3://bucket/events/dt=2026-11-*/country=JP/")`. The dataset lists only under `events/`, keeps the paths that match, and still reads `dt` and `country` as partition columns.
- A user has the paths already: `Parquet.dataset(Seq("s3://b/t/dt=1/a.parquet", "s3://b/t/dt=2/b.parquet"))`. Partition columns come from the `key=value` folders the paths share below their common parent.
- `ds.files` shows what matched. A pattern that matches nothing fails with the pattern in the message, as an empty root does today.

Decisions:

- **Glob syntax**, matched per path segment against the paths below the root: `*` (any characters within a segment), `?` (one character), `[abc]` and `[a-z]` (one of a set), `{a,b}` (alternatives), and `**` (any number of segments). This is DuckDB's and Spark's common subset.
  - The root is the longest leading part with no glob character, cut at the last `/`. Only that root is listed.
  - A pattern that ends with `/`, or whose last segment has no glob, keeps everything below the match, as a plain root does.
  - The `_`/`.` skip rule still applies to the paths that match.
  - A path that contains a glob character literally can be escaped with `\`.
- **Explicit file lists**: `Parquet.dataset(paths: Seq[String])`, plus an overload that takes an `ObjectStore`.
  - Every path must be local, or every path must use the same scheme and bucket, so one store serves them all.
  - Partitions are the `key=value` segments below the longest common parent folder, so every file must have the same keys, as today.
  - Listed files are read even when a name starts with `_` or `.`, because the caller asked for them. They are deduplicated and sorted by path.
  - No listing request is made. Sizes come from the footer reads.
- **Sync footer prefetch** on the JVM and Scala Native: `prepare` fetches up to `withPrefetch` (4) footers at a time on a small thread pool, created for the read and shut down after it. Results keep file order. Scala.js keeps the sequential loop. The default and the option are shared with the async path.
- **Tests** cover glob forms (`*`, `?`, sets, alternatives, `**`, escapes), pruning on top of a pattern, explicit lists with partitions, mixed-bucket and missing-file errors, and sync prefetch giving the same result as sequential reads. One S3 test reads a glob and a file list.
- **Docs**: the dataset section of `docs/text/parquet.md`, `for-ai-agents.md`, and the uni skill (version bump).

## Alternatives and Why Not?

- **Regex instead of glob**: no other data tool uses regex for paths, and `.` and `/` would need escaping in every pattern.
- **List the whole bucket and match the full key**: costs a full listing. Cutting at the first glob segment lists only what can match.
- **Parse partitions anywhere in a listed path**: a `key=value` folder above the common parent is the same for every file, so it adds no information and can clash with column names.
- **Prefetch with `Rx.await` on the async path**: blocking on Rx from sync code risks deadlocks on Native's single-threaded event loop. Plain threads are simpler and only exist where they work.
