# Installation

## Requirements

- Scala 3.3+
- sbt 1.9+ or sbt 2.x

## Adding Dependencies

Add uni to your `build.sbt`:

```scala
// Core utilities (object wiring, logging, JSON, HTTP, Rx, etc.)
libraryDependencies += "org.wvlet.uni" %% "uni" % "__UNI_VERSION__"
```

### HTTP Server on the JVM

To run an HTTP server on the JVM, add the Netty-based server module
(it pulls in `uni` transitively):

```scala
// Netty-based HTTP server (JVM only)
libraryDependencies += "org.wvlet.uni" %% "uni-netty" % "__UNI_VERSION__"
```

See the [REST Server guide](../http/server.md) for usage. On Scala.js and
Scala Native, the HTTP server backends (`NodeServer` / `NativeServer`) are
included in `uni` itself — no extra module is needed.

### Testing Framework

Add [UniTest](../core/unitest.md) as a test dependency and register its
test framework:

```scala
libraryDependencies += "org.wvlet.uni" %% "uni-test" % "__UNI_VERSION__" % Test
testFrameworks += new TestFramework("wvlet.uni.test.Framework")
```

## Cross-Platform Projects

For Scala.js or Scala Native projects on sbt 1.x, use `%%%`:

```scala
// Scala.js / Scala Native (sbt 1.x)
libraryDependencies += "org.wvlet.uni" %%% "uni" % "__UNI_VERSION__"
libraryDependencies += "org.wvlet.uni" %%% "uni-test" % "__UNI_VERSION__" % Test
```

On sbt 2.x, `%%` resolves the platform-specific artifact for all
platforms, so `%%%` is not needed.

::: tip Scala Native and libcurl
No system libraries are needed to build a Scala Native binary against uni. Only
reaching for the HTTP client pulls in libcurl, which must then be present as a
shared library — see
[Linking libcurl on Scala Native](../http/client.md#linking-libcurl-on-scala-native).
:::

## Imports

Common imports for getting started:

```scala
// Object wiring
import wvlet.uni.design.Design

// Logging
import wvlet.uni.log.{LogSupport, Logger, LogLevel}

// JSON
import wvlet.uni.json.JSON

// HTTP
import wvlet.uni.http.{Http, HttpRequest, HttpResponse}

// Reactive streams
import wvlet.uni.rx.Rx

// Control flow
import wvlet.uni.control.{Retry, CircuitBreaker, Resource}
```

## Verifying Installation

Create a simple test to verify the installation:

```scala
import wvlet.uni.log.LogSupport

object Main extends App with LogSupport:
  info("uni is working!")
```

Run with:

```bash
sbt run
```

You should see log output with your message.

## IDE Support

uni works with all major Scala IDEs:

- **IntelliJ IDEA** with Scala plugin
- **VS Code** with Metals
- **Neovim** with Metals

The library uses standard Scala 3 features, so IDE support is seamless.
