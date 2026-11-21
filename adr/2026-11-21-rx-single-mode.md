# `RxRunner` has one semantics: the `continuous` flag is gone

## Context

Step 3c of `plans/2026-11-21-rx-r3-event-model.md`, second part. After
`2026-11-21-rx-run-on-r3.md`, every public entry point (`run`, `runOnce`, `runContinuously`)
already used the continuous runner. The `continuous = false` branches in `RxRunner` ran only from
tests that built a runner instance, and the live-var Lean models (`RxLive.lean`,
`RxLiveFanIn.lean`) still generated cases for both modes.

## Decision

- `RxRunner` takes no parameter. Every operator keeps its former continuous-mode behavior: an
  `OnError` passes and the stream continues, a `Stop` returned after it is transient, and
  `OnCompletion` ends a stream.
- `zip` and `join` complete in every case. Their completion used to be off in continuous mode,
  so since `2026-11-21-rx-run-on-r3.md` a `run` over a `zip` of finite sources never completed.
- A failed `OnCompletion` from one source of `merge` or `parFlatMap` ends the operator with that
  failure and cancels the other sources, as `RxR3Ops.lean` says for nested failures
  (`flatMap_nested_failed`). An `OnError` from the function still passes, and `parFlatMap` hands
  the permit on.
- `Rx.sequence` emits its whole list after a downstream `Stop` (transient), but stops when
  cancelled.
- The Lean live-var models lose `Mode`: each operator has the continuous rule for an operator error
  and ends at a var's failure. `take` no longer has a sink that answers `Stop` to every value,
  since that `Stop` is transient now; `take_once` is replaced by `take_done`. The generator writes
  1262 cases instead of 2143.

## Consequences

- Breaking only for code that built `RxRunner(continuous = ...)` itself.
- `RxRecover.lean`, `RxNested.lean`, and `Rx.lean` keep their trace models; they describe a stream
  as `run` sees it, cut at its first error or end.
- Left for later: whether a sink should answer `Continue` to an `OnError` so that `Stop` can mean
  "stop" again. Today `Stop` after an error is transient everywhere, which keeps the old continuous
  behavior intact.
