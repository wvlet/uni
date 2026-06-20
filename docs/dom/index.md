# Web UI (RxElement)

`wvlet.uni.dom` is a reactive DOM toolkit for building browser UIs in Scala.js.
Components are plain Scala classes, the HTML/SVG DSL is type-checked, and any
[`Rx`](../rx/) value embedded in the markup re-renders the affected DOM in place
when it changes — no virtual DOM, no separate template language.

::: warning Scala.js only
This module lives under `uni/.js` and targets the browser. There is no JVM or
Scala Native counterpart. Add the `uni` dependency to a Scala.js project to use
it.
:::

## Setup

Every example below assumes these imports. The `given` import enables embedding
`String`, `Int`, `Rx`, `Seq`, and `Option` directly as children/attribute
values; `implicitConversions` is required by Scala 3 for those conversions.

```scala
import wvlet.uni.dom.all.*
import wvlet.uni.dom.all.given
import wvlet.uni.rx.Rx
import scala.language.implicitConversions
```

## Hello World

Extend `RxElement` and implement the single abstract method `def render`, then
mount it with the `renderTo` extension:

```scala
class Hello extends RxElement:
  override def render: RxElement = div("Hello, World!")

Hello().renderTo("app")   // mounts into <div id="app"> (created if absent)
```

`renderTo(nodeId)` returns an [`RxDomNode`](#mounting-and-teardown). If no element
with that id exists, it creates a `<div id=nodeId>` and appends it to
`document.body`.

## Components

### RxElement

`RxElement` is the component base. Override `render` to return the markup; add
lifecycle hooks as needed (all default to no-ops):

| Hook | When |
|------|------|
| `def render: RxElement` | produces the markup (required) |
| `beforeRender: Unit` | before the first render |
| `onMount(node: Any): Unit` | after the element is attached to the DOM (`node` is the `dom.Node`) |
| `beforeUnmount: Unit` | before the element is detached |

```scala
class Panel extends RxElement:
  override def beforeRender: Unit  = println("about to render")
  override def onMount(node: Any): Unit = println("mounted")
  override def render: RxElement   = div(cls -> "panel", "content")
```

Components nest by embedding one in another's markup:

```scala
class Inner extends RxElement:
  override def render: RxElement = span("inner")

class Outer extends RxElement:
  override def render: RxElement = div(Inner())
```

### RxComponent

`RxComponent` is a `trait` for "chrome" that wraps caller-provided content.
Override `render(content: RxElement)` and invoke it with `apply(content)` (or
`apply()` for empty content):

```scala
object MainFrame extends RxComponent:
  override def render(content: RxElement): RxElement =
    div(cls -> "main-frame", content)

MainFrame(span("editor")).renderTo("app")   // chrome wraps "editor"
```

## HTML DSL

### Tags

HTML tags are values (`div`, `span`, `h1`–`h6`, `p`, `a`, `img`, `ul`, `li`,
`table`, `form`, `input`, `button`, `select`, …). Calling a tag with children
and attributes builds an element:

```scala
div(cls -> "container", id -> "main",
  h1("Title"),
  p("Some content"),
  a(href -> "/next", "Go next")
)
```

### Attributes

Attributes use `name -> value` (or call syntax `name(value)`):

```scala
input(tpe -> "email", placeholder -> "you@example.com", required.noValue)
div(data("user-id") -> "123", data("role") -> "admin")   // data-* attributes
```

A few names are suffixed to avoid clashing with same-named tags — the **tag**
keeps the bare name:

| Use this attribute | for the HTML attribute | because the bare name is the tag |
|--------------------|------------------------|----------------------------------|
| `cls` (or `` `class` ``) | `class` | — |
| `styleAttr` | `style` | `style` is the `<style>` tag / CSS builder |
| `titleAttr` | `title` | `title` is the `<title>` tag |
| `tpe` (or `` `type` ``) | `type` | — |
| `forId` (or `` `for` ``) | `for` | — |

Use `+=` to **append** to an existing value (handy for classes), and `.noValue`
for boolean attributes:

```scala
div(cls -> "base", cls += "highlighted")   // class="base highlighted"
button(disabled.noValue, "Can't click")
```

### Events

Event handlers accept either a typed `E => U` or a no-arg `() => U`:

```scala
button(onclick -> { () => println("clicked") }, "Click me")
input(oninput -> { (e: dom.Event) => handle(e) })
form(onsubmit -> { (e: dom.Event) => e.preventDefault() })
```

The full set is available: mouse, keyboard, form (`onchange`, `oninput`,
`onsubmit`), focus, clipboard, drag, touch, pointer, wheel, animation, and media
events.

### Inline styles and CSS

The `style` object is dual-purpose — typed CSS properties, a raw string, or the
`<style>` tag:

```scala
div(style(display := "flex", gap := "8px", color := "blue"))  // typed properties
div(styleAttr -> "display:flex; gap:8px;")                    // raw string
head(style("body { margin: 0; }"))                            // <style> element
```

### Conditionals

`when` / `unless` include markup conditionally (they render nothing when the
condition doesn't hold):

```scala
div(
  when(isLoggedIn, span("Welcome back")),
  unless(items.isEmpty, ul(/* ... */))
)
```

### SVG

SVG tags and attributes are in scope from the same import; the SVG namespace is
applied automatically to children of an `<svg>`:

```scala
svg(viewBox -> "0 0 100 100",
  circle(cx -> 50, cy -> 50, r -> 40, fill -> "blue")
)
```

## Reactivity

### Embedding Rx

Embed an `Rx[A]` (or `RxVar[A]`) anywhere a child is expected; the toolkit
subscribes and re-renders just that section when the value changes:

```scala
class Counter extends RxElement:
  private val count = Rx.variable(0)
  override def render: RxElement =
    div(cls -> "counter",
      p(count.map(c => s"Count: ${c}")),
      button(onclick -> { () => count.update(_ + 1) }, "Increment")
    )
```

`Rx.variable(x)` creates an `RxVar`; mutate it with `update(f)`, `set(v)`, or
`:= v`. An `Rx` passed as an **attribute** value is likewise subscribed:

```scala
val activeClass = Rx.variable("idle")
div(cls -> activeClass, "status")   // class updates when activeClass changes
```

### Two-way form binding

Bind a `RxVar` to a form control so edits flow back into the variable and
programmatic changes flow into the DOM:

```scala
val username = Rx.variable("")
input(tpe -> "text", value.bind(username))      // text input / textarea

val subscribed = Rx.variable(false)
input(tpe -> "checkbox", checked.bind(subscribed))
```

Use `value.bindOnChange(v)` to update on the `change` event (e.g. for `<select>`)
instead of on every keystroke.

## Mounting and teardown

`renderTo` (and the lower-level `DomRenderer`) returns an `RxDomNode` holding the
created `dom.Node` and a `Cancelable`. **Keep it and call `cancel()`** to detach
listeners and unsubscribe the `Rx` bindings when the component goes away:

```scala
val mounted = MyApp().renderTo("app")
// later:
mounted.cancelable.cancel
```

`RxDomNode` also exposes `outerHTML`, `innerHTML`, and `textContent` for
inspection (used heavily in tests).

For advanced cases, `wvlet.uni.dom.DomRenderer` offers `renderToNode`,
`renderTo(node, ...)`, `createNode`, and `renderToHtml` (server-side string
rendering).

## Browser Integrations

The package includes reactive wrappers over common browser APIs. Each is an
`object` exposing `Rx` streams and/or helper nodes (imported via
`wvlet.uni.dom.all.*`):

| Module | What it does |
|--------|--------------|
| `Router` | Client-side routing over the History API — `Route`/`RouterInstance`, `outlet: Rx[A]`, `push`/`replace`, `link`, `isActive` |
| `Portal` | Render children into a different DOM subtree (`Portal.toBody`, `Portal.to(id)`) |
| `Clipboard` | Read/write the clipboard, `onCopy`/`onPaste` handlers, `copyOnClick` |
| `DragDrop` | Drag-and-drop state machine — `draggable`, `dropZone`, `fileDropZone`, `state: Rx[DragState]` |
| `Geolocation` | `getCurrentPosition`, `watch` → `position: Rx[Option[GeoPosition]]` |
| `Storage` | Reactive `localStorage`/`sessionStorage` — `Storage.local[A](key, default)` → an `RxVar`-like `StorageVar` |
| `MediaQuery` | `MediaQuery.matches(query)` → `.rx: Rx[Boolean]` for responsive layouts |
| `Focus` | Focus tracking (`active: Rx[Option[Element]]`), focus traps, autofocus on mount |
| `Keyboard` | Global shortcuts — `Keyboard.bind("ctrl+s", () => save())`, `isPressed`, `modifiers` |
| `ClickOutside` | Detect clicks outside an element (`hide(visible)` for menus/popovers) |
| `NetworkStatus` | `online`/`offline: Rx[Boolean]` |
| `WindowScroll` / `WindowDimensions` / `WindowVisibility` | Reactive window scroll position, size, and tab visibility |
| `AnimationFrame` | `requestAnimationFrame` loops — `loop`, `once`, `fixedStep`, `timed` |
| `Transition` | Enter/leave CSS transitions — `fade(visible)(...)`, `slide(visible)(...)` |
| `Validate` | Form validation — `required`, `minLength`, `pattern`, `email`; field and whole-form state |
| `DomRef` | Direct element handles — `current: Option[E]`, `focus()`, `getBoundingClientRect()` |
| `DomObservers` | `IntersectionObserver` / `ResizeObserver` bindings (lazy load, element-size → `RxVar`) |

Each integration that attaches global listeners returns a `Cancelable` (or has a
`stop()`), so you can detach it during teardown.
