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
- Amended 2026-11-18: `recover` returns the downstream's answer to the recovered value instead of always `Continue`. Before, an operator after `recover` that failed on the recovered value did not stop the source, so the sink received events after `OnError` (`map(throw on 2).recover(_ => 0).map(throw on 0)` over `1 2 3` emitted `1 E 3 C`). The Lean model in `spec/lean/UniSpec/RxRecover.lean` and its 3-operator cases cover it.
- Amended 2026-11-20: a new input value cancels the previous nested stream of `flatMap` and `transformRx` before `f` runs, so a throwing `f` also switches away from it. Before, the replaced nested `RxVar` stayed subscribed and kept emitting after the `OnError`, and the input's completion waited for it forever. The tracker now drops the cancelled stream, so the input's completion is reported at once.
- Amended 2026-11-20: `zip` completes once a completed source has no buffered value left, so its shortest source ends it, and the check also runs right after a value is paired. Before, it completed once every source had delivered any event, values included. Each `RxVar` delivers its current value on subscription, so `zip` over vars in default mode emitted its first pair and completed at once; tests used `runContinuously`, which hid it. After completing, `zip` and `join` ignore later events, so a live source no longer fills a buffer that can never be paired. `join` still completes when every source completed. The Lean model in `spec/lean/UniSpec/RxLive.lean` found both.
- Amended 2026-11-20: outside continuous mode, an error that `recoverWith` recovers ends its input, as an input error does for `transformRx`. The input is cancelled, later input events are dropped, and the operator ends with the recovery stream. Before, the input stayed subscribed: an error that does not end its source (a throwing `map` over an `RxVar`) let later input values reach the sink after the recovery stream, and the sink could get two `OnCompletion`s. RPC server streaming (`2026-11-06-rpc-server-streaming.md`) relies on the recovery ending the stream, and now no input event can follow it. In continuous mode the input stays subscribed, and its next value cancels the recovery stream. The Lean model in `spec/lean/UniSpec/RxLive.lean` found it.
