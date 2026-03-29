# Future Async Test Support for UniTest

## Goal

Add native `scala.concurrent.Future` support to unitest with a full Future-based async execution pipeline, matching AirSpec's proven pattern.

## Design

### Architecture: Full Future-based pipeline

The entire test execution chain is Future-based:
- `executeTest` returns `Future[TestResult]` (not `TestResult`)
- `runTests` chains tests with recursive `processQueue()` using `Future.flatMap`
- `execute(..., continuation)` calls `continuation` inside `.foreach` after all async work completes
- Rx streams are converted to Future via platform-specific `runRxAsFuture`

### Why this matters on Scala.js

Previously, `continuation(Array.empty)` was called in a `finally` block before async Futures completed. Now it's called inside `.foreach` on the Future chain — the sbt test runner is notified only after all test work (including async Futures) finishes.

### Platform implementations

- **JVM/Native**: `runRxAsFuture` wraps `rx.await` in a `Future { ... }`
- **Scala.js**: `runRxAsFuture` uses `Promise[Any]` + `RxRunner.runOnce` callbacks
- **UniTestEngine (JVM-only)**: Uses `Await.result` on `executeTest` for JUnit compatibility

### Files changed

1. `UniTest.scala` — `executeTest` returns `Future[TestResult]`, `classifyException` extracted
2. `UniTestTask.scala` — `runTests` returns `Future[Unit]`, continuation in `.foreach`
3. JVM/JS/Native `compat.scala` — `runRxAsFuture` replaces `runRxTest`/`runFutureTest`
4. `UniTestEngine.scala` — `Await.result` around `executeTest`
5. `UniTestSelfTest.scala` — Tests adapted for Future-returning `executeTest`
