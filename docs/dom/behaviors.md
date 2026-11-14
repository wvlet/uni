---
description: Build your own accessible Scala.js widgets from the interaction logic uni-ui components are made of — roving focus, typeahead, selection, reordering, stick-to-bottom scrolling, and announcements.
---

# Behaviors

A behavior is one piece of interaction logic, such as arrow-key navigation, a typeahead search, or
keeping a chat scrolled to its newest message. Each one does a single job, separate from any markup
or styling. The [uni-ui components](./ui) are built from them. Use them, from
`wvlet.uni.ui.base`, to build widgets that uni-ui doesn't have, so a new widget handles the
keyboard, focus, and screen readers the same way the built-in ones do.

Most behaviors come in two parts:

- **A model**: plain Scala state and functions, such as `RovingFocus.nextIndex` or
  `InputHistory.previous`. It needs no DOM, so you can test it directly and reuse it in any
  component.
- **A binding**: a modifier you put on an element, such as `stick.binding` or
  `history.keys(draft)`. It attaches listeners or observers on mount and removes them on unmount.
  Bindings are built on uni-dom's `OnMount` and `EventListenerBinding`, which work on any element.

A behavior that changes state your app owns, such as `Selection` or `InputHistory`, writes it the
same way the components do. Your `onChange` handler sees each change first, with its
[reason and source](./changes), and can cancel it.

```scala
import org.scalajs.dom
import wvlet.uni.dom.all.*
import wvlet.uni.dom.all.given
import wvlet.uni.rx.Rx
import wvlet.uni.ui.*
import wvlet.uni.ui.base.*
import scala.language.implicitConversions
```

## Catalog

| Behavior | What it does | Used by |
|---|---|---|
| `RovingFocus` | Arrow keys, Home, and End within a group with one tab stop | Tabs, Menu, Toolbar |
| `ListNavigation` | Arrow keys, PageUp/PageDown, Home, and End in a long list | VirtualList, DataTable, Tree |
| `Typeahead` | Typing the start of a label jumps to that item | Select, Menu, VirtualList, Tree |
| `Selection` | Multi-select: click, Ctrl/Cmd to toggle, Shift for a range | VirtualList, Tree, DataTable (`withMultiSelect`), your lists |
| `Reorder` | Drag an item, or press Alt+Arrow keys, to move it within a list | Dock tabs (keys), your lists |
| `FocusRecovery` | Keeps focus in a list when the focused item is removed | Dock tabs |
| `FocusTrap`, `FocusReturn` | Keep Tab inside a dialog; restore focus when it closes | Dialog, Popover |
| `Dismiss` | Escape and outside presses close the topmost overlay only | all overlays |
| `Anchored` | Places a floating element next to its anchor and flips it to stay on screen | Popover, Tooltip, Menu |
| `Windowing` | Renders only the rows in view in a long list | VirtualList, DataTable |
| `StickToBottom` | Follows new content unless the reader scrolled up | chat threads, logs |
| `InputHistory` | ArrowUp and ArrowDown recall earlier entries, as in a terminal | chat composers |
| `Announce` | Reads a message to screen reader users through a live region | status updates |
| `Fuzzy` | Scores a query against a label, for command palettes | CommandPalette |
| `TreeModel`, `DockModel` | The pure state of a tree and of a dock layout | Tree, Dock |
| `TextDirection` | Mirrors ArrowLeft and ArrowRight in a right-to-left layout | Tabs, Tree, DataTable, Dock |

uni-dom adds lower-level helpers that behaviors build on: `Keyboard` (shortcuts, `onEnter`),
`PointerDrag` and `PaneResize`, `DragDrop`, `Focus`, and `ClickOutside`.

## StickToBottom

A chat thread or a log should follow new content while the reader watches the end, and stay put
when they scroll up to read. Put `stick.binding` on the scrolling element. `atBottom` tells
whether it is following, for example to show a "Jump to latest" button.

```scala
val stick    = StickToBottom()
val messages = Rx.variable(Seq.empty[String])

div(
  div(cls -> "thread", stick.binding, messages.map(_.map(m => p(m)))),
  button(
    hidden  -> stick.atBottom,
    onclick -> { () => stick.scrollToBottom(smooth = true) },
    "Jump to latest"
  )
)
```

New content, a child growing (a streamed reply re-rendering), and a resize all keep the view at the
bottom while following. Call `scrollToBottom()` when the reader sends a message, so they see the
reply even if they had scrolled up.

## InputHistory

ArrowUp on the first line of a field brings back the previous entry; ArrowDown on the last line goes
forward, and past the newest entry restores what was being written. In the middle of multi-line
text, the arrows move the caret as usual. As in readline, an edit to a recalled entry stays with
that entry while you move between entries; submitting (`add`) drops the edits, and they never
change the saved entries.

```scala
val draft   = Rx.variable("")
val history = InputHistory(capacity = 100)

textarea(
  value.bind(draft),
  history.keys(draft),
  Keyboard.onEnter { () =>
    history.add(draft.get)
    send(draft.get)
    draft := ""
  }
)
```

`add` skips blank entries and a repeat of the newest one. A recall is a change to `draft` with the
`NavigationKey` reason; pass an `onChange` handler as the second argument of `keys` to watch or
refuse it. To keep the history across sessions, save `history.entries` and pass them back as
`InputHistory(initial = saved)`. The model methods `previous(text)` and `next(text)` take the
text in the field, so the history can keep it as the draft or as an edit of the entry being left.

## Typeahead

Keys typed within half a second of each other add up to one search, so "ca" finds "Cat" rather
than an item starting with "a". Typing the same letter again moves on to the next item that starts
with it. Keep one `Typeahead` per widget and call it from the widget's `keydown` handler:

```scala
val typeahead = Typeahead()
val labels    = Seq("Apple", "Cabbage", "Car", "Cat")
val active    = Rx.variable(-1)

ul(
  tabindex -> "0",
  onkeydown -> { (e: dom.KeyboardEvent) =>
    typeahead.onKey(e, labels, active.get).foreach { i =>
      e.preventDefault()
      active := i
    }
  },
  labels.map(li(_))
)
```

Keys with Ctrl, Cmd, or Alt, and keys typed during IME composition, are not part of a search.

While a search is under way, `typeahead.searching` is true and Space is a character of it, so
"new york" finds "New York". If your widget selects or activates on Space, check
`typeahead.joinsSearch(e)` (a plain Space during a search) first and send the Space to `onKey`
instead. Select, Menu, VirtualList, and Tree already do.

## Selection

`Selection` handles multi-select over an ordered list of keys: a plain press selects one item,
Ctrl or Cmd toggles one, and Shift selects the range from the last item pressed (the anchor).
Ctrl+A or Cmd+A selects all, and Escape clears. The selected keys live in your `RxVar`, and
`onChange` sees each change before it lands.

```scala
val files    = Seq("a.txt", "b.txt", "c.txt", "d.txt")
val selected = Rx.variable(Set.empty[String])
val selection = Selection(selected).onChange { d =>
  if d.source == ChangeSource.Agent then d.cancel()
}

ul(
  role     -> "listbox",
  tabindex -> "0",
  ariaMultiselectable -> "true",
  onkeydown -> { (e: dom.KeyboardEvent) => if selection.onKey(e, files) then e.preventDefault() },
  files.map { f =>
    li(
      role          -> "option",
      ariaSelected  -> selection.isSelected(f).map(_.toString),
      onclick       -> { (e: dom.MouseEvent) => selection.press(f, files, e) },
      f
    )
  }
)
```

`select`, `toggle`, `extendTo`, `selectAll`, and `clear` change the selection from code. Each one
takes an optional `ChangeCause`; the default is `ChangeCause.current`. `setAnchor` moves the range
start without changing the selection.

`VirtualList`, `Tree`, and `DataTable` already use `Selection` through `.withMultiSelect(setVar)`, which
also wires Shift+Arrow and Space. See [Collections](./collections).

## Reorder

`Reorder` lets the user change the order of a list. Dragging an item shows a line where it would
land and moves it on release; Escape cancels the drag. Dragging near the edge of the list's scroll
container scrolls it. On a focused item, Alt+ArrowUp and Alt+ArrowDown move it one step, and
Alt+Home and Alt+End move it to either end. The order lives in
your `RxVar`, `onChange` sees each move (reason `drag` or `navigation-key`) and can cancel it, and
every move is announced to screen readers, such as "Moved Review to position 1 of 3".

```scala
val tasks   = Rx.variable(Seq("Write", "Review", "Ship"))
val reorder = Reorder(tasks).onChange { d =>
  if d.source == ChangeSource.Agent then d.cancel()
}

ul(
  tasks.each(identity) { t =>
    li(tabindex -> "0", reorder.item(t), span(Reorder.handle, "⋮⋮"), t)
  }
)
```

- `reorder.item(k)` goes on each item. With `Reorder.handle` on an element inside it, only that
  element starts a drag, so the rest of the item can hold text fields or buttons.
- Space, Enter, and letter keys are left alone, so `Selection` and `Typeahead` work on the same
  items. Focus stays on the moved item.
- When the `RxVar` holds the items rather than their keys, `Reorder.by(items, label = _.title)(_.id)`
  matches them by key and writes the moved items back; `onChange` then sees the new `Seq` of
  items. `VirtualList` and `DataTable` wire one in with `.withReorder(itemsVar)`, see
  [Collections](./collections).
- `Reorder(order, label = _.title)` names items in announcements, and
  `orientation = Orientation.Horizontal` uses Alt+ArrowLeft and Alt+ArrowRight for a row. In a
  right-to-left layout (`dir="rtl"` on the row or an ancestor) the two keys swap, so the item moves
  the way the arrow points.
- `reorder.withSelection(selected)` moves the selected items together. Pass the same `RxVar` as a
  `Selection` or a list's `withMultiSelect`. Dragging a selected item, or pressing Alt+Arrow on it,
  moves every selected item as one block in their current order, as one `onChange`, announced as
  "Moved 3 items to position 2 of 10". An item outside the selection moves alone.
- uni-ui.css marks the dragged item (`data-dragging="true"`) and the drop line
  (`data-drop="before"` or `"after"`). Restyle them with `[data-reorder-item][data-drop]`.
- `reorder.moveTo(k, index)` and `reorder.moveAll(keys, index)` move items from code, and
  `Reorder.moved`, `Reorder.movedAll`, `Reorder.dropIndex`, `Reorder.keyTarget`, and
  `Reorder.autoscrollStep` are the pure math, for widgets that do their own drag. In the Dock,
  Alt+ArrowLeft, Alt+ArrowRight, Alt+Home, and Alt+End move a tab within its group, mirrored in a
  right-to-left layout.

## FocusRecovery

When the focused item of a list is removed, for example by closing a tab or deleting a row, the
browser moves focus to the page, and a keyboard user loses their place. `FocusRecovery.within`
moves focus to the item that took its place, else to the new last item, else to the container.

```scala
val todos = Rx.variable(Seq("Write docs", "Ship it"))

ul(
  tabindex -> "-1",
  FocusRecovery.within("li"),
  todos.map(_.map(t => li(tabindex -> "0", t)))
)
```

If your code already moves focus somewhere else, `FocusRecovery` leaves it there.
`FocusRecovery.neighbor(keys, removed)` is the same choice as a pure function, for widgets that move
focus themselves.

## Announce

Some changes are visible on screen but not to screen reader users, such as a finished reply, a
result count, or a lost connection. `Announce` reads a message aloud through a live region that is
visually hidden and shared by the whole page.

```scala
Announce.prepare()                     // once, at startup
Announce.polite("Response complete")   // after the screen reader finishes what it is saying
Announce.assertive("Connection lost")  // right away; keep it for errors
```

Call `Announce.prepare()` once when the app starts. Screen readers often skip a message put into a
region that has just appeared, so without it the first message is held back a moment. Saying the
same message twice reads it twice. The regions carry `data-slot="announcer"`, so tests
can find them.

## Right-to-left layouts

In a right-to-left layout (`dir="rtl"` on the widget or an ancestor, or CSS `direction: rtl`),
ArrowLeft and ArrowRight swap, as the WAI-ARIA Authoring Practices require. Tabs, the Dock (tabs
and the separators of a row), Tree (ArrowLeft expands, ArrowRight collapses), and DataTable columns
do this on their own; vertical keys, Home, and End don't change.

- `TextDirection.mirrors(e)` is true for ArrowLeft or ArrowRight on a right-to-left target. It reads
  the computed direction when the key is pressed (and only for those keys), so a later `dir` change
  applies at once. `TextDirection.isRtl(el)` reads it for any element.
- `RovingFocus.nextIndex`, `TreeModel.onKey`, `DataTable.nextColumn`, and `Reorder.keyTarget` take
  `rtl = true` to mirror the horizontal keys. `TextDirection.logicalKey(key, rtl)` does the same for
  your own key handling: it returns the key a left-to-right layout would read.
- `checkPattern` expects the mirrored keys when the widget is laid out right to left.
- `FoldablePane` and `Splitter` place their grip on a fixed side (`Edge.Left` or `Edge.Right`), so
  their arrow keys follow the grip, not the reading direction.

```scala
def onKey(e: dom.KeyboardEvent, current: Int, count: Int): Option[Int] =
  RovingFocus.nextIndex(e.key, current, count, rtl = TextDirection.mirrors(e))
```

## Building your own

Write the model as plain Scala and test it without a browser. Then add a binding: an `OnMount` for
observers and listeners that need the element, or an `EventListenerBinding` for a single event.
Return everything you attach as a `Cancelable` so it is removed on unmount:

```scala
def logScroll(label: String): DomNode = OnMount { el =>
  val listener: scala.scalajs.js.Function1[dom.Event, Unit] = _ => println(s"${label}: ${el.scrollTop}")
  el.addEventListener("scroll", listener)
  wvlet.uni.rx.Cancelable(() => el.removeEventListener("scroll", listener))
}
```
