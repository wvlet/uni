# RxRunner.run stops at its end

## Goals

- `RxRunner.run` delivers nothing after its terminal event, as its contract ("run until the first error or the end") says.
- A `run` that has ended stops holding its upstream subscription, so an `RxVar` no longer keeps the effect alive and `RxTracker` agrees with what is subscribed.

## Background

- `run` answers `Stop` to the terminal event, but `RxVar` ignores `Stop` and keeps emitting. So `run(n.map(throwOn3))` then `n := 2; 3; 4; 5` delivers `OnNext(1), OnNext(2), OnCompletion(Failure), OnNext(4), OnNext(5)`. `setException` followed by `n := 4` does the same.
- `runContinuously` got an `ended` guard in the R3 continuous step (`adr/2026-11-21-rx-continuous-r3.md`). `run` never did, though its contract became "first error or end" in #1071 (`adr/2026-11-21-rx-oncompletion-result.md`). So yes, this is the R3 follow-up that was left out.
- The Lean models already describe the fixed behaviour. `RxR3.lean` models default mode as cutting the trace at the first `fail` or `done`. `RxLive.lean` models the operator layer under a sink that answers `Stop` (its suite drives the `RxRunner` class directly, not the `RxRunner.run` entry point), and says events after a terminal one can still arrive unless something guards against them. The companion `run` is that guard, so neither model nor generated suite changes.
- `RxRunner.runOnce` deliberately keeps its upstream after its first event: that event is mid-stream, and the source's own completion still drives `concat` cleanup (`adr/2026-10-14-rx-completion-semantics.md`). `run`'s terminal event comes after the whole pipeline has ended, so nothing upstream is left to drive.
- `RxTracker` already counts a `run` as ended at its terminal event, while the `RxVar` still holds the subscriber. Cancelling makes the two agree.
- `RxVar` delivers over a snapshot of its subscribers, so cancelling inside a delivery is safe (`take(n)` already does it).
- When a subscriber throws, `RxVar` delivers `OnError` to it a second time. `Rx.run` throws on a failure from inside its effect, so today `v.run(...)` followed by `v.setException(e)` reaches the effect twice.
- Every `Rx.run` goes through `RxRunner.run`, and so do the HTTP client, the Netty, socket and Node servers, `StdioTransport` and Electron. They cancel the returned `Cancelable` later themselves; none needs the subscription after the end.
- `RxSpecTest`, `RxNestedSpecTest` and `RxRecoverSpecTest` call `RxRunner.run` over `Rx.queue`, so the new cancel applies to them. Their expected traces already stop at the first terminal event.

## Design

Lane: **Express** — a clear diagnosis, a small non-UI fix in one entry point, no always-gate area. Merge waits for the user's decision at finalize, as the launch prompt asked.

- `run` gets an `ended` flag like `runContinuously`: the first terminal event reaches the effect, and every later event is dropped with `Stop`. The flag is set before the effect runs, so an effect that throws (as `Rx.run` does) is not called again by `RxVar`'s `OnError` retry.
- At its end, `run` cancels its upstream subscription, even when the effect throws. When the end arrives while subscribing (`Rx.single`, `Rx.fromSeq`, a failed `Rx.future`, or a `map` that throws on a var's current value), the cancel runs as soon as the subscription returns. A later cancel by the caller is a no-op.
- One visible consequence: `onCancel(f)` upstream of where the run ended now runs `f` at that end, as it already does for `take(n)`. An end that passed through `onCancel` still skips `f`.
- `runOnce` and `runContinuously` are unchanged.
- `throttleLast` (user's choice at the hang): it forwarded its input's end at once and emitted the pending value from its timer afterwards, which only reached the sink because `run` had no guard. Now the end stops the timer, emits the pending value if it is unreported, then forwards the end.
- Regression tests in `uni/src/test/scala/wvlet/uni/rx/` cover both reported scripts, an end while subscribing, a throwing effect, and the `onCancel` case. They check that nothing reaches the upstream after the end.
- `adr/2026-11-21-rx-time-provider-and-tracker.md` gets a one-line amendment: an ended `run` now also cancels its upstream.
- The ui-gallery `Timeline` guard stays: it also covers unmount. Its comment doesn't cite this bug, so it stays as is.

## Alternatives and Why Not?

- Guard only, keep the subscription: stops the wrong deliveries, but an ended `run` over an `RxVar` keeps the effect and the pipeline alive until the caller cancels.
- Make `RxVar` honour `Stop`: `RxVar` is shared across subscribers and modes, and continuous mode treats `Stop` as transient. That is a wider change, out of scope.
