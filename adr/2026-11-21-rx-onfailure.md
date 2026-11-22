# The end of a stream is `OnCompletion` or `OnFailure`

## Context

`2026-11-21-rx-oncompletion-result.md` made `OnCompletion(result: Result[Unit])` the only end of
a stream. In practice the `Result[Unit]` slot reads like a payload. It raised the question of
whether `OnCompletion` should carry the stream's last value, which it never does: values arrive
only as `OnNext`, and `lastOption` gives the last one. The nested match
(`case OnCompletion(Result.Success(_))`) is also longer than it needs to be. And an old
`case OnCompletion =>` still compiled, with only an "Unreachable case" warning, and never matched.

## Decision

- The end of a stream is a `sealed trait OnEnd extends RxEvent` with two cases:
  `case object OnCompletion` (the stream completed) and `case class OnFailure(e: Throwable)` (the
  stream failed). A stream reports at most one end.
- Handlers match `case OnCompletion =>` and `case OnFailure(e) =>`. Code that only needs to know
  the stream ended (operators that forward the end, `RxRunner`'s end guards, trackers) matches
  `case end: OnEnd`.
- `type RxRunEvent = OnNext | OnEnd`. `RxRunner.run` and `runOnce` deliver the first error as
  `OnFailure(e)`, as before.
- `OnError` keeps its meaning: an error the stream continues past. `OnError` says an error
  happened, and `OnFailure` says the stream failed and ended.

Alternatives considered:

- Keep `OnCompletion(Result[Unit])`: one end event, but the unit payload invites the wrong
  reading, and the nested patterns are long.
- `OnInterrupted(e)`: suggests that something outside the stream stopped it, such as a thread
  interrupt or a cancel. These ends come from the stream's own source, and a cancel sends no
  event.
- `RxEnd` for the parent: it reads like a stream type (`RxVar`, `RxOption`), not an event. `OnEnd`
  follows the event names ("on" plus a noun), and a bare `case OnEnd =>` fails to compile, since
  the trait has no companion value, so it cannot become a pattern that never matches.
- `OnTermination` for the parent: the ReactiveX term, but longer, and it suggests a thread or
  process ending.
- `OnFailure` as a nested case of `OnCompletion`: rejected in the earlier ADR. A shared `OnEnd`
  parent gives the same "one end" view to the code that needs it, and flat cases to handlers.

R3 maps one to one: its `OnCompleted(Result.Success)` is `OnCompletion`, and its
`OnCompleted(Result.Failure(e))` is `OnFailure(e)`.

## Consequences

- Breaking. `OnCompletion(...)` patterns and `OnCompletion.success` / `OnCompletion.failure(e)`
  fail to compile. Replace them with `OnCompletion` and `OnFailure(e)`, and replace
  `c: OnCompletion` type patterns with `c: OnEnd`. An old `case OnCompletion =>` from before
  `2026-11-21-rx-oncompletion-result.md` works again.
- The Lean models keep their own event names (`done`, `failed`). Only comments that name the
  Scala events changed.
