# Arrow IPC writer for result delivery

## Goals

- A uni server can send query results to Python (pandas, polars), Node, and ADBC clients as Arrow IPC, so the client loads columns directly instead of decoding JSON or MessagePack rows.
- Works on JVM, Scala.js, and Scala Native with no new dependencies.
- Writes both Arrow formats: the stream format (HTTP responses, sockets) and the file format (`.arrow` files with a footer that readers can seek).

## Background

- uni already has unboxed column vectors: `ColumnBatch` / `ColumnVector` in `wvlet.uni.parquet`, typed by `ParquetField`. `Parquet.readBatches` and the Iceberg and dataset readers return them, and `ParquetBatchWriter` writes them. The writer takes these same vectors, so no new vector type is needed.
- Arrow metadata is FlatBuffers. uni already writes Thrift (Parquet footers) and Avro (Iceberg manifests) by hand. The Arrow message set is small and fixed, so a hand-written FlatBuffers encoder follows the same approach.
- Arrow sits only at end-user boundaries. Wvlet workers exchange data in the engine's own vector format. So the writer favours output that pyarrow, polars, and Arrow JS read without surprises over zero-copy tricks.
- The reader is a separate later item. Tests still have to check the bytes we write without a uni reader.

## Design

Lane: Standard. It adds a new public API package.

User flow:
- A server developer has `ColumnBatch`es, for example from `Parquet.open(path).readBatches()` or built by hand.
- They call `Arrow.newStreamWriter(out, schema)` (or `newFileWriter`), then `write(batch)` for each batch and `close()`. `Arrow.toStreamBytes(schema, batches)` covers small results in memory.
- They return the bytes as `application/vnd.apache.arrow.stream`. The client reads them with `pyarrow.ipc.open_stream(...)`, `polars.read_ipc_stream`, or `apache-arrow`'s `tableFromIPC`.
- A column the writer cannot map, such as VARIANT, fails at writer creation and names the column. The error comes before any bytes are written, so the server can still send another response.
- Each batch is checked against the schema (row counts, nulls in non-null fields, values that do not fit) before any of its bytes are written, as `ParquetBatchWriter` does. When `write` throws mid-stream, the server aborts the response and does not call `close()`, so the client sees a truncated stream instead of a stream that looks complete.

Decisions:
- New package `wvlet.uni.arrow` in the `uni` module. Its entry point is `Arrow`, and the options class is `ArrowWriteOptions` (`withCompression`). The `Message`/`Schema`/`Field`/`RecordBatch`/`Footer` tables and the FlatBuffers builder are `private[arrow]`.
- The schema comes from a `ParquetSchema`, mapped by a public `ArrowSchema.fromParquet`. Users can read the mapping, but there is no separate Arrow type builder in v1.
- Type mapping. Each line is the Parquet type, then the Arrow type it becomes:
  - BOOLEAN to bool (bit-packed). INT32/INT64 with INT(8/16/32/64, signed or not) to the matching int type, narrowed from uni's Int arrays. FLOAT and DOUBLE to float32 and float64.
  - STRING, ENUM, and JSON to utf8. BYTE_ARRAY to binary. FIXED_LEN_BYTE_ARRAY to fixed_size_binary(n). UUID to fixed_size_binary(16) tagged with the canonical `arrow.uuid` extension. FLOAT16 to float16.
  - DATE to date32. TIMESTAMP(unit) to timestamp(unit), with tz `"UTC"` when `isAdjustedToUTC` and no tz otherwise. TIME to time32(ms) or time64(us/ns). Legacy INT96 to timestamp(ns, UTC); a value outside the years 1677 to 2262 fails the batch and names the column.
  - DECIMAL to decimal128(p, s) up to precision 38, and decimal256 above that. Values are widened from the unscaled Int or Long, or byte-reversed and sign-extended from FIXED_LEN_BYTE_ARRAY or BYTE_ARRAY (Parquet stores them big-endian; Arrow is little-endian).
  - BSON, GEOMETRY, and GEOGRAPHY to binary (WKB for the geo types). UNKNOWN to null.
  - LIST to list (int32 offsets). MAP to map (non-null keys). Group to struct. VARIANT and unrecognized logical types are rejected and name the column.
  - Nullability follows the Parquet repetition. A validity bitmap is written only when a column has nulls.
- One `ColumnBatch` becomes one RecordBatch message. Batch size is the caller's choice, as with `ParquetBatchWriter`.
- Metadata version V5, little-endian, with every buffer padded to 8 bytes. 64-byte alignment is only a recommendation in the spec, and pyarrow reads 8-byte-aligned buffers without copying.
- Compression: NONE by default, with `ZSTD` and `LZ4_FRAME` as options. ZSTD uses uni's codec. LZ4_FRAME adds a small frame wrapper (header, blocks, end mark, and an xxHash32 header checksum) around uni's LZ4 block compressor. A buffer that does not shrink is stored uncompressed, as the spec allows (length `-1`).
- Dictionary encoding is not in v1, which narrows the item: it lists `DictionaryBatch` in the message set. The `DictionaryBatch` table moves to the reader item. Strings go out as plain utf8, even when Parquet decoded them from a dictionary page. pandas turns dictionary columns into `category` dtype, and the file format forbids replacing a dictionary between batches. So dictionaries would need an explicit opt-in, and that pairs better with the reader item.
- Tests:
  - A test-only minimal IPC decoder, checked first against a few vendored `apache/arrow-testing` integration files (stream and file formats for primitive, nested, decimal, and datetime data), then used to round-trip everything the writer produces on all three platforms.
  - A committed pyarrow check script (`uv run --with pyarrow`) that reads the sample files a JVM test writes. It runs locally for this PR, and in the JVM CI job as a `setup-uv` step plus the script after the sbt tests, so pyarrow stays a dev-only dependency.
- Docs: an "Arrow IPC" page next to the Parquet docs, and one row in `for-ai-agents.md`. The plugin skill digest gets the same entry, its snippets compile in `docSnippets`, and `plugins/uni/.claude-plugin/plugin.json` gets a version bump.

## Alternatives and Why Not?

- **Depend on arrow-java / flatbuffers-java.** JVM-only, heavy (Netty, off-heap allocator), and against uni's no-dependency rule.
- **Generate FlatBuffers code with `flatc`.** No Scala target, and generated Java does not run on JS or Native. The fixed message set is small enough to write by hand.
- **A separate Arrow vector type.** It would duplicate `ColumnVector`. Parquet, Iceberg, and hand-built batches already produce `ColumnVector`s.
- **Byte-for-byte golden comparison with pyarrow output.** FlatBuffers layout differs between encoders, so equal bytes are not a valid test. Decoding and comparing values is.
