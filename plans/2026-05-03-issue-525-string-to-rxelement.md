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

Add `Conversion[String, RxElement]` (and the same for the other primitives that
already have `String -> DomNode` conversions: `Int`, `Long`, `Float`, `Double`,
`Boolean`, `Char`) to `wvlet.uni.dom.all`.

`Embedded(...)` already extends `RxElement`, so the implementation is
identical to the existing `*ToDomNode` conversions, just with a different return
type. The `String -> DomNode` conversions stay as-is so existing call sites keep
working.

Adding both `String -> DomNode` and `String -> RxElement` is safe: the compiler
picks whichever fits the expected type and there is no overlap because Scala 3
selects conversions by the *expected* type, not by ambiguity.

## Files to change

- `uni/.js/src/main/scala/wvlet/uni/dom/all.scala` — add `*ToRxElement` givens
  alongside the existing `*ToDomNode` block (lines 165-176).
- `uni-dom-test/src/test/scala/wvlet/uni/dom/RxElementTest.scala` — add a
  regression test that calls a method taking `RxElement` with a string literal
  and an integer literal.

## Out of scope

The container givens (`Rx`, `RxOption`, `Option`, `Seq`, `List`) — these stay
as `-> DomNode` because the only common consumer site is element children,
where `DomNode` is already accepted. Promoting them would expand the surface
area without a concrete migration use case.
