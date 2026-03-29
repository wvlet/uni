# Future Async Test Support for UniTest

## Goal

Add native `scala.concurrent.Future` support to unitest so that tests returning `Future[T]` are automatically awaited, mirroring the existing `RxOps[?]` pattern.

## Design

### Pattern
Follow the existing Rx async test pattern:
1. `executeTest` in `UniTest.scala` detects `Future[?]` return type
2. Delegates to platform-specific `compat.runFutureTest[A](future: Future[A]): A`

### Platform implementations
- **JVM**: `Await.result(future, timeout)` with default 30s timeout
- **Native**: `Await.result(future, timeout)` with default 30s timeout
- **Scala.js**: Check `future.value` for already-completed futures; for pending futures, throw a clear error (JS cannot block)

### Changes
1. `UniTest.scala` — Add `Future[?]` case in `executeTest` match, add `awaitFuture` helper
2. JVM `compat.scala` — Add `runFutureTest` using `Await.result`
3. Native `compat.scala` — Add `runFutureTest` using `Await.result`
4. JS `compat.scala` — Add `runFutureTest` using `future.value`
5. `UniTestSelfTest.scala` — Add Future success/failure test cases
