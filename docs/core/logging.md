# Logging

uni provides a comprehensive logging framework with structured logging support.

## LogSupport Trait

The easiest way to add logging is to extend `LogSupport`:

```scala
import wvlet.uni.log.LogSupport

class MyService extends LogSupport:
  def process(data: String): Unit =
    info(s"Processing: ${data}")
    debug("Detailed information")

    try
      riskyOperation()
    catch
      case e: Exception =>
        error("Operation failed", e)
```

## Log Levels

Available log levels in order of severity:

| Level | Method | Use Case |
|-------|--------|----------|
| ERROR | `error()` | Errors requiring attention |
| WARN | `warn()` | Potential issues |
| INFO | `info()` | General information |
| DEBUG | `debug()` | Debugging details |
| TRACE | `trace()` | Fine-grained tracing |

## Logger Configuration

### Setting Log Levels

```scala
import wvlet.uni.log.{Logger, LogLevel}

// Set global default
Logger.setDefaultLogLevel(LogLevel.DEBUG)

// Set for specific logger
Logger("MyService").setLogLevel(LogLevel.TRACE)
```

### Creating Named Loggers

```scala
val logger = Logger("MyApp")
logger.info("Application started")
logger.debug("Configuration loaded")
```

## Logging with Exceptions

Include stack traces in error logs:

```scala
try
  riskyOperation()
catch
  case e: IOException =>
    error("IO operation failed", e)
  case e: Exception =>
    error(s"Unexpected error: ${e.getMessage}", e)
```

## Zero-Overhead Logging with Scala Macros

The logging methods use Scala 3 `inline` macros, which means:

1. **Automatic lazy evaluation**: The message is only evaluated if the log level is enabled
2. **Zero overhead**: If the log level is disabled, there is no runtime cost for creating log messages

```scala
// expensiveComputation() is only called if DEBUG is enabled
debug(s"Result: ${expensiveComputation()}")
```

Unlike traditional logging frameworks, you don't need to wrap expensive computations with level checks.

## Source Location

Log messages automatically include source location at the end of the message:

```
2024-01-15 10:30:45.123+0900  info [MyService] Processing: data - (MyService.scala:14)
```

The source code location `(file:line)` is captured at compile time using Scala macros.

## Writing Logs to a File

`FileLogHandler` writes log records to a file with automatic rotation.
It runs unchanged on JVM, Scala.js (Node.js), and Scala Native, using
the [FileSystem](/core/filesystem) abstraction underneath.

```scala
import wvlet.uni.io.FileSystemInit
import wvlet.uni.log.{FileLogHandler, FileLogHandlerConfig, Logger}

// On Scala.js / Native, register the file system implementation once
// at startup. No-op on JVM.
FileSystemInit.init()

val handler = FileLogHandler("app.log")
Logger.setDefaultHandler(handler)
```

By default, `FileLogHandler` rotates daily and whenever the active file
exceeds 100 MB, keeps the most recent 100 rotated files, and gzips
each rotated file. Rotated files are named
`{stem}-YYYY-MM-DD.{index}.log.gz` and sit next to the active log.

### Tuning rotation

Configure the handler through `FileLogHandlerConfig`:

```scala
import wvlet.uni.log.{FileLogHandler, FileLogHandlerConfig}

val config =
  FileLogHandlerConfig("app.log")
    .withMaxSizeInBytes(10 * 1024 * 1024) // 10 MB per file
    .withMaxNumberOfFiles(30)             // keep 30 rotated files
    .withCompressRotated(true)            // gzip rotated files

val handler = FileLogHandler(config)
```

`FileLogHandlerConfig` ships with both `withXxx` setters and these
escape hatches:

| Builder | Effect |
|---------|--------|
| `withPath(p)` | Change the active log file path. |
| `withMaxSizeInBytes(n)` | Rotate once the active file exceeds `n` bytes. Default 100 MB. |
| `withMaxNumberOfFiles(n)` | Keep at most `n` rotated files; oldest are deleted on rotation. Default 100. |
| `withFormatter(f)` | Override the log formatter. Default `AppLogFormatter`. |
| `withLogFileExt(ext)` | Override the active-log extension (default `".log"`). |
| `withCompressRotated(b)` | Toggle gzip of rotated files. Default `true`. |
| `noCompression` | Disable gzip; rotated files keep their `.log` extension. |
| `noRotation` | Disable both size and count limits — write a single file forever. |

### Writing without rotation

When you just want a plain file sink (for example, a short-lived CLI
that appends to a debug log), use `noRotation`:

```scala
val handler = FileLogHandler(
  FileLogHandlerConfig("debug.log").noRotation
)
```

::: tip Cross-platform note
`FileLogHandler` itself lives in shared sources, but it talks to disk
through `wvlet.uni.io.FileSystem`. Call `FileSystemInit.init()` once
during startup on Scala.js and Scala Native so the platform-specific
file-system implementation is registered. On JVM the call is a no-op
and can be omitted.
:::

## Best Practices

1. **Use appropriate levels** - Don't log everything as INFO
2. **Include context** - Log relevant data for debugging
3. **Avoid sensitive data** - Don't log passwords, tokens, etc.
4. **Use structured data** - Include key-value pairs for parsing
5. **Keep messages concise** - Clear, actionable messages

## Example: Service with Logging

```scala
import wvlet.uni.log.LogSupport

class OrderService(db: Database) extends LogSupport:

  def createOrder(userId: String, items: List[Item]): Order =
    info(s"Creating order for user: ${userId}, items: ${items.size}")

    val order = try
      val order = db.createOrder(userId, items)
      info(s"Order created: ${order.id}")
      order
    catch
      case e: DatabaseException =>
        error(s"Failed to create order for user: ${userId}", e)
        throw e

    debug(s"Order details: ${order}")
    order
```
