---
layout: home
markdownStyles: false
title: Uni - One Scala 3 Library for the Whole Stack
description: "Uni is the Scala 3 successor of Airframe: object wiring, logging, JSON/MessagePack, Rx, HTTP, RPC, MCP servers, Parquet and Iceberg on object storage, and testing with minimal dependencies, on JVM, Scala.js, and Scala Native."
---

<llm-exclude>
<!-- The visible page is the UniHome component (docs/.vitepress/theme/UniHome.vue). The llm-only
Markdown below is published only in /uni/index.md and llms-full.txt, as the page's answer-first
summary for AI agents; docSnippets compiles its Scala fences. Keep the two in sync. -->
<UniHome />
</llm-exclude>

<llm-only>

# Uni

**Uni is a Scala 3 library that gives you object wiring (Design), logging,
JSON/MessagePack, Rx, HTTP client/server, RPC, MCP servers, Parquet and Iceberg
tables on object storage, typed LLM APIs, web UI, and testing from one project,
with minimal dependencies, and the same code runs on JVM, Scala.js, and Scala
Native.** It is the Scala 3 successor of [Airframe](https://github.com/wvlet/airframe),
for people who build services, CLIs, and tools in Scala 3 and want one
consistent set of conventions across these concerns.

## Install

::: code-group

```scala [sbt] sbt
// Every Uni release is published to the wvlet Maven repository
resolvers += "wvlet" at "https://repo.wvlet.org/maven2"

libraryDependencies += "org.wvlet.uni" %% "uni" % "__UNI_VERSION__"

// Testing
libraryDependencies += "org.wvlet.uni" %% "uni-test" % "__UNI_VERSION__" % Test
testFrameworks += new TestFramework("wvlet.uni.test.Framework")

// Optional: serve Http.server on Netty (JVM only)
libraryDependencies += "org.wvlet.uni" %% "uni-netty" % "__UNI_VERSION__"
```

```scala [Scala CLI] skip
//> using repository https://repo.wvlet.org/maven2
//> using dep org.wvlet.uni::uni:__UNI_VERSION__
```

:::

## Example: a JSON endpoint with Design, logging, and Weaver

`Design` builds `GreetingApi` and its `Greeter` through their constructors,
`derives Weaver` gives `Greeting` its JSON codec, and `Http.server` serves the
`@Endpoint` methods. The same code runs on the JVM (JDK 24+), Node.js, and
Scala Native.

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
    info(s"Greeting ${name}")
    greeter.greet(name)

@main def server =
  Design.newDesign.build[GreetingApi] { api =>
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

## Why one library?

- **Minimal dependencies.** On the JVM, `uni` depends only on the Scala 3
  standard library. Scala.js and Scala Native add only the platform shims they
  need, such as `java.time` support.
- **One version to upgrade.** Object wiring, logging, codecs, HTTP, and tests are
  released together, so they never disagree on transitive dependencies.
- **One codebase on three platforms.** The same code runs on the JVM, in Node.js or
  the browser, and as a Scala Native binary.
- **One set of conventions.** Case classes become JSON, RPC payloads, and MCP
  tool arguments through the same `Weaver` codecs.

Focused libraries and ecosystems such as sttp, circe, ZIO, and MacWire are good
choices, and if they already serve you well there is no need to switch. Uni
fits best when you want these pieces from one place, especially when targeting
Scala.js or Scala Native.

## What's inside

All of these come with the one `uni` dependency, except the uni-ui components
(`uni-ui`). [All Modules](/modules) lists every page.

- **Core:** [Design](/core/design) (object wiring), [logging](/core/logging),
  [Weaver](/core/weaver) (JSON and MessagePack), [YAML](/text/yaml),
  [Markdown](/text/markdown), [Rx](/rx/), [control flow](/control/) (retry,
  circuit breaker, cache, resources), and [files and IO](/core/io)
- **HTTP & RPC:** [client](/http/client), [server](/http/server),
  [RPC](/http/rpc), [OpenAPI](/http/openapi), [Server-Sent Events](/http/sse),
  and [WebSocket](/http/websocket)
- **Cloud Data:** [object storage](/cloud/storage) (S3, GCS, Azure Blob),
  [Parquet](/cloud/parquet), [Iceberg](/cloud/iceberg),
  [Delta Lake](/cloud/delta), and [Arrow IPC](/cloud/arrow)
- **AI:** typed [LLM APIs](/ai/) (Anthropic, OpenAI, Gemini),
  [MCP servers](/mcp/), and [UiAgent](/dom/agents)
- **Web UI & CLI:** [uni-dom and uni-ui](/dom/) (Scala.js), the
  [UI gallery](https://wvlet.org/uni/gallery/), [Electron apps](/http/electron),
  and [CLI tools](/cli/)
- **Testing & Build:** [UniTest](/core/unitest) and the
  [sbt plugins](/build/sbt-uni)

## Web UI

Uni builds browser and desktop UIs in Scala.js. [uni-dom](/dom/rxelement)
(in `uni`) renders plain Scala classes with a type-checked HTML DSL, and an `Rx`
value in the markup re-renders only its part of the DOM. [uni-ui](/dom/ui) adds
ready-made, accessible components styled by one CSS file. The same code runs in
an [Electron](/http/electron) app, and [UiAgent](/dom/agents) lets an AI agent
operate the screen. The [UI gallery](https://wvlet.org/uni/gallery/) runs every
component live next to its code.

## Artifacts

| Artifact | Contents | Platforms |
|----------|----------|-----------|
| `uni` | Everything above, except the uni-ui components | JVM, Scala.js, Scala Native |
| `uni-test` | UniTest framework | JVM, Scala.js, Scala Native |
| `uni-netty` | Optional Netty backend for `Http.server`, with Netty tuning options | JVM |
| `uni-ui` | [UI components](/dom/ui) for uni-dom: controls, overlays, virtualized collections, a dock, and `UiAgent` for AI agents | Scala.js |

## Used by Wvlet

[Wvlet](https://github.com/wvlet/wvlet), a flow-style query language and its
CLI and server, is built on `uni`, `uni-test`, and `uni-netty`.

## For AI agents

- [`llms.txt`](https://wvlet.org/uni/llms.txt) (page index) and
  [`llms-full.txt`](https://wvlet.org/uni/llms-full.txt) (all docs in one file)
- [Uni for AI Agents](/guide/for-ai-agents): canonical imports, an Airframe
  migration table, and common mistakes
- [Context7](https://context7.com/wvlet/uni)
- Claude Code plugin: `/plugin marketplace add wvlet/uni`, then
  `/plugin install uni@uni`

</llm-only>
