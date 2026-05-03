# #524 — `style` attribute vs `<style>` tag collision in `all`

## Findings

The migration path the issue asks for already exists, just not
discoverably:

- `wvlet.uni.dom.HtmlAttrs.styleAttr` is defined as
  `attr("style")` (HtmlAttrs.scala line 49).
- `wvlet.uni.dom.HtmlAttrs.titleAttr` is the same pattern for `title`
  (line 50).
- `all.scala` line 42 has a half-line comment: `// Use styleAttr and
  titleAttr for the attribute versions`.

So `import wvlet.uni.dom.all.*` then `div(styleAttr -> "height: 64px;")`
already works today — the migration just needs to know the rename.

## Approach (issue's option 2: "Provide an alternative attribute name")

Take the path uni already chose, just make it findable:

1. **Document the migration shape** on `all`'s scaladoc, so a reader
   doesn't need to discover the rename from a one-line code comment.
2. **Pin a regression test** in `package example.dom` (so the test
   actually goes through `import wvlet.uni.dom.all.*`) that exercises
   `styleAttr -> "..."` and `titleAttr -> "..."`. If a future refactor
   drops or renames either attribute, the test fails to compile.
3. Don't rename the `<style>` tag (issue's option 1) — that would
   break `style("body { ... }")` call sites.

## Why option 2 over option 1

The issue lists both. Option 1 (rename tag to `styleTag`, expose
`style` as the attribute) is closer to airframe-rx-html's split, but
in this codebase `style` is already an *object* (`CssStyles.scala:50`)
that supports both `style("body { … }")` (as `<style>` tag) *and*
`style(display := "flex")` (as a CSS-rule builder). Renaming it would
break the second form too.

`styleAttr` / `titleAttr` is the additive, non-breaking path. The only
gap is that migrating code doesn't know to look for it.

## Files to change

- `uni/.js/src/main/scala/wvlet/uni/dom/all.scala` — expand the
  scaladoc on `object all` to show both `style` (tag/CSS) and
  `styleAttr` (attribute) usage and name them as the airframe-rx-html
  migration shape.
- `uni-dom-test/src/test/scala/example/dom/StyleAttrTest.scala` — new
  test in a sibling package that pins the migration contract.

## Out of scope

- Renaming the `<style>` tag (issue's option 1).
- Compile-error help text for users who try `style -> "..."` — would
  need a custom `@implicitNotFound`-style message and is a bigger
  surface change.
