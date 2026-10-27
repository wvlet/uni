# Run Rx merge / parFlatMap and add Rx.parallel

## Goals

- `rx.merge(other)`, `RxParallel.merge(rxs)` (two or more sources), `parFlatMap`, and `parMapN` all throw `MatchError` when run: `RxRunner` has no case for `MergeOp` or `ParFlatMapOp`. #317 added the ops but not their runner cases. Make them work.
- Make `RxParallel` discoverable from `Rx`: `Rx.parallel.parTraverseN(...)` (user request on #771).

## Background

- The ops are declared in `uni-core` (`Rx.MergeOp`, `Rx.ParFlatMapOp`) and constructed from `uni` (`RxParallel.scala`). `RxRunner` lives in `uni-core`, so the runner cases cannot use `RxSemaphore` or `RxScheduler` (both in `uni`); the module-direction rule is the same one as in the 2026-10-27 `Rx.AsyncOp` ADR.
- Completion is terminal (2026-10-14 ADR): an operator reports `OnCompletion` once, after all of its inputs end, and nothing after it.
- `Rx` sources cannot be paused, so there is no backpressure on the input of `parFlatMap`.

## Design

Lane: Standard; the user approved the direction on #771 ("finish #771 first, then do the follow-up PR").

`RxRunner` (uni-core):

- `MergeOp(sources)`: subscribe to every source. Forward each `OnNext` under a lock, since sources can emit from different threads. Complete once every source has completed. The first `OnError` is forwarded, the other sources are cancelled, and later events are dropped. A downstream `Stop` cancels everything too.
- `ParFlatMapOp(input, n, f)`: for each input value, start `f(v)` if fewer than `n` inner streams are active; otherwise queue it. When an inner stream completes, start the next queued one. Complete when the input and every inner stream have completed. An error from the input, from `f`, or from an inner stream is forwarded once and cancels the rest. Output order is the order in which values arrive.
- The runner adds no threads. An inner `Rx` that runs synchronously runs on the caller's thread, so the concurrency comes from the inner streams.

`uni`:

- `parMapN(n)(f)` runs each `f(a)` on `RxScheduler.default` (`Rx.single(f(a)).start.flatMap(_.join)`), so it really runs in parallel, as its scaladoc promises. Results arrive in completion order.
- `extension (r: Rx.type) def parallel: RxParallel.type` in `RxParallel.scala`, available with `import wvlet.uni.rx.*`.

Tests: shared tests (they return `Rx`, so they also run on JS and Native) for merge order and completion, merge error, parFlatMap with synchronous inner streams, parFlatMap error, and `take` cancelling. A JVM test checks that at most `n` inner streams are active.

Docs: `docs/rx/concurrency.md` gets a section on parallel operators; the skill's `rx.md` lists `merge`, `parMapN`, and `Rx.parallel`; plugin bumped to 0.3.1.

Out of scope (reported): `RxParallel.race` and `parSequenceN` block with `.await` inside `Rx.single`, so they throw on Scala.js.
