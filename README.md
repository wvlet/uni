<p align="center">
  <img src="https://wvlet.org/uni/uni-logo-128x128.png" alt="Uni Logo" width="120">
</p>

# Uni

[![Maven Central](https://img.shields.io/maven-central/v/org.wvlet.uni/uni_3.svg?label=Maven%20Central)](https://central.sonatype.com/artifact/org.wvlet.uni/uni_3)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)

**Essential Scala Utilities** — Refined for Scala 3 with minimal dependencies.

## Documentation

**[https://wvlet.org/uni/](https://wvlet.org/uni/)**

## Modules

| Module | Description |
|--------|-------------|
| `uni` | Core utilities: Design (DI), logging, JSON, MessagePack, Rx, HTTP client, control flow |
| `uni-test` | Lightweight testing framework with cross-platform support |
| `uni-netty` | Netty-based HTTP server |

Cross-platform: JVM, Scala.js, and Scala Native.

## sbt 2.x Plugins

Uni also ships sbt plugins for **sbt 2.x**, usable in any sbt 2.x project
(with or without the uni library):

| Plugin | Description |
|--------|-------------|
| [`sbt-uni`](https://wvlet.org/uni/build/sbt-uni) | Type-safe HTTP/RPC client generation from service traits, plus `~uniRestart` — background fork-run on every code change (an sbt 2.x port of sbt-revolver's `reStart`) |
| [`sbt-uni-crossproject`](https://wvlet.org/uni/build/sbt-uni-crossproject) | `crossProject(...)` for building one source tree for JVM, Scala.js, and Scala Native — a single-plugin replacement for portable-scala's `sbt-crossproject`, which is not available for sbt 2.x |
| [`sbt-uni-playwright`](https://wvlet.org/uni/build/sbt-uni-playwright) | Run Scala.js tests in a real headless browser (Chromium, Firefox, or WebKit) via Playwright — ES module support and a faithful DOM, with no Node.js installation required |

Add the ones you need to `project/plugins.sbt`:

```scala
// HTTP/RPC client generation + background fork-run (~uniRestart)
addSbtPlugin("org.wvlet.uni" % "sbt-uni" % "<version>")

// Cross-build one source tree for JVM, Scala.js, and Scala Native
addSbtPlugin("org.scala-js"     % "sbt-scalajs"          % "1.22.0")
addSbtPlugin("org.scala-native" % "sbt-scala-native"     % "0.5.12")
addSbtPlugin("org.wvlet.uni"    % "sbt-uni-crossproject" % "<version>")

// Run Scala.js tests in a real browser
addSbtPlugin("org.wvlet.uni" % "sbt-uni-playwright" % "<version>")
```

For example, `sbt-uni-crossproject` defines cross-platform projects with
shared sources in `src/` and platform-specific code in `.jvm/`, `.js/`,
and `.native/` folders:

```scala
lazy val core = crossProject(JVMPlatform, JSPlatform, NativePlatform)
  .crossType(CrossType.Pure)
  .in(file("core"))
  .settings(/* shared settings */)
```

See the [build plugin docs](https://wvlet.org/uni/build/sbt-uni) for
details.

## Using `uni`

Add the dependency to your `build.sbt` (replace `<version>` with the
version shown in the Maven Central badge above):

```scala
libraryDependencies += "org.wvlet.uni" %% "uni" % "<version>"
```

For Scala.js or Scala Native, use `%%%`:

```scala
libraryDependencies += "org.wvlet.uni" %%% "uni" % "<version>"
```

A minimal example using the logging API:

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

See the [reference docs](https://wvlet.org/uni/) for Design, Rx,
JSON/MessagePack, HTTP, and more.

## Using `uni-test`

`uni-test` is a lightweight test framework. Add it as a test
dependency and register the framework:

```scala
libraryDependencies += "org.wvlet.uni" %% "uni-test" % "<version>" % Test
testFrameworks += new TestFramework("wvlet.uni.test.Framework")
```

For Scala.js or Scala Native projects, use `%%%`:

```scala
libraryDependencies += "org.wvlet.uni" %%% "uni-test" % "<version>" % Test
```

Write a test by extending `UniTest`:

```scala
import wvlet.uni.test.UniTest

class MyTest extends UniTest:
  test("addition works") {
    (1 + 1) shouldBe 2
  }
```

Run with `sbt test`. See the [UniTest guide](https://wvlet.org/uni/core/unitest)
for assertions, property-based testing, and more.

## License

Apache License 2.0
