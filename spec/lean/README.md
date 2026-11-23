# Lean specs for Uni

This Lake project states parts of Uni's semantics in [Lean 4](https://lean-lang.org/), proves their
properties, and generates test cases that Uni's own UniTest suites replay. Lean is a spec and a test
oracle here, never a dependency: no published artifact and no sbt project uses it. See
[`adr/2026-11-18-lean-specs.md`](../../adr/2026-11-18-lean-specs.md).

| Model | Covers | Generated cases | Replayed by |
| --- | --- | --- | --- |
| `UniSpec/Rx.lean` | `map`, `filter`, `take` completion rules | `uni/src/test/scala/wvlet/uni/rx/RxSpecCases.scala` | `RxSpecTest` (JVM, JS, Native) |
| `UniSpec/RxNested.lean` | `concat`, `merge`, `flatMap` completion rules | `uni/src/test/scala/wvlet/uni/rx/RxNestedSpecCases.scala` | `RxNestedSpecTest` (JVM, JS, Native) |
| `UniSpec/RxRecover.lean` | `recover` and the old default and continuous modes, kept as the baseline `RxR3.lean` compares against | none (the R3 cases replace them) | none |
| `UniSpec/RxLive.lean`, `UniSpec/RxLiveFanIn.lean` | `flatMap`/`transformRx` switching, `recoverWith`, `split`, `zip`/`join`, `merge`, `parFlatMap` permits, `take`, and cancel over live `RxVar`s, under `RxRunner`'s single semantics | `uni/src/test/scala/wvlet/uni/rx/RxLiveSpecCases.scala` | `RxLiveSpecTest` (JVM, JS, Native) |
| `UniSpec/RxR3.lean`, `UniSpec/RxR3Ops.lean` | The target event model (`OnError` never ends a stream, `OnCompletion` with a result does), proofs that the old default and continuous modes are projections of it, and `flatMap`, `take`, `recoverWith` under it (see `plans/2026-11-21-rx-r3-event-model.md`) | `uni/src/test/scala/wvlet/uni/rx/RxR3SpecCases.scala` (`map`, `filter`, `take`, `recover`, `flatMap`, `recoverWith`) | `RxR3SpecTest` (JVM, JS, Native), through `run` and `runContinuously` |
| `UniSpec/MsgPack.lean` | MessagePack integer, str/bin/array/map/ext header, float, and timestamp bytes | `uni/src/test/scala/wvlet/uni/msgpack/spi/MsgPackSpecCases.scala` | `MsgPackSpecTest` (JVM, JS, Native) |
| `UniSpec/HttpReader.lean`, `UniSpec/HttpSmuggle.lean` | HTTP/1.1 request framing on a keep-alive connection (the built-in JVM and Native servers), and agreement with a family of lenient parsers on every request it keeps the connection open after (no request smuggling) | `uni/.jvm-native/src/test/scala/wvlet/uni/http/HttpReaderSpecCases.scala` | `HttpReaderSpecTest` (JVM, Native) |
| `UniSpec/DomRender.lean` | Where `DomRenderer` places `Rx` sections, keyed lists (`each`), and `RxElement`s, and which nodes it keeps | `uni-dom-test/src/test/scala/wvlet/uni/dom/DomRenderSpecCases.scala` | `DomRenderSpecTest` (Chromium) |

You need Lean only to change a model. `./sbt test` runs the checked-in cases without it.

## Build and regenerate

Install [elan](https://github.com/leanprover/elan) (for example `brew install elan-init`), which
picks the Lean version in `lean-toolchain`. A weekly workflow
([`lean-update.yml`](../../.github/workflows/lean-update.yml)) bumps that file to the newest stable
Lean release in a `deps/lean-toolchain` PR, which merges once the required `Lean spec` check passes. Then, in this directory:

```bash
lake build --wfail      # check every proof, run the proof gate, and build the generators
lake exe gen-rx-cases        # rewrite RxSpecCases.scala from the Rx model
lake exe gen-rx-nested-cases # rewrite RxNestedSpecCases.scala from the nested-stream Rx model
lake exe gen-rx-r3-cases     # rewrite RxR3SpecCases.scala from the R3 event model
lake exe gen-msgpack-cases   # rewrite MsgPackSpecCases.scala from the MessagePack model
lake exe gen-http-reader-cases # rewrite HttpReaderSpecCases.scala from the request-framing model; fails if a lenient parser frames a request differently
lake exe gen-rx-live-cases   # rewrite RxLiveSpecCases.scala from the live-RxVar models
lake exe gen-dom-render-cases  # rewrite DomRenderSpecCases.scala; fails if the model's renderer disagrees with its spec
```

Commit the regenerated Scala file with the model change. The `Lean spec` CI job fails when the
checked-in cases differ from the model's output.

## Changing a model

- When `RxRunner` or `OffsetPacker` behaviour changes on purpose, change the model first, re-prove
  the theorems, and regenerate. A failing spec test then shows where the Scala code disagrees with
  the new rule.
- When a theorem no longer holds, the rule it states changed: update the matching ADR as well.
- The Rx models leave out concurrency. Only the live-var models (`RxLive.lean`,
  `RxLiveFanIn.lean`) model cancellation. `RxRunner` has one semantics: the R3 cases check it
  through `runContinuously` and through `run`, whose sink ends at the first error or end. The
  nested-stream cases are synchronous only. `RxRecover.lean` and the default mode of `RxNested.lean`
  and `Rx.lean` describe `run`'s view (a stream cut at its first error or end). The
  MessagePack model leaves out payload bytes and `unpackValue` dispatch. The
  DOM model leaves out attributes, bindings, events, portals, `onMount` timing, and re-entrant
  updates (a render that sets a variable). Add a theorem only for behaviour a model can express.
