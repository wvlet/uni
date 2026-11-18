---
description: uni-ui overlays for Scala.js — popovers, tooltips, menus, context menus, dialogs, selects, and toasts — with focus handling, Escape stacking, and flip-and-shift placement.
---

# Overlays

Popovers, tooltips, menus, dialogs, selects, and toasts render at the end of `<body>`, so a pane's
`overflow: hidden` never clips them. A dialog or popover can render [in place](#in-place) instead. The ones that open from a trigger take the trigger element and
add `aria-expanded`, `aria-controls`, and the toggle to it.

```scala
import wvlet.uni.dom.all.*
import wvlet.uni.dom.all.given
import wvlet.uni.rx.Rx
import wvlet.uni.ui.*
import scala.language.implicitConversions
import wvlet.uni.ui.base.{Align, Side}

val filterOpen = Rx.variable(false)
val menuOpen   = Rx.variable(false)
val confirm    = Rx.variable(false)

div(
  Popover(filterOpen, trigger = Button("Filter"), content = Seq(Input(query).withLabel("Filter")))
    .withAlign(Align.End),
  Tooltip("Close dock", trigger = IconButton("Close dock", closeIcon)).withSide(Side.Left),
  Menu(
    menuOpen,
    trigger = IconButton("More", moreIcon),
    entries = Seq(
      MenuItem("Rename").onSelect(() => rename()),
      MenuSeparator,
      MenuItem("Delete").onSelect(() => confirm := true)
    )
  ),
  AlertDialog(confirm, title = "Delete this session?")
    .withDescription("Its messages can't be restored.")
    .withConfirmLabel("Delete")
    .danger
    .onConfirm(() => delete())
)
```

| Component | What it does |
|-----------|--------------|
| `Popover(open, trigger = element, content = Seq(...))` | A non-modal panel next to `trigger`. `.withSide`, `.withAlign`, `.withLabel` |
| `Tooltip(text, trigger = element)` | A text label that shows on hover (after `.withDelay(ms)`, 500 by default) and on keyboard focus; `text` can be an `Rx[String]` |
| `Menu(open, trigger = element, entries = Seq(...))` | A list of `MenuItem`s and `MenuSeparator`s. Items take `.onSelect`, `.withShortcut`, `.withDisabled`, and `.withChecked(rx)` |
| `ContextMenu(target, entries = Seq(...))` | A menu that opens at the pointer when `target` is right-clicked |
| `Dialog(open, content = Seq(...))` | A modal dialog with a backdrop. `.withTitle`, `.withDescription`, `.withActions(buttons*)`, `.noDismissOnBackdrop()` |
| `AlertDialog(open, title = "...")` | A confirm-or-cancel dialog: `.onConfirm`, `.onCancel`, `.withConfirmLabel`, `.danger` |
| `Select(selected, options = Seq(...), optionLabel = a => text)` | A button with a list of options, bound to an `RxVar[A]`; `options` can be an `Rx[Seq[A]]` |
| `Toaster()` | Mount one per app, then call `.info`, `.success`, or `.error` on it to show a message |

They all behave the same way:

- **Closing.** Escape, a press outside, or setting `open := false` closes an overlay. When overlays
  are nested, such as a menu inside a dialog, Escape closes only the top one. Closing a dialog
  also closes the popovers, menus, and selects opened from inside it, each through its own
  `onChange` with reason `parent-close`; a handler that cancels keeps its `open` value.
- **Modal.** While a dialog is open, the rest of the page is `inert`: pointers, the keyboard,
  screen readers, and [agents](./agents) reach only the dialog and the overlays opened from it.
  The `Toaster` region stays live, so toasts can still be closed. The page is live again as soon
  as the dialog starts closing, so focus can go back while the dialog fades out. An
  [inline](#in-place) dialog blocks only its panel.
- **Focus.** Opening moves focus into the overlay, and closing moves it back to the trigger. A
  dialog keeps Tab and Shift+Tab inside it and focuses the element with `autofocus` first. When
  the trigger is gone, as when the dialog's action deleted the row it was in, focus moves to the
  element now in its place, else the one before it. This also works when the row is deleted
  right after `open := false`, and in a list that renders every row again.
- **Keyboard.** In menus and selects, the arrow keys, Home, End, and typed letters move between
  items, and Enter or Space chooses one.
- **Placement.** An anchored overlay flips to the other side of its trigger when there's no room,
  and shifts to stay inside the window. Its final side is in `data-side`. It follows the trigger
  while open, also when the trigger moves without a scroll or resize, as in a CSS transition.
- **Animation.** Overlays fade in on open and out on close. A closing overlay gets
  `data-state="closed"` and `inert`, and stays in the document until its exit animation ends.
  Focus and Escape have already moved on by then, and an overlay closed in the frame it opened
  plays its exit too. Style
  `.uni-popover[data-state='closed']` (and the other overlay classes) to change the exit; with
  `animation: none`, or under `prefers-reduced-motion`, overlays unmount at once.

## In place

`.withInline(true)` renders a `Dialog`, `AlertDialog`, or `Popover` where it is instead of at the
end of `<body>`. Use it for a dialog inside a panel, an app preview, or a fixed corner widget,
and in tests.

```scala
val renameOpen  = Rx.variable(false)
val sessionName = Rx.variable("")

div(
  styleAttr -> "position: relative",
  Button("Rename").onClick(() => renameOpen := true),
  Dialog(renameOpen, content = Seq(Input(sessionName).withLabel("Name")))
    .withTitle("Rename")
    .withInline(true)
)
```

- **Dialog.** The backdrop covers the nearest positioned ancestor, so give the panel
  `position: relative`. While the dialog is open, the content of that panel outside the dialog is
  `inert`, and the rest of the page stays live. So the dialog leaves out `aria-modal`, and Escape
  closes it only while focus is inside it. The focus trap, a press outside, `onChange`, focus
  return, and the exit animation work as usual.
- **Popover.** The trigger and the panel are wrapped in `span.uni-popover-anchor`, and CSS places
  the panel under the trigger. `withSide` and `withAlign` don't apply, and `data-side` is not set.
  Style `.uni-popover--inline` to move it. Pane overflow can clip it.

`wvlet.uni.ui.base` holds the pieces they share, for building your own overlay: `Anchored.place`
(placement math), `Dismiss` (the Escape and outside-press stack), `FocusTrap`, and `FocusReturn`.
