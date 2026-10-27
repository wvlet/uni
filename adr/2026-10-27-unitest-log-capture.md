# UniTest routes suite-thread log records to sbt's test loggers

## Context

sbt buffers what a test framework writes to the `sbt.testing.Logger`s of a task and prints the buffer when the suite ends, so suites running in parallel don't interleave. UniTest writes its class header, result lines and summary there. uni log records went through the root `java.util.logging` handler straight to stderr, outside that buffer. In CI (td-llm-proxy), a suite's log lines printed above its header, mixed with other suites' logs, so a failure could not be matched with its error output.

## Decision

- `TestLogCapture.install()` wraps the root logger's handlers in a routing handler when a `UniTestRunner` starts. `done()` restores the original handlers, unless test code replaced them in the meantime. A nested runner (a test that drives the runner) reuses the installed handler.
- The blocking `UniTestTask.execute` sets a `ThreadLocal` to the task's loggers around the suite and restores the previous value afterwards. On that thread, records are formatted with `SourceCodeLogFormatter` and sent to the loggers' `error`/`warn`/`info` by level. Records from other threads go to the original handlers.
- On the JVM, construction, hooks and bodies run on the sbt task thread (see `2026-10-27-unitest-suite-thread.md`), so this covers test code and what it calls synchronously.

## Why not

- `InheritableThreadLocal`: pool threads outlive the suite that created them and would attribute a later suite's records to a finished or unrelated suite. A wrong block is worse than the console.
- Printing results directly to stdout: keeps time order, but parallel suites interleave line by line.
- Capturing `System.out`/`System.err`: process-global, cannot be split per suite.

## Consequences

- Logs from servers' worker threads or `Future`s on the global pool still print directly to the console.
- Scala.js keeps its async `execute` without routing. On Native, bodies run on the global pool, so few records are routed.
- Test code that calls `Logger.setDefaultHandler`/`Logger.init` or clears root handlers turns routing off for the rest of the run.
