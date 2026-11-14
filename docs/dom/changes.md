---
description: See and cancel every change a uni-ui component makes with onChange, tell a pointer, key, program, or AI agent apart with ChangeSource and ChangeCause, and target component parts by data-slot.
---

# Changes & Parts

Components write user input into the `RxVar`s you pass them: `open`, `selected`, a field's
text, a tree's open rows, a table's sort, a reorderable list's order, a splitter's size, a
dock's layout. `onChange` sees
each such change before it lands. The handler gets a `ChangeDetails` with the `key` that
changes, the new `value` and the `previous` one, a `reason`, and a `source`. Calling
`cancel()` keeps the old value:

```scala
import wvlet.uni.dom.all.*
import wvlet.uni.dom.all.given
import wvlet.uni.rx.Rx
import wvlet.uni.ui.*
import scala.language.implicitConversions

val region   = Rx.variable("tokyo")
val unsaved  = Rx.variable(true)
val settings = Rx.variable("general")

div(
  Select(region, options = Seq("tokyo", "oregon", "frankfurt"), optionLabel = s => s).onChange { d =>
    if d.source == ChangeSource.Agent && d.value == "frankfurt" then d.cancel()
  },
  TabList(settings, label = "Settings", tabs = Seq(Tab("general", "General"), Tab("billing", "Billing")))
    .onChange(d => if unsaved.get then d.cancel())
)
```

- **Reasons.** `ChangeReason` names the interaction: `Press`, `ItemPress`, `ActionPress`,
  `EscapeKey`, `OutsidePress`, `NavigationKey`, `Typeahead`, `Input`, `Drag`, `Reset`,
  `DeleteKey`, `FocusOut`, and `Imperative` for calls such as `dock.open(id)`. Make your own
  with `ChangeReason("voice-command")`.
- **Sources.** `ChangeSource` is `Pointer` (mouse, touch, or pen), `Keyboard`, `Programmatic`,
  or `Agent`. A click that Enter or Space made on a button counts as `Keyboard`. Code that
  wraps a block in `ChangeSource.withSource(ChangeSource.Agent) { ... }` marks every change
  inside it, which is how an AI agent driving the UI gets told apart from the user.
- **What is reported.** Only changes the component makes. Writing `open := false` yourself
  reports nothing, and neither does a write that leaves the value as it was.
- **Text fields.** A refused edit puts the field back. During an IME composition, the text
  stays in the field and goes through `onChange` once, when the composition ends.
- **Callbacks.** `onClick`, `onSelect`, `onRun`, `onConfirm`, and `onCancel` keep their
  signatures. Inside one, `ChangeCause.current` tells what triggered it:

```scala
Button("Delete").onClick { () =>
  if ChangeCause.current.source == ChangeSource.Keyboard then println("pressed with a key")
}
```

## Parts

Every element a component renders carries `data-slot`, a stable name for CSS, tests, and
tools. The root is the component's name, such as `button`, `menu`, or `data-table`, and its
parts add a suffix: `menu-item`, `dialog-title`, `dock-tab`, `select-option`. Classes and
`data-state` stay as they are, so existing styles keep working.

```css
[data-slot='menu-item'][aria-disabled='true'] { opacity: 0.4; }
```
