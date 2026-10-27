# CLI Utilities

uni provides utilities for building command-line applications with styled output, progress indicators, and command launching.

## Overview

| Component | Description |
|-----------|-------------|
| [Terminal Styling](./tint) | Colorful terminal output with Tint |
| [Progress Indicators](./progress) | Progress bars and spinners |
| [Command Launcher](./launcher) | Type-safe CLI argument parsing |

## Quick Start

### Colored Output

```scala
import wvlet.uni.cli.Tint

println(Tint.green("Success!"))
println(Tint.red.bold("Error!"))
println(Tint.yellow.bgBlue("Warning"))
```

### Progress Bar

```scala
import wvlet.uni.cli.ProgressBar

val bar = ProgressBar(total = 100).start()
for i <- 1 to 100 do
  Thread.sleep(50)
  bar.increment()
bar.finish()
```

### Command Launcher

```scala
import wvlet.uni.cli.launcher.*

case class MyApp(
  @option(prefix = "-n,--name", description = "User name")
  name: String = "world",
  @option(prefix = "-v,--verbose", description = "Enable verbose output")
  verbose: Boolean = false
)

// Parse command-line arguments
val app = Launcher.execute[MyApp](args)
if app.verbose then println(s"Running with name: ${app.name}")
println(s"Hello, ${app.name}!")
```

## Features

- **Cross-Platform** - Works on JVM, Scala.js, and Scala Native. On Scala.js, colors and
  progress output render in Node.js; in a browser, they are turned off
- **Automatic Detection** - Detects terminal capabilities
- **Fluent API** - Method chaining for styling
- **Type-Safe** - Compile-time argument validation

## Package

```scala
import wvlet.uni.cli.*
import wvlet.uni.cli.launcher.*
```
