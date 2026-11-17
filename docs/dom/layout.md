---
description: Lay out a Scala.js app with uni-ui — resizable foldable side panes, splitters, a status bar, and a Dock of tab groups and splits that the user rearranges and the app saves as JSON.
---

# Layout & Dock

Side panes that fold and resize, a status bar, and a `Dock` of panels the user rearranges.

| Component | What it does |
|-----------|--------------|
| `FoldablePane(open, width = w, edge = e, content = Seq(...)).withSplitter(splitter)` | A side pane that folds to zero width; its content never reflows |
| `Splitter(size, range, edge, label)` | A resize grip. Drag it, or press the arrow keys (Shift for bigger steps); double-click or Enter resets |
| `StatusBar(content*)` | A bar along the bottom; `StatusBar.spacer` pushes the rest to the end |

A resizable sidebar:

```scala
import wvlet.uni.dom.all.*
import wvlet.uni.dom.all.given
import wvlet.uni.rx.Rx
import wvlet.uni.ui.*
import scala.language.implicitConversions

val open  = Rx.variable(true)
val width = Rx.variable(272)
val range = SizeRange(min = 208, default = 272, max = 480)

FoldablePane(open, width = width, edge = Edge.Right, content = Seq(ChatList()))
  .withSplitter(Splitter(width, range, Edge.Right, "Resize sidebar"))
```

`Edge` names the pane's inner side, the one that faces the main content: `Edge.Right` for a left
sidebar, `Edge.Left` for a right-hand dock.

## Dock

A `Dock` shows panels in tab groups and splits that the user rearranges, like the side panels of an
editor. Its arrangement is a `DockLayout`: plain data in an `RxVar` that you own. Save it with
`Storage.local`, which stores it as JSON:

```scala
val layout = Storage.local(
  "app.dock",
  DockLayout(DockNode.column(DockNode.group("details", "activity"), DockNode.group("terminal")))
)

Dock(
  layout,
  label = "Dock",
  panels = Seq(
    DockPanel("details", title = "Details", content = DetailsView()),
    DockPanel("activity", title = "Activity", content = ActivityLog())
      .withTab(Icons.activity, "Activity", Badge(count)),
    DockPanel("terminal", title = "Terminal", content = ShellView()).withClosable(false)
  )
).withActions(IconButton("Close dock", Icons.panelRight).onClick(() => dockOpen := false))
```

- **Layout.** A layout is a tree of `DockNode.Split(direction, children, sizes)` and
  `DockNode.Group(panels, active)`. `DockNode.row`, `DockNode.column`, and `DockNode.group` build
  one, and `sizes` are shares of the space that sum to 1. The layout names panels by id only.
- **Floating groups.** "Float" in a tab's menu lifts the panel into a group floating over the grid,
  saved in the layout's `floating` as a `DockFloat(panels, active, box)`. The `DockBox` holds its
  left, top, width, and height as shares of the dock (0 to 1), so it keeps its place when the
  window resizes and always stays inside the dock. Drag the grip at the left of its tab bar to move
  it and its bottom-right corner to resize it, or focus the grip and press the arrow keys to move it
  (Shift+arrow keys resize it, 16px a step). Tabs move into and out of a float as between other
  groups; a float holds one group, so a drop anywhere on it adds the tab. "Dock" in the menu puts a
  panel back into the grid. Pressing a float brings it to the front.
- **Detached windows.** With `.withDetach(true)`, "Open in new window" moves a panel into a browser
  window of its own, for a second monitor, and `dock.detach(id)` does the same from code. The
  content keeps its state, the page's styles and theme come along, and its tab shows where it went.
  The panel comes back when the window closes, on `dock.reattach(id)`, or when the panel is closed;
  `dock.detached` lists the detached panels. A detached window isn't saved, because a browser blocks
  windows that open without a click, so after a reload the panel is back in its tab. The window
  comes from `window.open` on the same origin, so an Electron app must allow `about:blank` in its
  `setWindowOpenHandler`, and should deny window opening and navigation in the new window
  (`did-create-window`). Menus and popovers opened inside the window still show in the main window,
  and stylesheets added to the page after the window opens don't reach it.
- **Rearranging.** Drag a tab onto another group's tabs to move it there, onto the middle of a
  group to add it, or onto one of a group's edges to split the group. A preview shows where it will
  land, and Escape cancels. A group that loses its last tab disappears.
- **Keyboard.** In a tab bar, the arrow keys, Home, and End switch tabs. With Alt, the same keys
  move the focused tab within its group, as [`Reorder`](./behaviors#reorder) does in a list.
  Delete closes a closable tab. A tab's context menu (right-click, Shift+F10, or the context-menu key) moves it left,
  right, or to the next group, splits it right or down, and closes it. The border between two
  children is a separator: drag it, or use the arrow keys (Shift moves it farther), Home and End to
  go to the limits, and Enter to share the space equally. In a right-to-left layout, the first pane
  of a row is on the right, and both the drag and the arrow keys follow it. No child gets smaller than `.withMinPanelSize(px)`
  (120 by default).
- **Panels.** A panel's content is created the first time it shows and is kept, so moving a panel
  keeps its state. `dock.open(id)` shows a panel, adding it back if the user closed it, and
  `dock.hidden` lists the closed ones. A panel with `.withClosable(false)` can't be closed and is
  added back when a saved layout is missing it.
- **Focus from code.** `dock.focusGroup(i)` moves keyboard focus to the active tab of group `i`,
  counted from 0 in reading order (the tab list named "group 1" is index 0), and `dock.groupCount`
  tells how many there are. `dock.focusPanel(id)` shows a panel and focuses its tab. Both return
  false when there is nothing to focus. The Dock binds no keys itself, so pick your own shortcuts:
  `(1 to 9).foreach(n => Keyboard.bind(s"ctrl+${n}", () => dock.focusGroup(n - 1)))`.
- **Old saves.** The Dock drops ids it doesn't know, so removing a panel from the app never breaks a
  saved layout. The JSON carries a format `version`; a save that is unreadable or from a newer
  version falls back to your default.
- **Changing it from code.** `DockModel` in `wvlet.uni.ui.base` holds the operations the Dock runs
  (`move`, `split`, `close`, `open`, `resize`, `float`, `dock`, `withBox`) as pure functions on a `DockLayout`, so you can set
  a layout from a command or test one without a DOM. `layout.open(id)` and `layout.close(id)` are
  shortcuts.
