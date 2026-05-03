# #527 — `RxComponent` for content-receiving components

## Problem

airframe-rx-html had an `RxComponent` trait whose `render` took the
child content as a parameter:

```scala
object MainFrame extends RxComponent:
  override def render(content: RxElement) = div(
    cls -> "h-screen max-h-screen",
    NavBar,
    content,
    URLParamManager()
  )

// At call sites:
MainFrame(editor).renderTo("main")  // outer chrome wraps `editor`
MainFrame()                          // standalone with no inner content
```

uni's `RxElement` only has `def render: RxElement` (no content
parameter), so this "outer chrome wraps inner content" pattern doesn't
have a direct uni equivalent. A hand-rolled `apply(content)` that
addModifies works for one-offs but breaks the
`object MyFrame extends RxComponent` ergonomics that callers rely on.

## Approach (issue's option 1)

Add `wvlet.uni.dom.RxComponent` mirroring airframe's API:

```scala
abstract class RxComponent:
  /** Render this component, wrapping the given content. */
  def render(content: RxElement): RxElement

  /** Wrap content using this component's chrome. */
  def apply(content: RxElement): RxElement = render(content)

  /** Render with empty content (standalone form). */
  def apply(): RxElement = render(RxElement.empty)
```

`RxElement.empty` already exists (`RxElement.scala`). Both `apply`
overloads return an `RxElement`, so the result chains cleanly into
the existing `renderTo` extension method.

Export `RxComponent` from `wvlet.uni.dom.all` so a single
`import wvlet.uni.dom.all.*` matches the airframe import shape.

## Files to change

- `uni/.js/src/main/scala/wvlet/uni/dom/RxComponent.scala` — new file
  with the abstract class.
- `uni/.js/src/main/scala/wvlet/uni/dom/all.scala` — add an
  `export wvlet.uni.dom.RxComponent` next to the existing `RxElement`
  re-export.
- `uni-dom-test/src/test/scala/wvlet/uni/dom/RxComponentTest.scala` —
  unit tests covering `apply(content)`, `apply()`, the `render` hook,
  and a worked "MainFrame wraps inner content" scenario from the issue.

## Why not also subclass `RxElement`

Tempting (would let `RxComponent` instances be embedded as children
directly), but `RxElement` requires `def render: RxElement` (no
parameter). Forcing `RxComponent` to satisfy both signatures would
either pick an arbitrary default for the no-arg form (lock-in) or
require the subclass to implement two `render` methods (boilerplate).
Keep `RxComponent` separate; users who want an embeddable element
call `.apply(...)` to convert.

## Out of scope

- Lifecycle hooks (`onMount`, `beforeUnmount`, etc.) — airframe's
  `RxComponent` didn't have them in the issue's example, and the
  inner `RxElement` returned from `render(content)` already supports
  them. Add only if a concrete migration use case shows up.
- A typed-content variant `RxComponent[A]`. The migration target uses
  `RxElement` content; promoting to a generic API is premature.
