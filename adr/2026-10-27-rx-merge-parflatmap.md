# Rx merge and parFlatMap run in RxRunner through a shared FanIn

## Context

`MergeOp` and `ParFlatMapOp` were added with the concurrency primitives (#317), but `RxRunner` had no case for them, so `merge`, `parFlatMap`, and `parMapN` threw `MatchError` when run. Both ops are declared in `uni-core`, where `RxRunner` lives, while `RxSemaphore` and `RxScheduler` are in `uni`; the runner cannot use them (the same module-direction constraint as `Rx.AsyncOp`).

## Decision

- Both cases use a private `FanIn` in `RxRunner`: a lock-guarded set of running slots (one per subscribed stream). Events are forwarded under the lock, because the streams can emit from different threads. The operator completes when the last slot ends. An error the downstream recovers from (`recover` returns `Continue`) ends only the failing slot, as `NestedStreamTracker` does for `flatMap`; otherwise the operator would never complete. An unrecovered error, a downstream `Stop`, or cancellation stops it, cancels every running slot, and drops later events. In continuous mode errors and `Stop` are transient (as in `TakeOp`), so only cancellation stops it.
- `MergeOp` opens all slots before subscribing, so a source that completes synchronously cannot complete the merge early.
- `ParFlatMapOp` keeps an input slot open until the input completes, and counts active nested streams under a separate lock. Values over the limit wait in an unbounded queue: `Rx` sources cannot be paused, so there is no backpressure. A finishing nested stream opens the slot of the next queued value before ending its own, so the slot set never goes empty in between. Nested streams are subscribed from a single drain loop, not recursively: synchronous nested streams complete inside their subscription, and recursion would add a stack frame per queued value.
- The runner adds no threads. `parFlatMap` overlaps only nested streams that are asynchronous. `parMapN` (in `uni`) wraps each `f(a)` in `Rx.single(f(a)).start.flatMap(_.join)` so it really runs on `RxScheduler.default`; results arrive in completion order. Cancelling drops the results of running calls but cannot interrupt them.
- `Rx.parallel` is an extension on `Rx.type` in `uni` that returns `RxParallel`, because `object Rx` in `uni-core` cannot refer to it.

## Consequences

- `merge`, `parFlatMap`, and `parMapN` work on JVM, Scala.js, and Native (shared `RxMergeTest`; the JVM `RxParallelTest` checks the parallelism bound).
- `RxParallel.race` and `parSequenceN` (and so `parSequence`, `parTraverse`, `parZip`) are built on these operators instead of blocking with `Rx.await` inside `Rx.single`, so they run on Scala.js. `parSequenceN` is `parFlatMap` over the indices, with each computation started as a fiber; the old version's `sem.acquire.run()` returned before the permit arrived and so did not bound parallelism. Each computation is wrapped in `RxParallel.forked`, an `AsyncOp` that starts a fiber on subscription and, when cancelled, first unregisters its `join` and then cancels the fiber. The order matters: a cancelled fiber wakes its joiners with `InterruptedException`, which must not reach the stream. So whatever cancels `merge`/`parFlatMap` (the first error, `take(1)` in `race`, or a cancelled subscription) also cancels computations that are already running, as far as they can be interrupted. `race` is `merge(rxs.map(forked)).take(1)`.
- Downstream code runs under the `FanIn` lock, like other operators that serialize concurrent inputs; it must not block waiting on another stream of the same operator.
