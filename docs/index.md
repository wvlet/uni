---
layout: home
title: Uni - One Scala 3 Library for the Whole Stack
description: "Uni is the Scala 3 successor of Airframe: object wiring, logging, JSON/MessagePack, Rx, HTTP, RPC, MCP servers, and testing with minimal dependencies, on JVM, Scala.js, and Scala Native."

hero:
  name: Uni
  text: One Scala 3 library for the whole stack
  tagline: Essential building blocks for Scala 3 applications, with minimal dependencies and one codebase for the JVM, Scala.js, and Scala Native.
  image:
    src: /uni-logo.svg
    alt: Uni
  actions:
    - theme: brand
      text: Get Started
      link: /guide/
    - theme: alt
      text: For AI Agents
      link: /guide/for-ai-agents
    - theme: alt
      text: Coming from Airframe?
      link: /guide/for-ai-agents#how-do-i-migrate-from-airframe
    - theme: alt
      text: GitHub
      link: https://github.com/wvlet/uni

features:
  - title: Cross-Platform
    details: Write it once and run it on the JVM, in Node.js or the browser, or as a Scala Native binary.
    link: /book/ch11-00-cross-platform
  - title: Object Wiring
    details: Design connects your services through their constructors and manages their lifecycles, so your classes stay plain Scala.
    link: /core/design
  - title: Logging
    details: LogSupport adds info/debug/warn with source locations to any class.
    link: /core/logging
  - title: JSON & MessagePack
    details: Add <code>derives Weaver</code> to a case class to get JSON and MessagePack codecs.
    link: /core/weaver
  - title: Rx
    details: Composable reactive streams and variables for async code and UI state.
    link: /rx/
  - title: HTTP Client
    details: Sync and async clients with automatic retry, streaming, Server-Sent Events, and WebSocket.
    link: /http/client
  - title: HTTP Server & RPC
    details: Serve REST endpoints, and define RPC services as Scala traits with type-safe clients.
    link: /http/server
  - title: MCP Servers
    details: Turn a Scala trait into an MCP server whose methods AI agents can call as tools.
    link: /mcp/
  - title: UniTest
    details: A lightweight test framework with shouldBe-style assertions.
    link: /core/unitest
---

<llm-only>

# Uni

</llm-only>

## What is Uni?

**Uni is a Scala 3 library that gives you object wiring (Design), logging,
JSON/MessagePack, Rx, HTTP client/server, RPC, MCP servers, and testing from one
project, with minimal dependencies, and the same code runs on JVM, Scala.js, and
Scala Native.** It is the
Scala 3 successor of [Airframe](https://github.com/wvlet/airframe), for people
who build services, CLIs, and tools in Scala 3 and want one set of conventions
instead of assembling a library per concern.

## Install

::: code-group

```scala [sbt]
libraryDependencies += "org.wvlet.uni" %% "uni" % "__UNI_VERSION__"

// Testing
libraryDependencies += "org.wvlet.uni" %% "uni-test" % "__UNI_VERSION__" % Test
testFrameworks += new TestFramework("wvlet.uni.test.Framework")

// Netty HTTP server (JVM only)
libraryDependencies += "org.wvlet.uni" %% "uni-netty" % "__UNI_VERSION__"
```

```scala [Scala CLI]
//> using dep org.wvlet.uni::uni:__UNI_VERSION__
```

:::

## Example: Design, logging, and JSON

```scala
import wvlet.uni.design.Design
import wvlet.uni.log.LogSupport
import wvlet.uni.weaver.Weaver

case class Greeting(name: String, message: String) derives Weaver

class Greeter extends LogSupport:
  def greet(name: String): String =
    info(s"Greeting ${name}")
    Weaver.toJson(Greeting(name, s"Hello, ${name}!"))

@main def hello =
  Design.newDesign.bindSingleton[Greeter].build[Greeter] { greeter =>
    println(greeter.greet("Uni")) // {"name":"Uni","message":"Hello, Uni!"}
  }
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
choices,
and if they already serve you well there is no need to switch. Uni fits best
when you want these pieces from one place, especially when targeting Scala.js
or Scala Native.

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

## Modules

| Module | Contents | Platforms |
|--------|----------|-----------|
| `uni` | Design, logging, JSON/MessagePack, Rx, HTTP client, Node.js/Native HTTP servers, RPC, MCP, [CLI utilities](/cli/), [Web UI](/dom/) (Scala.js) | JVM, Scala.js, Scala Native |
| `uni-netty` | Netty HTTP server, annotation router, HTTP RPC server | JVM |
| `uni-test` | UniTest framework | JVM, Scala.js, Scala Native |
