# Arrow IPC writer for result delivery

- Status: Accepted
- Date: 2026-11-23

## Context

Servers built on Uni need to send query results to Python (pyarrow, pandas, polars), Node.js (Arrow JS), and ADBC clients. Those clients load Arrow record batches without decoding rows. Arrow metadata is FlatBuffers. The official Java implementation (arrow-java, flatbuffers-java) is JVM-only and pulls in Netty and an off-heap allocator, and `flatc` has no Scala target. Uni already writes Thrift (Parquet footers) and Avro (Iceberg manifests) by hand, and it already holds results as unboxed `ColumnBatch` vectors.

## Decision

- **Arrow only at end-user boundaries.** `wvlet.uni.arrow` delivers results to clients. Wvlet workers exchange data in the engine's own vector format, so the writer favors output that every reader handles without surprises over zero-copy tricks.
- **A hand-written FlatBuffers builder.** `format.FlatBufferBuilder` builds back to front like the official builders, writes every scalar field even when it equals the default, and gives each table its own vtable. `format.ArrowMessages` encodes the fixed message set: `Message`, `Schema`, `Field`, the type union, `RecordBatch`, and `Footer`, at metadata V5. Slot numbers and enum values follow the Arrow `.fbs` files.
- **The input is `ColumnBatch`, typed by `ParquetSchema`.** There is no separate Arrow vector or type builder. `ArrowSchema.fromParquet` is the public mapping, and an internal `ArrowPlan` tree pairs each Parquet field with its Arrow field and its leaf conversion. Batches are checked with the Parquet writer's `BatchShredder.check`, widened to `private[uni]` and given `allowInt96`, so both writers accept and reject the same batches.
- **Whole batches or nothing.** A schema that cannot be mapped (VARIANT, unknown logical types, DECIMAL precision over 76) fails at writer creation. The schema message goes out with the first batch, so a failed creation leaves the output empty. Each batch is checked and encoded in memory before any of its bytes are written. Value checks (int8/int16 ranges, INT96 length and range, decimal width) use the vector's own null flags, so rows under a null parent pass, as they do in the Parquet writer. `abort()` closes the output without the end-of-stream marker; stream readers accept a stream that ends between messages, so callers must also fail the transport.
- **No dictionary encoding.** Strings are always plain utf8, even when Parquet decoded them from a dictionary. pandas turns dictionary columns into `category` dtype, and the file format forbids replacing a dictionary between batches. Dictionaries come with the reader work, as an opt-in.
- **Compression as the spec defines it.** NONE by default. ZSTD uses uni's compressor. LZ4_FRAME uses `io.codec.Lz4Frame` (frame header with xxHash32, independent 4 MiB blocks, raw blocks when incompressible) around the LZ4 block compressor. A buffer that does not shrink is stored with uncompressed length `-1`, and empty buffers stay empty. Buffers are padded to 8 bytes, not 64.
- **Tests without a uni reader.** A test-only decoder (`ArrowTestReader`) is first checked against vendored `apache/arrow-testing` files and their integration JSON, then used to read the writer's output on all three platforms. The JVM CI job also runs `check_with_pyarrow.py` (through `uv`; pyarrow is CI-only), which compares pyarrow's values with the test decoder's and runs `validate(full=True)`.

## Consequences

- Results reach pyarrow, pandas, polars, and Arrow JS from JVM, Scala.js, and Scala Native with no new dependency.
- Values the Parquet writer accepts but Arrow rejects are not all caught. For example, an unscaled decimal over its precision only fails pyarrow's full validation.
- A Scala 3 issue shaped the encoder: an inline method that referenced a case class from another package failed at run time with `NoClassDefFoundError` for the package. Buffers are recorded through a plain method (`BatchEncoder.endBuffer`).
- Follow-ups: the Arrow reader (and `DictionaryBatch` with it), Flight SQL, and the C Data Interface.
