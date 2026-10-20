# Rx.take(n) completes right after the nth value

## Context

`take(n)` sent `OnCompletion` only when an (n+1)th `OnNext` arrived. On a source that goes quiet after n values, such as an `RxVar`, it never completed, so it hung `flatMap`/`transformRx` chains and UniTest Rx bodies (see `2026-10-14-rx-completion-semantics.md` and `2026-10-19-unitest-rx-empty-completion.md`). Returning `Stop` is not enough to end such a source: `RxVar` ignores it, and `Rx.single` and `SeqOp` in continuous mode still emit afterwards.

## Decision

- `take(n)` emits `OnCompletion` right after delivering the nth value, unless the downstream already returned `Stop`. It then cancels the upstream itself and drops any later event behind an `AtomicBoolean`. Because a synchronous source delivers values before `run(in)` returns its `Cancelable`, the cancel is repeated after `run` returns.
- An upstream `OnError` or `OnCompletion` before n values also ends `take` and cancels the upstream. Errors stay terminal, even in continuous mode.
- `take(n <= 0)` completes immediately and never subscribes.
- `RxVar.propagateEvent` iterates over a snapshot of its subscribers, still under its lock. Cancelling a subscription from inside an update otherwise mutates the `ArrayBuffer` during `foreach`, which throws `ConcurrentModificationException`.

## Consequences

- `take(n)` streams complete earlier but emit the same values. `filter(p).take(1)` is now a valid way to await the first matching value; `uni-dom-test`'s `awaitValue` uses it with `timeout`.
- During an update, an `RxVar` still delivers that update to a subscriber that another subscriber cancelled in the same pass. `take` and `flatMap` guard against this themselves.
- When the downstream returns `Stop` before the nth value (for example `runOnce`), `take` forwards nothing more but does not cancel the upstream. `runOnce` relies on the upstream's own completion to drive `concat` cleanup, so an `RxVar` subscription there is released only when the caller cancels.
