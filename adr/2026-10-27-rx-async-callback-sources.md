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
- `register` returns a `Cancelable` that unregisters the waiter, and the callback returns whether the result was delivered. Cancelling a run first marks it done, then unregisters, so every interleaving is covered: a waiter still queued is removed; a waiter already woken but not yet delivered gets `false` and hands back what it won (a semaphore releases its permits; a queue gives the element to the next taker or the front of the queue). A new waiting primitive must do the same.
- After removing a cancelled `acquireN` waiter, the semaphore re-checks the waiters behind it, since a smaller request may now fit.
- Returned queue elements may push the queue over `capacity` until they are taken (waiting offerers are admitted only below capacity); keeping element order was preferred over the strict bound.
- A waiting `offer` cancelled before its element is accepted never enqueues it. Once accepted, the offer stands even if its run was cancelled, because the element may already be taken.
- `RxDeferred.get` and `RxFiber.join` remove their callback on cancel; there is nothing to hand back. Cancellation reaches these waiters through `timeout` and `RxParallel.race` (which cancels losing fibers), since both cancel their input run.
