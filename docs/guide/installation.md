# Installation

## Requirements

- Scala 3.9 (LTS) or later. Uni's artifacts are compiled with Scala 3.9, and
  older compilers cannot read their TASTy.
- sbt 1.9+ or sbt 2.x

## Adding Dependencies

Add the wvlet Maven repository and Uni to your `build.sbt`:

```scala
// Every Uni release is published here (see "The wvlet Maven Repository" below)
resolvers += "wvlet" at "https://repo.wvlet.org/maven2"

// Core utilities (object wiring, logging, JSON, HTTP, Rx, etc.)
libraryDependencies += "org.wvlet.uni" %% "uni" % "__UNI_VERSION__"
```

### HTTP Server

`uni` includes an HTTP server on every platform: a built-in server on the JVM
(JDK 24+) and Scala Native, and Node.js `http` on Scala.js. The annotation
router and RPC handlers are in `uni` too, so no extra module is needed.

On the JVM, you can optionally serve the same code on Netty, for Netty's
tuning options or for a JDK older than 24. Add the Netty module (it pulls in
`uni` transitively):

```scala
// Optional: serve Http.server on Netty (JVM only)
libraryDependencies += "org.wvlet.uni" %% "uni-netty" % "__UNI_VERSION__"
```

See the [REST Server guide](../http/server.md) for usage.

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
No system libraries are needed to build a Scala Native binary against Uni. Only
reaching for the HTTP client pulls in libcurl, which must then be present as a
shared library — see
[Linking libcurl on Scala Native](../http/client.md#linking-libcurl-on-scala-native).
:::

## The wvlet Maven Repository

Every Uni release is published to the wvlet Maven repository,
`https://repo.wvlet.org/maven2`, and the versions on this site are the
newest ones there. Maven Central limits how much a project may publish
each month, so only some releases are copied to Central; the newest one
there is `__UNI_CENTRAL_VERSION__`. The artifacts and signatures are the
same files that Maven Central gets, under the same coordinates.

Add the repository to your build:

```scala
// build.sbt
resolvers += "wvlet" at "https://repo.wvlet.org/maven2"

// project/plugins.sbt, for Uni's sbt plugins
resolvers += "wvlet" at "https://repo.wvlet.org/maven2"
```

```scala
// scala-cli
//> using repository https://repo.wvlet.org/maven2
```

```scala
// Mill (build.mill)
def repositories = super.repositories() ++ Seq("https://repo.wvlet.org/maven2")
```

```kotlin
// Gradle (build.gradle.kts)
repositories {
    mavenCentral()
    maven("https://repo.wvlet.org/maven2")
}
```

To use only Maven Central, pin a version that is on Central
(`__UNI_CENTRAL_VERSION__` or older) and remove the resolver. Features
added after that version, such as Parquet support, are not on Central yet.

::: tip Publishing a library that depends on Uni
If your library is published to Maven Central, depend on a Uni version
that is on Maven Central (`__UNI_CENTRAL_VERSION__` or older) and don't
add the wvlet resolver to its build. Your users then need no extra
resolver for Uni.
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

Uni works with all major Scala IDEs:

- **IntelliJ IDEA** with Scala plugin
- **VS Code** with Metals
- **Neovim** with Metals

The library uses standard Scala 3 features, so IDE support is seamless.
