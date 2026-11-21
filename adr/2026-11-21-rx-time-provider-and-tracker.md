# Rx time and frame providers, and subscription tracking

## Context

Uni's Rx time operators read `System.nanoTime` and per-platform timers directly. Tests of
`interval`, `throttleLast`, or `timeout` had to sleep and were marked pending on Scala.js, and
`Rx.interval` did not run at all on Scala Native (`compat.newTimer` was `???`). Nothing told a
developer which subscriptions were still running, so an `RxVar` binding nobody cancelled went
unnoticed.

R3 (Cysharp/R3, a .NET Rx redesign) solves both with a `TimeProvider`/`FrameProvider` passed to
time operators and an `ObservableTracker` that lists live subscriptions. This ADR adopts those two
ideas without R3's change of the event model (`OnErrorResume`, a terminal `OnCompleted(Result)`),
which is left for a separate decision.

## Decision

- `RxTimeProvider` (extends `Ticker`) supplies `currentNanos`, `scheduleOnce`, and
  `schedulePeriodically`. `Rx.interval`, `Rx.timer`/`delay`, `throttleFirst`, `throttleLast`,
  `sample`, and `timeout` take it as a `using` parameter. The companion's `given default` is
  `RxTimeProvider.system`, so call sites are unchanged and a `given` in lexical scope overrides it.
  The provider is stored in the operator when the `Rx` is built, not looked up when it runs. A
  dynamic "current provider" would be global mutable state shared by parallel test suites.
- `ManualTimeProvider` runs due tasks in time order on the thread that calls `advance`, including
  tasks scheduled while advancing. Tests are synchronous and deterministic on every platform.
- `RxFrameProvider.requestFrame` backs `Rx.everyFrame`, `Rx.nextFrame`, and `sampleFrame`. The
  default is `requestAnimationFrame` in a browser and a 16 ms timer elsewhere. `ManualFrameProvider`
  runs one frame per `advanceFrame()`; a callback requested during a frame goes to the next frame,
  as in rAF.
- `sampleFrame` flushes its held value before a terminal event, so a UI never loses the last state.
- The system provider uses one shared scheduler thread on the JVM and Native
  (`scheduleWithFixedDelay`, matching the old `java.util.Timer` fixed-delay behavior), instead of a
  `java.util.Timer` thread per subscription. `Rx.interval`'s first tick now comes one interval after
  the subscription on every platform. On the JVM it used to fire immediately with ~0 ms.
- `throttleFirst` always passes its first value. The old `-interval` sentinel worked only because
  `System.nanoTime` is large, and it dropped the first value on a clock that starts at 0.
- `RxTracker` hooks only the `RxRunner` companion entry points (`run`, `runOnce`,
  `runContinuously`), so nested operator subscriptions are not counted and an inactive tracker
  costs one volatile read and one atomic read per top-level run. A subscription ends on cancel,
  or on `OnError`/`OnCompletion` outside continuous mode. `trace { }` tracks the current thread
  only (a thread-local stack of trackers), so parallel suites do not see each other's
  subscriptions. `startGlobal` tracks all threads for debug views.
- `RxSubscription.origin` skips the Rx entry frames by class name (`RxRunner`, `RxOps`, `Rx`,
  `RxVar`, ...), not by package, because user code and tests can live in `wvlet.uni.rx`.
- uni-test adds `assertNoRxLeaks { }` on top of `RxTracker.trace`.

## Consequences

- Source compatible. Binary incompatible: the time operators and op case classes gained a
  parameter, and the `private[rx]` `Timer` trait was removed.
- Code that builds `Rx.interval` itself, outside the scope of a test's `given`, still uses the
  system clock. A test that needs to drive it has to pass the provider through.
- `runOnce` over an `RxVar` shows up as active until cancelled, because the runner keeps the
  upstream subscription (see `2026-10-14-rx-completion-semantics.md`). This is a real open
  subscription, not a false positive.
- `trace` does not see subscriptions started on other threads (Future callbacks, fibers).
- Amended 2026-11-21: `RxRunner.run` now cancels its upstream at its terminal event and drops
  any event a source sends after it. Before, an `RxVar` kept delivering to a `run` that had ended
  (values after `OnCompletion(Failure)`), while the tracker already counted it as ended. An
  `onCancel(f)` above the point where the run ended now runs `f` at that end, as it does for
  `take(n)`. `runOnce` still keeps its upstream. `throttleLast` used to forward its input's end
  at once and emit the pending value from its timer afterwards; it now stops the timer and emits
  that value before the end.
