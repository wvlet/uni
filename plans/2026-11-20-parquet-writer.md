# Pure-Scala Parquet Writer

## Goals

- Write Apache Parquet files from case classes and from `Map`/JSON records on the JVM, Scala.js (Node.js files, and in-memory bytes everywhere including browsers), and Scala Native, with no new dependencies.
- Written files read back with the uni-parquet reader and with other engines (DuckDB, Spark, parquet-java), with column statistics that `RowGroupFilter` can use.
- A Scala user can go from `Seq[Event]` to a `.parquet` file in one call, the way `Parquet.readAs[Event]` reads one today.

## Background

- uni-parquet (#1009) reads Parquet in pure Scala. Its ADR listed writing as not supported; the reader plan deferred it to its own plan once the reader was stable.
- Reusable pieces: `format/Enums` (physical types, encodings, codecs, logical types), `format/Metadata` (footer and page-header case classes), `ParquetSchema`/`ParquetField`, `BitPacking` (RLE levels), and the codec dispatch.
- Missing pieces: a Thrift compact *writer*, value and level encoders, column statistics, page and row-group assembly, and compressors. Only GZIP compression exists today (`wvlet.uni.io.Gzip`, platform zlib on JVM, Native, and Node.js); Snappy and LZ4 exist only as decoders.
- The reader hands rows to Weaver as MessagePack. Weaver already packs every case class, `Map[String, Any]`, and JSON value into MessagePack, but not always in the column's type: `Instant` is a timestamp extension, `BigDecimal` and `UUID` are strings, `Array[Byte]` inside a case class is an array of ints (only `Any` values pack as binary), and `Map[String, Any]` falls back to `toString` for types it does not know. JSON has no timestamps or decimals at all.
- Airframe's airframe-parquet offers `Parquet.newWriter[A](path)`, `Parquet.newRecordWriter(path, schema)`, and `Parquet.toParquetSchema(surface)` on top of parquet-java. Its users expect those shapes; Uni follows its own conventions where they differ.

## Design

Lane: Standard — a new public API and file format output in a published module.

**User flow**

- Case classes: `Parquet.write[Event](path, events)` for a whole collection, or `Parquet.newWriter[Event](path)` then `write(e)` per row and `close()` (usable with Uni's `Resource`).
- Records with an explicit schema: `Parquet.newRecordWriter(path, schema)`; `write` accepts `Map("id" -> 1, "name" -> "aina")`, a JSON object string, `Seq(3, "aina")` (values in schema order), or a JSON array string.
- In memory: the same writers target an in-memory buffer (`Parquet.newWriter[Event]()` then `toByteArray` after `close()`, or `Parquet.writeBytes[Event](events)`). This is the browser path.
- How users know it worked: the file reads back with `Parquet.readAs[Event]`, and `Parquet.open(path).schema` prints the derived schema.
- Failure: a value that does not fit its column (a string in an INT64 column, a missing required field, a decimal that overflows its precision) throws `ParquetException` naming the column. The row is validated before anything is appended, so the writer stays usable and the next row can be written.
- I/O errors: an exception from the output stream propagates; the writer is then closed without a footer, so the partial file is not a valid Parquet file (readers reject it rather than read part of it). A writer never closed also leaves no footer.

**Schema**

- `ParquetSchema.of[A]` derives a schema from `Surface.of[A]` (Airframe's `toParquetSchema`); `ParquetSchema.fromSurface(surface)` for the runtime form. Uni's Surface resolves nested case classes at compile time, so no `knownSurfaces` argument is needed; recursive types fail with a clear error.
- Type mapping: `Option` → OPTIONAL; `Seq`/`List`/`Array` → 3-level LIST; `Map[K, V]` → MAP (key REQUIRED); nested case class → group; `Instant` → INT64 TIMESTAMP(MICROS, UTC); `String`, enums → STRING; `Array[Byte]` → BYTE_ARRAY; `Int`/`Long`/`Short`/`Byte` → INT32/INT64 with INT(8/16) annotations; `Float`, `Double`, `Boolean`; `UUID` → FIXED(16) UUID; `BigDecimal` → DECIMAL.
- Non-Option fields are REQUIRED, matching Scala semantics.
- `BigDecimal` defaults to DECIMAL(38, 18) stored as FIXED_LEN_BYTE_ARRAY(16), the widest Spark-compatible decimal. `ParquetWriteOptions.withDecimal(precision, scale)` changes the default, `withDecimal("price", 10, 2)` overrides one column, and the schema builder sets it per column. A value whose digits do not fit is an error, never silently rounded.
- `Instant` is truncated to microseconds (nanoseconds are dropped); the docs say so, and tests compare truncated values.
- Schema builder for record writers: `ParquetSchema.builder("record").required("id", ParquetType.INT64).optional("name", ParquetType.STRING).list(...).group(...)`, computing paths, levels, leaf `columnIndex`, and `typeLength` (16 for DECIMAL(38) and UUID) the same way the footer decoder does.
- Column names are the Scala field names as written (`userName`), so they read back by the same names; Weaver's loose name matching keeps `user_name` files readable either way.

**Writing values**

- One value path for every input: case class / `Map` / JSON → MessagePack (Weaver, or the existing JSON-to-MessagePack converter) → a shredder that walks the MessagePack value against the `ParquetSchema` and emits values plus repetition/definition levels per leaf column (Dremel striping, the inverse of the reader's `Assembly`).
- The shredder converts per column, so every input reaches the same physical value: a timestamp extension, epoch millis, or ISO string → TIMESTAMP; a number or decimal string → DECIMAL; a UUID string → FIXED(16); binary or an int array → BYTE_ARRAY. Weaver itself is not changed. Tests cover these conversions for case-class, `Map`, and JSON input.
- The shredder validates a whole row before appending, which is what makes the "no half-written rows" rule above cheap.

**File layout**

- Data page v1 only; v2 is left for later (the reader already reads both).
- Encodings: PLAIN values, RLE/bit-packed levels, and dictionary encoding (RLE_DICTIONARY with a PLAIN dictionary page) on by default; a column falls back to PLAIN for the rest of the row group when its dictionary exceeds the limit (default 1 MiB).
- Limits with Parquet's usual defaults: page size 1 MiB, row group 128 MiB of encoded data, all `withXXX` options on `ParquetWriteOptions`. A row group buffers in memory (compressed pages) until it is flushed.
- Statistics per column chunk: min/max in the new `min_value`/`max_value` fields with the column's sort order (unsigned bytes for strings), plus null count; `column_orders` is set so other readers trust them. Binary min/max longer than 64 bytes are omitted rather than truncated.
- Footer and page headers via a new Thrift compact writer and a `MetadataEncoder` that mirrors `MetadataDecoder`. `created_by` names uni-parquet and its version.

**Compression**

- UNCOMPRESSED, SNAPPY (default, new pure-Scala compressor), LZ4_RAW (small pure-Scala compressor), and GZIP via `wvlet.uni.io.Gzip` on the JVM, Native, and Node.js (browsers get an error naming the codec).
- ZSTD and BROTLI compressors are out of scope; files using them still read.
- Codecs stay `private[parquet]`, as the reader ADR decided.

**Output**

- Files through `FileSystem.writeStream(IOPath)`, or an in-memory buffer on every platform. The writer is synchronous.
- On the JVM and Native, row groups stream to the file as they are flushed. On Scala.js, `writeStream` buffers the whole file and writes it on `close()` (Node.js files and the browser's in-memory file system alike), so peak memory there is the file size; the docs say so.

**Verification and docs**

- Tests: round trips through the reader for every type and nesting on all three platforms, dictionary fallback, row-group and page splits, statistics with `RowGroupFilter`, and each codec. A dev-time script checks that DuckDB reads the written files with the expected values; committed tests do not need DuckDB.
- Docs in this PR: a "Writing" section in `docs/text/parquet.md` (and remove its "Not supported: writing" line and the "other libraries for writing files" wording), `docs/guide/for-ai-agents.md`, the `uni` skill's `references/parquet.md` with a plugin version bump, the module line in `AGENTS.md`, and a writer ADR that supersedes the reader ADR's "writing is not supported" line.

## Alternatives and Why Not?

- **Give Weaver a binary codec for `Array[Byte]`.** Cleaner for Parquet, but it changes how every Uni user's `Array[Byte]` fields serialize to JSON and MessagePack; the shredder accepting both forms avoids that.
- **Read case-class fields through Surface getters instead of MessagePack.** Faster, but needs a second value path for `Map`/JSON and duplicates Weaver's type handling; the MessagePack path can be optimized later behind the same shredder, as the reader ADR says for reading.
- **Copy Airframe's API exactly (`toParquetSchema`, `knownSurfaces`, parquet-java's `ParquetWriter.Builder`).** Uni has no Hadoop configuration to mirror and Surface already resolves nested types; `ParquetSchema.of[A]` and `ParquetWriteOptions` follow Uni's config convention.
- **Default to DECIMAL as a string or to `scale` taken from the first value.** A file whose type depends on data is surprising; a fixed, documented default plus an override is predictable.
- **Write data page v2 by default.** Some engines still read v1 best; v1 is the most compatible default.
