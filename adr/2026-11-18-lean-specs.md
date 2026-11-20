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
- The second model covers MessagePack integers and str/bin/array/map/ext headers, as if-chains that
  mirror `OffsetPacker`. Theorems: decoding inverts encoding and consumes exactly the encoded bytes,
  every byte is below 256, the chosen format is no longer than any other the spec allows, and
  non-negative integers never use `int*`. `MsgPackSpecTest` checks the exact bytes `OffsetPacker`
  writes at every format boundary, which `RoundTripTest`'s round-trips cannot catch, and reads them
  back with `OffsetUnpacker`. Lengths stop at `Int.MaxValue` because the Scala API takes an `Int`.
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
- The MessagePack model leaves out floats, timestamps, payload bytes, and `unpackValue` dispatch.
- Proof style: write encoders as if-chains and give each branch its own proof step. A `first | ... |
  ...` combinator that tries the wrong lemma can send Lean's defeq check into deep unfolding of `be
  k n` (maximum recursion), which `first` does not catch.
- `UniSpec/RxNested.lean` extends the Rx trace model to `concat`, `merge`, and `flatMap` with
  synchronous sources, where `RxRunner` is deterministic: `merge` subscribes in order and
  `flatMap` runs each nested stream to its end or until it goes quiet. Its theorems: `concat`
  identity and associativity, `merge` completes exactly when every source completes and fails
  exactly when one fails (an ending invariant under any reordering, so it also covers asynchronous
  interleavings), and `flatMap` completes only after its input and last nested stream complete,
  with the single-value identity laws. The cases use `Rx.MergeOp` directly so 0 and 1 sources go
  through `RxRunner`'s merge rather than `RxParallel.merge`'s shortcuts.
- `UniSpec/HttpReader.lean` models HTTP/1.1 request framing in `HttpConnectionReader` (see `2026-11-18-jvm-builtin-http-server.md`). `HttpReaderSpecTest` (JVM and Native, in `uni/.jvm-native`) replays each byte stream in reads of several sizes. The model leaves out the size limits on lines and headers, timeouts, and `100 Continue`. `2026-11-18-socket-server-netty-parity.md` extends it to strict head parsing, chunked bodies, and keep-alive.
- `UniSpec/RxRecover.lean` covers `recover` and continuous mode. Operators act per event, and the
  mode decides the cuts: default mode cuts at the source's first terminal event (`Rx.queue` stops
  there) and at the sink's (it answers `Stop`); continuous mode cuts at neither, except inside
  `take`. Theorems: a total `recover` never emits `OnError`; a recovered source error leaves the
  stream open with no completion in default mode, while a recovered operator error continues to
  completion; continuous mode delivers events after a terminal one; `take` still ends at the first
  terminal event in continuous mode. Its cases found that continuous-mode `take(n)` skipped its
  completion when the nth value's downstream failed, and review of the model found `recover`
  ignoring the downstream's `Stop`, so events reached the sink after `OnError`. Both are fixed
  (see `2026-10-19-rx-take-completion.md` and `2026-10-14-rx-completion-semantics.md`).
- `UniSpec/RxLive.lean` drives live `RxVar`s with a script of `set`, `stop`, `fail`, and `cancel`
  actions and mirrors each operator's state in `RxRunner`. `RxVar` delivers synchronously, so a
  script gives one event order on every platform. It covers `flatMap`/`transformRx` switching
  (`NestedStreamTracker`), `split`, `zip`/`join`, `take` after a downstream `Stop` (including the
  `Stop`-before-nth case above), and cancellation. Its theorems: a replaced nested stream reaches
  the sink with nothing, the operator completes only after the input and the current nested stream
  have both ended, `split` emits a list only when its keys change and stops removed rows after it,
  one `zip` buffer is always empty until it fails, `take(n)` with a sink that stops at every event
  delivers exactly one, and nothing reaches the sink after `cancel`. The sink records every event,
  including events after a terminal one, since `RxVar` ignores `Stop`. Its cases found that a
  throwing `flatMap` function left the previous nested var subscribed, and that `zip` over vars
  completed after its first pair in default mode (both fixed, see
  `2026-10-14-rx-completion-semantics.md`). It leaves out continuous mode, `recoverWith`, and
  `parFlatMap`.
- `UniSpec/DomRender.lean` models how `DomRenderer` places what an `Rx` renders. The parent is a flat
  list of nodes with ids. The model runs the renderer's own steps: insert before an anchor, the
  backward walk that clears a section, and the keyed walk behind a cursor. A spec (`denote`) gives
  the text the current values should show. Theorems cover the primitives: inserting at an anchor,
  the clear walk removing exactly a section's content when its markers are paired and throwing when
  they are not, and one keyed item per key. Agreement with the spec, the main invariant, is checked
  rather than proved: `gen-dom-render-cases` runs all 370 views (every shape to depth 2, plus
  components nested in sections at depth 3, each with its own variables and again with shared
  ones) through every update and fails on any disagreement. A
  general proof would need an invariant relating the flat list to the tree of live sections, which
  costs far more than it adds over exhaustive small cases. `DomRenderSpecTest` replays the cases in
  Chromium, checking the text and which leaves stay the same DOM node. The model also keeps the
  renderer as it was before the fix (`appendThenMove`), where 24 of the cases fail. Reading the code
  against the model's invariant found three bugs from an `RxElement` appending to the end of the
  parent and moving only its last node to the anchor, and `RxElement(rx)` never rendering. Review of
  the model found a fifth: an `RxVar` notifies the subscribers it had before an update, so a section
  nested in another on the same variable got the update after the outer one removed its markers,
  and its clear walk threw. Sections and keyed lists now ignore updates once cancelled, as the model
  assumed. All are fixed (see `2026-11-05-keyed-list-rendering.md`).
- Next candidates: continuous-mode `flatMap`/`merge`, `recoverWith`, `parFlatMap` ordering, and
  MessagePack floats and timestamps.
- Rejected: linking Lean-compiled C into Scala Native (one platform, ships the Lean runtime);
  verifying the Scala code with Stainless (does not fit Uni's Scala 3 code); hand-written expected
  outputs in property tests (they restate the implementation's own assumptions).
