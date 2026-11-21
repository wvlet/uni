# Sinks answer `Continue` to an error, so `Stop` means stop

## Context

After `2026-11-21-rx-single-mode.md`, `RxRunner` had one semantics, but kept a rule from the old
continuous mode: every sink answered `Stop` to any non-value event, including an `OnError` the
stream continues past, so every operator ignored a `Stop` (it was "transient"). An operator could
therefore not tell "the downstream wants nothing more" from "the downstream saw an error":
`take(n)` kept counting after a downstream stopped, and `Rx.sequence`, `merge`, `parFlatMap`, an
`RxSource` reader, and `sampleFrame` kept emitting.

## Decision

- `runContinuously` answers `Continue` to an `OnError`. `run` and `runOnce` answer `Stop` to the
  event that ends their run (the first error, or the end), as before.
- Operators honor a downstream `Stop` again, as the old default mode did:
  - `take(n)` forwards nothing more after a `Stop` before the nth value, and does not complete
    after a `Stop` on the nth;
  - `recover`, `transform`, and `transformTry` complete after the replacement value of a failed
    end only if the downstream continues;
  - `Rx.sequence`, the `RxSource` reader, and `sampleFrame` stop emitting;
  - `merge` and `parFlatMap` stop and cancel their running streams.
- `RxLiveSpecTest`'s sink answers like `runContinuously`. The Lean models did not change: they
  never depended on a `Stop` after an error, and every generated case passes as before.

## Consequences

- A custom effect passed to an `RxRunner` instance must answer `Continue` to an `OnError` it wants
  to continue past. The public entry points do this for their callers.
- `RxVar` still ignores `Stop`; an operator over it stops forwarding instead.
