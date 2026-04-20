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
