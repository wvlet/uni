# #525 — `String -> RxElement` implicit conversion

## Problem

Methods declared with an `RxElement` parameter cannot accept string literals
because today only `Conversion[String, DomNode]` is in scope through
`wvlet.uni.dom.all.*`. Even though `RxElement <: DomNode`, Scala 3 does not
search for a `String -> Supertype` conversion when the parameter type is the
subtype.

```scala
class NavBar extends RxElement:
  private def navItem(name: RxElement): RxElement = a(href -> "#", name)

  navItem("Editor")  // does not compile
```

## Approach

For every existing `Conversion[X, DomNode]` in `wvlet.uni.dom.all`, add a
matching `Conversion[X, RxElement]`. Covers the primitives (`String`, `Int`,
`Long`, `Float`, `Double`, `Boolean`, `Char`) and the container shapes
(`Rx[A]`, `RxOption[A]`, `Option[A]`, `Seq[A]`, `List[A]`).

`Embedded(...)` already extends `RxElement`, so each new conversion has the
same body as its `DomNode` counterpart — only the target type differs. The
`*ToDomNode` conversions stay as-is so existing call sites keep working.

Adding both `X -> DomNode` and `X -> RxElement` is safe: Scala 3 picks the
conversion by the expected type. At call sites typed as `RxElement` the new
one fires; at call sites typed as `DomNode` the existing one fires (or the
new one wins by subtyping — same observable behavior either way).

## Why include containers

Helpers that take `RxElement` are a common airframe-rx-html migration target,
and those helpers are commonly fed `Rx[String]`, `Option[A]`, or `Seq[A]` —
not just primitive literals. Without the container variants, the migration
ergonomics are still half-baked. (Per gemini-code-assist review on PR #529.)

## Files changed

- `uni/.js/src/main/scala/wvlet/uni/dom/all.scala` — `*ToRxElement` givens
  alongside the existing `*ToDomNode` block.
- `uni-dom-test/src/test/scala/wvlet/uni/dom/RxElementTest.scala` —
  regression tests that ascribe primitives, `Rx`, `Option`, `Seq`, and
  `List` values to `RxElement` and assert the resulting `Embedded(...)`
  payload.
