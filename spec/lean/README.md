# Lean specs for Uni

This Lake project states parts of Uni's semantics in [Lean 4](https://lean-lang.org/), proves their
properties, and generates test cases that Uni's own UniTest suites replay. Lean is a spec and a test
oracle here, never a dependency: no published artifact and no sbt project uses it. See
[`adr/2026-11-18-lean-specs.md`](../../adr/2026-11-18-lean-specs.md).

| Model | Covers | Generated cases | Replayed by |
| --- | --- | --- | --- |
| `UniSpec/Rx.lean` | `map`, `filter`, `take` completion rules | `uni/src/test/scala/wvlet/uni/rx/RxSpecCases.scala` | `RxSpecTest` (JVM, JS, Native) |
| `UniSpec/RxNested.lean` | `concat`, `merge`, `flatMap` completion rules | `uni/src/test/scala/wvlet/uni/rx/RxNestedSpecCases.scala` | `RxNestedSpecTest` (JVM, JS, Native) |
| `UniSpec/RxRecover.lean` | `recover` and continuous mode (`runContinuously`) | `uni/src/test/scala/wvlet/uni/rx/RxRecoverSpecCases.scala` | `RxRecoverSpecTest` (JVM, JS, Native) |
| `UniSpec/RxLive.lean` | `flatMap`/`transformRx` switching, `split`, `zip`/`join`, `take` after `Stop`, and cancel over live `RxVar`s | `uni/src/test/scala/wvlet/uni/rx/RxLiveSpecCases.scala` | `RxLiveSpecTest` (JVM, JS, Native) |
| `UniSpec/MsgPack.lean` | MessagePack integer and str/bin/array/map/ext header bytes | `uni/src/test/scala/wvlet/uni/msgpack/spi/MsgPackSpecCases.scala` | `MsgPackSpecTest` (JVM, JS, Native) |
| `UniSpec/HttpReader.lean` | HTTP/1.1 request framing on a keep-alive connection (the built-in JVM and Native servers) | `uni/.jvm-native/src/test/scala/wvlet/uni/http/HttpReaderSpecCases.scala` | `HttpReaderSpecTest` (JVM, Native) |

You need Lean only to change a model. `./sbt test` runs the checked-in cases without it.

## Build and regenerate

Install [elan](https://github.com/leanprover/elan) (for example `brew install elan-init`), which
picks the Lean version in `lean-toolchain`. Then, in this directory:

```bash
lake build              # check every proof and build the generator
lake exe gen-rx-cases        # rewrite RxSpecCases.scala from the Rx model
lake exe gen-rx-nested-cases # rewrite RxNestedSpecCases.scala from the nested-stream Rx model
lake exe gen-rx-recover-cases # rewrite RxRecoverSpecCases.scala from the recover/continuous model
lake exe gen-msgpack-cases   # rewrite MsgPackSpecCases.scala from the MessagePack model
lake exe gen-http-reader-cases # rewrite HttpReaderSpecCases.scala from the request-framing model
lake exe gen-rx-live-cases   # rewrite RxLiveSpecCases.scala from the live-RxVar model
```

Commit the regenerated Scala file with the model change. The `Lean spec` CI job fails when the
checked-in cases differ from the model's output.

## Changing a model

- When `RxRunner` or `OffsetPacker` behaviour changes on purpose, change the model first, re-prove
  the theorems, and regenerate. A failing spec test then shows where the Scala code disagrees with
  the new rule.
- When a theorem no longer holds, the rule it states changed: update the matching ADR as well.
- The Rx models leave out concurrency. Only `RxLive.lean` models cancellation and a sink that
  answers `Stop` to every event; the others model downstream `Stop` only as the sink stopping at
  its first terminal event (default mode). The nested-stream cases are synchronous
  only, and continuous mode covers `map`, `filter`, `take`, and `recover`. The
  MessagePack model leaves out floats, timestamps, payload bytes, and `unpackValue` dispatch. Add a
  theorem only for behaviour a model can express.
