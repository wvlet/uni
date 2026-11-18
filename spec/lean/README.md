# Lean specs for Uni

This Lake project states parts of Uni's semantics in [Lean 4](https://lean-lang.org/), proves their
properties, and generates test cases that Uni's own UniTest suites replay. Lean is a spec and a test
oracle here, never a dependency: no published artifact and no sbt project uses it. See
[`adr/2026-11-18-lean-specs.md`](../../adr/2026-11-18-lean-specs.md).

| Model | Covers | Generated cases | Replayed by |
| --- | --- | --- | --- |
| `UniSpec/Rx.lean` | `map`, `filter`, `take` completion rules | `uni/src/test/scala/wvlet/uni/rx/RxSpecCases.scala` | `RxSpecTest` (JVM, JS, Native) |

You need Lean only to change a model. `./sbt test` runs the checked-in cases without it.

## Build and regenerate

Install [elan](https://github.com/leanprover/elan) (for example `brew install elan-init`), which
picks the Lean version in `lean-toolchain`. Then, in this directory:

```bash
lake build              # check every proof and build the generator
lake exe gen-rx-cases   # rewrite RxSpecCases.scala from the model
```

Commit the regenerated Scala file with the model change. The `Lean spec` CI job fails when the
checked-in cases differ from the model's output.

## Changing a model

- When `RxRunner` behaviour changes on purpose, change the model first, re-prove the theorems, and
  regenerate. A failing `RxSpecTest` then shows where the Scala code disagrees with the new rule.
- When a theorem no longer holds, the rule it states changed: update the matching ADR as well.
- The model leaves out downstream `Stop`, continuous mode, cancellation, and concurrency. Add a
  theorem only for behaviour the model can express.
