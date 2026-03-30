# IO

uni provides cross-platform I/O utilities including subprocess execution.

## Subprocess Execution

The `IO` object provides three methods for running external commands:

| Method | Behavior |
|--------|----------|
| `IO.run` | Execute and capture output, return `CommandResult` |
| `IO.call` | Like `run`, but throws on non-zero exit code |
| `IO.spawn` | Start process without waiting, return `Process` handle |

### IO.run

Execute a command and capture its output. Accepts a single command line string (automatically tokenized) or separate arguments:

```scala
import wvlet.uni.io.IO

// Single command line string — tokenized automatically
val result = IO.run("ls -la /tmp")

// Separate arguments
val result = IO.run("ls", "-la", "/tmp")

println(result.exitCode)  // 0
println(result.stdout)    // file listing
println(result.stderr)    // empty
println(result.isSuccess) // true
```

Quoted arguments are handled correctly:

```scala
IO.run("sh -c 'echo hello world'")
IO.run("""grep -r "TODO" src/""")
```

Non-zero exit codes are returned without throwing:

```scala
val result = IO.run("sh -c 'exit 42'")
result.exitCode  // 42
result.isSuccess // false
```

### IO.call

Like `run`, but throws `NonZeroExitCodeException` on failure:

```scala
import wvlet.uni.io.{IO, NonZeroExitCodeException}

val result = IO.call("echo", "hello")
result.stdout.trim // "hello"

// Throws NonZeroExitCodeException
try
  IO.call("sh", "-c", "exit 1")
catch
  case e: NonZeroExitCodeException =>
    println(s"Exit code: ${e.exitCode}")
```

### IO.spawn

Start a process without waiting for completion. Returns a `Process` handle with access to stdin/stdout/stderr streams:

```scala
import wvlet.uni.io.IO

val proc = IO.spawn("cat")
proc.stdin.write("hello".getBytes("UTF-8"))
proc.stdin.close()

val output = String(proc.stdout.readAllBytes(), "UTF-8")
proc.waitFor() // 0
```

Timed waiting to avoid hanging:

```scala
import java.util.concurrent.TimeUnit

val proc = IO.spawn("long-running-command")
if proc.waitFor(5, TimeUnit.SECONDS) then
  println(s"Exited with: ${proc.exitValue()}")
else
  proc.destroyForcibly()
```

## ProcessConfig

All three methods accept a `ProcessConfig` for advanced options:

```scala
import wvlet.uni.io.{IO, ProcessConfig, IOPath}

val config = ProcessConfig.default
  .withWorkingDirectory(IOPath("/tmp"))
  .withEnv("MY_VAR", "value")
  .withRedirectErrorToOutput(true)

val result = IO.run(Seq("my-command", "--flag"), config)
```

| Option | Description |
|--------|-------------|
| `withWorkingDirectory(dir)` | Set the working directory |
| `withEnv(key, value)` | Add an environment variable |
| `withEnv(vars)` | Add multiple environment variables |
| `withInheritIO(true)` | Inherit parent's stdin/stdout/stderr |
| `withRedirectErrorToOutput(true)` | Merge stderr into stdout |

## Platform Support

| Platform | Status |
|----------|--------|
| JVM | Supported (uses `java.lang.ProcessBuilder`) |
| Scala Native | Supported (uses `java.lang.ProcessBuilder`) |
| Scala.js | Not supported (throws `UnsupportedOperationException`) |
