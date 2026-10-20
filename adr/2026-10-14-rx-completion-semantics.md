# Rx completion semantics: `OnCompletion` is terminal

## Context

`RxRunner` inherited two airframe-rx behaviors that made `OnCompletion` ambiguous:

- `FilterOp` emitted `OnCompletion` for every rejected value while keeping its upstream subscription. Its likely purpose: a rejected value from a source that never completes on its own (an `RxVar`, or `Rx.const`, which emitted no `OnCompletion`) could still end a one-shot `runOnce`/`await`.
- `FlatMapOp` forwarded its input's `OnCompletion` as soon as it arrived, even while a nested stream was still running.

`Rx.future` is `filter(_.isDefined)` over an `RxVar[Option[A]]`, so every pending future produced a spurious completion. Each consumer that took `OnCompletion` at face value was wrong:

- uni-test's `runRxAsFuture` and `compat.await` ended async tests before their assertions ran. The Scala.js tests passed vacuously.
- `NodeHttpServer.runResponse` answered async handlers with 404.
- `concat` and `lastOption` ran their completion step early. For example, `startAndAwait`'s `concat(cleanup)` stopped the server while the test block was pending.

Separately, `runOnce` returned `Stop` after the first event, but `RxVar` and `Rx.single` ignore `Stop`. So `runOnce` callers also received a second event: the real `OnCompletion` after `OnNext`. That is why Netty's `runRxResponse` needed an `AtomicBoolean` guard.

## Decision

`OnCompletion` means the stream has ended, as in standard Rx:

- `filter` skips rejected values and reports only the upstream's completion.
- `Rx.const` / `Rx.fromTry` (`TryOp`) emit `OnCompletion` after their value, as `Rx.single` does. A rejected constant therefore still terminates. A filtered `RxVar` waits for a matching value, because the variable can still change.
- `flatMap` completes only when both its input and the current nested stream have terminated. A nested `OnError` also terminates the nested stream, because a failed `Rx.future` emits no `OnCompletion`. If the downstream recovered from the error, `flatMap` completes there. The state is guarded by a lock because, on the JVM, the two sides may terminate on different threads.
- `runOnce` delivers only the first event to its effect. It does not cancel the upstream, so a source's own completion still drives `concat` cleanup inside the chain.

The alternatives considered, fixing only `Rx.future` or only `runOnce`, are compared in `plans/2026-10-14-rx-filter-completion.md`. Fixing only `Rx.future` would leave every other `filter` broken. A fix in `runOnce` alone cannot tell a spurious completion from a real one.

## Consequences

- This is a breaking change. Code that relied on filter-rejection completing a stream behaves differently, for example a `runOnce`/`await` over a filtered `RxVar` that used to resolve to `()`. `Rx.const(...).concat(...)` now proceeds to the second stream.
- Async tests on Scala.js now assert for real. The first run surfaced a missing half-open `'end'` handler in `NodeWebSocket`, which is fixed.
- The Netty `runRxResponse` `AtomicBoolean` guard is now redundant. It can be removed in a follow-up.
- `transformRx` (`TransformRxOp`) follows the same rule as `flatMap`; both share `NestedStreamTracker`. An input `OnError` that `f` turns into a nested stream counts as the input's termination, so the operator completes when that nested stream ends (except in continuous mode, where an `RxVar` may emit again).
- The `filter` caveat in `2026-06-20-background-task.md` (`progressStream` uses `flatMap`) no longer applies. The `flatMap` form still works.
