# YAML: keep the line breaks of block scalars, and separate moved directive documents

## Goals

- Close two gaps in `adr/2026-10-28-yaml-lossless-tree.md` ("Not supported"), so an edit never changes a value it didn't touch and `writeStream` output is valid for strict readers.
  1. A block scalar (`|`, `>`, `|+`) can lose the line breaks at its end when it's rewritten.
  2. A document with a `%YAML` or `%TAG` directive, moved after a document that doesn't end with `...`, is written as is. A strict reader rejects it.

## Background

Reproduced on main (90ce63e9):

- `parseDocument("a: x").set(Seq("a"), "y\n\n").toYaml` gives `a: |+\n  y\n`, which reads as `"y\n"`. A document built in code has the same problem. The writer leaves the last line break of a block scalar to the text after it, but nothing follows at the end of a document that has no final line break. This also affects clip (`|`): `"a: |\n  x\nb: 1"` with `b` removed reads `a` as `"x"`.
- The parser ends a block scalar's `raw` at its last content line. The empty lines after it are part of a `|+` value, but they go into the next node's leading. So removing or moving that next node drops them: `"a: |+\n  x\n\nb: 1"` with `b` removed reads `a` as `"x"` instead of `"x\n\n"`. In a stream, they go to the next document's slice.
- YAML requires a `...` line before the directives of a document that follows another document. `writeStream` adds only `---`.

## Design

Lane: **Express**. Both are bug fixes with a clear cause; no public API changes.

- **Parser:** a keep (`+`) block scalar's `raw` ends before its final line break, so it includes its trailing empty lines. That matches what the writer prints for a changed `|+` scalar, so both cases follow one rule: a block scalar whose value ends with a line break needs one more line break after it. Comment lines after the scalar still lead the next node.
- **Writer:** remember where the last block scalar that needs a line break ends. If nothing is written after it by the end of the document, add a line break. In the middle of a document the next node's leading supplies it, as now.
- **writeStream:** before a document whose leading has a directive line, add `...` unless the previous document already ends with one.
- Tests: exact round trips and edits for `|`, `|+`, and `>` at the end of a document with and without a final line break, after removing and moving the next key, in code-built documents, and in streams; directive documents reordered in a stream. Update the ADR's "Not supported" section.

## Alternatives and Why Not?

- **Write the final line break as part of the scalar.** Every caller then has to know not to add another, and the unchanged-source case would still lose the empty lines that live in the next node's leading.
- **Only fix keep chomping.** Clip scalars lose their line break in the same situation, and the fix is the same.
