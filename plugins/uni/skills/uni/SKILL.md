---
name: uni
description: Write idiomatic Scala 3 code with Uni (org.wvlet.uni, packages wvlet.uni.*), the Airframe successor for object wiring (Design), logging (LogSupport), JSON/MessagePack codecs (Weaver), reactive streams (Rx), HTTP client/server with SSE, WebSocket, and OpenAPI, typed RPC (also over Electron IPC), MCP servers, browser UIs (RxElement, uni-ui), CLI argument parsing, retry, and unit testing (UniTest) on JVM, Scala.js, and Scala Native. Use when a Scala 3 project's build.sbt mentions org.wvlet.uni or its code imports wvlet.uni, when the user asks for object wiring, logging, JSON, HTTP, RPC, MCP, CLI tools, or tests in Scala 3 with few dependencies, or when migrating from Airframe (wvlet.airframe, wvlet.log, AirSpec).
---

# Uni

Uni is one Scala 3 library (`org.wvlet.uni`) for object wiring, logging, JSON/MessagePack,
Rx, HTTP (with SSE and WebSocket), RPC, MCP servers, CLI tools, and testing, and
the same code runs on JVM, Scala.js, and Scala Native. It replaces Airframe; the package root is `wvlet.uni.*`.

## sbt setup

```scala sbt
// build.sbt — use the latest version from
// https://central.sonatype.com/artifact/org.wvlet.uni/uni_3 (or the one already in the build)
libraryDependencies ++= Seq(
  "org.wvlet.uni" %% "uni"      % "<version>",
  "org.wvlet.uni" %% "uni-test" % "<version>" % Test
)
testFrameworks += new TestFramework("wvlet.uni.test.Framework")

// Optional, JVM only: serve Http.server on Netty instead of the built-in server
libraryDependencies += "org.wvlet.uni" %% "uni-netty" % "<version>"

// Optional, all platforms: read and write Apache Parquet files
libraryDependencies += "org.wvlet.uni" %% "uni-parquet" % "<version>"
```

On sbt 2.x use `%%` for all platforms. On sbt 1.x use `%%%` for Scala.js and
Scala Native.

Every release is on `https://repo.wvlet.org/maven2`; only some reach Maven
Central. For a newer version than Central has, an application adds
`resolvers += "wvlet" at "https://repo.wvlet.org/maven2"`. A library published to
Maven Central should depend on a Central version and not add that resolver.

## Canonical imports

| Task | Import | Entry point |
|------|--------|-------------|
| Object wiring | `wvlet.uni.design.Design` | `Design.newDesign.bindSingleton[A].build[A] { a => ... }` |
| Logging | `wvlet.uni.log.LogSupport` | `class A extends LogSupport` then `info(...)`, `debug(...)` |
| JSON / MessagePack codec | `wvlet.uni.weaver.Weaver` | `case class P(...) derives Weaver`, `Weaver.toJson(p)`, `Weaver.fromJson[P](json)` |
| JSON tree | `wvlet.uni.json.JSON` | `JSON.parse(text)` |
| YAML config / frontmatter | `wvlet.uni.text.yaml.{YAML, Frontmatter}` | `YAML.fromYaml[P](text)`, `YAML.parse(text)`, `Frontmatter.parse(md).as[P]`, `YAML.parseDocument(text).set(path, v).toYaml` (keeps comments), `YAML.parseDocuments`/`YAML.writeStream` for `---` streams; see [references/core.md](references/core.md#yaml-and-frontmatter) |
| Markdown to HTML | `wvlet.uni.text.markdown.{Markdown, MarkdownConfig}` | `Markdown.toHtml(md)` (raw HTML escaped by default), `Markdown.parse(md)`, `.frontmatterAs[P]`, `.toMarkdown` (lossless); see [references/core.md](references/core.md#markdown) |
| Cron schedules | `wvlet.uni.text.cron.{Cron, UtcOffset}` | `Cron.parse(expr).next(instant)`, `.nextN(instant, n)`, `.describe`; UTC or a fixed `UtcOffset` only; see [references/core.md](references/core.md#cron-schedules) |
| Parquet files (`uni-parquet` artifact) | `wvlet.uni.parquet.{Parquet, ParquetReadOptions, RowGroupFilter, ParquetWriteOptions, ParquetSchema, ParquetType}` | `Parquet.readAs[A](path)`, `Parquet.readRows(path)` for `Map[String, Any]` rows, `Parquet.readJson(path)`, `Parquet.open(path).readRowGroup(i)` for column vectors, `Parquet.openUrl(url)` over HTTP Range requests, `Parquet.openS3("s3://bucket/key", config)` from S3/R2/MinIO, `Parquet.openGcs("gs://bucket/key")` from Google Cloud Storage; `Parquet.write(path, rows)`, `Parquet.newWriter[A](path)`, `Parquet.newRecordWriter(path, schema)` for writing; pure Scala on every platform; see [references/parquet.md](references/parquet.md) |
| S3 / R2 / MinIO / Google Cloud Storage objects | `wvlet.uni.storage.{ObjectStore, ObjectUri, S3Config, AwsCredentialsProvider, GcsConfig, GoogleCredentialsProvider, StorageException}` | `ObjectStore.s3(config)` or `ObjectStore.gcs(config)` (Google Application Default Credentials) then `get`, `getRange`, `head`, `put`, `list`, `delete`, `newOutputStream` (multipart; `abort()` on error), `presign`; `...Async` variants for browsers; `S3Config.r2(accountId)`, `S3Config.gcs` (HMAC), `S3Config.minio(url)`; see [references/storage.md](references/storage.md) |
| Reactive streams | `wvlet.uni.rx.Rx` | `Rx.variable(v)`, `Rx.single(v)`, `.map`, `.flatMap`, `.scan(init)(f)`, `items.split(_.id)` (one `Rx` per key); see [references/rx.md](references/rx.md) |
| Concurrent blocking code (JVM, Native) | `wvlet.uni.control.Fiber` | `Fiber { f() }.join()`, `Fiber.parMap(items, maxConcurrency = 16)(f)`, `Fiber.par(a, b)`, `Fiber.race(a, b)`, `Fiber.scope { s => s.fork(...) }`; virtual threads on JDK 24+; not on Scala.js (use `RxFiber`); see [references/rx.md](references/rx.md#fiber-plain-blocking-code) |
| Persisted settings | `wvlet.uni.config.SettingStore` | `SettingStore.of[A].withAppName(app).withXXX(...).load(fileName)`; see [references/core.md](references/core.md#persisted-settings-settingstore) |
| HTTP client | `wvlet.uni.http.{Http, Request}` | `Http.client.newSyncClient.send(Request.get(url))` |
| HTTP server (all platforms) | `wvlet.uni.http.Http` | `Http.server.withPort(8080).withRxHandler(handler).start()` with `uni` alone; on the JVM a built-in server (JDK 24+), or Netty when `uni-netty` is on the classpath; JVM-only projects can use `Netty.server` (`wvlet.uni.http.netty.Netty`); see [references/http-server.md](references/http-server.md) |
| REST router (all platforms) | `wvlet.uni.http.router.{Router, RouterHandler}` | [references/http-server.md](references/http-server.md) |
| Typed RPC | `wvlet.uni.http.rpc.RPCRouter`, `wvlet.uni.http.rpc.RPCHandler` | [references/rpc.md](references/rpc.md) |
| MCP server (stdio) | `wvlet.uni.mcp.{MCP, description}` | `MCP.server.withName(n).withTools[T](impl).serveStdio()` |
| MCP client (HTTP) | `wvlet.uni.mcp.MCP` | `MCP.client.withName(n).connect(url)` returns `Rx[MCPClient]` |
| Off-heap memory | `wvlet.uni.canvas.{Canvas, OffHeapMemoryAllocator}` | `Canvas.newOffHeapCanvas(size)` (`Long` offsets, beyond 2 GB), `readInt`/`writeLong` (little-endian; `*BigEndian` variants), `close()` frees it now; JVM needs Java 22+; see [references/core.md](references/core.md#off-heap-memory-canvas) |
| Retry | `wvlet.uni.control.Retry` | `Retry.withBackOff().run { ... }` |
| CLI arguments | `wvlet.uni.cli.launcher.*` | `Launcher.execute[A](args)` on a case class with `@option` fields |
| Terminal emulator | `wvlet.uni.cli.term.TerminalEmulator`, `wvlet.uni.dom.TerminalView` (Scala.js) | `term.write(output)`, `term.screen.text`; `TerminalView(term)` draws it in the DOM; `PosixPty.spawn` (JVM, Native) or `NodePty.spawn` (Scala.js) runs a shell in a PTY; see [references/cli.md](references/cli.md#terminal-emulator) |
| Browser UI (Scala.js; `uni-ui` artifact for components) | `wvlet.uni.dom.all.*`, `wvlet.uni.ui.*` | `class V extends RxElement: def render = div(...)`, `V().renderTo("app")`; `Button("Send").primary.onClick(() => send())`, `Input(textVar)`, `Menu(openVar, trigger = element, entries = Seq(MenuItem("Copy").onSelect(f)))`, `Dialog(openVar, content = element)`, `VirtualList(itemsRx, rowHeight = 24, key = _.id, render = a => node)`, `Dock(layoutVar, label = "Dock", panels = Seq(DockPanel(id, title = "...", content = element)))`; render lists with `itemsRx.each(_.id)(render)`; `.onChange(d => if ... then d.cancel())` sees and can refuse each change a component makes; `UiAgent.install()` lets an AI agent snapshot and operate the UI, and `UiHistory.record()` undoes its changes (`undoSource(ChangeSource.Agent)`, `stage(label)`) without overwriting later edits; load `uni-ui.css` and restyle with `--uni-*` variables; see [references/ui.md](references/ui.md) |
| Unit tests | `wvlet.uni.test.*` | `class T extends UniTest: test("...") { x shouldBe y }` |
| Error-path tests | `wvlet.uni.test.*` | `val logs = captureLogs { ... }; logs.warnings shouldContain "..."` |

## Uni or the Scala Toolkit

The [Scala Toolkit](https://docs.scala-lang.org/toolkit/introduction.html) (sttp,
uPickle, os-lib, MUnit) is a good fit for scripts, teaching, Scala 2.13 or Scala 3
before 3.9, and running subprocesses (os-lib). Uni fits Scala 3.9+ applications and
services that need object wiring, logging, an HTTP server, RPC, MCP servers, or the
same code on Scala.js and Scala Native. They work in one build: keep the Toolkit
libraries a project already uses, and use Weaver for types that Uni's server or RPC
encodes. See https://wvlet.org/uni/guide/uni-vs-scala-toolkit.

## Mistakes to avoid

- **Scala 2 syntax.** Uni is Scala 3 only: indentation syntax, `given`/`using`,
  `enum`, `derives`. Omit `new` for classes (`StringBuilder()`).
- **ScalaTest matchers in UniTest.** Write `(x >= 1) shouldBe true`, not
  `x should be >= 1`. For type checks, `v shouldMatch { case _: T => }` instead of
  `asInstanceOf`. Use `shouldNotBe`, `shouldContain`, `intercept[E] { ... }`.
- **Silencing expected errors with `Logger.of[X].suppressWarnings`.** In tests, wrap the
  code in `captureLogs { ... }` and assert on `logs.warnings`; see
  `references/testing.md`.
- **Importing only `UniTest`.** Use `import wvlet.uni.test.*`; `shouldBe defined`
  and `shouldBe empty` need the matcher objects from that package.
- **Checking `isSuccessful` after `send`.** The HTTP client throws on non-2xx:
  `HttpException` for most 4xx, `HttpMaxRetryException` (not a subclass) after
  retrying 5xx/429. RPC client calls fail the same way. Report server errors
  with `ServiceStatus.NOT_FOUND_U5.newException("...")` (`wvlet.uni.http`) from
  RPC, `Router`, or any `Http.server` handler: the response gets its HTTP
  status. `RPCStatus` is its deprecated old name.
- **Hand-written JSON codecs.** Add `derives Weaver` to the case class.
- **Adding `uni-netty` just to serve HTTP or RPC.** `Http.server`, `RouterHandler`
  (`wvlet.uni.http.router`), and `RPCHandler` (`wvlet.uni.http.rpc`) are in `uni` on
  every platform. Add `uni-netty` only for Netty's tuning options or a JDK before 24.
- **A second object-wiring framework or logging backend next to Uni.** Uni
  already provides them. A JSON library the project already uses (such as uPickle)
  can stay for its own types; derive `Weaver` for types that Uni's server or RPC encodes.
- **Airframe imports.** `wvlet.airframe.*`, `wvlet.log.*`, and `wvlet.airspec.*`
  are the old library. Map them with [references/airframe-migration.md](references/airframe-migration.md).
- **Hand-written setup and teardown in tests.** Bind servers, clients, and temp directories
  with `addDesign` / `addTestDesign` in a trait that extends `UniTest`, and take them as test
  arguments: `test("ping") { (client: HttpSyncClient) => ... }`. A self-type fixture
  (`trait F { this: UniTest => }`) compiles, but only the last one's hooks run. See
  [references/testing.md](references/testing.md#reusable-fixtures).
- **Constructing services by hand.** Take dependencies as constructor parameters
  and let `Design` build them; bind interfaces with `bindImpl[A, B]` and values with
  `bindInstance[A](v)`.

## References

Read the reference for the task before writing code:

| Task | Reference |
|------|-----------|
| Object wiring, logging, JSON, YAML, Markdown, cron, persisted settings, off-heap memory, retry, MCP server | [references/core.md](references/core.md) |
| Typed RPC service, server, generated client, and streaming results | [references/rpc.md](references/rpc.md) |
| HTTP client | [references/http-client.md](references/http-client.md) |
| Reading Parquet files (local, bytes, HTTP, S3, GCS) into case classes, `Map[String, Any]` rows, JSON, or column vectors, and writing them from case classes, maps, or JSON | [references/parquet.md](references/parquet.md) |
| Reading and writing objects in S3, Cloudflare R2, MinIO, or Google Cloud Storage; AWS and Google credentials; presigned and signed URLs | [references/storage.md](references/storage.md) |
| HTTP server, REST controllers, filters, CORS | [references/http-server.md](references/http-server.md) |
| Unit, HTTP, RPC, property, and uni-dom UI tests; test tags | [references/testing.md](references/testing.md) |
| Rx operators, RxRef/RxDeferred/RxSemaphore, fibers (`RxFiber` and direct-style `Fiber`), parallelism | [references/rx.md](references/rx.md) |
| Server-sent events and WebSocket (server and client) | [references/streaming.md](references/streaming.md) |
| OpenAPI document from REST routers | [references/openapi.md](references/openapi.md) |
| Browser UI: RxElement, uni-ui components, styling, DOM tests (Scala.js) | [references/ui.md](references/ui.md) |
| Electron desktop app (RPC over IPC, Scala.js) | [references/electron.md](references/electron.md) |
| CLI options and sub-commands, colors, progress bars, terminal emulator | [references/cli.md](references/cli.md) |
| Migrating from Airframe | [references/airframe-migration.md](references/airframe-migration.md) |

- Complete, CI-tested example apps to copy (CLI tool, REST + RPC server, stdio MCP
  server, Scala.js UI): https://github.com/wvlet/uni/tree/main/examples
- Full documentation in one Markdown file: https://wvlet.org/uni/llms-full.txt
- Page index: https://wvlet.org/uni/llms.txt (any page is also available as `<page>.md`)
- Uni and the Scala Toolkit (sttp, uPickle, os-lib, MUnit), and using both in one build:
  https://wvlet.org/uni/guide/uni-vs-scala-toolkit.md
