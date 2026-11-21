# A failed Rx source ends with `OnCompletion(Result.Failure(e))`

## Context

Step 3b of `plans/2026-11-21-rx-r3-event-model.md`, second part, after
`2026-11-21-rx-continuous-r3.md`. Sources still reported a failure as `OnError`, which in the
target model (`spec/lean/UniSpec/RxR3.lean`) is an error the stream continues past. A failed
`Rx.future` therefore still sent `OnError` with no end.

## Decision

- Sources end with `OnCompletion(Result.Failure(e))`: `Rx.exception` / `Rx.fromTry` /
  `Rx.fromResult` (`TryOp`), a failing `Rx.single` body, a failed async callback (`AsyncOp`:
  `RxDeferred`, `RxFiber`, ...), `RxVar.setException`, and failed futures (`FutureVar`, which
  replays that event to late subscribers). An exception thrown by the downstream effect while a
  source delivers a value stays an `OnError`.
- Operators that react to errors also handle a failed end:
  - `recover` and `transform`/`transformTry` emit the replacement value, then a successful
    `OnCompletion` (in continuous mode even after a downstream `Stop`, which is transient there);
  - `recoverWith` always continues as the recovery stream, since the input has ended;
  - `transformRx` completes once the nested stream built from the failure ends, in either mode;
  - `tapOn` sees `Failure(e)`;
  - `zip` and `join` end with a failed `OnCompletion` when a source failed;
  - `RxVar.foreach` throws the failure, as it did for `OnError`.
- `RxLive.lean`'s `transformRx` treats a var's failure as the end of its input in both modes,
  which changed one generated case (`fail 0, stop 2` now completes in continuous mode).

## Consequences

- Breaking: `setException` ends an `RxVar`'s subscriptions, in continuous mode too. Before, a
  subscriber received `OnError` and kept receiving later values. A state variable that should
  stay live reports errors through its value (for example an `RxVar[Result[A]]`).
- A failed nested stream ends `parFlatMap` (and `flatMap`) with a failure, as `RxR3Ops.lean`
  says (`flatMap_nested_failed`). A `recover` after it emits its value and completes: to recover
  each item and keep going, put `recover` inside the nested stream.
- Default mode (`run`) is unchanged for the Lean-generated cases: they pass as before.
- Left for step 3b: check `flatMap`, `merge`, and `parFlatMap` against the R3 model with
  generated cases; then drop continuous mode (3c).
