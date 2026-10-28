# UniTest: test error paths quietly with captureLogs / captureStdout / captureStderr

## Goals

- Let a test that triggers an error on purpose keep the expected warnings and stack traces out of the test output, and assert that they were logged.
- Replace the #804 idiom (`Logger.of[X].suppressWarnings { ... }`, ad-hoc `Console.withOut`), which:
  - needs internal logger class names (`NettyRequestHandler`, `RPCDispatcher`, `HttpAccessLogFilter`) that a test author finds only by grepping the output,
  - asserts nothing: the test still passes if the error stops being logged,
  - changes a logger level for the whole JVM during the block, which hides or races with suites running in parallel,
  - leaves stdout/stderr capture to each test.
- Settle the scoping question: what an unscoped `captureLogs` takes from other threads, and what happens to records it shouldn't own.

## Background

- `TestLogCapture` (ADR `2026-10-27-unitest-log-capture.md`) wraps the root `java.util.logging` handlers in a `RoutingHandler` and sends records from the suite thread to sbt's loggers. It is installed only when routing is on (CI or `--log-capture`), and it routes only the suite thread; records from server workers and pool threads go to the console. The Netty warnings in #804 came from worker threads.
- On the JVM the suite runs on its sbt task thread (ADR `2026-10-27-unitest-suite-thread.md`), so "the thread running the block" identifies a suite reliably. Pool threads cannot be attributed to a suite.
- Handler wrapping must survive several runners in one sbt JVM, each with its own class loader. The existing router solves this with a `java.util.function.Supplier` unwrap; the new handler reuses that mechanism.
- Scala.js has no threads; Scala Native runs bodies on its global pool. `java.util.logging` and `Console.withOut` exist on all three platforms.

## Design

Lane: **Standard** — new public UniTest API.

API, mixed into `UniTest` (so it needs no import in a suite):

```scala
val logs = captureLogs {                          // any logger, any thread
  server.start { s => get(s"${s.url}/error").statusCode() shouldBe 500 }
}
logs.warnings shouldContain "Error in Rx handler" // String: WARN messages, one per line
logs.errors shouldBe ""                           // String: ERROR messages
logs.records                                      // Seq[LogRecord], for structured checks

val logs2 = captureLogs[RPCDispatcher] { ... }    // only loggers named X or its children

val out = captureStdout { Launcher.of[GitHubCli].execute(Array("--help")) }
out shouldContain "usage: uni-gh"
val err = captureStderr { ... }
```

Key points:

- **Capture handler**: on entry, `captureLogs` registers a `Capture` in `TestLogCapture` and makes sure a capturing wrapper is on the root handlers (ref-counted install, the same Supplier unwrap as the router, so it works with routing on or off and under the JUnit engine). On exit it unregisters and, when the last capture ends, unwraps itself. Log levels are never changed.
- **Who gets a record** (the open question) — recommendation:
  - A record from the thread that opened the capture goes to that capture.
  - A record from a thread that is running *another* suite (marked by a ThreadLocal the runner sets around every suite, routing on or off) is never captured; it goes to the normal handlers. So parallel suites keep their own output.
  - A record from an unattributed thread (server workers, `Future` pools) goes to every active capture whose logger scope matches. Several captures can hold the same record; nothing is lost because...
  - **...when the block throws**, the captured records are printed through the original handlers, so a failing test still shows its logs. When it returns normally, they are only returned.
  - Trade-off: during an unscoped capture, a pool-thread record of an unrelated parallel suite can be swallowed. `captureLogs[X]` avoids that, and the docs recommend it for long blocks.
- **`captureLogs[X]`** scopes by logger name prefix (`X`'s class name), from any thread. Only the API shape is decided; if Scala 3 overloading `captureLogs { }` vs `captureLogs[X] { }` turns out ambiguous, the scoped form becomes `captureLogsOf[X]`.
- **`captureStdout` / `captureStderr`** use `Console.withOut` / `Console.withErr` (thread-inheritable, not process-global) and return the text. Writes to `System.out`/`System.err` directly from Java code are not captured; documented.
- **Bodies are synchronous**: `captureLogs` returns after the body returns. Records a server logs after the response (e.g. in a completion callback) can arrive late; the server-test helpers stop the server inside the block, so its records land before the block ends. A `Future`/`Rx` body is not awaited; documented.
- **Platforms**: same code on all three. On Scala.js every record is "own thread"; on Native, bodies run on pool threads, so all records are treated as unattributed (still captured).
- **Docs**: "Testing error paths" section in `docs/core/unitest.md` and `plugins/uni/skills/uni/references/testing.md` (snippets compiled by `docSnippets`); plugin version bump; update `docs/guide/for-ai-agents.md` if it lists UniTest helpers; amend the log-capture ADR's Consequences.
- **Migration** (in scope): replace the #804 `suppressWarnings` / `Console.withOut` sites in uni-netty tests, `MCPServerTest`, `LogLevelTest`, and `GitHubCliTest` with the new helpers, adding an assertion on the captured warning where #804 had none.

## Alternatives and Why Not?

- **Capture every record from every thread, print unmatched records at the end** (the leaning in the request): knowing which records a test "matched" needs assertion tracking that `shouldContain` on a `String` cannot provide, and records printed at block end appear apart from the suite that emitted them. Excluding other suites' threads gets the same protection without tracking.
- **Scope strictly by logger name** (no unscoped form): safe for parallel suites, but brings back the need to know internal logger names, which is the first problem we are fixing.
- **Lower the log level, as `suppressWarnings` does**: global and racy; asserts nothing.
- **Redirect `System.out`/`System.err`**: process-global, would capture other suites' output.
