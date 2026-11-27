# Introduction

**Uni is one Scala 3 library for the whole stack.**

Object wiring, logging, JSON and MessagePack, reactive streams, HTTP clients
and servers, RPC, Parquet and Iceberg on object storage, typed LLM APIs, MCP
servers, a browser UI toolkit, and testing: small, composable pieces that work
the same on the **JVM**, in the **browser and Node.js** (Scala.js), and as
**native binaries** (Scala Native), with minimal dependencies.

It distills the most-used building blocks of the
[Airframe](https://github.com/wvlet/airframe) ecosystem into one cohesive,
Scala-3-first library.

::: tip Get going in 30 seconds
Add one dependency ([Installation](./installation)) and you have all of it,
with no modules to assemble.
:::

## A quick taste

A JSON endpoint in a handful of lines: `Design` builds `GreetingApi` and its
`Greeter` through their constructors, `LogSupport` logs with the source
location, `derives Weaver` gives `Greeting` its JSON codec, and `Http.server`
serves the `@Endpoint` methods on the JVM (JDK 24+), Node.js, or Scala Native:

```scala
import wvlet.uni.design.Design
import wvlet.uni.http.{Http, HttpMethod}
import wvlet.uni.http.router.{Endpoint, Router, RouterHandler}
import wvlet.uni.log.LogSupport
import wvlet.uni.weaver.Weaver

case class Greeting(name: String, message: String) derives Weaver

class Greeter:
  def greet(name: String): Greeting =
    Greeting(name, s"Hello, ${name}!")

class GreetingApi(greeter: Greeter) extends LogSupport:
  @Endpoint(HttpMethod.GET, "/hello/:name")
  def hello(name: String): Greeting =
    info(s"Greeting ${name}")      // logs with source file + line
    greeter.greet(name)

@main def server =
  Design.newDesign.build[GreetingApi] { api =>   // wired, started, and cleaned up
    val router = Router.of[GreetingApi]
    Http.server
      .withPort(8080)
      .withRxHandler(RouterHandler.withControllers(router, api))
      .start(_.awaitTermination())
  }
```

```text
$ curl localhost:8080/hello/Uni
{"name":"Uni","message":"Hello, Uni!"}
```

## Why Uni?

- **Minimal dependencies.** On the JVM, `uni` depends only on the Scala 3
  standard library.
- **One codebase, three runtimes.** The same source compiles for the JVM,
  Scala.js, and Scala Native.
- **Composable.** Small, focused APIs designed to combine, not to lock you in.
- **Scala 3 first.** Built around `derives`, `given`, enums, and macros.
- **Production-tested.** Refined from the Airframe ecosystem, and used by
  [Wvlet](https://github.com/wvlet/wvlet).

## Uni and Airframe

Uni is the Scala 3 successor of [Airframe](https://github.com/wvlet/airframe).
The two projects have different goals:

- **Uni** supports only Scala 3. Dropping Scala 2 lets it make full use of
  Scala 3 features in its APIs and implementation. It also bundles the core
  utilities into a few modules (`uni`, `uni-netty`, `uni-test`) instead of
  many `airframe-*` artifacts.
- **Airframe** supports both Scala 2 and Scala 3, and it remains maintained.
  There is no plan to deprecate it.

::: tip Which one should I use?
For a new Scala 3 project, use Uni. If your project still needs Scala 2, or you
depend on Airframe modules that Uni doesn't cover, keep using Airframe. See the
[Airframe migration table](./for-ai-agents#how-do-i-migrate-from-airframe) for
how APIs map between them.
:::

## What's inside

Everything below comes with the one `uni` dependency, except the uni-ui
components (`uni-ui`). The groups match the cards on the [home page](/);
[All Modules](/modules) lists every page.

#### Core

| Module | Description |
|--------|-------------|
| [Design](/core/design) | Object wiring with lifecycle management |
| [Logging](/core/logging) | Logging with source-location tracking |
| [Weaver](/core/weaver) | JSON, MessagePack, and `Map` codecs from one `derives Weaver` |
| [JSON](/core/json) and [MessagePack](/core/msgpack) | Pure-Scala parsers, value trees, and a JSON query DSL |
| [YAML](/text/yaml), [Markdown](/text/markdown), and [Cron](/text/cron) | Text formats, with Weaver decoding for YAML |
| [Rx](/rx/) | Reactive streams and variables for async code and UI state |
| [Control](/control/) | Retry, circuit breaker, rate limiter, cache, resources, background tasks, and [fibers](/control/fiber) |
| [IO](/core/io) and [FileSystem](/core/filesystem) | Cross-platform file I/O with `IOPath`, and subprocesses |
| [Surface](/core/surface), [Result](/core/result), [SettingStore](/core/settings), [Utilities](/core/utilities) | Type introspection, typed errors, persisted settings, IDs and encodings |

#### HTTP & RPC

| Module | Description |
|--------|-------------|
| [HTTP Client](/http/client) | Sync and async clients with automatic retry and streaming |
| [REST Server](/http/server) | A built-in server on every platform (or Netty on the JVM), with an annotation router |
| [RPC](/http/rpc) | Scala traits as remote services, with type-safe clients |
| [OpenAPI](/http/openapi) | OpenAPI 3.0 documents from `@Endpoint` routes |
| [Server-Sent Events](/http/sse) and [WebSocket](/http/websocket) | Server→client streaming and bidirectional connections |

#### Cloud Data

| Module | Description |
|--------|-------------|
| [Object Storage](/cloud/storage) | S3, R2, MinIO, Google Cloud Storage, and Azure Blob without the cloud SDKs |
| [Parquet](/cloud/parquet) | Read and write Parquet files and partitioned datasets |
| [Iceberg](/cloud/iceberg) and [Delta Lake](/cloud/delta) | Read, write, update, and compact Iceberg tables; read Delta Lake tables |
| [Arrow IPC](/cloud/arrow) | Send column batches to pyarrow, polars, and Arrow JS |

#### AI

| Module | Description |
|--------|-------------|
| [LLM APIs](/ai/) | Typed Anthropic, OpenAI, and Gemini (also on Vertex AI and Bedrock) APIs, with converters between vendors |
| [MCP Servers](/mcp/) | A Scala trait as an MCP server whose methods AI agents call as tools |
| [UiAgent](/dom/agents) | Let an AI agent read and operate a uni-ui screen (`uni-ui`) |

#### Web UI & CLI

| Module | Description |
|--------|-------------|
| [Web UI](/dom/) | Reactive browser UIs (Scala.js): RxElement and the HTML DSL in `uni`, ready-made components in `uni-ui` |
| [Desktop Apps](/http/electron) | Electron apps with a Scala.js renderer and typed RPC to the main process |
| [CLI](/cli/) | Terminal styling, progress bars, line editing, and command launching |

#### Testing & Build

| Module | Description |
|--------|-------------|
| [UniTest](/core/unitest) | Lightweight, cross-platform test framework (`uni-test`) |
| [sbt plugins](/build/sbt-uni) | RPC client generation, browser tests, cross-building, and small javadoc jars |

## Next steps

- [**Installation**](./installation): add Uni to your sbt or Scala CLI project
- [**Design Principles**](./principles): the architecture and the *why*
- [**All Modules**](/modules): every module page, grouped by task
