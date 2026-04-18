# Installation

## Requirements

- Scala 3.3+
- sbt 1.9+

## Adding Dependencies

Add uni to your `build.sbt`:

```scala
// Core utilities (DI, logging, JSON, HTTP, Rx, etc.)
libraryDependencies += "org.wvlet.uni" %% "uni" % "__UNI_VERSION__"

// LLM agent framework (optional)
libraryDependencies += "org.wvlet.uni" %% "uni-agent" % "__UNI_VERSION__"

// AWS Bedrock integration (optional)
libraryDependencies += "org.wvlet.uni" %% "uni-bedrock" % "__UNI_VERSION__"
```

## Cross-Platform Projects

For Scala.js or Scala Native projects:

```scala
// Scala.js
libraryDependencies += "org.wvlet.uni" %%% "uni" % "__UNI_VERSION__"

// Scala Native
libraryDependencies += "org.wvlet.uni" %%% "uni" % "__UNI_VERSION__"
```

## Imports

Common imports for getting started:

```scala
// Dependency injection
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
