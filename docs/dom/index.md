# Web UI (RxElement)

`wvlet.uni.dom` is a reactive DOM toolkit for building browser UIs in Scala.js.
Components are plain Scala classes, the HTML/SVG DSL is type-checked, and any
[`Rx`](../rx/) value embedded in the markup re-renders the affected DOM in place
when it changes — no virtual DOM, no separate template language.

::: warning Scala.js only
This module targets the browser and has no JVM or Scala Native counterpart. Add
the `uni` dependency to a Scala.js project to use it. (The rendering core ships
in `uni-core`, which `uni` depends on, so `uni-test` can test components.)
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
input(tpe -> "email", placeholder -> "you@example.com", required)
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

Use `+=` to **append** to an existing value (handy for classes). Boolean
attributes such as `disabled`, `required`, `readonly`, and `hidden` work on their
own, and also take a `Boolean` or `Rx[Boolean]`, where `false` removes the
attribute. Use `HtmlTags.attr(name).boolean` for any other one:

```scala
import wvlet.uni.dom.HtmlTags

val busy = Rx.variable(false)

div(cls -> "base", cls += "highlighted")   // class="base highlighted"
button(disabled, "Can't click")
button(disabled -> busy, "Send")           // toggles with busy
video(HtmlTags.attr("playsinline").boolean)
```

### Events

Event handlers accept either a typed `E => U` or a no-arg `() => U`:

```scala
import org.scalajs.dom

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
div(style(style.display := "flex", style.gap := "8px", style.color := "blue"))  // typed properties
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
A binding attaches its own listener, so an `oninput` or `onchange` handler on the
same element runs alongside it.

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

## Testing components

`uni-test` on Scala.js includes a component testing kit in
`wvlet.uni.dom.testing`: render a component into a real DOM, fire user events,
and query the result. Run the tests in a real browser with the
[sbt-uni-playwright Plugin](../build/sbt-uni-playwright), which uses headless
Chromium, the same engine as the Electron renderer.

```scala
import org.scalajs.dom
import wvlet.uni.dom.all.*
import wvlet.uni.dom.all.given
import wvlet.uni.dom.testing.*
import wvlet.uni.rx.Rx
import scala.language.implicitConversions

class CounterTest extends DomTestSupport:
  test("clicking +1 increments the counter"):
    val count = Rx.variable(0)
    val m = mount(
      div(
        span(count.map(c => s"count=${c}")),
        button(onclick -> { () => count := count.get + 1 }, "+1")
      )
    )
    fireEvent.click(m.getByText("+1"))
    m.text shouldContain "count=1"
```

`DomTestSupport` extends `UniTest` and unmounts every element `mount` rendered
after each test, so tests don't leak DOM into one another. Outside `UniTest`,
call `m.unmount()` yourself, or mix in `DomTestSession` and call
`cleanupMounted()`.

### Querying

`mount` returns a `Mounted` handle:

| Method | Returns |
|--------|---------|
| `get(css)` / `query(css)` / `queryAll(css)` | The first match (or fail), `Option`, or all matches |
| `getByText(text)` / `queryByText(text)` / `queryAllByText(text)` | Elements whose trimmed text equals a `String` or matches a `Regex`; the deepest match wins |
| `getByTestId(id)` / `queryByTestId(id)` | Elements with `data-testid="id"` |
| `text` / `html` | The rendered `textContent` / `innerHTML` |

### Firing events

`fireEvent` dispatches bubbling, cancelable events, the way real input does,
and returns `false` if a handler called `preventDefault()`:

| Kind | Methods |
|------|---------|
| Mouse | `click`, `dblclick`, `mouseDown`, `mouseUp`, `mouseMove`, `mouseOver`, `mouseOut`, `mouseEnter`, `mouseLeave`, `contextMenu`, with optional `clientX`, `clientY`, `button`, and `shiftKey`/`ctrlKey`/`altKey`/`metaKey` |
| Pointer | `pointerDown`, `pointerMove`, `pointerUp`, `pointerCancel`, `pointerOver`, `pointerOut`, `pointerEnter`, `pointerLeave`, with optional `clientX`, `clientY`, `pointerId`, `pointerType`, and `button` |
| Keyboard | `keyDown`, `keyUp`, `keyPress` with a `key`, optional `code`, modifier keys, and `isComposing` (a key pressed while an IME composition is open) |
| Form | `input(el, value)` and `change(el, value)` set the value first, `setChecked(el, checked)`, `submit` |
| Focus | `focus`, `blur` |
| Other | `custom(target, eventType)`, or `dispatch(target, event)` for an event you built |

Enter and leave events don't bubble, as in the browser. To simulate a drag,
send `pointerDown` to the handle and `pointerMove`/`pointerUp` to whatever
listens for them (often `dom.window`):

```scala
fireEvent.pointerDown(m.get("[role=separator]"), clientX = 300)
fireEvent.pointerMove(dom.window, clientX = 350)
fireEvent.pointerUp(dom.window, clientX = 350)
```

### Waiting

`Rx` updates re-render synchronously, so assert right after `fireEvent`. Two
cases need a wait; return the `Rx` from the test body:

- `onMount` runs a step after `mount` returns (the renderer learns about
  attachment from a `MutationObserver`). `settle()` emits once those callbacks
  have run:

  ```scala
  val m = mount(AutoFocusInput())
  settle().map { _ => dom.document.activeElement shouldBe m.get("input") }
  ```

- Values that come from browser events, such as `resize` or a media-query
  change, arrive later. `awaitValue(rx)(p)` emits the first value that
  satisfies `p`, or fails after `timeoutMillis` (2 s by default).

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
| `Keyboard` | Global and scoped shortcuts — `Keyboard.bind("ctrl+s", () => save())`, `isPressed`, `modifiers`; `onEnter` for IME-safe Enter in text fields |
| `PointerDrag` / `PaneResize` | Pointer drags tracked on `window` (`dragging: Rx[Boolean]`), and drag-to-resize handles for split panes and sidebars |
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

### Persisting values with Storage

`Storage.local` and `Storage.session` store `String`, `Int`, `Long`, `Double`, and
`Boolean` values out of the box. An enum whose cases take no parameters gets a codec
with `derives StorageCodec`, which stores each value by its case name:

```scala
import wvlet.uni.dom.all.*

enum Theme derives StorageCodec:
  case System, Light, Dark

val theme = Storage.local("theme", Theme.System)
theme := Theme.Dark // stored as "Dark"
```

A stored name that matches no case, for example after a case was renamed, reads as
the default value. For other types, write a `given StorageCodec[A]` with `encode`
and `decode`.

### Enter in text fields

`Keyboard.onEnter` submits on plain Enter and leaves Shift+Enter (a newline) to
the textarea. It also skips the Enter that confirms an IME conversion, so users
typing Japanese, Chinese, or Korean can pick a candidate without sending the
message:

```scala
val draft = Rx.variable("")
textarea(value.bind(draft), Keyboard.onEnter(() => send(draft.get)))
```

Shortcuts from `Keyboard.bind` and `Keyboard.scoped` never fire during IME
composition either. In your own `onkeydown` handler, check
`Keyboard.isComposing(e)` for the same behavior.

### Resizable panes

`PaneResize` turns any element into a resize handle: dragging changes a size
`RxVar` within a `SizeRange`, and double-clicking restores the default. It adds
`role="separator"` and `aria-orientation`; the handle's look and position stay
in your markup:

```scala
val width  = Rx.variable(272)
val resize = PaneResize(width, SizeRange(min = 208, default = 272, max = 480), ResizeDirection.Right)

aside(
  cls       -> "relative",
  styleAttr -> width.map(w => s"width: ${w}px"),
  div(
    cls       -> "absolute inset-y-0 right-0 w-2 cursor-col-resize touch-none",
    ariaLabel -> "Resize sidebar",
    resize.bind
  ),
  sidebarContent
)
```

`ResizeDirection` is the way the pointer moves to make the pane larger: `Right`
for a left sidebar, `Left` for a right-hand dock, `Up` or `Down` for vertical
splits. `resize.dragging` is an `Rx[Boolean]`, handy for turning off a width
transition during the drag. Constructing a `PaneResize` clamps the current size
into its range, so a width kept in `Storage.local` and saved under an older,
wider range is corrected before the pane renders.

For other drags (sliders, movable cards), use `PointerDrag(onMove = (dx, dy) =>
...)` directly; it reports the distance from the start point and ends on
`pointerup` or `pointercancel`.
