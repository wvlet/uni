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
- Test body executes synchronously (registering nested tests as side effects); only the return value may be async

### Why this matters on Scala.js

Previously, `continuation(Array.empty)` was called in a `finally` block before async Futures completed. Now it's called inside `.foreach` on the Future chain — the sbt test runner is notified only after all test work (including async Futures) finishes.

### executeTest: Future[TestResult]

The `executeTest` method in `UniTest.scala`:
- Runs the test body synchronously via `runWithContext`
- Pattern-matches the return value:
  - `Future[?]` → chains with `.transform(toTestResult)`
  - `RxOps[?]` → converts to Future via `compat.runRxAsFuture`, then transforms
  - sync value → `Future.successful(TestResult.Success(...))`
- Synchronous exceptions are caught and wrapped in `Future.successful(classifyException(...))`
- A shared `toTestResult` function deduplicates the Success/Failure transform logic

### runTests: Future[Unit] with recursive processQueue

The synchronous `while testQueue.nonEmpty` loop is replaced with a recursive `processQueue()` function:
- Returns `Future.unit` when queue is empty
- Calls `executeTest(testDef)` which returns `Future[TestResult]`
- In `.flatMap`, reports results, tracks stats, discovers nested tests, then recurses
- After queue completes, `.map` logs per-class summary with timing

### execute: continuation in .foreach

```scala
Future(compat.newInstance(className, testClassLoader))
  .flatMap(testInstance => runTests(...))
  .recover { case e => handleSpecLevelError(...) }
  .foreach { _ => continuation(Array.empty) }
```

### Platform compat: runRxAsFuture

- **JVM/Native**: `Future(rx.await)(executionContext)` — blocking inside Future
- **JS**: `Promise[Any]` + `RxRunner.runOnce` callbacks (non-blocking)
- No `runFutureTest` or `runRxTest` — Futures flow naturally through the pipeline

### UniTestEngine (JVM-only JUnit integration)

Uses `Await.result(instance.executeTest(testDef), 30.seconds)` — JUnit engine only runs on JVM where blocking is fine. Uses 30s timeout to prevent hangs.

## Files changed

1. `UniTest.scala` — `executeTest` returns `Future[TestResult]`, `classifyException` extracted, `toTestResult` deduplicates transform
2. `UniTestTask.scala` — `runTests` returns `Future[Unit]`, recursive `processQueue()`, continuation in `.foreach`, integrated with TestStats/timing
3. JVM/JS/Native `compat.scala` — `runRxAsFuture` replaces `runRxTest`/`runFutureTest`
4. `UniTestEngine.scala` — `Await.result` with 30s timeout around `executeTest`
5. `UniTestSelfTest.scala` — Tests adapted for Future-returning `executeTest`
