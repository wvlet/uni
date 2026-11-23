# Next steps for the HTTP/1.1 reader's Lean spec

## Where it stands

`spec/lean/UniSpec/HttpReader.lean` (691 lines) models `HttpConnectionReader`
(`uni/.jvm-native/src/main/scala/wvlet/uni/http/SocketHttpProtocol.scala`) as
`readAll : maxSize → Bytes → List Outcome`. `GenHttpReaderCases.lean` writes 127 cases to
`HttpReaderSpecCases.scala`. `HttpReaderSpecTest` (JVM, Native) replays each case in reads of 1, 2,
3, 5, 8, 13, 64 and 65536 bytes. uni-netty does not replay them; `NettyMalformedRequestTest` checks a
few of the same statuses by hand.
The `Lean spec` CI job runs `lake build` and checks that the generated cases are up to date. See
`adr/2026-11-18-lean-specs.md` and `adr/2026-11-18-socket-server-netty-parity.md`.

Today's theorems are local facts: lines and heads split the input, a framed body never exceeds the
limit, `Transfer-Encoding` beats `Content-Length`, a request framed both ways closes the
connection, and only the last outcome can be other than a kept-alive request.

The model does not state the property the strict parser exists for: **no request smuggling**. It
also has no rule about read splits, no size limits, and no check that the proofs are complete.
h5i's `h5i-app-h1` (a Rust HTTP/1.1 parser translated to Lean) proves these properties. This plan
adapts them to Uni's setup, where Lean is a spec and test oracle and is never linked into Uni.

## Goals

1. CI rejects a model whose proofs are unfinished: `sorry`, `native_decide`, or an extra axiom.
2. A theorem that the reader's framing agrees with every member of a family of lenient parsers.
3. A theorem that a decided outcome does not change when more bytes arrive, which is what the Scala
   test's read splits rely on.
4. Line, header and trailer limits in the model, so the 414 and 431 cases come from Lean too.
5. Evidence that the cases catch real bugs: planted bugs in the Scala reader must fail
   `HttpReaderSpecTest`.

## Non-goals

- Proving things about the Scala code. Lean and Scala still meet only through generated cases (ADR
  2026-11-18). Uni has no Scala-to-Lean translator like Aeneas.
- The Node.js server, which uses Node's own parser.
- Timeouts, `100 Continue`, and request-target decoding. They stay left out, as today.
- Mathlib. The project stays on Lean core so a build takes seconds.

## Steps

Each step is one PR. Step 1 comes first. Steps 2 to 5 are independent.

### 1. A proof gate for every model

`lake build` passes with `sorry`, which is only a warning. Add `spec/lean/Check.lean` with a small
command that collects every `theorem` in the `UniSpec` namespace and runs `collectAxioms` on each.
It fails unless each one uses only `propext`, `Classical.choice` and `Quot.sound`. That catches
`sorryAx` (including one left by an elaboration error) and any `axiom` declaration. It also fails
on `Lean.ofReduceBool`, which `native_decide` adds.

- Add a `check` executable (`lake exe check`) and run it in the `Lean spec` job after
  `lake build`. Also build with `lake build --wfail`, so new warnings fail CI.
- Add a deliberate failure case in a scratch file during development to confirm the gate
  catches it. Don't commit that file.
- This covers the Rx, MessagePack and DOM models too. Fix anything it finds in the same PR.

### 2. No smuggling, relative to a lenient family

Add `UniSpec/HttpSmuggle.lean`, modeled on h5i's `H1Smuggle.lean`. A smuggling attack needs two
parsers on one byte stream to disagree on where a message ends. Define
`Interp (o : Opts) : Bytes → Option (Nat × Framing)`, a family of lenient head parsers. Each
`Opts` field is a choice that real proxies and servers have shipped:

| Option | Lenient choice |
| --- | --- |
| `lines` | end lines at CRLF only, or also at a bare LF |
| `dupLast` | of several `Content-Length`s, the last one wins (otherwise the first) |
| `teWins` | with both framing headers, `Transfer-Encoding` wins (otherwise `Content-Length`) |
| `stripColonWs` | `Transfer-Encoding :` names the coding, or is another field |
| `unfold` | obs-fold continues the previous field, or is ignored |
| `trimValue` | trailing whitespace is trimmed from values, or kept |
| `leadingZeros` | `Content-Length: 007` reads as 7, or is rejected |

Every member compares names case-insensitively, ends the head at the first empty line, and treats
the body as chunked when the last coding is `chunked`. Members must not reuse the reader's own
helpers (`isTokenChar`, `lower`, `scanLine`). Each member gets its own small definitions, so a bug
in a shared helper can't make both sides agree by accident.

Theorem `no_smuggling`: if `readHead` and `frame` accept bytes `s`, then every member, given any
prefix of `s` that holds the whole head, accepts too, with the same head length and the same
framing (`length n` or `chunked`). State in the doc comment which parsers fall outside the family,
as h5i does. Examples: a parser that compares names case-sensitively, or one that reads a
`Content-Length` list.

Do this in two stages, so the theorem has evidence before the proof is done:

- **2a (checked, not proven). Done.** `UniSpec/HttpSmuggle.lean` defines the family (64 members,
  the six options above; `leadingZeros` was dropped, since a member that rejects leading zeros
  cannot smuggle). `GenHttpReaderCases` checks every input and 67,191 one-edit variants of each
  input's first head: an insertion of CR, LF, SP, HTAB, `,`, CRLF, or CRLF plus SP or HTAB at each
  position, a byte replaced with one of the first five, or a header line repeated. It fails if a
  member frames a request the reader keeps the connection open after differently. A member that
  rejects counts as agreeing, because a proxy that rejects forwards nothing. Uni's reader trims
  trailing whitespace from values, where h5i's rejects it, so the members that keep it reject
  `Content-Length: 2 `. It also fails if some option never splits the family. Variants that the
  reader rejects (or closes after), where the edit makes two members frame differently, become
  `smugglingCases`: the first per kind of outcome and set of options that matter, 8 today. A
  planted bug in the model (whitespace before the colon stripped from names) gave 1,216
  disagreements.
- **2b (proven).** `no_smuggling` itself. Chunked bodies get a separate theorem over a smaller
  family: bare LF in a size line, and extensions containing CR.

### 3. Outcomes don't depend on read splits

`HttpReaderSpecTest` checks splits on 128 inputs. Prove the rule behind it:

- `readOne_extend`: if `readOne s = (o, rest)` and `o` is not caused by end of input, then
  `readOne (s ++ t) = (o, rest ++ t)`. Outcomes caused by end of input are `closed`,
  `Incomplete request` and `Incomplete request body`.
- To make that statable, split `Outcome.bad` into end-of-input outcomes and decided ones. One way
  is a new `Outcome.eof (code) (message)` constructor that the generator prints exactly as today,
  so the Scala side doesn't change.
- `readAll_extend` follows by induction, using `readAllAux_only_last`.

### 4. Size limits

Add `Limits` (`maxLine`, `maxHeader`, `maxBody`) in place of `maxSize`, matching
`HttpReaderLimits`, with 414 for the request line, 431 for headers and trailers, and 400 for a long
chunk-size line. Theorems:

- `readHead_reads`: once `maxLine + maxHeader + 4` bytes are buffered, the head outcome depends on
  those bytes alone. This is the model-side rule that a slow client can't make the reader buffer
  without bound.
- Each limit rejects with its own status. For example, a request line one byte over the limit gets
  414 whatever follows it.

The generator then writes boundary cases (exactly at the limit, one byte over) with small limits.
`HttpReaderSpecTest` passes the same small limits instead of `1 << 20`.

### 5. Planted bugs in the Scala reader must fail the cases

Add `spec/lean/mutants/http-reader.txt`. Each entry is an `old`/`new` replacement in
`SocketHttpProtocol.scala`, in the format of h5i's `[[mutant]]`. Start with about 10, each a bug a
lenient parser has shipped:

- accept a bare LF
- trim whitespace before the colon
- the last `Content-Length` wins
- `Content-Length` beats `Transfer-Encoding`
- accept `chunked` applied twice
- allow `Transfer-Encoding` on HTTP/1.0
- `>` instead of `>=` at the body limit
- keep the connection open after a request framed both ways
- accept a `Host` header twice
- an off-by-one in `readChunked`'s CRLF check

A script, `scripts/mutate-http-reader.sh`, applies each one in a scratch copy of the worktree, runs
`./sbt "uniJVM/testOnly *HttpReaderSpecTest"`, and reports the survivors. (`testOnly` forces the
run, because sbt 2's test cache can report a run as cached.) A surviving mutant means a missing case, which gets added to `inputs`. Run the
script on demand and when this model changes, not in CI: each mutant recompiles `uni`.

## Docs

- Update `spec/lean/README.md`: the gate, the new model file, and the mutant script.
- Add an entry to `adr/2026-11-18-lean-specs.md` (or a new ADR) for the smuggling theorem and what
  the family leaves out.

## Open questions

- Is the extra outcome constructor in step 3 worth it, or should the end-of-input cases be
  recognized by their message text? The constructor is cleaner to state theorems about.
- Should step 2's family also cover response heads (`HttpResponseWriter`)? Response splitting is
  the same class of bug: a CR or LF in a header value. A follow-up plan could model
  `serializeHead` the way h5i proves `write_head_spec` and `write_field_spec`.
