# Continuous mode follows the R3 event model: `OnCompletion` ends, `OnError` passes

## Context

Step 3b of `plans/2026-11-21-rx-r3-event-model.md`, first part. After 3a
(`2026-11-21-rx-oncompletion-result.md`), `run` and `runOnce` already end at the first error with
a failed `OnCompletion`. Continuous mode (`runContinuously`, used by `subscribe` and uni-dom) still
had the old rules: it delivered events after `OnCompletion`, `take` ended at the first `OnError`,
and `recover` did not see a failed end. The target model (`spec/lean/UniSpec/RxR3.lean`,
`RxR3Ops.lean`) says `OnError` never ends a stream and `OnCompletion` always does.

## Decision

- A new generator, `spec/lean/GenRxR3Cases.lean`, writes `RxR3SpecCases.scala` from the target
  model: `map`, `filter`, a throwing `map`, `take`, and `recover` over sources that emit values,
  `OnError` (and continue), and a successful or failed `OnCompletion`. `RxR3SpecTest` replays them
  through `RxRunner.runContinuously`.
- `runContinuously` stops at its first `OnCompletion`, success or failure, and drops later
  events.
- In continuous mode `take` passes `OnError` on without counting it or ending.
- `recover` handles a failed `OnCompletion`: it emits the recovered value, then a successful
  `OnCompletion`, and drops later input events. Continuous mode completes even when the
  downstream answered `Stop` to the recovered value, because there `Stop` after an error is
  transient.
- `RxRecover.lean`'s continuous mode stays as the pre-R3 behavior that `RxR3.lean` compares
  against, and `GenRxRecoverCases.lean` now writes only its default-mode cases. Continuous-mode
  cases come from the R3 model.

## Consequences

- Breaking for continuous subscribers that relied on events after `OnCompletion` (for example an
  `RxVar` that was stopped and then set again) or on `take` ending at an error. No code in Uni did.
- Default mode is unchanged: `RxRecoverSpecCases` (default mode) and the other Lean-generated
  suites pass as before.
- Still to do in step 3b: sources (`RxVar.setException`, failed futures, `Rx.exception`, queues)
  end with a failed `OnCompletion` instead of `OnError`; `flatMap`, `transformRx`, and
  `recoverWith` follow `RxR3Ops.lean` (with `resumeAsFailure` before RPC streaming's
  `recoverWith`); `zip`, `join`, `merge`, and `parFlatMap` are checked against the model. Then
  continuous mode can be dropped (3c).
