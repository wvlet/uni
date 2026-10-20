# Rx.take(n) honours a downstream Stop before the nth value

## Goals

- Close the known limitation left by #721: when the downstream returns `Stop` on value k < n, `take` returns `Stop` but stays open. An `RxVar` upstream ignores `Stop`, so `take` keeps forwarding later values (and the upstream's completion) to a downstream that already stopped, and its upstream subscription is never released.
- Settle how far `take` should go: stop forwarding only, or also cancel the upstream.

## Background

- `take(n)` (`RxRunner` `case TakeOp`) already sets `done` and cancels its upstream after the nth value, on upstream `OnError`/`OnCompletion`, and drops late events (#721, `adr/2026-10-19-rx-take-completion.md`).
- The `Stop` protocol is advisory: operators pass the downstream's `RxResult` upstream and sources such as `SeqOp` and `IntervalOp` honour it, but `RxVar` ignores it. No other operator cancels its upstream on a downstream `Stop`.
- `RxRunner.runOnce` returns `Stop` on the first event and is documented *not* to cancel the upstream, so a source's own completion still drives `concat` cleanup (for example `startAndAwait { ... }.concat(cleanup)` stopping the server; see `plans/2026-10-14-rx-filter-completion.md`). UniTest Rx bodies, `RxFiber` and `RxParallel` run through `runOnce`.

## Design

Lane: **Standard**. The change is small, but the choice below changes when upstream cleanup runs under `runOnce`.

Recommended (A): when the downstream returns `Stop` before the nth value, set `done` so `take` forwards nothing more, and return `Stop`. Do **not** cancel the upstream.
- The downstream sees no event after it asked to stop, which matches the guarantee `take` already gives after completion.
- The upstream keeps running to its own completion, so `concat` cleanup under `runOnce` behaves as it does without `take`.
- An `RxVar` subscription stays registered until the caller cancels the returned `Cancelable`. This is the same for every other operator under `runOnce`.

Tests (shared `RxTest`):
- `RxVar.take(3)` where the downstream returns `Stop` on the first value: later `v := ...` updates and `v.stop()` deliver nothing more.
- `Rx.sequence(...).concat(cleanupRx).take(5)` under `runOnce`: the first value is delivered and the concat cleanup still runs.

## Alternatives and Why Not?

- (B) Also cancel the upstream on a downstream `Stop`. This releases the `RxVar` subscription immediately, but it contradicts the `runOnce` contract: with `take` downstream of a `concat(cleanup)`, any `runOnce`/UniTest caller would skip the cleanup. Fixing subscription release belongs in the `Stop` protocol or in `RxVar` itself, which is a wider redesign.
- Leave it as is: downstreams already ignore late events (`runOnce` dedups), so the only cost is the late forwarding and the held subscription. Cheap to fix, so not worth leaving open.
