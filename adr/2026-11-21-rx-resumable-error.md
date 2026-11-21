# Internal `ResumableError`, toward `OnError` that never ends a stream

## Context

Step 2 of `plans/2026-11-21-rx-r3-event-model.md`. The Lean models `spec/lean/UniSpec/RxR3.lean`
and `RxR3Ops.lean` show that R3's split of an error into a non-terminal one and a terminal failure
covers both run modes. Today's default mode is that model with each non-terminal error turned into
a failure before the sink (`default_mode_eq`, `flatMap_default_eq`, `take_default_eq`).
Continuous mode over a live source is the model as is (`continuous_mode_eq`). Before an operator
can act on the difference, the event has to carry it.

## Decision

The target public events (step 3):

- `OnError(e)` never ends a stream: it is always resumable.
- A stream ends only with `OnCompletion(result)`, which carries a `Result` (R3's
  `OnCompleted(Result)`). A source that fails (`RxVar.setException`, a failed `Rx.future`,
  `Rx.exception`) ends with a failed completion.

Events name what happened, as `OnNext` does. An R3-style `OnErrorResume` names what to do next,
so it is not used as a public name.

To get there one operator at a time, step 2 adds an internal marker and changes nothing visible:

- `private[rx] final class ResumableError` extends the `OnError` case class. `case OnError(e)`
  matches it, and `ResumableError(e) == OnError(e)`, so every operator keeps treating it as
  today's `OnError`. In step 3 an operator opts in by matching `case ResumableError(e)` first.
- Operators raise it when their own function throws on a value: `map`, `filter`, `scan`, the
  `flatMap` and `transformRx` functions, `split`'s key, `tap`/`tapOn`, and `transform`/
  `transformTry` on a value. An error raised while handling an error keeps that error's kind
  (`ResumableError.like`). Sources keep a plain `OnError`, and so do the `recover`/`recoverWith`
  functions and `parFlatMap`, which step 3 revisits.
- `RxRunner.run`, `runOnce`, and `runContinuously` give their effect a plain `OnError`
  (`ResumableError.toPublic`), so user code, uni-dom, HTTP/RPC, and uni-test never see the
  marker. Public events change once, at the step 3 consolidation, not twice.
- An operator that rebuilds an error from its throwable (`OnError(e)`) instead of forwarding the
  event may drop the mark. That is invisible now and is fixed per operator in step 3.

## Consequences

- No behavior change: the Lean-generated spec suites and all existing tests pass unchanged.
- Step 3 is a breaking change. Amended 2026-11-21: an earlier version said a class-carrying
  `OnCompletion` makes each `case OnCompletion =>` site fail to compile. It does not: the old
  pattern compiles with an "Unreachable case" warning and never matches. See
  `2026-11-21-rx-oncompletion-result.md` for the shape chosen and how the sites were found.
  Code that treats `OnError` as terminal keeps today's default-mode behavior, because the model
  proves default mode is that conversion.
- `recoverWith` is the one known behavior change (`RxR3Ops.lean`, `old_default_inconsistent`).
  RPC server streaming needs a terminal conversion before its `recoverWith`.
