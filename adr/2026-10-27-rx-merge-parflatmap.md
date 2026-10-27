# Rx merge and parFlatMap run in RxRunner through a shared FanIn

## Context

`MergeOp` and `ParFlatMapOp` were added with the concurrency primitives (#317), but `RxRunner` had no case for them, so `merge`, `parFlatMap`, and `parMapN` threw `MatchError` when run. Both ops are declared in `uni-core`, where `RxRunner` lives, while `RxSemaphore` and `RxScheduler` are in `uni`; the runner cannot use them (the same module-direction constraint as `Rx.AsyncOp`).

## Decision

- Both cases use a private `FanIn` in `RxRunner`: a lock-guarded set of running slots (one per subscribed stream). Events are forwarded under the lock, because the streams can emit from different threads. The operator completes when the last slot ends. The first error, a downstream `Stop`, or cancellation stops it, cancels every running slot, and drops later events.
- `MergeOp` opens all slots before subscribing, so a source that completes synchronously cannot complete the merge early.
- `ParFlatMapOp` keeps an input slot open until the input completes, and counts active nested streams under a separate lock. Values over the limit wait in an unbounded queue: `Rx` sources cannot be paused, so there is no backpressure. A finishing nested stream starts the next queued value before ending its slot, so the slot set never goes empty in between.
- The runner adds no threads. `parFlatMap` overlaps only nested streams that are asynchronous. `parMapN` (in `uni`) wraps each `f(a)` in `Rx.single(f(a)).start.flatMap(_.join)` so it really runs on `RxScheduler.default`; results arrive in completion order.
- `Rx.parallel` is an extension on `Rx.type` in `uni` that returns `RxParallel`, because `object Rx` in `uni-core` cannot refer to it.

## Consequences

- `merge`, `parFlatMap`, and `parMapN` work on JVM, Scala.js, and Native (shared `RxMergeTest`; the JVM `RxParallelTest` checks the parallelism bound).
- `RxParallel.race`, `parSequence`, `parTraverse`, and `parZip` still block with `Rx.await` inside `Rx.single`, so they throw on Scala.js. Making them non-blocking is a separate change.
- Downstream code runs under the `FanIn` lock, like other operators that serialize concurrent inputs; it must not block waiting on another stream of the same operator.
