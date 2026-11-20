# Lean model of RxRunner with live sources: switch, split, zip/join, Stop and cancel

## Goals

- State the RxRunner rules that UI code depends on and that the existing Lean models cannot express, because those models only take finite, pre-filled traces (`Rx.queue`) that run to the end.
- Generate cases that UniTest replays through `RxRunner` on JVM, JS, and Native, so a later RxRunner change is checked against the stated rule without Lean installed.
- Find disagreements between the rules in the ADRs and the code, as the `RxRecover` model did twice.

## Background

- `Rx.lean`, `RxNested.lean`, and `RxRecover.lean` model a stream as one finished event list. They cannot say "the outer var changes while the inner var is still live", "a row var is set", or "the subscriber cancels between two updates".
- The UI uses exactly these: `flatMap` over an `RxVar` (route → page), `split` (keyed lists via `eachRx`), `join` (combining several vars), `take(1)` and `runOnce` (await a value).
- `RxVar` is synchronous: `:=`, `stop()`, and `setException` deliver to subscribers before they return, and a new subscriber gets the current value at once. So a script of var updates gives one deterministic event order on every platform. The models can stay sequential.
- Rules already written down in ADRs and code comments:
  - `flatMap`/`transformRx`: a new input value cancels the previous nested stream (`c1.cancel`); `NestedStreamTracker` completes the operator only after the input and the current nested stream have both ended, in either order (`2026-10-14-rx-completion-semantics.md`).
  - `split`: one row var per first-occurrence key, a row emits only when its value differs, an unchanged key list is not re-emitted, removed rows stop after the new list is emitted, and completion or cancel stops every row (`2026-11-06-rx-scan-split.md`).
  - `take(n)`: after a downstream `Stop` before the nth value it forwards nothing more but keeps the upstream subscribed (`2026-10-19-rx-take-completion.md`, listed as unmodeled in `2026-11-18-lean-specs.md`).
  - `zip` and `join` (`CombinedStream`) have no ADR. Their completion rules exist only in code: `join` completes when every source completed. `zip` completes once every source has delivered any event, values included, and the check also runs right after subscribing (`RxRunner.scala:1092`, `1116`). An `RxVar` delivers its current value on subscribe, so in default mode `zip` over two vars emits the first pair and completes at once. The existing zip tests over vars use `runContinuously`, which hides this.
  - `RxVar` ignores a downstream `Stop`, and `CombinedStream` never checks the downstream's answer, so a sink can receive events after a terminal one (`2026-10-19-rx-take-completion.md`).

## Design

Lane: **Standard** — a new model whose cases may expose RxRunner bugs, and whether to fix them here changes the PR's scope.

- **One new model, `UniSpec/RxLive.lean`, with its generator and a `RxLiveSpecTest`.** It follows the existing style: Lean core only, a generator that writes `uni/src/test/scala/wvlet/uni/rx/RxLiveSpecCases.scala` as scalafmt-formatted text, a `Lean spec` CI check, and a README row.
- **Script model.** A case is a pipeline over named vars plus a script of actions: `set var v`, `stop var`, `fail var`, `cancel`. The model is a state machine that mirrors RxRunner's per-operator state (current nested generation, the row table, zip buffers) and returns what the sink records. The Scala test builds the same pipeline over real `RxVar`s, runs the script, and compares the recorded events.
- **Sinks.** The sink records every event it receives, including any after a terminal one, so the model mirrors what RxRunner really delivers; a theorem that the output ends at its first terminal event holds only where the code guarantees it, and a case that breaks it is an exposed bug (below). It answers either as `RxRunner.run` does (`Stop` to a terminal event) or as `runOnce` does (`Stop` to every event, before `runOnce`'s own filter). Continuous mode is left out of this model.
- **Scope, in order:**
  - `flatMap` and `transformRx` switching over an outer var with nested vars. Theorems: no event from a replaced nested var reaches the sink; the operator completes exactly when the input and the current nested var have both ended, in either order; a failing nested var surfaces `OnError`. When `f` throws, the code does not cancel the previous nested var, so its later values still reach the sink; the model mirrors this and lists it as an exposed case.
  - `split`. The sink records the key list and each row's events. Theorems: the list is emitted exactly when the key sequence changes; a kept row emits exactly when its value differs; a removed row completes after the list that dropped it; completion and cancel complete every live row.
  - `zip` and `join` over two vars. Theorems: the kth `zip` output pairs the kth values (in default mode this needs the `zip` completion fix below); `join` emits the latest pair after both have a value and completes when both completed; one error yields one `OnError`.
  - Downstream `Stop` and cancel. Theorems: with the `runOnce` sink, `take(n)` for n ≥ 1 delivers exactly one event however the var keeps updating, completing, or failing; after `cancel`, the main sink records nothing more for every operator above (a `split` cancel still completes each row, which only row subscribers see).
- **Left out:** `recoverWith` (nearly the `transformRx` error path, small extra value), `parFlatMap` ordering (its order depends on thread scheduling, so a sequential model would only restate `FanIn`), continuous mode, and the DOM renderer.
- **Bugs the cases expose** are fixed in this PR when the fix is small and the ADR rule is clear, with the ADR amended, as `RxRecover` did. A fix that needs a new rule becomes a follow-up, and its case is left out of the generated set with a note. Known before writing any proof:
  - `zip` over live vars completes after its first pair in default mode. Proposed rule: `zip` completes when a completed source has no buffered value left (the shortest source ends it), and `join` stays as is. This changes `zip`'s behaviour, so it is a question on the card.
  - A throwing `f` in `flatMap` leaves the previous nested var subscribed. Proposed: cancel it on every new input value, before calling `f`, so a failure also switches away from it. Small and consistent with the switch rule, so fixed here.
- **Coordination with the DOM-render PR:** in `adr/2026-11-18-lean-specs.md` this PR adds a consequence bullet for the new model and edits "Next candidates" only to drop what it covers; in `spec/lean/README.md` it adds its own row and updates the "Rx models leave out cancellation" bullet. Both PRs touch the same ADR line and the end of the same table, so whichever merges second rebases and resolves those two spots by hand; no other file overlaps.

## Alternatives and Why Not?

- Extend `RxNested.lean` with an action list: its theorems are about finished traces, and mixing in live state would make every existing proof restate the new state.
- Model concurrency (interleavings across threads): RxVar is synchronous, and the UI paths above run on one thread; interleavings would cost far more proof effort for `parFlatMap` only.
- Four separate model files: they would repeat the script runner and the case format four times.
