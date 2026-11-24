# MPC1 typed column decoders

## Context

`MPC1File.readColumn` decodes every row of a column into a MessagePack `Value`, which a query engine then unboxes again to build column vectors. MPC1 has no declared schema: a column's type is whatever its values are, and a column can mix types (a stray string in an integer column, or ints and floats together). Readers decide the type when they read the data (schema-on-read).

## Decision

- **Typed readers return Parquet `ColumnVector`s.** `readLongs`, `readDoubles`, `readBooleans`, `readStrings`, and `readBinaries` (and `MPC1Decoder.decode*` on a decompressed block) return `LongColumn`, `DoubleColumn`, `BooleanColumn`, and `BinaryColumn` of a synthesized optional `ParquetField.Primitive`, so code that consumes Parquet batches (including `ArrowWriter`) consumes MPC1 columns unchanged. The field's `columnIndex` is the MPC1 column position.
- **A value of another type is null, like nil.** An exception for one stray value would abort a whole query over schema-on-read data. `readLongs` over `1, "x", 3` gives `1, null, 3`. An unsigned 64-bit integer over `Long.MaxValue` is null for `readLongs`.
- **Only corrupt blocks throw** `MPC1Exception`, for example when the block holds the never-used byte 0xc1.
- **Two widenings:** `readDoubles` reads integers, including unsigned 64-bit values over `Long.MaxValue`, and `readBinaries` reads strings as UTF-8 bytes.
- **`columnType` probes format bytes** and skips payloads without building values. It returns `LONG`, `DOUBLE` (floats or floats + ints), `BOOLEAN`, `STRING`, `BINARY` (bins or bins + strings), `NIL` (no non-nil values, including no rows), or `ANY` (arrays, maps, ext values such as timestamps, other mixes). It stops at the first value that makes the column `ANY`. It names the reader that keeps every value.

## Alternatives

- Fail on a mismatched value: rejected, since one odd row would break query processing.
- An MPC1-only vector type: it would duplicate `ColumnVector`, and engines would convert again for Arrow or shared operators.
- `(Array[Long], Array[Boolean])` results: they lose the column name and the offset layout that `BinaryColumn` defines for strings.

## Consequences

- A caller that needs every value of a mixed column checks `columnType` and uses `readColumn` for `ANY`, since a typed read nulls the values of other types.
- Each `MPC1File` typed read decompresses the block again; callers that probe and then decode call `MPC1Decoder` on one `readColumnBlock` result.
- There is no timestamp (ext) reader yet; ext columns are `ANY`.
