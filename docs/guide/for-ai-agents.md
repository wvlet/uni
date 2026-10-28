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
- An HTTP/RPC server (Netty, JVM) or a stdio MCP server for AI tools
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
libraryDependencies ++= Seq(
  "org.wvlet.uni" %% "uni"       % "__UNI_VERSION__",
  "org.wvlet.uni" %% "uni-test"  % "__UNI_VERSION__" % Test
)
testFrameworks += new TestFramework("wvlet.uni.test.Framework")

// JVM-only Netty HTTP server
libraryDependencies += "org.wvlet.uni" %% "uni-netty" % "__UNI_VERSION__"
```

Uni requires Scala `__SCALA_VERSION__` or later. On sbt 2.x, use `%%` for all
platforms. On sbt 1.x, use `%%%` for Scala.js and Scala Native projects.

## What are the canonical imports?

| Task | Import | Entry point |
|------|--------|-------------|
| Object wiring | `wvlet.uni.design.Design` | `Design.newDesign.bindSingleton[A].build[A] { a => ... }` |
| Logging | `wvlet.uni.log.LogSupport` | `class A extends LogSupport` then `info(...)`, `debug(...)` |
| JSON / MessagePack codec | `wvlet.uni.weaver.Weaver` | `case class P(...) derives Weaver`, `Weaver.toJson(p)`, `Weaver.fromJson[P](json)` |
| JSON tree | `wvlet.uni.json.JSON` | `JSON.parse(text)` |
| Reactive streams | `wvlet.uni.rx.Rx` | `Rx.variable(v)`, `Rx.single(v)`, `.map`, `.flatMap` |
| Persisted settings | `wvlet.uni.config.SettingStore` | `SettingStore.withAppName("my-app").withVersion(n).open("settings", AppSettings())`, then `update(...)`; saved to `~/.config/my-app/` (localStorage in a browser). See [Persisted Settings](../core/settings) |
| HTTP client | `wvlet.uni.http.{Http, Request}` | `Http.client.newSyncClient.send(Request.get(url))` |
| HTTP server (JVM, `uni-netty`) | `wvlet.uni.http.netty.NettyServer` | See [REST Server](../http/server) |
| Typed RPC | `wvlet.uni.http.rpc.{RPCRouter, RPCClient}` | Server: `RPCRouter.of[Api](impl)`; client: `RPCClient.of[Api]`. See [RPC](../http/rpc) |
| Server-sent events | `wvlet.uni.http.{Response, ServerSentEvent}` | Server: `Response.eventStream(rx)`; client: `Http.client.newAsyncClient.sendSSE(request)`. See [SSE](../http/sse) |
| WebSocket | `wvlet.uni.http.{WebSocketHandler, WebSocketContext}` | Server: `.withWebSocketRoute(path) { req => handler }`; client: `Http.webSocketClient.connect(url, handler)`. See [WebSocket](../http/websocket) |
| OpenAPI | `wvlet.uni.http.openapi.{OpenAPI, OpenAPIConfig}` | `OpenAPI.of(Router.of[A], OpenAPIConfig()).toYAML`. See [OpenAPI](../http/openapi) |
| Electron IPC (Scala.js) | `wvlet.uni.electron.{ElectronRPCServer, ElectronRenderer}` | See [Desktop Apps (Electron)](../http/electron) |
| MCP server / client | `wvlet.uni.mcp.MCP` | `MCP.server.withTools[T](impl)...`, `MCP.client.connect(url)`; see [Build an MCP Server](../mcp/) |
| Retry / circuit breaker | `wvlet.uni.control.{Retry, CircuitBreaker}` | `Retry.withBackOff().run { ... }` |
| CLI arguments | `wvlet.uni.cli.launcher.*` | `Launcher.execute[A](args)` on a case class with `@option` fields. See [Command Launcher](../cli/launcher) |
| Unit tests | `wvlet.uni.test.*` | `class T extends UniTest: test("...") { x shouldBe y }` |
| Test fixtures | `wvlet.uni.test.*` | `addDesign(_.bindSingleton[A])` in a trait that extends `UniTest`, then `test("...") { (a: A) => ... }`. See [Reusable Fixtures](../core/unitest#reusable-fixtures) |
| Error-path tests | `wvlet.uni.test.*` | `val logs = captureLogs { ... }; logs.warnings shouldContain "..."`; also `captureStdout` / `captureStderr`. See [Testing Error Paths](../core/unitest#testing-error-paths) |

## How do I migrate from Airframe?

| Airframe | Uni |
|----------|-----|
| `wvlet.airframe.Design` / `newDesign` | `wvlet.uni.design.Design` / `Design.newDesign` |
| `wvlet.log.LogSupport` | `wvlet.uni.log.LogSupport` |
| `wvlet.airframe.codec.MessageCodec` | `wvlet.uni.weaver.Weaver` (`derives Weaver`) |
| `wvlet.airframe.surface.Surface` | `wvlet.uni.surface.Surface` |
| `wvlet.airframe.rx.Rx` | `wvlet.uni.rx.Rx` |
| `wvlet.airframe.http.Http` | `wvlet.uni.http.Http` |
| `wvlet.airframe.control.Retry` | `wvlet.uni.control.Retry` |
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
  of `HttpException`. Failed RPC client calls throw the same exceptions.
- **Hand-writing test setup and teardown.** Bind servers, clients, and temp
  directories with `addDesign` / `addTestDesign` in a trait that extends
  `UniTest`, and take them as test arguments. A self-type fixture
  (`trait F { this: UniTest => }`) compiles, but when two of them override a
  hook, only the last one runs.
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
| [`rest-server`](https://github.com/wvlet/uni/tree/main/examples/rest-server) | `NettyServer` with `Router` and `RPCRouter`, a typed RPC client, `Design` wiring, and a UniTest suite |
| [`mcp-server`](https://github.com/wvlet/uni/tree/main/examples/mcp-server) | A stdio MCP server with `MCP.server.withTools[...]` and how to add it to Claude Code |
| [`web-ui`](https://github.com/wvlet/uni/tree/main/examples/web-ui) | A Scala.js todo list with `RxElement` and `Rx.variable` |

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
