# UI gallery: linking each part of the preview to the code that creates it

- Status: Accepted
- Date: 2026-11-21
- Plan: `plans/2026-11-21-ui-gallery-docs.md`

## Context

The UI gallery (`examples/ui-gallery`, the docs' Gallery page) shows each uni-ui story next to its
code. Readers should be able to point at a part of the preview and find the line that made it, and
point at a line and see what it makes. Matching component names in the code text (the first
version) highlights every line that mentions `Button`, misses components whose root has no fixed
CSS class, and can't tell two buttons on different lines apart.

## Decision

- **A macro tags each component with its source line.** `SourceTag(code)` walks the typed tree of
  a story and wraps each expression that builds a `wvlet.uni.ui` component in
  `SourceTag.tag(e, kind, line)`. The wrapper sets `data-src-kind` and `data-src-line` on the
  component's root element when it mounts. A closed dialog renders a placeholder that isn't an
  element, so the attributes are not modifiers. The macro also returns the list of tags, so chips
  and line marks are known before anything renders (closed dialogs, playground previews).
- **Only where a plain `RxElement` fits.** A tagged component is an `RxElement`, not a `Button`,
  so the macro wraps an argument only when the parameter accepts an `RxElement` (including
  by-name, varargs, and named arguments), and a lambda's result only when its declared type does.
  `MenuItem` entries (typed `MenuEntry`), the receiver of `.primary`, and references to a `val`
  are left as they are.
- **Story `def`s are `inline`.** `Story(id, title, summary)(buttons)` passes the call into the
  macro, which sees the inlined body with its original positions. Readers who copy a story get a
  plain method; `inline` changes nothing for them. Scala 3 rejects case classes inside an
  `inline def`, so a story's data types sit just above it, inside the same `// #region`.
- **The wrapping call is built with the reflection API, not a quote.** A quote re-owns the
  lambdas inside the spliced tree (`onClick` handlers), which crashed lambda lifting.
- **Playgrounds substitute values into the shown code.** A playground story's `inline def` takes
  its props `p`; the code panel replaces each `p.<name>` with the control's value written as
  Scala. The code shown still compiles, because the source keeps `p.<name>`.

Rejected: adding a source-position API to uni-dom (a public API for one example), wrapping every
story in a visible `story { ... }` block (readers would copy a call that doesn't exist in their
app), and keeping the regex matching (inexact).

## Consequences

- The incremental compiler doesn't recompile the stories when only `SourceTag.scala` changes, so
  run `./sbt exampleUiGallery/clean` after editing the macro.
- A component built outside the story's code (in a helper method that isn't `inline`, or held in
  a `val` first) is not tagged.
- Overlays rendered at the end of `<body>` (an open menu's popup) carry no tags, so the inspector
  finds their triggers, not the popups.
