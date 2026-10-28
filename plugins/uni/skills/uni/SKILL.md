---
name: uni
description: Write idiomatic Scala 3 code with Uni (org.wvlet.uni, packages wvlet.uni.*), the Airframe successor for object wiring (Design), logging (LogSupport), JSON/MessagePack codecs (Weaver), reactive streams (Rx), HTTP client/server with SSE, WebSocket, and OpenAPI, typed RPC (also over Electron IPC), MCP servers, CLI argument parsing, retry, and unit testing (UniTest) on JVM, Scala.js, and Scala Native. Use when a Scala 3 project's build.sbt mentions org.wvlet.uni or its code imports wvlet.uni, when the user asks for object wiring, logging, JSON, HTTP, RPC, MCP, CLI tools, or tests in Scala 3 with few dependencies, or when migrating from Airframe (wvlet.airframe, wvlet.log, AirSpec).
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

// JVM-only Netty HTTP/RPC server
libraryDependencies += "org.wvlet.uni" %% "uni-netty" % "<version>"
```

On sbt 2.x use `%%` for all platforms. On sbt 1.x use `%%%` for Scala.js and
Scala Native.

## Canonical imports

| Task | Import | Entry point |
|------|--------|-------------|
| Object wiring | `wvlet.uni.design.Design` | `Design.newDesign.bindSingleton[A].build[A] { a => ... }` |
| Logging | `wvlet.uni.log.LogSupport` | `class A extends LogSupport` then `info(...)`, `debug(...)` |
| JSON / MessagePack codec | `wvlet.uni.weaver.Weaver` | `case class P(...) derives Weaver`, `Weaver.toJson(p)`, `Weaver.fromJson[P](json)` |
| JSON tree | `wvlet.uni.json.JSON` | `JSON.parse(text)` |
| YAML config / frontmatter | `wvlet.uni.text.yaml.{YAML, Frontmatter}` | `YAML.fromYaml[P](text)`, `YAML.parse(text)`, `Frontmatter.parse(md).as[P]`; see [references/core.md](references/core.md#yaml-and-frontmatter) |
| Markdown to HTML | `wvlet.uni.text.markdown.{Markdown, MarkdownConfig}` | `Markdown.toHtml(md)` (raw HTML escaped by default), `Markdown.parse(md)`; see [references/core.md](references/core.md#markdown) |
| Reactive streams | `wvlet.uni.rx.Rx` | `Rx.variable(v)`, `Rx.single(v)`, `.map`, `.flatMap`; see [references/rx.md](references/rx.md) |
| Persisted settings | `wvlet.uni.config.SettingStore` | `SettingStore.of[A].withAppName(app).withXXX(...).load(fileName)`; see [references/core.md](references/core.md#persisted-settings-settingstore) |
| HTTP client | `wvlet.uni.http.{Http, Request}` | `Http.client.newSyncClient.send(Request.get(url))` |
| HTTP server (JVM, `uni-netty`) | `wvlet.uni.http.netty.{NettyServer, RouterHandler}` | [references/http-server.md](references/http-server.md) |
| Typed RPC | `wvlet.uni.http.rpc.RPCRouter`, `wvlet.uni.http.netty.RPCHandler` | [references/rpc.md](references/rpc.md) |
| MCP server (stdio) | `wvlet.uni.mcp.{MCP, description}` | `MCP.server.withName(n).withTools[T](impl).serveStdio()` |
| MCP client (HTTP) | `wvlet.uni.mcp.MCP` | `MCP.client.withName(n).connect(url)` returns `Rx[MCPClient]` |
| Retry | `wvlet.uni.control.Retry` | `Retry.withBackOff().run { ... }` |
| CLI arguments | `wvlet.uni.cli.launcher.*` | `Launcher.execute[A](args)` on a case class with `@option` fields |
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
  retrying 5xx/429. RPC client calls fail the same way.
- **Hand-written JSON codecs.** Add `derives Weaver` to the case class.
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
| Object wiring, logging, JSON, YAML, Markdown, persisted settings, retry, MCP server | [references/core.md](references/core.md) |
| Typed RPC service, server, and generated client | [references/rpc.md](references/rpc.md) |
| HTTP client | [references/http-client.md](references/http-client.md) |
| HTTP server, REST controllers, filters | [references/http-server.md](references/http-server.md) |
| Unit, HTTP, RPC, and property tests; test tags | [references/testing.md](references/testing.md) |
| Rx operators, RxRef/RxDeferred/RxSemaphore, fibers, parallelism | [references/rx.md](references/rx.md) |
| Server-sent events and WebSocket (server and client) | [references/streaming.md](references/streaming.md) |
| OpenAPI document from REST routers | [references/openapi.md](references/openapi.md) |
| Electron desktop app (RPC over IPC, Scala.js) | [references/electron.md](references/electron.md) |
| CLI options and sub-commands, colors, progress bars | [references/cli.md](references/cli.md) |
| Migrating from Airframe | [references/airframe-migration.md](references/airframe-migration.md) |

- Complete, CI-tested example apps to copy (CLI tool, REST + RPC server, stdio MCP
  server, Scala.js UI): https://github.com/wvlet/uni/tree/main/examples
- Full documentation in one Markdown file: https://wvlet.org/uni/llms-full.txt
- Page index: https://wvlet.org/uni/llms.txt (any page is also available as `<page>.md`)
- Uni and the Scala Toolkit (sttp, uPickle, os-lib, MUnit), and using both in one build:
  https://wvlet.org/uni/guide/uni-vs-scala-toolkit.md
