# Use Lean 4 to specify and check Uni's core semantics

## Goals

- Answer "how can we use Lean in Uni?" with one concrete, low-cost starting point, not a research program.
- Catch semantic bugs before users do. Several recent ADRs fixed behaviour that was wrong but looked right in tests: `OnCompletion` not terminal, `take(n)` completion, async tests passing vacuously. A precise model would have stated the intended behaviour up front.
- Keep Uni's promise of minimal dependencies: Lean must never become a dependency of any published artifact.

## Background

- Lean 4 is both a theorem prover and a functional language. A Lean file can define a reference implementation, prove its properties, and `#eval` it to print data. A model can also be machine-checked rather than just written down.
- Uni is a Scala library for JVM, JS, and Native. Lean has no practical path into the Scala runtime: calling Lean's C output from Scala Native drags in the Lean runtime on one platform only. So Lean can serve as a **spec and test oracle**, not as runtime code.
- The parts of Uni that suit a formal spec are small, pure, and governed by external or written rules:
  - **Rx operator semantics** (`RxRunner`): completion, `take`, `merge`, and `flatMap` rules are now written as prose in ADRs (`2026-10-14-rx-completion-semantics.md`, `2026-10-19-rx-take-completion.md`), and the bugs came from cases those rules left implicit.
  - **MessagePack** (`wvlet.uni.msgpack`): the wire format has a public spec, which says encoders SHOULD pick the shortest format. `RoundTripTest` checks round-trips, but no test pins the exact bytes.
- `Rx.queue` accepts any `RxEvent`, including `OnError`, `OnCompletion`, and events after a terminal one, and delivers queued events synchronously. Test inputs can therefore be any event trace, on every platform.
- AI agents increasingly write Lean proofs well, so the cost of keeping a small proof up to date is lower than it was.

## Design

Lane: Standard — new tooling and CI job; the user approved the direction and chose the Rx pilot.

- **Where it lives:** a standalone Lake project under `spec/lean/`, outside the sbt build. It uses Lean core only, with no Mathlib, so it builds in seconds. No sbt plugin, no published artifact, and no change to `build.sbt` dependencies.
- **Pilot: a trace model of `map`, `filter`, and `take`.** A stream is the list of events a downstream sees, where the downstream always continues. Each operator is a function from an input trace to an output trace.
  - The model states the ADR rules as theorems:
    - every output is well-formed (no event after a terminal);
    - `filter` ends exactly when its input ends, so a rejected value never completes it;
    - `take(n)` emits at most n values;
    - `take(n)` completes right after the nth value without waiting for upstream;
    - `take(n <= 0)` completes at once.
  - Out of scope for the pilot: downstream `Stop`, continuous mode, cancellation, and concurrency. `flatMap`, `merge`, and `concat` come next, once the pilot shows the workflow pays off.
- **Bridge to Scala via generated cases, not proofs about Scala.** A Lean `main` enumerates small inputs, each a list of values ending in completion, an error, or silence, and pipelines of up to two operators. It writes the expected outputs into a generated Scala file, marked "Generated, do not edit".
  - The file sits in shared test sources, so the cases run on JVM, JS, and Native.
  - A UniTest suite feeds each input into `Rx.queue`, runs the pipeline through `RxRunner`, and compares the events it receives.
  - The sbt test does not need Lean installed; contributors who never touch the spec see only a normal test.
- **CI:** a new `lean` filter in the `changes` job of `test.yml` covers `spec/lean/**`, the generated cases, and the workflow file. Rx source changes run `RxSpecTest` in the normal test jobs. The job it gates installs Lean via `elan`, runs `lake build` (which checks the proofs), and fails if regenerating the cases changes the checked-in file.
- **Docs and ADR:** a short `spec/lean/README.md` covering how to install, build, and regenerate. An ADR records "Lean as spec and oracle, never a runtime dependency".
- **Follow-up, not this PR:** a MessagePack header model that proves round-trip and shortest encoding, and emits byte-exact cases for `OffsetPacker`/`OffsetUnpacker`.

## Alternatives and Why Not?

- **Lean-compiled code linked into Scala Native.** Native-only, ships the Lean runtime, and conflicts with minimal dependencies.
- **Prove properties of the Scala code itself, for example with Stainless.** Stainless targets a Scala subset that Uni's Scala 3 code does not fit, and adds a heavy toolchain to the main build.
- **Extend the existing `forAll` property tests.** Expected outputs would come from the same reasoning as the implementation, so they would restate its assumptions. A Lean model is an independent statement of the spec, with proven properties.
- **MessagePack first.** Smaller, but no bug motivates it. The Rx rules are where past bugs came from.
- **Use Uni as a client of the Lean language server (LSP/REPL over JSON-RPC).** A possible example app for Uni's RPC stack, but it does not improve Uni itself.
