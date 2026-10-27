# Callback-based Rx sources run through `Rx.AsyncOp`

## Context

`RxDeferred.get`, `RxFiber.join`, `RxSemaphore.acquire`, and the waiting forms of `RxBoundedQueue.offer`/`take` (added in #317) wait for another party to complete them. Each returned an anonymous `Rx` with a private register method that `RxRunner` was meant to handle "specially", but `RxRunner` lives in `uni-core` and never got a case for them, so running one threw `MatchError`. Tests skipped them. The JVM async HTTP client is built on `RxDeferred.get`, so `Http.client.newAsyncClient.send(...)` could not be consumed with `run`, `subscribe`, or `await`. `RxRunner` cannot match on these types because `uni` depends on `uni-core`, not the other way around.

## Decision

- `uni-core` defines `private[rx] case class Rx.AsyncOp[A](register: (Try[A] => Unit) => Unit, name: String)`. `RxRunner` runs it by calling `register` once per run with a callback that emits `OnNext` + `OnCompletion`, or `OnError`.
- The callback delivers at most one result and nothing after the run is cancelled (an `AtomicBoolean` guard). It may be called synchronously inside `register` (already completed) or later from any thread.
- The five waiting operations build `AsyncOp` around their existing lock-free register loops. `private[rx]` is visible across the module boundary because both modules use the package `wvlet.uni.rx`.

Rejected: moving the primitives into `uni-core` (bigger move; `RxScheduler`/`RxFiber` have platform parts in `uni`), a public `Rx.async` (more API surface; `AsyncOp` can be made public later), and building on `RxVar` like `Rx.future` (continuous updates and `stop()` handling do not fit one-shot semantics).

## Consequences

- Any new waiting primitive in `wvlet.uni.rx` should return `Rx.AsyncOp` instead of an anonymous `Rx`. An anonymous `Rx` with no `RxRunner` case fails at run time with `MatchError`.
- Cancelling a run stops delivery, but it does not remove the registered waiter. A cancelled `acquire` or `take` can still consume a permit or element when it completes.
