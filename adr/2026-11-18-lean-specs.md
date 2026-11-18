# Lean 4 as a spec and test oracle, never a dependency

## Context

Several Rx fixes (`2026-10-14-rx-completion-semantics.md`, `2026-10-19-rx-take-completion.md`)
corrected behaviour that was wrong but passed its tests: `filter` completing on a rejected value,
`take(n)` waiting for an (n+1)th value. The intended rules existed only as prose. Lean 4 can state
such rules as functions, prove their properties, and evaluate them to produce data. But Uni must keep
minimal dependencies on JVM, JS, and Native, and Lean has no practical path into those runtimes.

## Decision

- Lean models live in a standalone Lake project, `spec/lean/`, outside the sbt build. They use Lean
  core only, with no Mathlib, so a build takes seconds.
- The first model is a trace model of Rx `map`, `filter`, and `take`. A stream is the list of
  events a downstream that always continues observes. Theorems state the ADR rules: outputs are
  well-formed, `filter` ends exactly when its input ends, `take(n)` emits at most n values,
  completes right after the nth one, and `take(n <= 0)` completes at once.
- Lean and Scala meet through generated cases, not proofs about Scala code. A Lean executable writes
  `RxSpecCases.scala` (scalafmt-formatted, marked "Do not edit"). `RxSpecTest` feeds each input into
  `Rx.queue`, which accepts any event sequence and delivers queued events synchronously, and compares
  what `RxRunner` emits. Generated Scala source, not a classpath resource, so the cases run on JS and
  Native too.
- The `Lean spec` CI job runs `lake build` and fails when regenerating changes the checked-in cases.
  It runs only when `spec/lean/**`, the generated file, or the workflow changes. The Scala side runs
  in the normal test jobs, so an Rx change is checked against the model without Lean installed.

## Consequences

- Contributors who do not change a model never need Lean. Changing intended Rx behaviour means
  changing the model and regenerating; the proofs then show which stated rules still hold.
- The model leaves out downstream `Stop`, continuous mode, cancellation, and concurrency, so it says
  nothing about the `Stop`-before-nth case in `2026-10-19-rx-take-completion.md`.
- Next candidates: `flatMap`/`concat`/`merge` completion in the same trace model, and a MessagePack
  header model that proves round-trip and shortest encoding and emits byte-exact cases for
  `OffsetPacker`/`OffsetUnpacker`.
- Rejected: linking Lean-compiled C into Scala Native (one platform, ships the Lean runtime);
  verifying the Scala code with Stainless (does not fit Uni's Scala 3 code); hand-written expected
  outputs in property tests (they restate the implementation's own assumptions).
