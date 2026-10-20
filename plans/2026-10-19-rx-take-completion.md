# Rx.take(n) completes after the nth value

## Goals

- `rx.take(n)` should complete as soon as the nth value has been delivered, not when an (n+1)th event happens to arrive.
- Today `Rx.variable(1).take(1)` never completes, so it hangs inside `flatMap` / `transformRx` chains and in UniTest Rx bodies. PR #720 had to work around it with a Promise + timeout in `uni-dom-test/.../AwaitValue.scala`.

## Background

- `RxRunner` `case TakeOp(in, n)` forwards the first n `OnNext` events and sends `OnCompletion` only from the `else` branch, i.e. when the (n+1)th `OnNext` arrives. Sources that stop after n values without sending `OnCompletion`, or never emit again (`RxVar`), leave `take(n)` open forever.
- Returning `RxResult.Stop` does not unsubscribe every source: `RxVar` ignores it, and `SingleOp` / `SeqOp` (in continuous mode) still send `OnCompletion` afterwards. So the operator must cancel the upstream itself and drop any event that arrives after it completed.
- For a synchronous source such as `RxVar`, the first value is delivered inside `run(in)`, before its `Cancelable` is returned. The cancel has to be deferred until `run` returns (the same placeholder pattern `FlatMapOp` uses for `c1`).

## Design

Lane: **Express** — a bug fix with a clear diagnosis in one operator, no always-gate area. Merge still waits for the user's decision (the launch prompt gave no merge authorization).

- `n <= 0`: send `OnCompletion` immediately, do not subscribe to the upstream, return `Cancelable.empty`.
- Otherwise keep a `done` flag (`AtomicBoolean`) and a placeholder `Cancelable`:
  - `OnNext`: deliver it and increment the count. When the count reaches n, mark done, send `OnCompletion` (only if the downstream still wants events), cancel the upstream, and return `Stop`.
  - `OnError` / `OnCompletion` from upstream before done: mark done and forward.
  - Any event after done: drop it and return `Stop`.
  - After `run(in)` returns, cancel the upstream if done was reached synchronously.
- `RxVar.propagateEvent` iterates over a snapshot of its subscribers. Completing `take` during an `RxVar` update now unsubscribes from inside the variable's own `ArrayBuffer.foreach`, which threw `ConcurrentModificationException` (it surfaced as an uncaught JS error in the `WindowDimensions` resize test). The lock is kept, so propagation stays serialized.
- Update the `take` Scaladoc to say it completes right after the nth value.
- Tests in `uni/src/test/scala/wvlet/uni/rx/RxTest.scala` (shared JVM/JS/Native): `take(1)` on an `RxVar` completes after the first value; `take(n)` on a source with exactly n values completes once with no duplicate completion; `take(0)` emits nothing and completes; later `RxVar` updates are not observed (upstream unsubscribed).
- `AwaitValue` workaround: simplify to `rx.filter(p).take(1).timeout(...)` only if trivial and its tests pass; otherwise leave it.

## Findings from the check

- No other first/head-like operator exists. `LastOp` completes on upstream `OnCompletion`, which is correct for "last" semantics — not the same bug.
- Existing tests (`RxTest "take"` on `Rx.sequence`, `RxRunnerTest "take"` with an error, `IntervalTest` `interval.take(3)`) see the same values; they only get completion earlier. No caller in the repo relies on late completion.

## Alternatives and Why Not?

- Complete on the nth value but rely on `Stop` to end the upstream: does not work for `RxVar`, which ignores `Stop`, and leaves a leaked subscriber.
- Treat `n <= 0` by subscribing and completing on the first event: still hangs on silent sources; completing immediately is simpler and matches common Rx libraries.
