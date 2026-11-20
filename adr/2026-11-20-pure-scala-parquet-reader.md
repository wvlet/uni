# A pure-Scala Parquet reader in uni-parquet

- Status: Accepted
- Date: 2026-11-20
- Plan: `plans/2026-11-20-parquet-reader.md`

## Context

Reading Parquet in Scala usually means parquet-java and its Hadoop dependencies, which are large
and run only on the JVM. Uni already reads JSON, MessagePack, and YAML on every platform; Parquet was the common columnar format it could not read on Scala.js or Scala Native.

## Decision

- **A separate cross-built module, `uni-parquet`**, depending on `uni`. The reader and its codecs
  are about 6k lines, too much to add to every `uni` user. It needs `uni` for `IOPath`, the HTTP
  client (range reads), `Rx`, and Weaver.
- **Decoding never does I/O.** All reads go through `ByteSource` (synchronous: files, bytes,
  HTTP on JVM/Native/Node) or `AsyncByteSource` (HTTP in browsers). A row group is planned
  (which byte ranges), fetched (nearby ranges merged), then decoded from byte arrays, so the
  synchronous and asynchronous APIs share every decoder.
- **Opening reads the last 64 KiB in one request.** Over HTTP that is a suffix `Range` request,
  which also reports the file size, so a small file opens in one round trip. A server that
  ignores `Range` and answers 200 with the whole file gets a clear error for column reads.
- **Every codec is written in Scala** (Snappy, LZ4 block and Hadoop framing, inflate, ZSTD from
  RFC 8878, Brotli from RFC 7932), so a file reads the same way on every platform. GZIP uses the
  platform's zlib through `wvlet.uni.io.Gzip` where one exists (JVM, Native, Node.js) and the
  Scala inflate in browsers. The codecs are `private[parquet]` until another module needs them
  (for example `Content-Encoding: zstd`); moving them to a shared package is a separate decision.
- **The Brotli static dictionary is embedded gzip-compressed and base64-encoded** in a generated
  source (`scripts/gen-brotli-dictionary.py`), and expanded with the Scala inflate on first use.
  Scala.js has no class-path resources, and the raw 122 KiB would be split across many string
  constants. The transform and context tables are generated from the reference sources
  (`scripts/gen-brotli-tables.py`) rather than typed by hand.
- **Thrift metadata is decoded without a schema** (`TStruct`: field id to value), with typed
  accessors on top. Unknown fields from newer writers are skipped for free, and optional fields of
  an unexpected type count as absent, since old writers reused some field ids.
- **Nested data is assembled into Arrow-like vectors** (`ListColumn` offsets, `StructColumn`,
  `MapColumn`, validity flags) from repetition and definition levels, following the spec's
  backward-compatibility rules for LIST and MAP. Rows are not assembled separately.
- **Rows reach Weaver as MessagePack.** A batch is packed into one buffer of maps (column name to
  value), then decoded with one unpacker. Weaver's loose name matching (`user_id` fills `userId`)
  makes decoding by name the right default; positional decoding would break when files differ in
  column order. Avoiding the MessagePack buffer with a custom `Unpacker` over the vectors is a
  possible optimization if profiles show the packing matters.
- **DATE and TIME become ISO-8601 strings in rows** because Weaver has no `LocalDate` codec;
  `ColumnVector.getValue` returns `LocalDate`/`LocalTime`, which exist on every platform.
- **`RowGroupFilter` only skips row groups.** It does not filter rows, and the API name says so.
  It understands only types whose statistics order is unambiguous (signed integers, floats
  without NaN bounds, booleans, UTF-8 strings with the new min/max fields, DATE, TIMESTAMP), and
  keeps row groups otherwise.

- **Row maps are a separate path from Weaver** (added with `plans/2026-11-20-parquet-read-rows.md`).
  `readRows`, `batch.row(i)`, and `readJson` build on `ColumnVector.getValue`, so values keep their
  Parquet types (`Instant`, `LocalDate`, `BigDecimal`, `UUID`) and keys keep schema order.
  `readAs[Map[String, Any]]` keeps going through MessagePack: `Weaver[A]` carries no type to detect
  the map case, so it returns Weaver's values (`Long`, ISO strings), and the docs point to
  `readRows`. `withBinaryAsString` is applied when a row group is decoded, so vectors, row maps, and
  case classes agree.
- **Column statistics expose min and max only where `RowGroupFilter` trusts the order**, and
  `None` otherwise, rather than raw bytes that users would have to decode and might misorder.

## Alternatives

- **Wrap parquet-java on the JVM and write a reader only for JS and Native.** Rejected: two
  implementations to keep consistent, and the JVM path would still pull in Hadoop.
- **Use platform ZSTD and Brotli libraries** (zstd-jni, libzstd, Node's zlib). Rejected for now:
  they differ per platform, browsers have none, and a pure decoder keeps behavior identical.
  A native fast path can be added later behind the same `Codecs` dispatch.
- **Generate the Thrift structs from `parquet.thrift`.** Rejected: a code generator in the build
  for about 20 structs costs more than it saves.

## Not supported

Writing (since added; see [`2026-11-20-pure-scala-parquet-writer.md`](2026-11-20-pure-scala-parquet-writer.md)),
modular encryption, LZO, the ALP encoding, and a single row with more than 2 GiB of values in one
column. Column chunks over 2 GiB are read in batches since
[`2026-11-20-parquet-batches-within-row-groups.md`](2026-11-20-parquet-batches-within-row-groups.md). Skipping
pages with the page index (ColumnIndex/OffsetIndex) and bloom filters are future work.
