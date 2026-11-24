# MPC1 typed column decoders

## Goals

- Let a query engine build column vectors from MPC1 files without boxing one MessagePack `Value` per row.
- Give callers a cheap way to learn a column's type before choosing a decoder.

## Background

- `MPC1File.readColumn(name)` decodes each row into a `Value`; that allocation dominates columnar scans.
- A column block is a sequence of MessagePack values, one per row, with nil for missing rows. MPC1 has no declared schema, so a column's type is only known by looking at its values.
- `wvlet.uni.cloud.parquet.ColumnVector` is already Uni's shared columnar type: the Arrow writer and Parquet consumers take it, and it stores primitives unboxed with a `nulls` flag array.

## Design

- Lane: pre-approved transcription. The task brief carries the design and delegates the open decisions (vector type, mismatch policy) to this session; no direction gate.
- Return Parquet `ColumnVector`s (`LongColumn`, `DoubleColumn`, `BooleanColumn`, `BinaryColumn`) with a synthesized optional field, so MPC1 columns plug into code that already consumes Parquet batches (and the Arrow writer). `nullFlags` stays null when no row is nil, as for Parquet.
- `MPC1File` methods: `columnType(name)`, `readLongs`, `readDoubles`, `readBooleans`, `readStrings`, `readBinaries`, each by name or `MPC1Column`.
- The same decoders are public on raw block bytes (`MPC1Decoder`), so a caller can decompress a block once, probe it, then decode it.
- `MPC1ColumnType`: `LONG`, `DOUBLE`, `BOOLEAN`, `STRING`, `BINARY`, `NIL` (no non-nil value, including an empty column), `ANY`.
  - Probe reads only format bytes and skips payloads; no `Value` is built.
  - int + float → `DOUBLE`; str + bin → `BINARY`; anything else mixed, arrays, maps, and ext (timestamps) → `ANY`.
- Mismatch policy (user decision): MPC1 is schema-on-read, so a value of another type is null, like nil. An exception would break query processing. Only a corrupt block throws `MPC1Exception`.
  - Allowed widenings: `readDoubles` accepts ints; `readBinaries` accepts strings. A uint64 over `Long.MaxValue` is null for `readLongs`.
  - Nil is valid for every type, so an all-nil column reads as an all-null vector of the requested type.
- Decoding runs `OffsetUnpacker` on a `ReadCursor` over the block; strings and binaries copy payload bytes straight into the vector's data buffer, without building `String`s.
- Tests cover each type on the lineitem fixture (`time`, `l_dummy`), written files with nils, widenings, and mismatches read as nulls, on JVM, JS, and Native.

## Alternatives and Why Not?

- A new MPC1-only vector type: duplicates `ColumnVector` and forces the engine to convert again for Arrow or shared operators.
- Fail on mismatch: one stray value would abort a whole query over schema-on-read data.
- Return `(Array[Long], Array[Boolean])` tuples: loses the name and the string/binary offset layout that `BinaryColumn` already defines.
