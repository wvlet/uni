# Reduce fuzz failures by lines, tokens, and tree nodes

## Goals

- Turn a large failing input, such as a 2 KB query, into the few clauses that matter, without the user writing a reducer.
- Finish the open part of the reducer item: ddmin over lines and tokens, and tree reduction through `Shrink`.
- Keep crash files and replay as they are: the saved input stays the reduced bytes.

## Background

- `Reduce.bytes` (ddmin, then lowering each byte, then ddmin again, #1204) is the only reducer. It works well for binary formats and for `fuzzGen`, where fewer and smaller bytes mean smaller values.
- Byte ddmin on text cuts through keywords and identifiers, so most candidates fail to parse with a different exception and are rejected. The result keeps far more text than needed. Line and token units keep each candidate lexically valid.
- Byte reduction cannot hoist a subtree in a `fuzzGen` value: removing bytes shifts every later draw, so a failure deep in a tree stays wrapped in its ancestors. `Shrink` works on the value and can replace a node with its child.
- Wvlet parses query text, so text reducers help it without any AST knowledge. A value-level tree shrink helps its generated ASTs (step 3 of the goal).

## Design

Lane: Standard — new public uni-test API (`Reduce.lines`, `Reduce.tokens`, `Reduce.shrink`, `Shrink.tree`), a change in what `fuzzGen` reports, and a reversal of the fuzz ADR's "typed targets need no `Shrink`".

- **`fuzzString` reduces by lines, then tokens, then drops bytes.** All three work on bytes, so invalid UTF-8 stays byte-exact in the crash file:
  - A line ends at `\n`.
  - A token is a run of ASCII `[A-Za-z0-9_]` or non-ASCII bytes (so UTF-8 sequences stay whole), a run of whitespace, or one other byte. Tokens join back to the input.
  - The last pass is byte ddmin without lowering: lowering would turn surviving identifiers into NUL bytes.
  - Binary `fuzz` targets keep today's reduction (ddmin + lowering).
- **`Reduce.lines` and `Reduce.tokens` are public** (`Array[Byte]` in and out, like `Reduce.bytes`), so a user can reduce a failing query in a test or REPL.
- **`Reduce.shrink(value, fails, maxSteps)(using Shrink[A])` is the greedy loop**: keep the first candidate for which `fails` is true, until none is.
  - `forAll` keeps its own loop (`PropertyCheck.minimise`) in this PR; it has a different failure rule (any `Throwable`) and every property test depends on it.
- **`Shrink.tree(children, withChildren)(local)` builds a `Shrink` for an AST.** Candidates, in order:
  - Hoist: replace the node with one of its children.
  - Drop children: all of them, then one at a time; `withChildren` returns None when the node needs a fixed arity (e.g. `Neg` with no child).
  - Shrink in place: the user's `local` candidates (e.g. `Num(v)` to `Num(0)`).
  - Recurse: shrink each child and put it back.
- **`fuzzGen` takes an explicit `shrink: Shrink[A] = Shrink.none` parameter**, not a `using`: derived givens for `List`/`Option`/tuples would otherwise switch value shrinking on for existing targets.
  - After byte reduction, the decoded value is shrunk with the same rule as bytes: fails with the same exception class, expected exceptions count as passing, skip/pending propagate, a slow input runs twice.
  - The report keeps `value:` (what the saved bytes replay), the crash_id, and the `.report` tied to the saved bytes. It adds `shrunk value:` and the shrunk value's exception on their own lines.
  - The crash file stays the bytes: a `Gen` has no inverse, so a shrunk value has no byte form.
- **One step budget for all passes.** `FuzzCheck.fail` counts calls to the predicate and hands each pass what remains of `maxReduceSteps`, so the cap of 30 steps for slow inputs covers the total.
- **ADR**: add an "Update: text and tree reduction" section to `adr/2026-11-26-fuzz-testing-in-unitest.md` (why byte reduction cannot hoist a subtree, so `fuzzGen` now accepts a `Shrink`), and update its index entry.
- **Tests**:
  - Lines and tokens reduce a planted failure in a long query text to the one bad token.
  - A failing subtree nested under several ancestors (`Add(List(Neg(Neg(Add(...)))))`): byte reduction keeps the ancestors, and `Shrink.tree` hoists them away.
  - `fuzzGen` reports the shrunk value, and existing targets without a `shrink` report as before.
- **Docs**: the fuzz sections of `docs/core/unitest.md`, the plugin skill's `references/testing.md` (with a `plugin.json` version bump), and `for-ai-agents.md` if it lists reducer APIs. New `scala` fences must compile in `docSnippets`.

## Alternatives and Why Not?

- **Derive tree shrinking from `Surface` (enum/case class fields of the same type).** Automatic, but needs reflection on every platform and guesses which fields are children; an explicit `children`/`withChildren` pair is a few lines per AST and exact. Can be added on top later.
- **Save the shrunk value's bytes by re-encoding it.** A `Gen` has no inverse, so there is no byte form to save.
- **A grammar-aware text reducer (Perses-style).** Needs a grammar per language; lines and tokens get most of the gain for query text with none.
