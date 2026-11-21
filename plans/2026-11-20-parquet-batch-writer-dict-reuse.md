# Reuse the reader's dictionary when the batch writer copies binary columns

Follow-up to `ParquetBatchWriter` (#1037), inside
[2026-11-20-parquet-read-write-performance-and-batch-api.md](2026-11-20-parquet-read-write-performance-and-batch-api.md)
and [adr/2026-11-20-parquet-batch-writer.md](../adr/2026-11-20-parquet-batch-writer.md).

## Goals

- Make the read → write copy (`readBatches` then `newBatchWriter`) of dictionary-encoded string
  columns cheaper: the writer should not hash and compare the bytes of every value when the reader
  already knows which dictionary entry each row holds.
- Keep the output identical: same dictionary pages, PLAIN fallback, statistics, and row groups.

## Background

- The reader attaches its dictionary (`BinaryColumn.dictionary`) and each row's entry
  (`dictIndices`, -1 for null or PLAIN values) to the binary vectors it builds. Case-class reads use
  them already; the writer ignores them.
- For each binary value the writer calls `BytesDictionary.indexOf`, which hashes all bytes and
  compares them with the matching entry. On a low-cardinality column almost every call finds an
  existing entry, so this work repeats per row.
- A reader dictionary belongs to one column chunk, and every batch cut from that row group shares
  the same dictionary object. The writer's row groups do not line up with the reader's.

## Design

Lane: Express — an internal performance change with no change in output or API, outside every
always-gate area.

- The column writer gets a second binary append that takes the reader's dictionary and entry
  index along with the bytes. Both the flat and nested shredder paths use it when the row has an
  entry (`dictIndices(i) >= 0`); other rows use today's append.
- The writer keeps a remap table, from reader entry to writer dictionary index, for one reader
  dictionary at a time (matched by identity). The first row that uses an entry looks up its own
  bytes through `BytesDictionary`, exactly as today; later rows read the table.
- A remapped index is reused only after comparing the row's bytes with the writer's entry. Read
  vectors expose their arrays, so a caller may edit `data` before writing; a mismatch falls back to
  the full lookup. This skips the hash and the probe, but not the compare: safe output over the last
  few percent.
- The table is dropped when the writer starts a new row group (its dictionary is cleared) and when
  a different reader dictionary arrives. It is never consulted after the writer falls back to PLAIN,
  so the fallback path is unchanged.
- Statistics stay correct without per-row work: min/max is updated only when the writer's
  dictionary adds a value, and a remapped entry is by definition already in it.
- Tests compare each copy byte for byte with a write of the same batches stripped of dictionary
  info: several reader row groups, a writer row group smaller than the reader's, the PLAIN fallback
  mid-row-group, list and map columns, dictionaries A, B, then A again in one writer row group, and
  edited `data`.
- Measure with the existing `write uni batch` cases in `examples/parquet-bench`: their batches come
  from `readBatches` of uni-written, dictionary-encoded files and are cached, so the timed part is
  the write alone, which is where this change lands. High-cardinality comment columns fall back to
  PLAIN in the source files and should not change. Run on JVM, Node.js, and Native; record
  before/after in the parent plan.

## Alternatives and Why Not?

- **Write the reader's dictionary page through unchanged.** Faster still, but the writer's page and
  fallback sizes, unused entries, and the row-group mismatch make it a different feature.
- **Trust the reader's indices without comparing bytes.** A little faster, but an in-place edit of
  a read vector would then write wrong values silently.
- **A hash map from (dictionary, entry) across several dictionaries.** Batches arrive in reader
  order, so one dictionary at a time covers the copy case at no lookup cost.
