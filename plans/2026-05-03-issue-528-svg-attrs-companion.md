# #528 — `object SvgAttrs extends SvgAttrs` for partial imports

## Problem

`wvlet.uni.dom.SvgAttrs` is only a trait. The airframe-rx-html migration
path uses targeted attribute imports:

```scala
import wvlet.airframe.rx.html.svgAttrs.{xmlns as _, *}
```

The uni equivalent fails to compile because traits cannot be imported
with renames-and-wildcard:

```scala
import wvlet.uni.dom.SvgAttrs.{xmlns as _, *}
// [error] value SvgAttrs is not a member of wvlet.uni.dom
```

(Note: the issue text mentioned `style` alongside `xmlns`, but uni's
`SvgAttrs` trait does not currently define a `style` attribute, so only
`xmlns` is a real collision target. The fix below addresses both today's
`xmlns` use case and any future SVG attr that needs to be renamed out.)

`import wvlet.uni.dom.all.*` does work, but it pulls in HtmlTags +
HtmlAttrs + SvgTags + SvgAttrs, which is too broad when a file only
needs SVG attributes and wants to disambiguate `xmlns` / `style` from
the HTML attribute names already in scope.

## Approach

Add a single line to `uni/.js/src/main/scala/wvlet/uni/dom/SvgAttrs.scala`:

```scala
object SvgAttrs extends SvgAttrs
```

This is exactly the change the issue text suggests, and it matches the
existing `HtmlTags` / `SvgTags` pattern in the same package — both
already have a trait *and* a companion `object` that mixes the trait in.
After this change, `import wvlet.uni.dom.SvgAttrs.{xmlns as _, *}` works
the same as the airframe-rx-html flavor.

Naming: stick with `SvgAttrs` (matches the trait + matches the existing
`HtmlTags` / `SvgTags` companions). The issue's lowercase suggestion
(`svgAttrs`) is for airframe parity, but uni uses the PascalCase form
elsewhere — consistency wins.

## Out of scope

`HtmlAttrs` is also a trait without a companion. The issue does not
mention it because the migration code uses `import wvlet.uni.dom.all.*`
for the HTML side (mixes in HtmlAttrs). Leave it for a follow-up if the
need arises.

## Files to change

- `uni/.js/src/main/scala/wvlet/uni/dom/SvgAttrs.scala` — append
  `object SvgAttrs extends SvgAttrs` after the `end SvgAttrs` marker.
- `uni-dom-test/src/test/scala/example/dom/SvgAttrsImportTest.scala` —
  new test in a sibling package that exercises the targeted-rename
  import. Must compile to pass; if the companion object disappears, the
  test fails to compile (regression guard).
