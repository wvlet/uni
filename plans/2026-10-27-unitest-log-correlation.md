# UniTest: keep log messages next to test results, and stop counting skips as failures

## Goals

- When a test fails in CI, the log messages it emitted should appear right above its `- name: ...` line, inside its suite block, so a reader can match a failure with its error output.
- A suite that skips or cancels itself in `beforeAll` should not show `1 failed` in its class summary.

## Background

From a td-llm-proxy CI log (sbt, suites in parallel):

- sbt buffers what a test framework writes to the `sbt.testing.Logger`s it hands each task, and flushes the buffer when the suite ends, so parallel suites don't interleave. UniTest writes the class header, every `+`/`-`/`x` line and the summary there.
- uni's log records go through the root `java.util.logging` handler (`ConsoleLogHandler`) straight to stderr, outside that buffer. So every log line of a suite prints *before* its class header, mixed with other suites' logs, and a failure block can't be matched with its logs:

  ```
  2026-10-27 23:14:31Z warn [FailoverBedrockClientProvider] Switched to AWS account: second
  ...
  [info] ...FailoverBedrockClientProviderTest:
  [info]   + constructor: requires at least one client
  ```

- `UniTestTask.runTests` adds `classFailed += 1` for any exception in `beforeAll`/`afterAll`, after `handleSpecLevelError` already classified it. A `cancel(...)` in `beforeAll` prints `! Suite: cancelled - ...` and then `1 tests, 0 passed, 1 failed` in red. The run total was already right (it uses the classified result).
- A skip thrown while constructing the suite prints `~ Suite: skipped - ...` with no class header, so it reads as part of the previous suite.

## Design

Lane: **Standard** — changes what UniTest prints during `sbt test` for every user.

1. **Route suite-thread logs to the sbt loggers** (`spi/TestLogCapture`, new):
   - At runner start, wrap the root logger's handlers in a routing handler (once; nested runners reuse it). `done()` restores the original handlers if this runner installed them.
   - The blocking `UniTestTask.execute` (JVM, Native) sets a `ThreadLocal` to the task's loggers around the suite, restoring the previous value afterwards.
   - A record arriving on a thread with loggers set is formatted with `SourceCodeLogFormatter` (the JVM console default) and sent to `error`/`warn`/`info` by level; otherwise it goes to the original handlers, as today.
   - On the JVM, suite construction, hooks and test bodies all run on the sbt task thread (ADR 2026-10-27-unitest-suite-thread), so this covers logs from test code and code it calls synchronously. Logs from other threads (a server's worker pool, `Future` on the global pool) still go to stderr directly.
   - Scala.js keeps its current behaviour (async `execute`, single thread, JS console handler).
2. **Count hook-level skips by kind**: `handleSpecLevelError` returns the classified `TestResult`, and `runTests` counts it with the same helper it uses for test results (skipped → skipped; pending/cancelled/ignored → pending; others → failed).
3. **Header for constructor-level outcomes**: print `ClassName:` before the `~`/`!`/`x` line when the suite can't be constructed.
4. Tests: a JVM test drives the runner in-process on a fixture suite that logs and fails, and checks the recorded logger lines are in order (header, log line, failure line). A fixture that cancels in `beforeAll` checks the summary says `0 failed`.

## Alternatives and Why Not?

- **`InheritableThreadLocal`, to also catch threads started by a test**: pool threads outlive the suite that created them and would send a later suite's logs to the wrong block. Wrong attribution is worse than none.
- **Print result lines straight to stdout instead of via the sbt loggers**: keeps time order, but parallel suites then interleave line by line, which is worse than today.
- **Capture `System.out`/`System.err`**: process-global, can't be split per suite when suites run in parallel.
- **Add a `cancelled` bucket to the summary**: the existing summary groups pending/cancelled/ignored as `pending`; changing that format is a separate decision.
