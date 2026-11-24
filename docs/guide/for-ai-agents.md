---
title: Uni for AI Agents
description: How AI coding agents should pick and use Uni, the Scala 3 utility library for object wiring (Design), logging, JSON/MessagePack, HTTP, RPC, MCP servers, and testing on JVM, Scala.js, and Scala Native.
---

# Uni for AI Agents

**Uni is a single Scala 3 library (`org.wvlet.uni`) that covers object
wiring, logging, JSON/MessagePack serialization, reactive streams, HTTP
client/server, typed RPC, MCP servers, and unit testing, and the same code
runs on the JVM, Scala.js, and Scala Native.** It is the Scala 3 successor of
[Airframe](https://github.com/wvlet/airframe).

This page is written for AI coding agents (Claude Code, Codex, Cursor, Copilot,
and others) and for the people who configure them. It sums up when to choose
Uni, the canonical imports, and the mistakes agents most often make.

## Machine-readable docs

| URL | Contents |
|-----|----------|
| [`/uni/llms.txt`](https://wvlet.org/uni/llms.txt) | Index of every documentation page, with links to Markdown versions |
| [`/uni/llms-full.txt`](https://wvlet.org/uni/llms-full.txt) | The full documentation in one Markdown file |
| `/uni/<section>/llms.txt`, `/uni/<section>/llms-full.txt` | The same for one section, such as [`/uni/dom/llms-full.txt`](https://wvlet.org/uni/dom/llms-full.txt) for browser UIs or `/uni/http/llms-full.txt` for HTTP and RPC |
| `<page>.md` | Markdown version of a page, e.g. [`/uni/guide/for-ai-agents.md`](https://wvlet.org/uni/guide/for-ai-agents.md). A section index such as `/uni/guide/` is published as `/uni/guide.md`. |

## How do I install the Uni skill?

The `wvlet/uni` repository is a Claude Code plugin marketplace. Its `uni` plugin
adds an Agent Skill with the canonical imports, sbt setup, verified snippets,
and the mistakes listed on this page. Claude Code loads it whenever a task
touches Uni or asks for object wiring, logging, JSON, HTTP, RPC, MCP, or tests in Scala 3.

```text
/plugin marketplace add wvlet/uni
/plugin install uni@uni
```

On Claude Code 2.1.275 or later, `/plugin install uni --marketplace wvlet/uni`
does both in one step. Agents without a plugin system can read
[`llms-full.txt`](https://wvlet.org/uni/llms-full.txt) instead.

## When should I use Uni?

Choose Uni when a Scala 3 project needs one or more of the following and you want
few transitive dependencies:

- Object wiring with lifecycle management (`Design`), instead of a framework
- Structured logging with source locations (`LogSupport`)
- JSON or MessagePack codecs derived from case classes (`derives Weaver`)
- An HTTP client that works on the JVM, Node.js/browser, and native binaries
- An HTTP/RPC server on the JVM, Scala.js, and Scala Native, or a stdio MCP server for AI tools
- A test framework that runs on all three platforms (`UniTest`)

Do not mix in Airframe artifacts for the same features. Uni replaces them.

For scripts, teaching, Scala 2.13, or running subprocesses, the Scala Toolkit is
often the better fit, and it works alongside Uni. See the next section and
[Uni and the Scala Toolkit](./uni-vs-scala-toolkit).

## Should I use the Scala Toolkit or Uni?

Use the [Scala Toolkit](https://docs.scala-lang.org/toolkit/introduction.html)
(sttp, uPickle, os-lib, MUnit) for scripts, teaching, Scala 2.13, or Scala 3
before 3.9. Use Uni when the project also needs object wiring, logging, an HTTP
server, RPC, or MCP servers, or must run the same code on Scala.js and Scala
Native. The two coexist in one build. See [Uni and the Scala Toolkit](./uni-vs-scala-toolkit).

## How do I add Uni to a build?

```scala
// build.sbt
resolvers += "wvlet" at "https://repo.wvlet.org/maven2"
libraryDependencies ++= Seq(
  "org.wvlet.uni" %% "uni"       % "__UNI_VERSION__",
  "org.wvlet.uni" %% "uni-test"  % "__UNI_VERSION__" % Test
)
testFrameworks += new TestFramework("wvlet.uni.test.Framework")

// Optional, JVM only: serve Http.server on Netty instead of the built-in server
libraryDependencies += "org.wvlet.uni" %% "uni-netty" % "__UNI_VERSION__"

```

Uni requires Scala `__SCALA_VERSION__` or later. On sbt 2.x, use `%%` for all
platforms. On sbt 1.x, use `%%%` for Scala.js and Scala Native projects.

`__UNI_VERSION__` is the newest release. Every release is published to
`https://repo.wvlet.org/maven2`, so add that resolver; only some releases are
copied to Maven Central (newest there: `__UNI_CENTRAL_VERSION__`). A library
published to Maven Central should depend on a Central version and not add the
resolver. See [The wvlet Maven Repository](./installation#the-wvlet-maven-repository).

## What are the canonical imports?

| Task | Import | Entry point |
|------|--------|-------------|
| Object wiring | `wvlet.uni.design.Design` | `Design.newDesign.bindSingleton[A].build[A] { a => ... }` |
| Logging | `wvlet.uni.log.LogSupport` | `class A extends LogSupport` then `info(...)`, `debug(...)` |
| JSON / MessagePack codec | `wvlet.uni.weaver.Weaver` | `case class P(...) derives Weaver`, `Weaver.toJson(p)`, `Weaver.fromJson[P](json)`; `Instant`, `LocalDate` (ISO `"2026-11-21"`), `LocalTime` (ISO `"09:30:15"`), `UUID`, and `ULID` fields are built in on every platform |
| JSON tree | `wvlet.uni.json.JSON` | `JSON.parse(text)` |
| YAML config / frontmatter | `wvlet.uni.text.yaml.{YAML, Frontmatter}` | `YAML.fromYaml[P](text)`, `YAML.parse(text)`, `Frontmatter.parse(md).as[P]`. To edit in place and keep comments: `YAML.parseDocument(text).set(path, v).toYaml` (`YAML.parseDocuments` and `YAML.writeStream` for `---` streams), `Frontmatter.parse(md).update(json).toText`. See [YAML](../text/yaml) |
| Markdown to HTML | `wvlet.uni.text.markdown.{Markdown, MarkdownConfig}` | `Markdown.toHtml(md)` (raw HTML escaped by default), `Markdown.parse(md)` for the AST, `.frontmatterAs[P]` for a YAML header, and `.toMarkdown` to write an edited AST (including `frontmatter`) back losslessly. See [Markdown](../text/markdown) |
| Cron schedules | `wvlet.uni.text.cron.{Cron, UtcOffset}` | `Cron.parse("30 9 * * MON-FRI")`, `.next(instant)` / `.nextN(instant, n)` (UTC, or a fixed `UtcOffset`; resolve named zones yourself), `.matches(instant)`, `.describe` ("At 09:30, Monday through Friday"). 5 or 6 fields, macros like `@daily`, Vixie day-of-month/day-of-week semantics. See [Cron](../text/cron) |
| CSV / TSV | `wvlet.uni.text.csv.{CSV, TSV, CSVFormat, CSVReader}` | `CSV.read[P](text)` (columns by header name; an unquoted empty field is missing, so the field takes its default or `None`), `CSV.parse(text)` for lines of strings, `CSV.readFile[P](path)` streams (close it if you stop early), `CSV.reader[P](chunks)` with `CSVReader.decodeUTF8(bytes)` for S3 or HTTP bodies, `CSV.write(values)` / `CSV.writeFile(path, values)`. `TSV` uses backslash escapes and `\N`, `TSV.quoted` quotes like CSV. Dialects: `withDelimiter`, `noHeader`, `withColumns`, `withNullString`, `withRaggedRows`, `withFormulaEscape`. See [CSV and TSV](../text/csv) |
| Parquet files | `wvlet.uni.cloud.parquet.{Parquet, ParquetReadOptions, ParquetDatasetOptions, ParquetTableFormat, ParquetDatasetWriteOptions, ParquetWriteMode, ParquetSortKey, ParquetFilter, ParquetWriteOptions, ParquetSchema, ParquetType}` | `Parquet.readAs[A](path)` (columns matched to fields by name), `Parquet.readRows(path or bytes)` for `Map[String, Any]` rows with typed values (`Instant`, `LocalDate`, `LocalTime`, `BigDecimal`; prefer it over `readAs[Map[String, Any]]`), `Parquet.readJson(path)` for `JSONObject` rows, `file.columnStatistics` for per-column null counts, sizes, and min/max, `Parquet.open(path or bytes)` then `.readAs[A](ParquetReadOptions.default.withColumns(...))` or `.readBatches(ParquetReadOptions.default.withBatchSize(rows))` / `.readRowGroup(i)` for unboxed `ColumnVector`s (row groups with over 1 GiB in a column are split into batches automatically); `Parquet.openUrl(url)` reads over HTTP Range requests and `Parquet.openS3("s3://bucket/key", config)` from S3, R2, or MinIO `Parquet.openGcs("gs://bucket/key")` from Google Cloud Storage, and `Parquet.openAzure("az://container/key")` from Azure Blob (write with `Parquet.newWriter[A](store.newOutputStream(uri))`) (`openUrlAsync` / `openS3Async` / `openGcsAsync` / `openAzureAsync` + `readAllAsync[A]()` or `readAllRowsAsync()` in browsers); `.withFilter(ParquetFilter.col("ts") >= instant && ParquetFilter.col("address.city") === "Tokyo")` returns only matching rows (SQL nulls: comparisons with null are unknown, match nulls with `isNull`; NaN matches nothing; literals convert only without loss, otherwise `IllegalArgumentException` at read start) and skips row groups by statistics; `.withRowGroupFilter(f)` only skips row groups (`RowGroupFilter` is the deprecated old name). Writing: `Parquet.write(path, rows)`, `Parquet.writeBytes(rows)`, or `Parquet.newWriter[A](path or OutputStream)` then `write(a)` and `close()`, with the schema from `ParquetSchema.of[A]` (`LocalTime` fields are TIME(MICROS) columns); `Parquet.newRecordWriter(path, ParquetSchema.builder(...).required("id", ParquetType.INT64).build())` (TIME columns: `ParquetType.TIME_MICROS` or `ParquetType.time(unit)`) takes `Map`, JSON object, `Seq`, or JSON array rows; `Parquet.newBatchWriter(path or OutputStream, schema)` then `write(batch)` writes `ColumnBatch`es (the vectors `readBatches` returns, physical values) column by column, and `schema.select(names*)` / `batch.select(names*)` project a copy; `ParquetWriteOptions` sets compression (SNAPPY default; `withCompression(CompressionCodec.ZSTD, level)` for a ZSTD level), sizes, dictionary, `withParallelism(n)` (JVM threads for batch and dataset writes, up to 8 by default), and `withDecimal(precision, scale)`. Pure Scala on every platform, with Snappy, Gzip, ZSTD, LZ4, and Brotli decoding built in. See [Parquet](../cloud/parquet) |
| Parquet datasets (folders, prefixes, globs) | `wvlet.uni.cloud.parquet.{Parquet, ParquetReadOptions, ParquetDatasetOptions, ParquetTableFormat, ParquetDatasetWriteOptions, ParquetWriteMode, ParquetSortKey, ParquetFilter, ParquetWriteOptions, ParquetSchema, ParquetType}` | Datasets: `Parquet.dataset("s3://bucket/prefix/" or gs://, az://, a local dir, or a glob such as `s3://bucket/t/dt=2026-11-*/country=JP` with `*`, `?`, `[a-z]`, `{a,b}`, `**`)` or `Parquet.dataset(Seq(path1, path2))` for an explicit file list (or `Parquet.dataset(store, uri)` for a store you configured; the dataset closes only a store it created) lists the files, turns `key=value` folders into columns (typed as recorded in the `_common_metadata` that `Parquet.writeDataset` writes; otherwise STRING unless `ParquetDatasetOptions.default.withPartitionType("dt", ParquetType.DATE)`), skips files whose partition values fail the filter (`ds.selectFiles(opts)`), filters rows in the dataset schema with `withFilter`, matches columns by name across files (missing columns and struct fields read as null; INT32 widens to INT64 or DOUBLE and FLOAT to DOUBLE across files, also inside structs, lists, and maps), and reads with `ds.readAs[A](opts)` / `readRows` / `readBatches` (iterators) or `readAllAsync[A]()`; close it with `ds.close()`. Writing datasets (case-class `LocalDate` fields are DATE columns and can be partitions): `Parquet.writeDataset(root, rows, ParquetDatasetWriteOptions.default.withPartitionBy("dt", "country"))` (or `Parquet.newDatasetWriter[A](root, options)`, `newDatasetRecordWriter(root, schema, options)`, `newDatasetBatchWriter(root, schema, options)`, each also with a store first) writes `key=value` folders with the partition columns left out of the files (null and empty values as `__HIVE_DEFAULT_PARTITION__`), returns `Seq[WrittenFile]`, takes `withMode(ParquetWriteMode.ErrorIfExists` (default) `/ Append / Overwrite / OverwritePartitions)`, `withMaxRowsPerFile`, `withMaxFileSize`, `withMaxOpenFiles`, `withMaxBufferedBytes`, `withSortBy("user", "ts")` or `withSortBy(ParquetSortKey.desc("ts"))` (sorts each partition's rows before writing, like Spark's `sortWithinPartitions`; row groups record `sorting_columns`), and `withWriteOptions`, publishes each file only when finished, adds `_common_metadata` (partition types) and `_SUCCESS` at the root when done (`withWriteCommonMetadata(false)` / `withWriteSuccessMarker(false)`), and deletes every file of the write on failure or `abort()`; `Parquet.writeDatasetAsync(root, rows, options)` / `newDatasetWriterAsync[A](store, root, options)` (also record and batch variants) write to object stores with async calls only, for browsers (files built in memory, 128 MiB cap by default). See [Parquet](../cloud/parquet#reading-a-dataset-of-files) |
| Delta Lake tables (read) | `wvlet.uni.cloud.parquet.{Parquet, ParquetDatasetOptions, ParquetTableFormat}` | A root with `_delta_log/` reads as a Delta Lake table (live files only, partitions typed by the table schema, `withVersion(n)` for time travel, `withTableFormat(ParquetTableFormat.Files)` to read raw files; deletion vectors and column mapping fail by name). Delta writes fail by name. See [Delta Lake Tables](../cloud/delta) |
| Iceberg tables | `wvlet.uni.cloud.iceberg.{Iceberg, IcebergTableChange, IcebergCompactionOptions}`, `wvlet.uni.cloud.parquet.{Parquet, ParquetDatasetWriteOptions, ParquetTableFormat, ParquetWriteMode, ParquetFilter}` | A root with `metadata/` holding `.metadata.json` files (or a `.metadata.json` path from the catalog; gzip-compressed `.gz.metadata.json` and `.metadata.json.gz` files too) reads as an Apache Iceberg table (current snapshot's files, `withSnapshotId(id)` for time travel, current schema for the current snapshot, columns matched by field ID so renamed columns read and dropped ones are hidden, identity partitions as partition columns, `ds.tableProperties`; format v1 to v3, with v3 `timestamp_ns` columns and `initial-default` values for columns older files lack; position deletes, deletion vectors, and equality deletes are applied before row filters; encrypted tables fail by name, since they need a KMS; `variant` columns read as Scala values (`Map`/`Seq`/primitives, shredded or not, also in plain Parquet files) and `geometry`/`geography` as WKB `Array[Byte]`). `ParquetDatasetWriteOptions.default.withTableFormat(ParquetTableFormat.Iceberg).withPartitionBy("dt")` writes an Iceberg table (Hadoop table: `metadata/vN.metadata.json` + `version-hint.text`, format v2, or v3 with row lineage and nanosecond timestamps via `withTableProperties(Map("format-version" -> "3"))`, which also upgrades a v2 table; data under `data/` with partition columns and field IDs kept, manifests with column stats): `ErrorIfExists` creates it, `withMode(ParquetWriteMode.Append)` commits a snapshot atomically via `ObjectStore.putIfAbsent` (retries a lost race), and the default `Auto` format appends to an Iceberg root it finds; `withMode(ParquetWriteMode.Overwrite)` commits a replacing snapshot (a new schema when the rows don't fit), and with `.withReplacedFiles(paths)` removes only those data files (copy-on-write update/delete: read them with `ds.withFiles(files)`, write the kept rows); `Iceberg.deleteRows(root, filter)` deletes the matching rows merge-on-read and returns the count (one `delete` snapshot of deletion vectors in v3, merged with earlier deletes, or position-delete files in v2; data files kept; nothing committed when no row matches); `Iceberg.updateRows(root, filter)(row => row.updated("k", v))` (or `Iceberg.updateRows[A](root, filter)(a => a.copy(...))` with a `Weaver[A]`) updates the matching `Map[String, Any]` (or `A`) rows merge-on-read and returns the count (one `overwrite` snapshot: new data files with the new rows plus deletion vectors or position deletes for the old ones; v3 rows keep their `_row_id`; a lost race keeps the written files when the other commit left the touched files, schema, and spec alone, and otherwise reads the rows and calls the function again, so it must be free of side effects); `Iceberg.compactTable(root, IcebergCompactionOptions.default)` rewrites data files with their deletes applied, merges small files per spec and partition (`withTargetFileSize`, `withMinInputFiles`, `noFileMerging()`, `withMinDeleteRatio`, `withFilter`), and removes deletion vectors, position deletes, and equality deletes that no remaining file needs, in one `replace` snapshot (v3 row lineage kept; returns `IcebergCompaction` counts); `withTableProperties(map)`; `Iceberg.alterTable(root, IcebergTableChange.AddColumn(col) / DropColumn / RenameColumn / WidenColumn / MakeOptional / SetProperty / RemoveProperty)` commits metadata-only changes, `Iceberg.expireSnapshots(root, retainLast = n)` expires older snapshots and deletes the files only they reference, `Iceberg.renameTable(from, to)` moves a local table and rewrites its metadata and position-delete files for the new location (current snapshot only), and `Iceberg.dropTable(root)` deletes a table; identity partitions on string/int/long/date only; `OverwritePartitions`, other transforms, catalog-owned tables (`NNNNN-uuid.metadata.json`), and Delta writes fail by name. Reads and writes also run in browsers through `Parquet.datasetAsync` and `writeDatasetAsync`; the `Iceberg` operations are blocking (JVM, Scala Native, Node.js). See [Iceberg Tables](../cloud/iceberg) |
| Parquet from TypeScript/JavaScript (npm `@wvlet/uni`) | `import { openParquet, writeParquet } from "@wvlet/uni/parquet"` | `await openParquet(bytes or Blob or path or url)` then `await file.readRows({ columns })` (plain objects; INT64 as `bigint`, timestamps as `Date` or `{ timestamps: "bigint" }`) or `for await (const b of file.batches())` with typed-array columns; `writeParquet(rows, [{ name, type }], { compression, compressionLevel })` returns a `Uint8Array`. Types ship in the package. See [Parquet](../cloud/parquet#from-typescript) |
| Parquet datasets from TypeScript/JavaScript | `import { openDataset, writeDataset, newDatasetWriter } from "@wvlet/uni/parquet"` | `await openDataset(root or glob or [files], { partitionTypes, storage })` then `await ds.readRows({ filter: [["dt", ">=", "2026-11-15"]], columns })`, `for await (const b of ds.batches(...))`, or `await ds.files({ filter })`; `await writeDataset(root, rows, schema, { partitionBy, mode, storage })`. `storage` is per call (`s3`, `gcs`, `azure` sections with explicit credentials, required in browsers). See [Parquet](../cloud/parquet#datasets-from-typescript) |
| Arrow IPC (result delivery) | `wvlet.uni.cloud.arrow.{Arrow, ArrowWriteOptions, ArrowCompression, ArrowSchema}` | `Arrow.newStreamWriter(out, parquetSchema)` then `write(batch)` per `ColumnBatch` and `close()` (end-of-stream marker; `abort()` after a failed write), or `Arrow.toStreamBytes(schema, batches)` for a response body (`Arrow.StreamMediaType`); `newFileWriter` / `toFileBytes` for `.arrow` files with a footer. One batch is one record batch, typed by `ArrowSchema.fromParquet(schema)` (UTC timestamps as `timestamp(unit, "UTC")`, DECIMAL as decimal128/256, UUID as `arrow.uuid`, strings as plain utf8, no dictionaries); VARIANT throws `ArrowException` at creation. `ArrowWriteOptions.default.withCompression(ArrowCompression.ZSTD or LZ4_FRAME)`. Writer only; read with pyarrow, polars, or Arrow JS. See [Arrow IPC](../cloud/arrow) |
| LLM vendor APIs | `wvlet.uni.ai.anthropic.*`, `wvlet.uni.ai.openai.*`, `wvlet.uni.ai.gemini.*`, `wvlet.uni.ai.{LlmRequest, LlmClient, JsonCodec}` | Typed requests, responses, and stream events, with each vendor's exact JSON (`JsonCodec[A].toJson` / `fromJson`; unknown blocks decode to `Unknown(raw)`). Clients: `AnthropicClient(AnthropicConfig.fromEnv).messages(req)` / `.stream(req)` (also `AnthropicHost.Vertex(...)`, `AnthropicHost.Bedrock(region)` for Claude on Bedrock via the Anthropic API), `OpenAIClient(OpenAIConfig.fromEnv).responses(req)` / `.streamResponses(req)` / `.chatCompletions(req)` / `.streamChatCompletions(req)` (any OpenAI-compatible `baseUrl`; `OpenAIConfig.bedrock(region)` for OpenAI models on Bedrock), `GeminiClient(GeminiConfig.fromEnv).generateContent(model, req)` / `.streamGenerateContent(model, req)` (`GeminiHost.Vertex(project, region)`). Convert vendors through the neutral model: `AnthropicConverter.toLlmRequest(req)`, `ResponsesConverter.fromLlmRequest(llm)`, and `XxxConverter.StreamDecoder()` / `StreamEncoder()` per stream; every client is an `LlmClient` with `generate(llmRequest)` and `stream(llmRequest)`. Clients don't retry (billable). See [LLM APIs](../ai/) |
| S3, S3-compatible, Google Cloud Storage, and Azure Blob objects | `wvlet.uni.cloud.storage.{ObjectStore, ObjectUri, S3Config, GcsConfig, AzureConfig, StorageException}`, `wvlet.uni.cloud.{AwsCredentialsProvider, GoogleCredentialsProvider, AzureCredentialsProvider, CloudException}` | `ObjectStore.s3()` (AWS credential chain in Java SDK v2 order: env, web identity (EKS IRSA), `~/.aws` profile (static keys, `role_arn` + `source_profile`, IAM Identity Center SSO, `credential_process`), ECS / EKS Pod Identity, EC2 IMDSv2) or `ObjectStore.s3(S3Config.r2(accountId) / S3Config.gcs (HMAC keys) / S3Config.minio(url).withCredentials(key, secret))`; `ObjectStore.gcs()` for `gs://` with Google OAuth (Application Default Credentials: `GOOGLE_APPLICATION_CREDENTIALS`, gcloud ADC file, metadata server) or `ObjectStore.gcs(GcsConfig().withCredentials(GoogleCredentialsProvider.fromFile(path)) / .withAccessToken(token))`; `ObjectStore.azure()` for `az://container/blob` (`AZURE_STORAGE_CONNECTION_STRING`, or `AZURE_STORAGE_ACCOUNT` with key, SAS, service principal, workload identity, or managed identity) or `ObjectStore.azure(AzureConfig().withSharedKey(account, key) / .withSasToken(token) / AzureConfig.fromConnectionString(s) / AzureConfig.azurite())`; then `get(uri)`, `getRange(uri, offset, length)`, `head(uri)` (`None` if missing), `put(uri, bytes)`, `putIfAbsent(uri, bytes)` (`Some(version)` when it created the object; `None` if one exists: S3 `If-None-Match`, GCS `ifGenerationMatch=0`, Azure `If-None-Match`), `putIfMatch(uri, bytes, version)` (compare-and-swap: `head(uri)` first for `ObjectInfo.version`, an opaque token (ETag on S3/Azure, generation on GCS), then `get`; `Some(newVersion)` when it replaced the object, `None` when it changed or is missing; other failures throw; refused on the `S3Config.gcs` preset, use `ObjectStore.gcs()`), `list(uri, recursive)`, `delete(uri)`, `newOutputStream(uri)` (S3 multipart, GCS resumable, or Azure block upload; `close()` publishes, `abort()` discards), `presign(uri, method, expiresSeconds)` (GCS V4 signed URLs need a service account; Azure gives a service or user delegation SAS URL); `...Async` variants return `Rx` and run in browsers. Errors are `StorageException` with codes such as `NoSuchKey` and `AccessDenied` (Azure keeps its own, such as `BlobNotFound`; `isNotFound` works on every store); credential failures (`NoCredentials`) are its parent `wvlet.uni.cloud.CloudException`. Pure-Scala SigV4, OAuth, RSA, and Azure Shared Key/SAS; no AWS, Google, or Azure SDK. See [Object Storage](../cloud/storage) |
| Reactive streams | `wvlet.uni.rx.Rx` | `Rx.variable(v)`, `Rx.single(v)`, `.map`, `.flatMap`, `.scan(init)(f)` (running value), `itemsRx.split(_.id)` (one stable `Rx` per key, completed when the key leaves), `.onCancel(f)` (cleanup when a subscriber cancels early), `.sampleFrame` (last value per animation frame); events are `OnNext(v)`, `OnError(e)` (the stream goes on), and one end, `OnCompletion` (a case object) or `OnFailure(e)`, both `OnEnd`, so `RxRunner.run(rx) { case OnNext(v) => ...; case OnCompletion => ...; case OnFailure(e) => ... }` (see [Stream Events](../rx/events)); tests drive time with `given time: ManualTimeProvider = RxTimeProvider.manual()` and `time.advance(n, unit)` (frames: `RxFrameProvider.manual()`, `advanceFrame()`), and find unclosed subscriptions with `val t = RxTracker(); t.trace { ... }; t.activeSubscriptions` or uni-test's `assertNoRxLeaks { ... }` |
| Persisted settings | `wvlet.uni.util.settings.SettingStore` | `SettingStore.of[AppSettings].withAppName("my-app").withVersion(n).load()`, then `update(...)`; saved to `~/.config/my-app/` (localStorage in a browser). See [SettingStore](../core/settings) |
| HTTP client | `wvlet.uni.http.{Http, Request}` | `Http.client.newSyncClient.send(Request.get(url))` |
| HTTP server (all platforms) | `wvlet.uni.http.Http` | `Http.server.withPort(8080).withRxHandler(handler).start()`, with `uni` alone: on the JVM a built-in server (JDK 24+), or Netty when `uni-netty` is on the classpath; Node.js on Scala.js; native sockets on Scala Native. Backend-only options: `.withBackend(Netty.options.withHandlerExecutorThreads(8))` (`Native.options` on Scala Native). See [REST Server](../http/server#cross-platform-servers) |
| HTTP server (JVM only) | `wvlet.uni.http.netty.Netty` | `Netty.server.withPort(8080).withHandlerExecutorThreads(8).withRxHandler(handler).start()`: every Netty setting in one config, for projects that never target Scala.js or Native. `NettyServer` was removed; use `Netty.server` (JVM only) or `Http.server`. See [REST Server](../http/server#jvm-only-apps-netty-server) |
| Tracing and metrics (all platforms) | `wvlet.uni.telemetry.Telemetry`, `wvlet.uni.telemetry.otlp.OtlpConfig`, `wvlet.uni.http.TelemetryFilter` | `val t = Telemetry().withServiceName("orders").withOtlp(OtlpConfig().withEndpoint("http://localhost:4318"))` (Datadog Agent or OTel Collector), then `Http.server.withFilter(TelemetryFilter(t))` and `Http.client.withTelemetry(t)`: W3C `traceparent` spans named by RPC method or route, plus `http.server/client.request.duration` histograms; `t.close()` at shutdown. `MCP.server.withTelemetry(t)` / `MCP.client.withTelemetry(t)` add MCP spans (`tools/call <tool>`, stdio too, `traceparent` in `params._meta`); an LLM client given `Http.client.noRetry.withTelemetry(t).newAsyncClient` adds GenAI spans (`chat <model>`, token usage, no prompts) and `gen_ai.client.inference.*` metrics. See [Telemetry](../http/telemetry) |
| UI usage telemetry (Scala.js) | `wvlet.uni.ui.telemetry.{UiTelemetry, UiTelemetrySink, UiTelemetryConfig}`, server: `wvlet.uni.http.UiTelemetryRelay` | `UiTelemetry.install(UiTelemetrySink.relay())` in the browser and `Http.server.withFilter(UiTelemetryRelay(telemetry))` on the server: `ui.press`, `ui.change`, `ui.open`/`ui.close`, `ui.view`, `command.run` by component ref, no values by default; `UiTelemetrySink.datadogRum()` for Datadog RUM. See [UI Telemetry](../dom/telemetry) |
| Static files (JVM, Node.js, Native) | `wvlet.uni.http.StaticFiles` | `Http.server.withFilter(StaticFiles("dist").withSpaFallback(true)).withRxHandler(api)`: serves `GET` for files under `dist` and passes other requests on. It refuses traversal, symlinks, and dotfiles by default. See [REST Server](../http/server#static-files) |
| CORS (JVM, Node.js, Native) | `wvlet.uni.http.{CorsFilter, CorsPolicy}` | `Http.server.withFilter(CorsFilter(CorsPolicy().withAllowedOrigins("https://app.example.com"))).withRxHandler(api)`: answers preflights and adds `Access-Control-*` headers for allowed origins. No origin is allowed by default. See [REST Server](../http/server#cors) |
| REST router (all platforms) | `wvlet.uni.http.router.{Router, RouterHandler}` | `Http.server.withRxHandler(RouterHandler(Router.of[Controller]))`. Moved from `wvlet.uni.http.netty`, where a deprecated alias remains. See [REST Server](../http/server) |
| Typed RPC | `wvlet.uni.http.rpc.{RPCRouter, RPCHandler, RPCClient}` | Server: `Http.server.withRxHandler(RPCHandler(RPCRouter.of[Api](impl)))` on every platform (`RPCHandler` moved from `wvlet.uni.http.netty`); client: `RPCClient.of[Api].newSyncClient(http).call[A]("method", args*)` (or `newAsyncClient` for `Rx[A]`); `newAsyncClient(http).stream[A]("method", args*)` streams every value of an `Rx` result over SSE or Electron IPC; cancelling it stops the server stream; declare such methods as returning `RxStream[A]` (`wvlet.uni.rx.RxStream`, built with `RxStream(rx)`) so sbt-uni's generated `AsyncClient` streams them. See [RPC](../http/rpc) |
| Server-sent events | `wvlet.uni.http.{Response, ServerSentEvent}` | Server: `Response.eventStream(rx)`; client: `Http.client.newAsyncClient.sendSSE(request)`. See [SSE](../http/sse) |
| WebSocket | `wvlet.uni.http.{WebSocketHandler, WebSocketContext}` | Server: `.withWebSocketRoute(path) { req => handler }`; client: `Http.webSocketClient.connect(url, handler)`, or `connect(request, handler)` to send upgrade headers (not in browsers); `onClose(ctx, statusCode, reason)` reports the close status; `.withWebSocketRoute(WebSocketRoute(path, factory, filter).withCloseOnRejection())` answers a filter rejection as close 4000 + status (4401); `WebSocketRelay(upstreamRequest).withStatsReporter(f).newHandler()` relays a session to an upstream WebSocket. See [WebSocket](../http/websocket) |
| OpenAPI | `wvlet.uni.http.openapi.{OpenAPI, OpenAPIConfig}` | `OpenAPI.of(Router.of[A], OpenAPIConfig()).toYAML`. See [OpenAPI](../http/openapi) |
| Browser UI helpers (Scala.js) | `wvlet.uni.dom.all.*` | `itemsRx.each(_.id)(item => li(...))` renders a list item by item, keeping unchanged rows' DOM nodes (prefer it over `itemsRx.map(_.map(...))`, which rebuilds the whole list), `itemsRx.eachRx(_.id)((first, current) => li(current.map(_.text)))` (also keeps a row's nodes when its value changes, passing later values through `current`), `Keyboard.onEnter(() => send())` (skips Shift+Enter and IME-confirm Enter), `Keyboard.scoped("escape" -> (() => close()))`, `PaneResize(width, SizeRange(min, default, max), ResizeDirection.Right).bind` on a handle, `PointerDrag(onMove = (dx, dy) => ..., onCancel = Some(_ => restore()))` (captures the pointer; `onCancel` runs on Escape or `pointercancel`). See [Browser Integrations](../dom/rxelement#browser-integrations) |
| UI components (Scala.js, `uni-ui`) | `wvlet.uni.ui.*` | Each component takes one parameter list; name its functions, triggers, and multi-value arguments, and pass several values as a `Seq` (`content` also takes one element): `Button("Send").primary.withDisabled(busy).onClick(() => send())`, `IconButton(label, icon)`, `Input(textVar)`, `val tabs = TabList(selectedVar, label = "Views", tabs = Seq(Tab(v, "Label"), ...))` with `TabPanel(tabs, render = v => ...)`, `FoldablePane(open, width = width, edge = Edge.Right, content = element).withSplitter(Splitter(width, SizeRange(min, default, max), Edge.Right, "Resize"))`, `ColorScheme.bind(schemeVar)`. Layout: `Stack(content*)` or `Stack.row(content*)` with `.withGap(px)`, `.withAlign(Alignment.Center)`, `.withJustify(Justify.Between)`; `Grid(content*).withMinColumnWidth(220)` (responsive) or `.withColumns(3)`, with `GridItem(child).withColumnSpan(2)`; `Separator()`. Overlays: `Popover(openVar, trigger = element, content = element)`, `Tooltip("Label", trigger = element)`, `Menu(openVar, trigger = element, entries = Seq(MenuItem("Copy").onSelect(f), MenuSeparator))`, `ContextMenu(target, entries = Seq(...))`, `Dialog(openVar, content = element).withTitle(t).withActions(buttons*)`, `AlertDialog(openVar, title = "...").onConfirm(f).danger` (focus goes to the next element when the action removes the opener; `.withInline(true)` on `Dialog`, `AlertDialog`, or `Popover` renders it in place, blocking only its `position: relative` panel), `Select(selectedVar, options = Seq(...), optionLabel = _.label)`, `val toasts = Toaster()` then `toasts.success(msg)`. Collections render only the rows in view (fixed row height): `VirtualList(itemsRx, rowHeight = 24, key = _.id, render = a => node)`, `Tree(rootsRx, children = _.children, key = _.path, render = a => node).withExpanded(setVar)`, `DataTable(rowsRx, key = _.id, columns = Seq(Column[Row]("name", "Name", cell = r => span(r.name)).sortBy(_.name))).withSort(sortVar)`; lists, trees, and tables select one row with `.withSelected(optionVar)` or several with `.withMultiSelect(setVar)`, and lists and tables move rows by drag or Alt+ArrowUp/Down with `.withReorder(itemsVar)` (`onChange` key `order`), `CommandPalette(openVar, commandsRx)` with `Command(id, label).withGroup(g).onRun(f)` (bind ⌘K yourself with `Keyboard.bindAll`); a `Dock(layoutVar, label = "Dock", panels = Seq(DockPanel(id, title = "...", content = element)))` of tab groups and splits the user rearranges, whose `DockLayout` (`DockNode.row/column/group`, and `floating = Seq(DockFloat.of(DockBox(x, y, w, h), ids*))` for groups over the grid) saves with `Storage.local(key, default)`, `.withDetach(true)` lets a panel open in a window of its own (`dock.detach(id)`), `dock.maximize(id)` / `dock.restore()` let a grid group fill the dock, and `dock.focusGroup(i)` (0-based) or `dock.focusPanel(id)` moves keyboard focus; sides and alignment are `wvlet.uni.ui.base.{Side, Align}`. Load `uni-ui.css` once: `enablePlugins(UniPlugin)` (sbt-uni) copies it from the jar to `uniUiCssFile` on every compile, for an `@import` in the app stylesheet; restyle with `--uni-*` CSS variables. Every component takes `.onChange(d => ...)` to see, and `d.cancel()` to refuse, the changes it makes (`d.reason`, `d.source`: `Pointer`/`Keyboard`/`Programmatic`/`Agent`); callbacks read `ChangeCause.current`; parts carry `data-slot` (`menu-item`, `dialog-title`). `UiAgent.install()` (`wvlet.uni.ui.agent`) lets an AI agent `snapshot()` the components and `act(ref, action, value)` on them as `ChangeSource.Agent` (an open dialog makes the page behind it `inert`, so the agent reaches only the dialog), with `UiAgent.hidden`/`secret`/`confirm(msg)` marks and an `AgentPolicy` (name list, tree, and table rows for it with `.withRowLabel(r => text)`; otherwise lists and trees use the typeahead text, then every component the key; `.withAgentConfirm(r => Option(msg))` asks the human before an agent selects or drops a guarded row; a field with `.withReadOnly(true)` refuses agent edits; toasts offer `dismiss`, and an open `CommandPalette` offers `run`, so give agents an opener for it); `agent.api` (`wvlet.uni.ui.agent.UiAgentApi`) serves as MCP tools or WebMCP (`agent.installWebMcp()`). `UiHistory.record()` records changes by source; `history.undoSource(ChangeSource.Agent)` reverts the agent's (Dock layouts and splitter sizes included) without overwriting later edits, and `history.stage(label)` collects agent suggestions to `accept()` or `revert()` together. See [Components](../dom/ui), [Changes & Parts](../dom/changes), and [Agents](../dom/agents) |
| UI behaviors (Scala.js, `uni-ui`) | `wvlet.uni.ui.base.*` | Interaction logic for building your own widgets: `StickToBottom()` (`stick.binding` on a scroller, `stick.atBottom`, `stick.scrollToBottom()`), `InputHistory()` (`history.keys(draftVar)` on a textarea, `history.add(text)`), `Typeahead().onKey(e, labels, active)`, `Selection(selectedSetVar).press(key, order, e)` (Shift range, Ctrl/Cmd toggle), `Reorder(orderVar)` or `Reorder.by(itemsVar)(_.id)` (`reorder.item(k)` on each item: drag, or Alt+ArrowUp/Down; `Reorder.grip` or `Reorder.handle` for a grip, the only place a touch drag starts; `.withSelection(selectedSetVar)` moves the selected items together), `FocusRecovery.within("li")`, `Announce.polite(msg)`, `RovingFocus.nextIndex` (`rtl = TextDirection.mirrors(e)` mirrors Left/Right; uni-ui widgets do this on their own), `ListNavigation`, `FocusTrap`, `Dismiss`, `Anchored`. uni-dom's `OnMount(el => cancelable)` binds any behavior to an element. See [Behaviors](../dom/behaviors) |
| Terminal emulator | `wvlet.uni.cli.term.{TerminalEmulator, TerminalConfig}` | `TerminalEmulator(TerminalConfig(cols = 80, rows = 24))`, `term.write(output)`, then `term.screen.text` or `term.screen.lineText(row)`: an xterm-compatible emulator on JVM, Scala.js, and Native, for checking what a TUI draws. `PtyProcess.connect(term, pty)` wires a shell; on Scala.js, `NodePty.spawn(nodePty, shell, args, options)` takes the app's `node-pty` module. On the JVM (Java 22+) and Native, `PosixPty.spawn(file, args, options)` starts the program in a real PTY on Linux and macOS (check `PosixPty.isSupported`). With shell integration (OSC 133), `term.commands` lists each `TerminalCommand(line, command, output, exitCode)` and `term.onCommandFinished` reports one as it ends |
| Terminal pane (Scala.js) | `wvlet.uni.dom.{TerminalView, TerminalViewConfig, TerminalTheme}` | `TerminalView(term)` draws the emulator in the DOM, with Uni's `TerminalTheme.uniDark` by default; `view.setTheme(TerminalTheme.uniLight)` switches (`TerminalTheme.presets` lists all). OSC 8 links with `http`, `https`, or `mailto` (`TerminalLink.isAllowed`) and plain `http`/`https` URLs in the output (`TerminalLink.findUrls`; off with `TerminalViewConfig().noUrlDetection()`) open on Cmd+click (Ctrl elsewhere); in Electron pass `view.onLinkClick` a bridge to `shell.openExternal` in the main process. OSC 133 prompts get a green or red marker; `view.scrollToPreviousPrompt()` / `scrollToNextPrompt()` (Cmd+Up/Down, Ctrl+Shift+Up/Down elsewhere) jump between them |
| Electron IPC (Scala.js) | `wvlet.uni.electron.{ElectronRPCServer, ElectronRenderer, ElectronMain}` | Renderer→main: `ElectronRPCServer.serve(ipcMain, router)` and `ElectronRenderer.install()`. Main→renderer: `ElectronRenderer.serve(router)` in the page (preload adds `serve`), `ElectronMain.newAsyncClient(ipcMain, webContents)` in main. See [Desktop Apps (Electron)](../http/electron) |
| MCP server / client | `wvlet.uni.ai.mcp.MCP` | `MCP.server.withTools[T](impl)...`, `MCP.client.connect(url)`; see [Build an MCP Server](../mcp/) |
| Off-heap memory | `wvlet.uni.canvas.{Canvas, OffHeapMemoryAllocator}` | `val c = Canvas.newOffHeapCanvas(size)` (`Long` size, beyond 2 GB), `c.writeInt(offset, v)`, `c.readLongBigEndian(offset)`, `c.slice(offset, length)`, then `c.close()` to free it now. Little-endian with `*BigEndian` variants; bounds-checked. JVM needs Java 22+ (FFM; check `Canvas.isOffHeapSupported`); Scala Native frees memory only on `close()`. Heap canvases: `Canvas.newCanvas(n)`, `Canvas.wrap(bytes)`. See [Canvas](../core/canvas) |
| Concurrent blocking code (JVM, Native) | `wvlet.uni.control.Fiber` | `Fiber { fetch(x) }.join()`, `Fiber.parMap(items, maxConcurrency = 16)(f)`, `Fiber.par(a, b)`, `Fiber.race(a, b)`, `Fiber.scope { s => s.fork(...) }` (a failure cancels the other forks). Runs on `RxScheduler.blocking` (virtual threads on JDK 24+); `cancel()` interrupts, `Fiber.onCancel(hook)` for calls an interrupt can't stop. Not on Scala.js: use `RxFiber` there. See [Fiber](../control/fiber) |
| Retry / circuit breaker | `wvlet.uni.control.{Retry, CircuitBreaker}` | `Retry.withBackOff().run { ... }` |
| CLI arguments | `wvlet.uni.cli.launcher.*` | `Launcher.execute[A](args)` on a case class with `@option` fields. See [Command Launcher](../cli/launcher) |
| Unit tests | `wvlet.uni.test.*` | `class T extends UniTest: test("...") { x shouldBe y }` |
| Test fixtures | `wvlet.uni.test.*` | `addDesign(_.bindSingleton[A])` in a trait that extends `UniTest`, then `test("...") { (a: A) => ... }`. See [Reusable Fixtures](../core/unitest#reusable-fixtures) |
| Error-path tests | `wvlet.uni.test.*` | `val logs = captureLogs { ... }; logs.warnings shouldContain "..."`; also `captureStdout` / `captureStderr`. See [Testing Error Paths](../core/unitest#testing-error-paths) |
| uni-dom UI tests (Scala.js) | `wvlet.uni.dom.testing.*` | With `uni-test`, `class T extends DomTestSupport`, `val m = mount(MyView())`, `fireEvent.click(m.get("button"))` (also `pointerDown`/`Move`/`Up`, `keyDown(..., isComposing = true)`), and return `settle().map { _ => ... }` to assert on `onMount` effects. `checkPattern(el, AriaPattern.Menu).map(_ shouldBe empty)` checks a widget against its WAI-ARIA pattern ([guide](../dom/testing#checking-aria-patterns)). Run in headless Chromium with the [sbt-uni-playwright Plugin](../build/sbt-uni-playwright#testing-uni-dom-components) |

## How do I migrate from Airframe?

| Airframe | Uni |
|----------|-----|
| `wvlet.airframe.Design` / `newDesign` | `wvlet.uni.design.Design` / `Design.newDesign` |
| `wvlet.log.LogSupport` | `wvlet.uni.log.LogSupport` |
| `wvlet.airframe.codec.MessageCodec` | `wvlet.uni.weaver.Weaver` (`derives Weaver`) |
| `wvlet.airframe.surface.Surface` | `wvlet.uni.surface.Surface` |
| `wvlet.airframe.rx.Rx` | `wvlet.uni.rx.Rx` |
| `wvlet.airframe.http.Http` | `wvlet.uni.http.Http` |
| `wvlet.airframe.http.netty.Netty.server` | `wvlet.uni.http.netty.Netty.server` (JVM only), or `Http.server` for cross-platform code |
| `wvlet.airframe.http.filter.Cors` (`Cors.Policy`, `Cors.newFilter`) | `wvlet.uni.http.CorsFilter(CorsPolicy())`, added with `Http.server.withFilter` |
| `wvlet.airframe.http.RPCStatus` / `RPCException` | `wvlet.uni.http.ServiceStatus` / `ServiceException`: same status names and codes, shared by RPC, Electron IPC, and MCP |
| `wvlet.airframe.canvas.Canvas` | `wvlet.uni.canvas.Canvas`: `release` is `close()`, values are little-endian on every platform, and off-heap canvases need Java 22+ on the JVM |
| `wvlet.airframe.control.Retry` | `wvlet.uni.control.Retry` |
| airframe-parquet `Parquet.newReader[A](path)` / `newReader[Map[String, Any]]` / `newReader[Json]` | `wvlet.uni.cloud.parquet.Parquet.readAs[A](path)` / `Parquet.readRows(path)` / `Parquet.readJson(path)`, which return a `Seq` (`ParquetFile.readAs` and `readRows` return an `Iterator`) instead of a reader whose `read()` returns null at the end |
| airframe-parquet `Parquet.newWriter[A](path)` / `newRecordWriter(path, schema)` | `Parquet.write(path, rows)` or `Parquet.newWriter[A](path)` for case classes with `derives Weaver` (schema from `ParquetSchema.of[A]`); `Parquet.newRecordWriter(path, ParquetSchema.builder(...)...build())` for maps and JSON |
| airframe-parquet `Parquet.readStatistics(path)` | `Parquet.open(path).columnStatistics` (`numValues`, `numNulls`, sizes, `min`, `max`) |
| `wvlet.airspec.AirSpec` | `wvlet.uni.test.UniTest` |
| AirSpec `initDesign` / `initLocalDesign` | `addDesign` / `addTestDesign` |
| AirSpec `test("...") { (a: A) => ... }` | the same: test arguments come from the suite's Design |

Uni uses a separate package root (`wvlet.uni.*`), so both libraries can coexist
during a migration. See [Appendix B: Uni and Airframe](../book/appendix-b-airframe)
for background.

## What mistakes do agents make with Uni?

- **Using Scala 2 syntax.** Uni is Scala 3 only. Use indentation syntax,
  `given`/`using`, `enum`, and `derives`.
- **Using ScalaTest matchers in UniTest.** Write `(x >= 1) shouldBe true`, not
  `x should be >= 1`. For type checks, write `v shouldMatch { case _: T => }`
  instead of `asInstanceOf`.
- **Importing only `UniTest`.** Use `import wvlet.uni.test.*`. `shouldBe defined`
  and `shouldBe empty` need matcher objects from that package.
- **Checking `isSuccessful` after `send`.** The HTTP client throws for non-2xx
  responses. Most 4xx statuses throw `HttpException`. Retryable statuses (5xx,
  429) throw `HttpMaxRetryException` after the retries, and it is not a subclass
  of `HttpException`. Failed RPC client calls throw the same exceptions. Report
  server errors by throwing `ServiceStatus.NOT_FOUND_U5.newException("...")`
  (`wvlet.uni.http.{ServiceStatus, ServiceException}`): RPC, `Router`, and
  other `Http.server` endpoints answer with its HTTP status, the
  `X-RPC-Status` header, and a JSON error body. `RPCStatus` is its deprecated
  old name.
- **Hand-writing test setup and teardown.** Bind servers, clients, and temp
  directories with `addDesign` / `addTestDesign` in a trait that extends
  `UniTest`, and take them as test arguments. A self-type fixture
  (`trait F { this: UniTest => }`) compiles, but when two of them override a
  hook, only the last one runs.
- **Adding `uni-netty` just to serve HTTP or RPC.** `Http.server`, `RouterHandler`,
  and `RPCHandler` are in `uni` and run on every platform (the JVM's built-in
  server needs JDK 24+), and limits and timeouts such as `withMaxHeaderSize` or
  `withIdleTimeoutMillis` are set on `Http.server` for every backend. Add
  `uni-netty` only for Netty's own options (native transport, handler executor
  threads) or an older JDK, and import the handlers from `wvlet.uni.http.router` and
  `wvlet.uni.http.rpc`, not `wvlet.uni.http.netty`. For large uploads,
  `withStreamingRequests(req => ...)` hands the handler `HttpContent.StreamContent`
  (`openStream`) instead of buffering the body.
- **Hand-writing JSON codecs.** Add `derives Weaver` to the case class.
- **Mixing two libraries for one concern in the same module.** Uni works
  alongside community libraries. If a project already uses uPickle, MUnit, sttp,
  or another library for a concern, keep using it there. Use Uni's equivalent
  for new code that is built on Uni.

## Where are complete example apps?

The [`examples/`](https://github.com/wvlet/uni/tree/main/examples) folder of the
repository has small apps that CI compiles and tests against the current source.
Copy one as a template:

| Example | Shows |
|---------|-------|
| [`cli-tool`](https://github.com/wvlet/uni/tree/main/examples/cli-tool) | `Launcher` sub-commands, `LogSupport`, `derives Weaver`, the HTTP client, and a Scala Native binary |
| [`rest-server`](https://github.com/wvlet/uni/tree/main/examples/rest-server) | `Http.server` with `Router` and `RPCRouter`, a typed RPC client, `Design` wiring, and a UniTest suite |
| [`rpc-native`](https://github.com/wvlet/uni/tree/main/examples/rpc-native) | One RPC server built with `Http.server` and `Design`, the same code on the JVM and as a Scala Native binary (no JVM), a typed client, a UniTest suite run on both, and a startup/memory/throughput benchmark |
| [`mcp-server`](https://github.com/wvlet/uni/tree/main/examples/mcp-server) | A stdio MCP server with `MCP.server.withTools[...]` and how to add it to Claude Code |
| [`web-ui`](https://github.com/wvlet/uni/tree/main/examples/web-ui) | A Scala.js todo list with `RxElement` and `Rx.variable` |
| [`ui-gallery`](https://github.com/wvlet/uni/tree/main/examples/ui-gallery) | One short story per uni-ui component (buttons, tabs, menus, dialogs, trees, tables, the dock), each a `def` that returns an `RxElement` |

## Example: a wired service with logging and JSON

```scala
import wvlet.uni.design.Design
import wvlet.uni.log.LogSupport
import wvlet.uni.weaver.Weaver

case class Greeting(name: String, message: String) derives Weaver

class GreeterService extends LogSupport:
  def greet(name: String): String =
    info(s"Greeting ${name}")
    Weaver.toJson(Greeting(name, s"Hello, ${name}!"))

@main def main =
  Design.newDesign
    .bindSingleton[GreeterService]
    .build[GreeterService] { greeter =>
      println(greeter.greet("Uni"))
    }
```
