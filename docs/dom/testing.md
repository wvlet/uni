---
description: Test Scala.js UI components in a real browser with uni-test's DomTestSupport — mount, query, fireEvent, wait for onMount — and check widgets against WAI-ARIA patterns with checkPattern.
---

# Testing

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
  test("clicking +1 increments the counter") {
    val count = Rx.variable(0)
    val m = mount(
      div(
        span(count.map(c => s"count=${c}")),
        button(onclick -> { () => count := count.get + 1 }, "+1")
      )
    )
    fireEvent.click(m.getByText("+1"))
    m.text shouldContain "count=1"
  }
```

`DomTestSupport` extends `UniTest` and unmounts every element `mount` rendered
after each test, so tests don't leak DOM into one another. Outside `UniTest`,
call `m.unmount()` yourself, or mix in `DomTestSession` and call
`cleanupMounted()`.

## Querying

`mount` returns a `Mounted` handle:

| Method | Returns |
|--------|---------|
| `get(css)` / `query(css)` / `queryAll(css)` | The first match (or fail), `Option`, or all matches |
| `getByText(text)` / `queryByText(text)` / `queryAllByText(text)` | Elements whose trimmed text equals a `String` or matches a `Regex`; the deepest match wins |
| `getByTestId(id)` / `queryByTestId(id)` | Elements with `data-testid="id"` |
| `text` / `html` | The rendered `textContent` / `innerHTML` |

## Firing events

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

```scala skip
fireEvent.pointerDown(m.get("[role=separator]"), clientX = 300)
fireEvent.pointerMove(dom.window, clientX = 350)
fireEvent.pointerUp(dom.window, clientX = 350)
```

## Waiting

`Rx` updates re-render synchronously, so assert right after `fireEvent`. Two
cases need a wait; return the `Rx` from the test body:

- `onMount` runs a step after `mount` returns (the renderer learns about
  attachment from a `MutationObserver`). `settle()` emits once those callbacks
  have run:

  ```scala skip
  val m = mount(AutoFocusInput())
  settle().map { _ => dom.document.activeElement shouldBe m.get("input") }
  ```

- Values that come from browser events, such as `resize` or a media-query
  change, arrive later. `awaitValue(rx)(p)` emits the first value that
  satisfies `p`, or fails after `timeoutMillis` (2 s by default).

## Testing uni-ui components

uni-ui components render into a real DOM the same way:

```scala
import wvlet.uni.ui.*

class SendButtonTest extends DomTestSupport:
  test("send runs once per click") {
    var sent = 0
    val m    = mount(Button("Send").onClick(() => sent += 1))
    fireEvent.click(m.get("button"))
    sent shouldBe 1
  }
```

## Checking ARIA patterns

`checkPattern(root, pattern)` checks a widget against its
[WAI-ARIA Authoring Practices](https://www.w3.org/WAI/ARIA/apg/patterns/) pattern and emits the
violations it finds. uni-ui runs it on every component in its own tests; run it on your widgets
too, including ones built from the [behaviors](./behaviors).

```scala
import org.scalajs.dom
import wvlet.uni.test.empty

class MorePatternTest extends DomTestSupport:
  test("the More menu follows the menu pattern") {
    val open = Rx.variable(false)
    mount(Menu(open, trigger = Button("More"), entries = Seq(MenuItem("Rename"), MenuItem("Delete"))))
    open := true
    // An overlay focuses its first item in onMount, so settle before checking
    settle()
      .flatMap(_ => checkPattern(dom.document.querySelector("[role=menu]"), AriaPattern.Menu))
      .map(_ shouldBe empty)
  }
```

A failing check lists each violation on one line, with a rule id you can filter on:
`[menu/item-name] div[role=menuitem]: needs an accessible name (aria-label, aria-labelledby, or text)`.

- **Structure:** roles and the roles they own, required states (`aria-selected`, `aria-expanded`,
  `aria-checked`, `aria-level`), one focus model (a single tab stop, or `aria-activedescendant` on a
  focusable container), and, stricter than the APG, an accessible name on every widget and id
  references (`aria-controls`, `aria-labelledby`, ...) that point to an element in the document.
- **Keyboard:** then it presses the keys the pattern requires and checks where the focus or the
  active item went. These keys change state (Escape closes a dialog), so call `checkPattern` last;
  pass `keyboard = false` to check the structure only. In a right-to-left layout it expects
  ArrowLeft and ArrowRight mirrored.

| Pattern | Pass | uni-ui component |
|---------|------|------------------|
| `Tabs` | the `tablist` or an element containing it | `TabList` with `TabPanel`, `Dock` |
| `Menu` | the open `menu` | `Menu`, `ContextMenu` |
| `MenuButton` | the closed trigger (it opens and closes the menu) | `Menu` |
| `Listbox` | the `listbox` | `Select` (open), `VirtualList` |
| `Combobox` | the `combobox` input | `CommandPalette` |
| `Dialog` / `AlertDialog` | the open dialog | `Dialog`, `AlertDialog`, `CommandPalette` |
| `Tree` | the `tree` | `Tree` |
| `Grid` | the `grid` | `DataTable` |
| `Tooltip` | the shown `tooltip` | `Tooltip` |
