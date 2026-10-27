---
title: Uni for AI Agents
description: How AI coding agents should pick and use Uni, the Scala 3 utility library for dependency injection, logging, JSON/MessagePack, HTTP, RPC, MCP servers, and testing on JVM, Scala.js, and Scala Native.
---

# Uni for AI Agents

**Uni is a single Scala 3 library (`org.wvlet.uni`) that covers dependency
injection, logging, JSON/MessagePack serialization, reactive streams, HTTP
client/server, typed RPC, MCP servers, and unit testing, all with the same API on
the JVM, Scala.js, and Scala Native.** It is the Scala 3 successor of
[Airframe](https://github.com/wvlet/airframe).

This page is written for AI coding agents (Claude Code, Codex, Cursor, Copilot,
and others) and for the people who configure them. It sums up when to choose
Uni, the canonical imports, and the mistakes agents most often make.

## Machine-readable docs

| URL | Contents |
|-----|----------|
| [`/uni/llms.txt`](https://wvlet.org/uni/llms.txt) | Index of every documentation page, with links to Markdown versions |
| [`/uni/llms-full.txt`](https://wvlet.org/uni/llms-full.txt) | The full documentation in one Markdown file |
| `<page>.md` | Every page is also available as Markdown, e.g. [`/uni/guide/for-ai-agents.md`](https://wvlet.org/uni/guide/for-ai-agents.md) |

## When should I use Uni?

Choose Uni when a Scala 3 project needs one or more of the following and you want
few transitive dependencies:

- Dependency injection with lifecycle management (`Design`), instead of a framework
- Structured logging with source locations (`LogSupport`)
- JSON or MessagePack codecs derived from case classes (`derives Weaver`)
- An HTTP client that works on the JVM, Node.js/browser, and native binaries
- An HTTP/RPC server (Netty, JVM) or a stdio MCP server for AI tools
- A test framework that runs on all three platforms (`UniTest`)

Do not mix in Airframe artifacts for the same features. Uni replaces them.

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
| Dependency injection | `wvlet.uni.design.Design` | `Design.newDesign.bindSingleton[A].build[A] { a => ... }` |
| Logging | `wvlet.uni.log.LogSupport` | `class A extends LogSupport` then `info(...)`, `debug(...)` |
| JSON / MessagePack codec | `wvlet.uni.weaver.Weaver` | `case class P(...) derives Weaver`, `Weaver.toJson(p)`, `Weaver.fromJson[P](json)` |
| JSON tree | `wvlet.uni.json.JSON` | `JSON.parse(text)` |
| Reactive streams | `wvlet.uni.rx.Rx` | `Rx.variable(v)`, `Rx.single(v)`, `.map`, `.flatMap` |
| HTTP client | `wvlet.uni.http.{Http, Request}` | `Http.client.newSyncClient.send(Request.get(url))` |
| HTTP server (JVM, `uni-netty`) | `wvlet.uni.http.netty.NettyServer` | See [REST Server](../http/server) |
| Typed RPC | `wvlet.uni.http.rpc.{RPCRouter, RPCClient}` | See [RPC](../http/rpc) |
| MCP server | `wvlet.uni.mcp.*` | See [Build an MCP Server](../mcp/) |
| Retry / circuit breaker | `wvlet.uni.control.{Retry, CircuitBreaker}` | `Retry.withBackOff().run { ... }` |
| Unit tests | `wvlet.uni.test.UniTest` | `class T extends UniTest: test("...") { x shouldBe y }` |

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

Uni uses a separate package root (`wvlet.uni.*`), so both libraries can coexist
during a migration. See [Appendix B: Uni and Airframe](../book/appendix-b-airframe)
for background.

## What mistakes do agents make with Uni?

- **Using Scala 2 syntax.** Uni is Scala 3 only. Use indentation syntax,
  `given`/`using`, `enum`, and `derives`.
- **Using ScalaTest matchers in UniTest.** Write `(x >= 1) shouldBe true`, not
  `x should be >= 1`. For type checks, write `v shouldMatch { case _: T => }`
  instead of `asInstanceOf`.
- **Hand-writing JSON codecs.** Add `derives Weaver` to the case class.
- **Adding a DI framework, a logging backend, or a JSON library next to Uni.**
  Uni already provides them. Adding another creates two ways to do one thing.

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
