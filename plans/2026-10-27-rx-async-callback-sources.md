# Run callback-based Rx sources (RxDeferred, RxFiber, RxSemaphore, RxBoundedQueue)

## Goals

- Make `RxDeferred.get`, `RxFiber.join`, `RxSemaphore.acquire`/`acquireN`, and the waiting forms of `RxBoundedQueue.offer`/`take` runnable with `run`, `subscribe`, and `await`.
- Fix the JVM async HTTP client: `Http.client.newAsyncClient.send(...)` is built on `RxDeferred.get`, so any consumer fails with `MatchError: RxDeferred.get`.

## Background

- #317 added these primitives in `uni` (package `wvlet.uni.rx`). Each waiting operation returns an anonymous `new Rx[A]` with a `private[rx] registerCallback`-style method, and comments say "handled specially by RxRunner".
- `RxRunner` lives in `uni-core` and pattern-matches on concrete op types. It never got a case for these, so running one throws `MatchError`. `RxConcurrencyTest` notes that these "require async callback support" and does not run them.
- `RxRunner` cannot depend on `uni` types (module direction is `uni` → `uni-core`), so it cannot match on `RxDeferred` itself.

## Design

**Lane: Standard** — touches core Rx execution; scope is a bug fix with no public API change.

- Add `private[rx] case class AsyncOp[A](register: (Try[A] => Unit) => Unit, name: String)` to `object Rx` in `uni-core`. `register` is called once per run with a completion callback; it may complete synchronously or later from another thread.
- `RxRunner` runs `AsyncOp` by calling `register` with a callback that delivers `OnNext` + `OnCompletion` or `OnError`, at most once, and not after the returned `Cancelable` is cancelled.
- Rebuild the five waiting operations on `AsyncOp`, reusing their existing lock-free register loops unchanged.
- `private[rx]` works across modules because both are in package `wvlet.uni.rx`, so there is no public API change.
- Tests: run each primitive in `RxConcurrencyTest` (immediate and delayed completion, errors, cancel), and add a JVM async HTTP client round trip.

## Alternatives and Why Not?

- **Move the primitives into `uni-core`.** Larger diff, and `RxScheduler`/`RxFiber` have platform-specific parts in `uni`.
- **A public `Rx.async` constructor.** Useful, but adds API surface; it can be exposed later by making `AsyncOp` public.
- **Make `RxDeferred.get` an `RxVar` like `Rx.future`.** `RxVar` emits updates continuously and needs `stop()` handling; it does not fit the one-shot semantics of semaphores and queues.
