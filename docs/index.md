---
layout: home
title: Uni - Essential Scala Utilities

hero:
  name: Uni
  text: Essential Scala Utilities
  tagline: Refined for Scala 3 with minimal dependencies
  image:
    src: /uni-logo-1024x1024.png
    alt: Uni
  actions:
    - theme: brand
      text: Get Started
      link: /guide/
    - theme: alt
      text: View on GitHub
      link: https://github.com/wvlet/uni

features:
  - title: Core Primitives
    details: Logging, Design (object wiring), Rx (async programming), unified JSON/MessagePack serialization, and a cross-platform FileSystem that works on JVM, Scala.js, and Scala Native.
    link: /core/
  - title: HTTP Client & Server
    details: Full-featured HTTP with automatic retry, circuit breaker, rate limiting, and streaming support.
    link: /http/
  - title: CLI Utilities
    details: Terminal styling, progress indicators, and type-safe command-line argument parsing.
    link: /cli/
  - title: UniTest
    details: Lightweight testing framework with expressive assertions, property-based testing, and cross-platform support.
    link: /core/unitest
---

## Getting Started

::: code-group

```scala [sbt]
libraryDependencies += "org.wvlet.uni" %% "uni" % "__UNI_VERSION__"
```

```scala [Scala CLI]
//> using dep org.wvlet.uni::uni:__UNI_VERSION__
```

:::

## Quick Example

```scala
import wvlet.uni.log.LogSupport

class MyService extends LogSupport:
  def greet(name: String): String =
    info(s"Greeting ${name}")
    s"Hello, ${name}!"

@main def hello =
  val service = MyService()
  println(service.greet("World"))
```

## Modules

| Module | Description |
|--------|-------------|
| `uni` | Core utilities: Design, logging, JSON, MessagePack, Rx, HTTP client |
| `uni-test` | Lightweight testing framework with cross-platform support |

## Cross-Platform Support

uni is designed for cross-platform Scala development:

- **JVM** - Full feature support
- **Scala.js** - Browser and Node.js environments
- **Scala Native** - Native binary compilation
