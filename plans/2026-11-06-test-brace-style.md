# Brace style for `test(...)` blocks

## Goals

- Write UniTest cases as `test("...") { ... }` everywhere, so you can see where each test body ends.
- Keep colon-style `test("..."):` from creeping back in, since CI cannot rely on scalafmt to catch it.

## Background

- 58 Scala files (mostly `uni-dom-test`, `uni-ui`, `sbt-uni-playwright`) use the colon form; 269 use braces.
- scalafmt never writes the colon form here (`fewerBraces` removal is off by default), so these were written by hand.
- scalafmt 3.11.5 cannot convert colon arguments back to braces. Tested: `rewrite.insertBraces` is ignored. `optionalBraces.insert` wraps `def` bodies and class templates but leaves `test(...):` alone.

## Design

Lane: **Express**: a mechanical rewrite of test code and docs plus a lint step, with no behavior change and no always-gate area.

- One-time script rewrites `test(...):` blocks to `test(...) { ... }`, handling nested tests and removing `end test` markers.
- Apply the same rewrite to user-facing docs and the `uni` skill (`docs/`, `plugins/uni/skills/`). Leave historical `plans/` as they are.
- Add a `git grep` step to the required **Code format** CI job that fails on `test(...):` at line end in `*.scala` and docs Markdown.
- Document the convention in AGENTS.md (Testing) and `.github/instructions/unitest.instructions.md`.
- Bump the `uni` plugin version, because its skill text changes.

## Alternatives and Why Not?

- scalafmt-only config: not possible in 3.11.5 (verified above). Unreleased 3.11.6 adds `insert.includeOwners`; revisit once it ships and drop the grep if scalafmt can enforce braces.
- Scalafix rule: adds a new tool and a custom rule just for this; a grep guard is enough.
- Global `optionalBraces.insert`: puts braces on the whole codebase, which goes against the repo's Scala 3 style.
