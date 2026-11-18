# Lean model of Rx flatMap, concat, and merge completion

## Goals

- Extend the Rx trace model (#945) to the operators that join streams, as the ADR's next candidate. `2026-10-14-rx-completion-semantics.md` fixed `flatMap` completing before its nested stream ended. `2026-10-27-rx-merge-parflatmap.md` added `merge`, which must complete only after every source.
- State those completion rules as theorems, and replay generated cases through `RxRunner` on every platform.

## Background

- The trace model describes what a downstream that always continues observes. Test inputs come from pre-filled `Rx.queue`s, which deliver their events synchronously on subscription.
- With synchronous sources, `RxRunner` is deterministic:
  - `merge` subscribes its sources in order, and each delivers everything it has before the next is subscribed;
  - `flatMap` runs each nested stream to its end, or until it goes quiet, before the next input event;
  - `concat` subscribes the second stream only when the first completes.
- `flatMap` is switch-like: a new input value cancels the previous nested stream, and a generation counter keeps a cancelled stream from ending the operator.

## Design

Lane: Express — spec and tests only, no library change, no always-gate area. The user asked for it directly.

- **Model** (`UniSpec/RxNested.lean`, on top of `UniSpec/Rx.lean`): `concat`, `merge` over a list of traces in subscription order, and `flatMap` with the nested trace given by a function.
- **Theorems:**
  - every output is well-formed;
  - `concat`: `Rx.empty` is a left and right identity, the operator is associative, and the second stream is never subscribed after the first fails or goes quiet;
  - `merge`: completes exactly when every source completes and fails exactly when some source fails. This ending does not depend on subscription order, so it holds for any interleaving. `merge` of nothing completes at once, and `merge` of one source is that source;
  - `flatMap`: completes only when the input has completed and the last nested stream has completed. When every nested stream completes, `flatMap` ends exactly as its input does. `x => Rx.single(x)` is a right identity, and a single input value gives exactly the nested stream (left identity).
- **Generated cases** (`RxNestedSpecCases.scala`): every pair of small inputs for `concat` and `merge`, some triples for `merge`, and four nested functions for `flatMap`:
  - `dup` emits `x, 10x`, then completes;
  - `open` emits `x` and goes quiet;
  - `failOdd` fails on odd values;
  - `empty` completes at once.
- **Replay** (`RxNestedSpecTest`): builds each pipeline from `Rx.queue` sources with `concat`, `Rx.MergeOp` (directly, so 0 and 1 sources skip `RxParallel.merge`'s shortcuts), and `flatMap`, then compares the events `RxRunner` emits.
- **CI:** the `Lean spec` job builds the new model and also checks the new generated file; the path filter adds it.
- **Out of scope:** asynchronous interleavings (the merge-ending theorem covers their completion, but the cases are synchronous only), `recover`, continuous mode, `parFlatMap`, and `transformRx`.

## Alternatives and Why Not?

- **Model merge as a relation over every interleaving.** It needs a nondeterminism encoding with no case generator; the order-independence of the ending covers what the completion rule says.
- **Extend `Rx.lean` in place.** It would mix the single-stream pilot with nested-stream state. A separate file keeps each model readable.
