# Experiment: R3's event model for Uni Rx (option B)

Follow-up to `plans/2026-11-21-rx-time-provider-tracker.md`. Question: would R3's events (a
non-terminal `OnErrorResume`, one terminal `OnCompleted(Result)`) remove the rules that
`RxRunner` has gained in the 2026-10/11 amendments, or only move them?

The model is `spec/lean/UniSpec/RxR3.lean`. It has four events: `next`, `resume` (an operator
error that does not end the stream), and the terminals `done` and `failed`. A stream ends at its
first terminal event, and there is no run mode.

## What the model proves

For the per-event operators (`map`, `filter`, a throwing `map`) of `RxRecover.lean`:

1. **Default mode is R3 plus `resumeAsFailure` at the sink** (`default_mode_eq`). Today's
   `RxRunner.run`, over any input, equals the R3 pipeline with every `resume` turned into `failed`
   before the sink. One-shot uses (`run`, `await`, RPC calls) can keep today's behavior by
   applying `resumeAsFailure` in those entry points.
2. **Continuous mode over a live source is R3 as is** (`continuous_mode_eq`). Over a var that has
   not stopped, `runContinuously` equals the R3 pipeline with no conversion. The two models
   differ only after a source ends. There, continuous mode keeps delivering values after
   `OnCompletion` (`continuous_after_done`), which R3 rules out.
3. **`recover` needs no mode.** A recovered `resume` continues, and a recovered `failed`
   completes (`recover_failed_completes`). Today's default mode leaves a recovered source error
   open forever (`recover_source_error_goes_quiet`), so a `concat` or anything else that waits
   for completion after `recover` over a failed source never proceeds.

So R3's events are no weaker than the current two modes. Both modes are projections of one trace,
and the mode becomes a property of the event instead of the runner.

## The amendments, one by one

| Rule (ADR) | Why it exists today | Under R3 events |
| --- | --- | --- |
| `flatMap` treats a nested `OnError` as its end (10-14) | a failed `Rx.future` sends `OnError` with no `OnCompletion` | **Gone**: a failed future is `failed`, which is terminal by definition |
| `recover` passes on the downstream's answer (11-18) | a failure after `recover` reached the sink after its `OnError` | **Gone**: an error after `recover` is `resume`; later values are part of the contract |
| `recoverWith` ends its input outside continuous mode (11-20) | an error that doesn't end its source (throwing `map` over an `RxVar`) let input values follow the recovery | **Gone**: `failed` ends the input, `resume` keeps it; the event decides, not the mode |
| `transformRx`: an input error counts as the input's end, except in continuous mode (10-14) | same ambiguity as `recoverWith` | **Gone**, same reason |
| `take(n)` in continuous mode: a `Stop` on the nth value doesn't suppress completion (10-19) | `Stop` is transient in continuous mode | **Gone**: no continuous mode |
| Recovered source error goes quiet in default mode (`RxRecover.lean`) | the source already stopped, so no completion follows | **Fixed**: recovered `failed` completes |
| `flatMap` cancels the previous nested stream on new input (11-20) | switch semantics | Unchanged |
| `zip` ends with its shortest source (11-20) | buffering rule | Unchanged |
| `Rx.future` replays its terminal event to a late subscriber (11-20) | race between completion and subscribe | Unchanged, but simpler: one terminal event to replay instead of `OnError`-without-`OnCompletion` |

Six of nine rules disappear or become a fix. The three that remain are about concurrency and
buffering, which the event model does not touch.

## What the model leaves out

- Stateful and nested operators (`flatMap`, `recoverWith`, `zip`, `take`, `merge`). The table
  argues them from the definitions; they are not proved. The next model step is an
  `RxLive.lean`-style script model over live vars with `resume`/`failed`.
- `RxResult.Stop` and cancellation. In R3 the observer is disposed on completion instead; whether
  `Stop` can go away is a separate question.
- Concurrency.

## Cost of doing B

- `RxEvent` gains `OnErrorResume` and `OnCompletion` carries a result, or `OnError` becomes
  terminal-only. All of `RxRunner` (about 1,400 lines) changes, plus `RxVar.setException`,
  `RxSource`, uni-test's `RxTestRunner`, `DomRenderer` (all its bindings use `runContinuously`),
  HTTP/RPC streaming, and the five Rx Lean models and their generated cases.
- Behavior a user can see: a throwing `map` inside `subscribe` already keeps going (continuous
  mode), so UI code doesn't change. In `run`/`await` code, behavior is unchanged when those entry
  points apply `resumeAsFailure` (theorem 1). What does change: `recover` over a failed source
  now completes, and a continuous subscription ends at `OnCompletion`.

## Recommendation

B looks worth doing. The model shows it subsumes both modes and removes most of the
mode-dependent rules. Do it in steps:

1. Extend the Lean model to `flatMap`, `recoverWith`, and `take` over live vars with
   `resume`/`failed`, and check the unchanged rules still hold.
2. Add `OnErrorResume` to `RxEvent` with today's mapping (operators emit it; `run` converts it
   with `resumeAsFailure`; `runContinuously` passes it), so nothing visible changes yet.
3. Make `failed` the only terminal error and drop continuous mode, one operator at a time, with
   the spec cases regenerated at each step.
