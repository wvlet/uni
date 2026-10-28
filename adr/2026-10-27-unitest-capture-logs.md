# UniTest captures log records of error-path tests instead of silencing them

## Context

Tests that make code fail on purpose (a handler that throws, an invalid request) filled `projectJVM/test` output with warnings and stack traces. #804 silenced them with `Logger.of[X].suppressWarnings { ... }`, which needs internal logger names found by grepping the output, asserts nothing, and lowers a JVM-wide log level while suites run in parallel. The warnings come from server worker threads, which `TestLogCapture` routing (see `2026-10-27-unitest-log-capture.md`) does not cover.

## Decision

- `UniTest` mixes in `OutputCapture`: `captureLogs { ... }` returns a `CapturedLogs` (`warnings`/`errors`/`messages` as newline-joined text, so `shouldContain` is a substring check, plus `records`). `captureStdout`/`captureStderr` return text through `Console.withOut`/`withErr`.
- `captureLogs` shares `TestLogCapture`'s root handler. The handler is reference-counted: a runner with routing on and every open capture acquire it, and the last release unwraps it with the existing `Supplier` mechanism. Log levels are never changed.
- Which capture owns a record:
  - a record from the thread that opened a capture goes to the innermost capture of that thread that accepts its logger;
  - a record from a thread running a suite (`UniTestTask.execute` marks it with a `ThreadLocal`, routing on or off) is never taken by another thread's capture;
  - a record from any other thread (server workers, `Future` pools) goes to every open capture that accepts its logger.
- `captureLogs[A]` scopes by the logger name `LogSupport` uses for `A` on the platform (`Logger.loggerNameOf`, `private[uni]`). One inline method serves both forms: an omitted type argument is inferred as `Any`, which means every logger. Overloading `captureLogs(body)` with `captureLogs[A](body)(using ClassTag[A])` is ambiguous.
- If the block throws, captured records are published again after the capture closes, so an enclosing capture takes them, or they print as if there had been no capture. A record that a worker thread logs after the capture closed is not dropped: the closed capture refuses it and it prints. A block that returns a `Future` or `Rx` fails, since its assertions would run after the capture ends.

## Why not

- Capture every thread and print records nobody asserted at the end: knowing which records a test matched needs assertion tracking that a `String` `shouldContain` cannot provide, and records printed at the end appear apart from the suite that emitted them.
- Scope strictly by logger name: brings back the need to know internal logger names.
- Redirect `System.out`/`System.err`: process-global, would capture other suites' output.

## Consequences

- During an unscoped capture, a record that an unrelated parallel suite logs from a pool thread can land in the capture and not print. `captureLogs[A]` avoids it; assertions that a capture is empty can be flaky for the same reason.
- Scala.js has one thread, so every record is the opener's. On Native, test bodies run on the global pool, so records count as unattributed and still reach the capture.
- Scala.js names the logger of a nested class after its outer class, so `captureLogs[A]` for a nested `A` also captures its siblings there.
