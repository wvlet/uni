# Introduction

**Uni is the standard library Scala 3 didn't ship with.**

Logging, dependency injection, JSON & MessagePack, reactive streams, HTTP
clients and servers, RPC, and a browser UI toolkit — small, composable pieces
that work *identically* on the **JVM**, in the **browser** (Scala.js), and as
**native binaries** (Scala Native), with almost no external dependencies.

It distills the most-used building blocks of the
[Airframe](https://github.com/wvlet/airframe) ecosystem into one cohesive,
Scala-3-first library.

::: tip Get going in 30 seconds
Add one dependency ([Installation](./installation)) and you have all of it —
no à-la-carte modules to assemble.
:::

## A quick taste

Dependency injection, structured logging, and lifecycle management — three
things you usually reach for three libraries for — in a handful of lines:

```scala
import wvlet.uni.design.Design
import wvlet.uni.log.LogSupport

class GreeterService extends LogSupport:
  def greet(name: String): String =
    info(s"Greeting ${name}")      // logs with source file + line
    s"Hello, ${name}!"

@main def main =
  Design.newDesign
    .bindSingleton[GreeterService]
    .build[GreeterService] { greeter =>   // wired, started, and cleaned up
      println(greeter.greet("Uni"))
    }
```

## Why Uni?

- 🪶 **Minimal dependencies** — foundational utilities without dragging in a
  heavy framework.
- 🌍 **One codebase, three runtimes** — the same source compiles for JVM,
  Scala.js, and Scala Native.
- 🧩 **Composable** — small, focused APIs designed to combine, not to lock you in.
- ⚡ **Scala 3 first** — built around `derives`, `given`, enums, and macros.
- 🔋 **Production-tested** — battle-hardened code, refined from the Airframe
  ecosystem.

## What's inside

#### Core

| Module | Description |
|--------|-------------|
| [Design](/core/design) | Object wiring (DI) with lifecycle management |
| [Logging](/core/logging) | Logging with source-location tracking |
| [Surface](/core/surface) | Compile-time type introspection |
| [FileSystem](/core/filesystem) | Cross-platform file I/O with `IOPath` |

#### Data & Serialization

| Module | Description |
|--------|-------------|
| [JSON](/core/json) | Pure-Scala JSON parser and DSL |
| [MessagePack](/core/msgpack) | Compact binary serialization |
| [Weaver](/core/weaver) | Derivation-based codecs — JSON / MessagePack / `Map` from one `derives Weaver` |

#### Async & Control Flow

| Module | Description |
|--------|-------------|
| [Rx](/rx/) | Reactive streams and async data flows |
| [Control](/control/) | Retry, circuit breaker, rate limiter, cache, resources |
| [BackgroundTask](/control/background-task) | Cancellable, progress-pollable background workers |

#### HTTP & RPC

| Module | Description |
|--------|-------------|
| [HTTP](/http/) | Cross-platform client **and** server (Netty / Node.js / Native) |
| [RPC](/http/rpc) | Type-safe remote calls, with client code generation |
| [WebSocket](/http/websocket) | Cross-platform bidirectional WebSocket client |
| [Server-Sent Events](/http/sse) | One-way server→client streaming |

#### UI & CLI

| Module | Description |
|--------|-------------|
| [CLI](/cli/) | Terminal styling, progress bars, command launching |
| [Web UI](/dom/) | Reactive browser UIs with RxElement (Scala.js) |

#### Testing

| Module | Description |
|--------|-------------|
| [UniTest](/core/unitest) | Lightweight, expressive, cross-platform test framework |

## Next steps

- 📦 [**Installation**](./installation) — add Uni to your sbt / Scala CLI project
- 🧭 [**Design Principles**](./principles) — the architecture and the *why*
- 🔍 [**Core Utilities**](/core/) — start exploring the foundational APIs
