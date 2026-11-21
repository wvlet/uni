# `OnCompletion(result)`, and `run` ends at the first error with a failed completion

## Context

Step 3a of `plans/2026-11-21-rx-r3-event-model.md`. The target public events are `OnError` that
never ends a stream and `OnCompletion` as the only end, carrying how the stream ended
(`adr/2026-11-21-rx-resumable-error.md`). Uni is young, so a breaking change now is cheaper than
compatibility shims later.

## Decision

- `OnCompletion` is a plain `case class OnCompletion(result: Result[Unit])`, with
  `OnCompletion.success` and `OnCompletion.failure(e)`. Handlers match
  `case OnCompletion(Result.Success(_))` and `case OnCompletion(Result.Failure(e))`.
- `RxRunner.run` and `runOnce` stop at the first error, as before, and deliver it as
  `OnCompletion(Result.Failure(e))` (R3's `OnErrorResumeAsFailure`; the Lean models prove default
  mode is exactly this conversion). Their effect is typed `RxRunEvent => U`, where
  `type RxRunEvent = OnNext | OnCompletion`, so a handler has three cases and the compiler checks
  them without an `OnError` case. A function typed `RxEvent => U` is still accepted.
- `RxRunner.runContinuously` (`subscribe`) delivers `OnError(e)` and keeps running, as before.
- Operators and sources keep today's internal events, so no behavior changes besides how `run`
  and `runOnce` report an error. Step 3b moves sources to failed completions and makes `OnError`
  non-terminal inside `RxRunner`.

Alternatives considered:

- `OnCompleted(result)` (R3's name): a new name would turn every old `case OnCompletion =>` into a
  compile error, but "on completed" is not English; `OnNext`, `OnError`, and `OnCompletion` are
  "on" plus a noun.
- The companion object as the success value (`object OnCompletion extends OnCompletion(...)`):
  `case OnCompletion =>` keeps working, but it is an equality test, so the exhaustivity checker
  reports `OnCompletion(Result.Success(_))` as unhandled in every handler.
- A sealed `OnCompletion` with a `case object` success and a nested `Failed(e)`: exhaustive and
  short, but it splits the end into two shapes instead of one event with a `Result`.

## Consequences

- Breaking. An old `case OnCompletion =>` compiles with only an "Unreachable case" warning and
  never matches, so downstream code must treat that warning as an error or search for the
  pattern. `effect(OnCompletion)` fails to compile (the companion is not an `RxEvent`). Every
  site in Uni was rewritten; the exhaustivity warnings of `RxEvent` matches located them.
- A `run`/`runOnce` handler that matches `OnError` gets an unreachable-pattern warning: it
  must match `OnCompletion(Result.Failure(e))` instead.
- `adr/2026-11-21-rx-resumable-error.md` said a class-carrying `OnCompletion` would make old
  sites fail to compile; that was wrong, as noted above, and is corrected there.
