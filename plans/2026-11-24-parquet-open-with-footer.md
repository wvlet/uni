# Open a Parquet file from an already-parsed footer

## Goals

- Let a caller that has already parsed a Parquet footer open the file again without parsing the footer again.
- Unblock footer caching in the wvlet engine. Its `ParquetSource` opens the same file about 5 times per query (schema, row estimate, column statistics, then once per scan). At TPC-H SF10 each open of the 2.2 GB lineitem.parquet parses the whole footer on the calling thread again, which adds about 50–90 ms of serial time per query.

## Background

- `ParquetFile(source, metadata)` already keeps the bytes and the footer apart. The constructor is `private[parquet]`. Outside the package, only `ParquetFile.open` and `openAsync` can build one, and both always read and parse the footer.
- Inside the package, `ParquetDataset` already reuses parsed footers this way (`ParquetFile(openSource(file), meta)`). This PR makes the same pattern public and leaves the dataset code unchanged.
- `FileMetaData` (schema elements, row groups with statistics, key-value metadata) is a public case class, and `file.metadata` already returns it. Callers can share it across threads and opens, as long as no one changes the statistics' min/max byte arrays.
- A `ParquetFile` owns its source and closes it on `close()`, so a caller cannot cache the `ParquetFile` itself. It has to cache the footer and give each open a fresh byte source.
- Whether a cached footer still matches the file is the caller's call. `FileSystem.info(path)` returns `size` and `lastModified: Option[Instant]`. For a cache key, the caller treats `None` (for example, a symlink on Native) as "do not cache".
- Rebuilding `ParquetSchema` on each open is cheap: its cost grows with the number of schema elements (about 16 for lineitem), while the footer parse grows with row groups × columns. `file.columnStatistics` is a lazy val that is computed again for each `ParquetFile`, so the engine should cache it as well if planning reads it more than once.

## Design

- Lane: **Standard**. It adds new public API.
- User flow (an engine or app author):
  - The first open uses `Parquet.open(path)` as today, then keeps `file.metadata` in a cache.
  - A later open calls `Parquet.open(path, metadata)`. This opens the bytes, skips the tail read and footer parse, and returns an ordinary `ParquetFile` that reads the same way.
  - To catch a changed file, the caller keys the cache on `FileSystem.info(path)`'s size and `lastModified`, and drops the entry when either one changes.
- New entry points:
  - `ParquetFile.open(source, metadata)` takes any `AsyncByteSource`. It returns a `ParquetFile` right away, because there is no I/O to wait for. The sync methods work when the source is a `ByteSource`, as with today's files.
  - `Parquet.open(path, metadata)` with `IOPath` and `String` overloads, the file-path form the engine needs. There is no `openAsync(source, metadata)`, since `ParquetFile.open(source, metadata)` already accepts async sources and does no I/O.
  - The private constructor stays private. The factory is the one way in, so it can gain checks later.
- No size check against the footer. A size check would cost a HEAD request on HTTP and object-store sources. A stale footer that points past the end of the file already fails on its range read, which names the file.
- No cache inside Uni. The cache policy (size, eviction, the Optimization toggle) belongs to the engine.
- Docs: a short "Reusing a parsed footer" note in `docs/cloud/parquet.md` and in the skill's `references/parquet.md`, plus a plugin version bump.
- Tests (shared code, so JVM, JS, and Native): reading with a reused footer returns the same rows as a fresh open. A stale footer that points past the end of the file fails with an error. The source is closed with the file.

## Alternatives and Why Not?

- A `ParquetFooter` handle with `reopen(source)`: it adds a new type that just wraps `FileMetaData`, which is already public.
- `file.withSource(newSource)` on an open file: it is harder to use from a cache, which holds metadata, not open files with closed sources.
- A built-in validity key type (size + mtime): `FileInfo` already carries both, and object stores need ETags instead, so a Uni type would fit only local files.
- A footer cache inside `Parquet.open`: it holds global mutable state in a library and hides the invalidation policy from the engine.
