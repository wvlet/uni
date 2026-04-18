# Text Parser Toolkit for uni-core

## Motivation

`wvlet-lang` contains a generic, reusable text-parsing toolkit (Span, Tokens,
TokenBuffer, TokenTypeInfo, ScannerBase) along with `CodeFormatter`. These
primitives have no dependency on the Wvlet language itself and are a natural fit
for Uni's standard utility library. Uni already contains a port of
`CodeFormatter` at `uni-core/src/main/scala/wvlet/uni/text/CodeFormatter.scala`
but is missing the scanner/token foundation.

The goal of this change is to land the remaining pieces in `uni-core` so that
future parsers (e.g., a rewrite of `wvlet.uni.json.JSONScanner`) can share a
single generic scanner base.

## Source references

From `/Users/leo/work/wvlet/wvlet-lang`:

- `wvlet-api/src/main/scala/wvlet/lang/api/Span.scala` (127 lines) — value class
  encoding start/end/point in a single Long.
- `wvlet-lang/src/main/scala/wvlet/lang/compiler/parser/Tokens.scala` — char
  constants (`LF`, `CR`, `SU`, etc.) and helpers.
- `.../parser/TokenBuffer.scala` — growable char buffer used by the scanner.
- `.../parser/TokenType.scala` — `TokenTypeInfo[Token]` typeclass interface.
- `.../parser/Scanner.scala` — `TokenData`, `ScanState`, `ScannerConfig`,
  abstract `ScannerBase[Token]`, and `Scanner.Region` ADT. Uses wvlet-specific
  `SourceFile`, `CompilationUnit`, `LinePosition`, `SourceLocation`,
  `WvletLangException`, `StatusCode` — to be stripped when porting.

## Proposed layout

Add the new files to `uni-core` under the existing `wvlet.uni.text` package
(which already hosts `CodeFormatter`). No new sbt subproject — the surface area
is small and keeping it next to `CodeFormatter` matches the "minimal deps"
philosophy of `uni-core`.

```
uni-core/src/main/scala/wvlet/uni/text/
├── CodeFormatter.scala      (exists)
├── Span.scala               (new)
└── parser/
    ├── Tokens.scala         (new)  char constants / helpers
    ├── TokenBuffer.scala    (new)
    ├── TokenType.scala      (new)  TokenTypeInfo trait
    └── Scanner.scala        (new)  TokenData, ScanState, ScannerBase, Region
```

Package: `wvlet.uni.text` for `Span`; `wvlet.uni.text.parser` for the rest.

## Dependency trimming when porting `Scanner.scala`

Wvlet's `ScannerBase` depends on a `SourceFile` + `CompilationUnit` stack that
is tied to the Wvlet compiler. To keep `uni-core` lean, the uni port will:

- Take the content as `IArray[Char]` (and optionally a display name for error
  messages) instead of a `SourceFile`.
- Drop the `TokenData.sourceLocation(using CompilationUnit)` helper. The
  `span: Span` helper stays.
- Replace `WvletLangException` / `StatusCode.UNEXPECTED_TOKEN` with a plain
  `TextParseException` (new, lightweight) or a configurable error callback.
- Swap `wvlet.log.LogSupport` for `wvlet.uni.log.LogSupport` (already in
  `uni-core`).

The resulting `ScannerBase` stays behaviorally equivalent for all of the
concrete subclasses (WvletScanner, SqlScanner, MarkdownScanner) that exist
today — the port changes only the source-file abstraction and error path.

## Testing

Add `uni-core/src/test/scala/wvlet/uni/text/` with:

- `SpanTest` — roundtrip start/end/point encoding, `extendTo`, `contains`,
  `NoSpan` semantics.
- `TokenBufferTest` — grow, clear, `toString`, `last`.
- A small `ScannerBaseTest` that implements a minimal token type (a toy
  whitespace/identifier/number scanner) to exercise `nextToken`, `lookAhead`,
  comment handling, and error reporting.

`CodeFormatter` already has (or will receive) coverage — not in scope here.

## Out of scope (follow-up)

- Migrating `wvlet.uni.json.JSONScanner` onto `ScannerBase`. JSON has a very
  different tokenization model (state machine over bytes, no comments,
  performance-sensitive), so the migration deserves its own PR once the toolkit
  is proven.
- Porting any concrete scanner from wvlet-lang (Wvlet/SQL/Markdown). Those stay
  in wvlet-lang; this PR only extracts the reusable substrate.

## Risks

- **Scope creep into uni-core.** Keeping this to ~5 small files plus tests
  mitigates this. Total new code is roughly 900–1000 lines, most of it
  mechanical copy from wvlet-lang.
- **API drift from wvlet-lang.** Both copies will evolve independently. The
  trimmed source-file abstraction already diverges, so we accept this and treat
  the uni copy as the canonical one going forward.

## Execution checklist

- [ ] Port `Span.scala` verbatim (it is already free of wvlet deps).
- [ ] Port `Tokens.scala`, `TokenBuffer.scala`, `TokenType.scala` verbatim
      (only package statement changes, swap `wvlet.log.LogSupport` for the uni
      one in `TokenBuffer`).
- [ ] Port `Scanner.scala`, stripping `SourceFile` / `CompilationUnit` /
      `WvletLangException` / `StatusCode` dependencies as described above.
- [ ] Add `TextParseException` (minimal, extends `RuntimeException`).
- [ ] Add unit tests under `uni-core` using UniTest.
- [ ] `./sbt scalafmtAll` and `./sbt coreJVM/test`.
- [ ] Open PR with `feature/` prefix and wait for Gemini review.
