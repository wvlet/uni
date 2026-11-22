# `subscribe` logs an unhandled error instead of throwing it

## Context

`subscribe(f)` (and `runContinuously(f)` and `RxVar.foreach(f)`) gives `f` values only. Its
handler threw an `OnError` or `OnFailure`, and where the throw landed depended on the source:

- `Rx.exception(e).subscribe()` threw `e` from `subscribe`.
- A throwing `map` over an `RxVar` threw from `:=`. It got there through `RxVar.propagateEvent`,
  which caught the first throw, sent the subscriber an `OnError`, and let the second throw escape.
  That escape also skipped the other subscribers of the variable, so they never received the
  value.
- `RxVar.setException(e)` threw nothing: the runner had already ended, so the retried `OnError`
  was dropped.
- With an asynchronous source, such as a future, the throw landed on another thread.

## Decision

- A values-only subscriber logs an unhandled `OnError` or `OnFailure` as a warning
  (`RxRunner.reportUnhandled`) and never throws it. R3 likewise reports unhandled errors to a
  handler instead of throwing.
- If a subscriber of an `RxVar` throws again while it handles the `OnError` from its own throw,
  the second throw is logged too, and the variable goes on to its other subscribers.
- `rx.run(f)` still throws a failure. It is the one-shot entry point, and synchronous callers
  rely on it.

Alternative considered: always throw. The caller of `setException(e)` would then get back the
exception it just set, and a throw from an asynchronous source still lands on another thread.

## Consequences

- Code that caught an exception from `:=` or `subscribe` gets none. Handle errors in the chain
  with `recover` or `tapOnFailure`, or use `RxRunner.runContinuously` to receive every event.
- One failing subscriber of an `RxVar` no longer stops the others from receiving a value.
