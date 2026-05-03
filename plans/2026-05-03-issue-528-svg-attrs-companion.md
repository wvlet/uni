# #528 — `object SvgAttrs extends SvgAttrs` for partial-import migration

## Problem

`wvlet.uni.dom.SvgAttrs` is only a trait. The airframe-rx-html migration
path uses targeted attribute imports:

```scala
import wvlet.airframe.rx.html.svgAttrs.{xmlns as _, *}
```

The naïve uni equivalent fails to compile because traits cannot be
imported with renames-and-wildcard:

```scala
import wvlet.uni.dom.SvgAttrs.{xmlns as _, *}
// [error] value SvgAttrs is not a member of wvlet.uni.dom
```

## What was already there

`wvlet.uni.dom.svgProperties` (in `all.scala`) already extends `SvgAttrs`,
and `import wvlet.uni.dom.svgProperties.{xmlns as _, *}` works today.
But airframe-rx-html migrations naturally try `SvgAttrs` (the trait
name) first and don't find this alias.

## Why not `object svgAttrs` (lowercase, matching airframe naming)

The issue text suggested either `object SvgAttrs` or lowercase
`object svgAttrs`. **The lowercase form does not work on
case-insensitive file systems** (default macOS APFS, Windows NTFS):
the compiler tries to write both `SvgAttrs.tasty` (for the trait) and
`svgAttrs.tasty` (for the object) at the same case-folded path. Only one
ends up on disk and downstream `import wvlet.uni.dom.svgAttrs.*` fails
with a "Not Found" at every call site.

This is also why uni's existing convention has *distinct* names for
its trait + alias pair — the alias `svgProperties` deliberately avoids
the case-fold collision with the trait `SvgAttrs`.

## Approach

Add `object SvgAttrs extends SvgAttrs` next to the trait. Companion
object + trait share the base name in Scala — the bytecode is
`SvgAttrs.tasty` (the trait/object pair) and `SvgAttrs$.class` (the
singleton instance), so there is no case-fold collision. Migrating
code can write `import wvlet.uni.dom.SvgAttrs.{xmlns as _, *}` and the
trait keeps working as before.

This is a new pattern for this codebase (the existing `HtmlTags` /
`SvgTags` companion objects contain *factory methods*, they don't
extend their traits). The pattern is justified: the only collision-
free alternative is renaming the trait, which would be a real breaking
change for downstream users that already mix in `SvgAttrs`.

(Per gemini-code-assist review on PR #531: an earlier draft tried
`object svgAttrs extends SvgAttrs` in `all.scala`. Reverted after the
case-insensitive FS collision broke 97 test compiles locally.)

## Files to change

- `uni/.js/src/main/scala/wvlet/uni/dom/SvgAttrs.scala` — append
  `object SvgAttrs extends SvgAttrs` after the `end SvgAttrs` marker,
  with a scaladoc note explaining the case-fold-collision rationale.
- `uni-dom-test/src/test/scala/example/dom/SvgAttrsImportTest.scala` —
  new test in a sibling package that exercises the targeted-rename
  import. Lives in `package example.dom` so the renames-and-wildcard
  form has to go through the new companion object — a regression of
  the alias fails to *compile* the test file.

## Out of scope

- An `object HtmlAttrs extends HtmlAttrs` for HTML attribute partial
  imports. Same case-fold problem as the SVG side, but the issue does
  not call it out and the migration path through `import all.*` covers
  most cases. Add when a concrete need surfaces.
