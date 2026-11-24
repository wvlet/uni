# Reuse Parquet page buffers and decode PLAIN values in bulk

## Goals

- Cut the allocation of Parquet reads: every compressed page is decompressed into a new array, about 16 GB in the wvlet engine's TPC-DS SF1 profile (#1171 left this for later).
- Cut the CPU of PLAIN INT32, INT64, FLOAT, and DOUBLE pages, which read each value byte by byte.
- Keep every result identical.

## Design

- Lane: Express. Internal performance change, no API change.
- Snappy and ZSTD get `decompressInto`, which LZ4 already has. `Codecs.decompressInto` covers SNAPPY, LZ4_RAW, and ZSTD; GZIP and BROTLI keep allocating.
- `ColumnChunkCursor` decompresses pages into one buffer. A page's buffer is free once its values are committed, because every decoder copies values out (PLAIN and delta binaries, dictionaries). The cursor only loads a page when none is held, so it never overwrites a page that is still waiting.
- The buffer comes from `PageBuffers`, a per-thread pool, and goes back when the chunk ends. So the next row group's chunks on the same thread reuse it. Many files have one page per chunk, so a buffer per cursor alone would save little. Limits: 64 buffers, 16 MiB per thread in all, nothing over 4 MiB. A cursor that is dropped early just doesn't return its buffer.
- Uncompressed V1 pages and dictionary pages are read in place from the chunk bytes, as V2 pages already are, instead of being copied.
- `Bytes.intsLE` / `intsAsLongsLE` / `longsLE` / `floatsLE` / `doublesLE` read values in bulk, with a range check on every platform:
  - JVM: a little-endian `ByteBuffer` view's bulk get, which copies memory on little-endian hardware. Widening uses a VarHandle loop, written in Java (as `Word8` is), since Scala boxes `VarHandle.get`.
  - Native: `memcpy` for the same-width reads.
  - JS: the current loops.

## Alternatives and Why Not?

- One buffer per cursor, with no pool: simpler, but it saves nothing when a chunk has a single page.
- Pooling the raw chunk bytes too: their lifetime spans the row group's fetch and the windowed reads. That needs a separate change.
