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
- The MessagePack model also covers floats and timestamps. A float is its IEEE 754 bits as a
  number, so the model needs no float arithmetic: `float 32`/`float 64` plus the big-endian bits,
  read back to the same bits. The cases also read each float at the other width (`unpackDouble` of a
  `float 32`, `unpackFloat` of a `float 64`); their expected bits come from Lean's own `Float32` and
  `Float`, an IEEE implementation independent of the three platforms, and a NaN only has to stay a
  NaN. Timestamps model `packTimestampEpochSecond`: the `floorDiv`/`floorMod` normalization of any
  `Int` nanos, the overflow `Math.addExact` throws on, and the choice among timestamp 32, 64, and
  96. Theorems: decoding returns the normalized seconds and nanos, and the format is the shortest
  that holds them. The cases also cover what `unpackTimestamp` rejects: seconds outside `Instant`'s
  range pack fine but cannot be read back. Writing the cases found that JS and Native wrote floats
  with `floatToIntBits`/`doubleToLongBits`, which replace every NaN with the canonical one, while
  the JVM kept the raw bits; all three now write the raw bits. On JS a `Float` is a JS number, so a
  signaling `float 32` NaN still comes back quiet there; the cases leave that one bit pattern out.
- Lean and Scala meet through generated cases, not proofs about Scala code. A Lean executable writes
  `RxSpecCases.scala` (scalafmt-formatted, marked "Do not edit"). `RxSpecTest` feeds each input into
  `Rx.queue`, which accepts any event sequence and delivers queued events synchronously, and compares
  what `RxRunner` emits. Generated Scala source, not a classpath resource, so the cases run on JS and
  Native too.
- The `Lean spec` CI job runs `lake build --wfail` and fails when regenerating changes the checked-in
  cases. The build includes a proof gate, `spec/lean/Check.lean`: every declaration in a `UniSpec`
  module may depend only on `propext`, `Classical.choice`, and `Quot.sound`, so a `sorry`, a
  `native_decide`, or an `axiom` fails the build rather than passing with a warning.
  It runs only when `spec/lean/**`, the generated file, or the workflow changes. The Scala side runs
  in the normal test jobs, so an Rx change is checked against the model without Lean installed.

## Consequences

- Contributors who do not change a model never need Lean. Changing intended Rx behaviour means
  changing the model and regenerating; the proofs then show which stated rules still hold.
- The model leaves out downstream `Stop`, continuous mode, cancellation, and concurrency, so it says
  nothing about the `Stop`-before-nth case in `2026-10-19-rx-take-completion.md`.
- The MessagePack model leaves out payload bytes and `unpackValue` dispatch.
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
- `UniSpec/HttpReader.lean` models HTTP/1.1 request framing in `HttpConnectionReader` (see `2026-11-18-jvm-builtin-http-server.md`). `HttpReaderSpecTest` (JVM and Native, in `uni/.jvm-native`) replays each byte stream in reads of several sizes. The model leaves out timeouts and `100 Continue`. Its size limits (`Limits`, matching `HttpReaderLimits`) count bytes as the reader does, so the CR LF that ends a line and the empty line may lie past a limit, and `UniSpec/HttpReaderLimits.lean` proves the head is decided within `maxLine + maxHeader + 6` bytes after the empty lines before it. `2026-11-18-socket-server-netty-parity.md` extends it to strict head parsing, chunked bodies, and keep-alive.
- `UniSpec/HttpSmuggle.lean` states the reason for the strict parsing: no request smuggling, relative to a family of 64 lenient head parsers (bare LF line ends, last `Content-Length` wins, `Transfer-Encoding` wins, whitespace before the colon stripped, obs-fold unfolded, trailing whitespace kept). Whenever the reader accepts a request and keeps the connection open, each member either rejects it or finds the same head length and framing. `UniSpec/HttpSmuggleProof.lean` proves it for every byte stream (`no_smuggling`), and `gen-http-reader-cases` also checks it over every input and every one-edit variant of each head, failing on any disagreement. Variants on which members disagree with each other become `smugglingCases`, which the reader must reject. Chunked bodies are not covered yet (see `plans/2026-11-23-http-reader-lean-spec.md`).
- `UniSpec/HttpReaderExtend.lean` proves why replaying a stream in reads of any size must give the outcomes `readAll` gives for the whole stream. An outcome other than `closed` or `incomplete` (`Outcome.atEof`) is fixed by the bytes before it: `readOne_extend` keeps it with any bytes after, and `readAll_extend` keeps a whole stream's outcomes when the last one is not `atEof`. `incomplete` is its own constructor so the theorem can name it. The generated cases still print it as `bad 400`.
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
  `2026-10-14-rx-completion-semantics.md`).
- The live-var model also covers `recoverWith`, continuous mode for `flatMap`/`transformRx`, and,
  in `UniSpec/RxLiveFanIn.lean`, `merge` and `parFlatMap` in both modes. `parFlatMap`'s order across
  threads depends on scheduling, but nested `RxVar`s deliver on the caller's thread, so a script
  fixes the order of its permit queue. The model proves the rule that holds under any scheduling:
  at most `parallelism` nested streams are subscribed after every step; its cases check that queued
  values start in input order. Its cases found that `recoverWith` kept its input subscribed after a
  recovery, so input events followed the recovery stream, and that in continuous mode a failed
  nested stream of `parFlatMap` held its permit for good (both fixed, see
  `2026-10-14-rx-completion-semantics.md` and `2026-10-27-rx-merge-parflatmap.md`).
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
- Rejected: linking Lean-compiled C into Scala Native (one platform, ships the Lean runtime);
  verifying the Scala code with Stainless (does not fit Uni's Scala 3 code); hand-written expected
  outputs in property tests (they restate the implementation's own assumptions).
