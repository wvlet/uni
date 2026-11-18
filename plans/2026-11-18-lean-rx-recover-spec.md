# Lean model of Rx recover and continuous mode

## Goals

- Extend the Rx trace model to `recover` and to continuous mode (`runContinuously`), the ADR's next candidates.
- Pin down two behaviours that today live only in code comments:
  - what happens after a recovered error;
  - which events continuous mode keeps delivering.

## Background

- The default runner's sink returns `Stop` on the first `OnError` or `OnCompletion`. A source stops on `Stop`, and `Rx.queue` also stops after delivering its own terminal event.
- `recover` turns a handled `OnError` into `OnNext` and answers `Continue`, so whether the stream goes on depends on where the error came from:
  - **from the source:** `Rx.queue` has already ended, so the stream goes quiet after the recovered value and never completes;
  - **from an operator in the chain** (a `map` that throws): the source sees `Continue` and keeps emitting. `RxTest`'s "recover in the middle" covers this case for `Rx.sequence`.
- Continuous mode (`RxRunner.runContinuously`, used for `RxVar` → DOM rendering) treats terminal events and `Stop` as transient: sources keep reading after them. `take(n)` is the exception, because `2026-10-19-rx-take-completion.md` keeps errors terminal there "even in continuous mode".

## Design

Lane: Express — spec and tests, plus the one-line `take` fix below, which the user approved ("Fix in this PR"). No always-gate area.

- **Fix found by the model:** in continuous mode, `take(n)` skipped its completion when the nth value's downstream returned `Stop` (after a downstream error), although the same `Stop` is ignored for the values before the nth. It now completes in continuous mode either way; the take ADR records the amendment.

- **Model** (`UniSpec/RxRecover.lean`, on top of `UniSpec/Rx.lean`):
  - `map`, `filter`, a throwing `map`, and `recover` act on each event and never cut the trace themselves;
  - `take` keeps its own end;
  - the mode decides where the trace is cut: default mode cuts at the source's first terminal event and again at the sink's, while continuous mode cuts at neither.
- **Theorems:**
  - default-mode outputs are well-formed, and on ordinary inputs `map`, `filter`, and `take` agree with the single-stream model;
  - a total `recover` never emits `OnError`;
  - a recovered source error leaves the stream open, with no completion, in default mode;
  - a recovered operator error lets the stream continue to its completion;
  - continuous mode delivers events after an error or completion;
  - `take` still ends at the first terminal event in continuous mode.
- **Generated cases** (`RxRecoverSpecCases.scala`): eight inputs, including ones with events after a terminal, run through pipelines of up to two operators in both modes. The operators are `even`, `take:2`, `inc`, `boom:2` (a `map` that throws on 2), and `rec:0` (`recover` to 0).
- **Replay** (`RxRecoverSpecTest`): `RxRunner.run` for default mode and `RxRunner.runContinuously` for continuous mode, over pre-filled `Rx.queue`s.
- **CI:** the `Lean spec` job also builds and checks the new model; the path filter adds the generated file.
- **Out of scope:** `recoverWith`, partial recovery functions, `RxVar`'s re-emission, and continuous-mode `flatMap`/`merge`.

## Alternatives and Why Not?

- **Model `Continue`/`Stop` feedback explicitly.** More faithful in general, but for these operators "the sink stops at its first terminal event" gives the same traces with far simpler proofs.
- **Change `recover` to complete after a recovered source error.** That would change behaviour no rule calls wrong; this PR records it as it is.
