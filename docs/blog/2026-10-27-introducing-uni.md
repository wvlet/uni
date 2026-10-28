---
title: "Introducing Uni: One Scala 3 Library for the Whole App"
date: 2026-10-27
author: Taro L. Saito
description: "Uni is one Scala 3 library with one set of conventions for object wiring, logging, object serialization (JSON and MessagePack), HTTP, typed RPC, MCP servers, Rx, CLI tools, and testing, on the JVM, Scala.js, and Scala Native. It is the Scala 3 successor of Airframe and is built to be read by AI coding agents as well as people."
image: /blog/many-libraries-or-one-uni.png
---

# Introducing Uni: One Scala 3 Library for the Whole App

**Uni is a Scala 3 library that gives an application everything it needs from
one dependency, with one set of conventions: object wiring, logging, object
serialization (JSON and MessagePack), an HTTP client and server, typed RPC,
MCP servers, Rx, CLI tools, and a test framework. The same code runs on the JVM, Scala.js, and
Scala Native.** Uni has shipped 37 releases since March 2026, and this post
is its first proper introduction.

![Many libraries, or one Uni? On the left, a developer and an AI assistant sit among piles of manuals beneath a tangled tower of boxes labeled HTTP client, JSON, HTTP server, logging, RPC, object wiring, MCP, CLI, and testing, next to a sign reading "Assemble it yourself". On the right, a unicorn carries all of those pieces in one bag, beside a sign reading "Uni: designed for Scala 3, one API, JVM, Scala.js, and Native, one style for you and your AI agent". A banner at the bottom says "Love the ecosystem? Mix and match. Uni plays well with others."](/blog/many-libraries-or-one-uni.webp)

## Many libraries, or one?

Building a Scala application usually starts with choosing libraries: one for
HTTP, one for JSON, one for logging, one for tests, maybe one for wiring
services together. Each is good at its job, and each comes with its own
documentation, its own style, and its own release schedule. Before writing
business logic, you learn several APIs and keep their versions compatible.
Platform support also varies from library to library when you target Scala.js
or Scala Native.

Uni takes the other path: **learn one library, build the whole app.**

- **One dependency.** `"org.wvlet.uni" %% "uni"` brings object wiring,
  logging, codecs, HTTP, RPC, MCP, Rx, and CLI utilities. On the JVM it depends
  only on the Scala 3 standard library.
- **One set of conventions.** A case class with `derives Weaver` becomes JSON,
  MessagePack, an RPC payload, and an MCP tool argument through the same codec.
  Services are plain classes wired by `Design`, and any class gets logging by
  mixing in `LogSupport`.
- **One version to upgrade.** All the pieces are released together, so they
  never disagree on transitive dependencies.
- **One codebase on three platforms.** The same code runs on the JVM, in
  Node.js or the browser, and as a Scala Native binary.

## What is in the box

| Need | Uni |
|------|-----|
| Object wiring and lifecycle | [Design](/core/design): constructor wiring, singletons, `onStart`/`onShutdown` hooks |
| Logging | [`LogSupport`](/core/logging): `info`/`debug`/`warn` with source locations |
| Object serialization | [Weaver](/core/weaver): `derives Weaver` for JSON and MessagePack |
| HTTP client | [`Http.client`](/http/client): sync and async, retries, Server-Sent Events, WebSocket |
| HTTP server | [REST Server](/http/server): Netty on the JVM (`uni-netty`), Node.js on Scala.js, a native server on Scala Native |
| Typed RPC | [RPC](/http/rpc): services defined as Scala traits, with type-safe clients |
| MCP servers | [`wvlet.uni.mcp`](/mcp/): a Scala trait becomes tools that AI agents can call |
| Reactive streams | [Rx](/rx/): composable streams and variables for async code and UI state |
| CLI | [Command Launcher](/cli/launcher), terminal colors, progress bars |
| Web UI (Scala.js) | [RxElement](/dom/): reactive HTML components driven by `Rx` |
| Desktop apps | [Electron](/http/electron): typed RPC between the UI and the main process over IPC |
| Testing | [UniTest](/core/unitest) (`uni-test`): `shouldBe`, `shouldMatch`, property checks |

## Quick start

Uni requires Scala 3.9 or later. Add it to `build.sbt`:

```scala sbt
scalaVersion := "__SCALA_VERSION__"

libraryDependencies += "org.wvlet.uni" %% "uni" % "__UNI_VERSION__"

// Testing
libraryDependencies += "org.wvlet.uni" %% "uni-test" % "__UNI_VERSION__" % Test
testFrameworks += new TestFramework("wvlet.uni.test.Framework")
```

Define a service as a trait, implement it as a plain class with logging, and
let `Design` wire it:

```scala
import wvlet.uni.design.Design
import wvlet.uni.log.LogSupport
import wvlet.uni.mcp.description
import wvlet.uni.weaver.Weaver

case class Greeting(name: String, message: String) derives Weaver

trait GreeterService:
  @description("Greet a person by name")
  def greet(@description("Who to greet") name: String): Greeting

class GreeterServiceImpl extends GreeterService with LogSupport:
  def greet(name: String): Greeting =
    info(s"Greeting ${name}")
    Greeting(name, s"Hello, ${name}!")

@main def hello =
  Design.newDesign.bindImpl[GreeterService, GreeterServiceImpl].build[GreeterService] {
    greeter =>
      println(Weaver.toJson(greeter.greet("Uni"))) // {"name":"Uni","message":"Hello, Uni!"}
  }
```

The same trait is also an MCP server. Every public method becomes a tool that
Claude Code or another MCP client can call, with its input schema derived from
the method signature:

```scala
import wvlet.uni.mcp.MCP

@main def greeterMcp(): Unit = MCP.server
  .withName("greeter")
  .withVersion("0.1.0")
  .withTools[GreeterService](GreeterServiceImpl())
  .serveStdio()
```

And it is tested with UniTest:

```scala test
test("greet a person") {
  GreeterServiceImpl().greet("Uni").message shouldBe "Hello, Uni!"
}
```

For a longer walk through, read the [Guide](/guide/) or
[The Uni Book](/book/), which builds a CLI, an HTTP service, and a
cross-platform app step by step.

## Demo: one trait, from server to browser to desktop

Uni's conventions carry across a whole application. Here, one service trait
becomes an HTTP API, a web UI, and an Electron desktop app.

Define the service once, in code shared by every platform:

```scala
import wvlet.uni.rx.Rx

case class CounterState(value: Int)

trait CounterApi:
  def get(): Rx[CounterState]
  def increment(amount: Int): Rx[CounterState]

class CounterApiImpl extends CounterApi:
  private var value = 0
  def get(): Rx[CounterState] = Rx.single(CounterState(value))
  def increment(amount: Int): Rx[CounterState] =
    value += amount
    Rx.single(CounterState(value))
```

**Serve it over HTTP.** On the JVM, `RPCRouter` turns the trait into routes,
and `uni-netty` serves them:

```scala
import wvlet.uni.http.netty.{NettyServer, RPCHandler}
import wvlet.uni.http.rpc.RPCRouter

@main def counterServer(): Unit = NettyServer
  .withPort(8080)
  .withRxHandler(RPCHandler(RPCRouter.of[CounterApi](CounterApiImpl())))
  .start(_.awaitTermination())
```

**Build the UI in Scala.js.** `RxElement` renders HTML that updates itself
when an `Rx.variable` changes, and `RPCClient` calls the trait's methods with
the same JSON codec the server uses. The same component runs in a browser
page or in an Electron window:

```scala skip
import wvlet.uni.dom.all.*
import wvlet.uni.dom.all.given
import wvlet.uni.http.Http
import wvlet.uni.http.rpc.RPCClient
import wvlet.uni.surface.Surface
import scala.language.implicitConversions

class CounterUI extends RxElement:
  private val rpc         = RPCClient.build(Surface.of[CounterApi], Surface.methodsOf[CounterApi])
  private lazy val client = Http.client.newAsyncClient
  private val count       = Rx.variable(0)

  override def render: RxElement = div(
    h1("Uni Counter"),
    p(count.map(_.toString)), // re-renders when count changes
    button(
      onclick -> { () => rpc.callAsync[CounterState](client, "increment", Seq(1)).run(show) },
      "+1"
    )
  )

  private def show(s: CounterState): Unit = count := s.value

  override def onMount(node: Any): Unit =
    rpc.callAsync[CounterState](client, "get", Seq.empty).run(show)
```

In a browser, the client sends these calls over HTTP to the server above.
Serve the page from the same origin as the server (a dev-server proxy works),
because the server does not add CORS headers.

**Ship it as a desktop app.** In Electron, the main process serves the same
trait over IPC instead of a network port, and the renderer calls
`ElectronRenderer.install()` once so `CounterUI`'s calls go over IPC too. No
HTTP server, no open ports:

```scala skip
import wvlet.uni.electron.{ElectronRenderer, ElectronRPCServer}
import scala.scalajs.js
import scala.scalajs.js.annotation.JSExportTopLevel

// Main process (Node.js)
@JSExportTopLevel("wireMainProcess")
def wireMainProcess(ipcMain: js.Dynamic): Unit =
  ElectronRPCServer.serve(ipcMain, RPCRouter.of[CounterApi](CounterApiImpl()))

// Renderer (Chromium)
@JSExportTopLevel("main")
def main(): Unit =
  ElectronRenderer.install()
  CounterUI().renderTo("app")
```

The whole app (service, UI, and business logic) is Scala, built from one
codebase. [`examples/electron-app`](https://github.com/wvlet/uni/tree/main/examples/electron-app)
is the runnable version with Tailwind CSS styling and packaging for macOS,
Windows, and Linux, and the [Electron Tutorial](/http/electron-tutorial)
builds it step by step from an empty directory.

## Built for AI agents, too

More and more Scala code is written together with AI coding agents, so Uni
treats agents as primary readers of its documentation:

- [`llms.txt`](https://wvlet.org/uni/llms.txt) and
  [`llms-full.txt`](https://wvlet.org/uni/llms-full.txt) publish the docs as
  Markdown, and every page has a `.md` copy.
- [Uni for AI Agents](/guide/for-ai-agents) lists the canonical imports, an
  Airframe-to-Uni mapping, and the mistakes agents most often make.
- A Claude Code plugin teaches the agent idiomatic Uni code, with snippets that
  are compile-checked in CI:

  ```text
  /plugin marketplace add wvlet/uni
  /plugin install uni@uni
  ```

- The docs are indexed by [Context7](https://context7.com/wvlet/uni) and
  [DeepWiki](https://deepwiki.com/wvlet/uni).

Because Uni has one style across all of these features, an agent that has
learned one part of it can write the rest in the same style.

## The Scala 3 successor of Airframe

Uni grew out of [Airframe](https://github.com/wvlet/airframe). It keeps
Airframe's most-used ideas (`Design` for object wiring, `Rx`, `Surface` for
compile-time type information, and derived codecs) and rebuilds them for
Scala 3 only, with fewer dependencies and a trimmed scope under the
`wvlet.uni.*` package. [Appendix B of the book](/book/appendix-b-airframe)
tells the story, and the
[migration table](/guide/for-ai-agents#how-do-i-migrate-from-airframe) maps
Airframe imports to Uni. Uni is already in production use:
[Wvlet](https://github.com/wvlet/wvlet), a flow-style query language with
its CLI and server, is built on `uni`, `uni-test`, and `uni-netty`.

## Uni and the rest of the ecosystem

The Scala ecosystem has many good libraries. The
[Scala Toolkit](https://docs.scala-lang.org/toolkit/introduction.html), with
sttp, uPickle, os-lib, and MUnit, is the easiest start for scripts, teaching,
and everyday tasks, and Cask is a simple way to serve HTTP on the JVM. Uni
works alongside them: you can add Uni for object wiring and logging and keep
using the libraries you already rely on. If a focused library already serves
you well, there is no need to switch. [Uni and the Scala
Toolkit](/guide/uni-vs-scala-toolkit) explains when each fits and how to use
them together.

## Try it

- [Guide](/guide/) and [Installation](/guide/installation)
- [The Uni Book](/book/)
- [Uni and the Scala Toolkit](/guide/uni-vs-scala-toolkit)
- [Uni for AI Agents](/guide/for-ai-agents)
- [GitHub: wvlet/uni](https://github.com/wvlet/uni)

Questions, ideas, and bug reports are welcome on
[GitHub](https://github.com/wvlet/uni/issues).
