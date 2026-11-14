---
description: uni-ui collections for Scala.js — virtualized VirtualList, Tree, and DataTable with keyboard navigation, multi-select, and drag or keyboard reordering, plus a fuzzy-search CommandPalette.
---

# Collections

Lists, trees, and tables render only the rows in view, so a list of ten thousand rows scrolls like
a list of ten. Every row has the same height in pixels, and the component scrolls inside its own
box: give it a height, or put it in a flex column that bounds it. Add styles with `+=` so the
component keeps its own `style`.

```scala
import wvlet.uni.dom.all.*
import wvlet.uni.dom.all.given
import wvlet.uni.rx.Rx
import wvlet.uni.ui.*
import scala.language.implicitConversions

case class FileNode(path: String, name: String, children: Seq[FileNode] = Seq.empty)
case class Call(id: Long, method: String, millis: Double)

val roots    = Seq(FileNode("src", "src", Seq(FileNode("src/main", "main"))))
val calls    = Rx.variable(Seq(Call(1, "getUser", 3.2)))
val expanded = Rx.variable(Set("src"))
val sort     = Rx.variable(Option(SortBy("millis", SortDirection.Descending)))

div(
  VirtualList(log.lines, rowHeight = 24)(_.id)(line => span(line.text))
    .withLabel("Log")(styleAttr += "height: 320px"),
  Tree(Rx.const(roots), (f: FileNode) => f.children)(_.path)(f => span(f.name))
    .withLabel("Files")
    .withExpanded(expanded)
    .onSelect(f => openFile(f)),
  DataTable(calls)(_.id)(
    Column[Call]("method", "Method")(c => span(c.method)).sortBy(_.method),
    Column[Call]("millis", "Time")(c => span(f"${c.millis}%.1f ms")).sortBy(_.millis).alignEnd
  ).withLabel("RPC calls")
    .withSort(sort)
)
```

| Component | What it does |
|-----------|--------------|
| `VirtualList(items, rowHeight)(key)(render)` | A long list (`role="listbox"`). `.withSelected(rxVar)` or `.withMultiSelect(setVar)`, `.onSelect`, `.withTypeahead(label)`, `.withOverscan(n)`, `.withReorder(itemsVar)` |
| `Tree(roots, children)(key)(render)` | An expandable tree (`role="tree"`) over your own data. `.withExpanded(rxVar)` holds the open keys, plus `.withSelected` or `.withMultiSelect(setVar)`, `.onSelect`, `.withTypeahead`, `.withRowHeight` |
| `DataTable(rows)(key)(columns*)` | A table (`role="grid"`) with a sticky header. A `Column(id, header)(cell)` takes `.sortBy(f)`, `.withWidth("120px")`, and `.alignEnd`; `.withSort(rxVar)` holds the `SortBy`, and `.withSelected(rxVar)` or `.withMultiSelect(setVar)` the selection; `.withReorder(rowsVar)`, and `.withRowLabel(r => text)` names rows for `UiAgent` |
| `CommandPalette(open, commands)` | A ⌘K search box over `Command`s in a dialog near the top of the window. Typing filters by fuzzy match. `Command(id, label)` takes `.withGroup`, `.withHint`, `.withShortcut`, `.withKeywords`, and `.onRun` |

- **Keys.** Each row is keyed, as with `each`, so a row keeps its DOM node while it stays in view and
  only changed rows render again.
- **Keyboard.** A list, tree, or table is one tab stop, and `aria-activedescendant` names the
  highlighted row. The arrow keys, Home, End, PageUp, and PageDown move it. Enter or a click
  selects. In a tree, Right opens a row or enters it, and Left closes it or goes to the parent.
  Tables move by cell, as in the WAI-ARIA grid pattern: Left and Right move along the row, Home
  and End go to its first and last cell, and Ctrl (or ⌘) with Home or End goes to the first or
  last row. `aria-activedescendant` then names the cell, and Enter still selects its row.
- **Multi-select.** `.withMultiSelect(setVar)` on a list, tree, or table lets the user select several
  rows, as in a file list. A click selects one row, Shift+click selects a range from the anchor,
  and Ctrl (or ⌘)+click adds or removes a row. Shift with an arrow key, Home, End, PageUp, or PageDown
  extends the range, Space toggles the highlighted row, Ctrl+A selects all, and Escape clears. Enter and a
  plain click still run `onSelect`. The container gets `aria-multiselectable="true"`, and
  `onChange` reports each change with the key `selected` and a `Set` value. The keys of rows that
  are filtered out stay in the set until the app removes them.
- **Multi-select in a tree.** Ranges and Ctrl+A cover the visible rows, so the children of a closed
  row are never selected by accident, and Left and Right keep their tree meaning even with Shift.
  Closing a row deselects the rows it hides, reported to `onChange` as a `selected` change after
  the `expanded` one; cancel it to keep them. Opening a row never changes the selection.
- **Multi-select in a table.** Shift with Home or End extends the selection to the first or last
  row instead of moving along the row, since rows are what a table selects. Plain Home and End
  still go to the row's first and last cell.
- **Reordering.** `.withReorder(itemsVar)` on a list or table lets the user move rows, as
  [`Reorder`](./behaviors#reorder) does: drag a row (near the top or bottom edge the list
  scrolls), or press Alt+ArrowUp, Alt+ArrowDown, Alt+Home, or Alt+End to move the highlighted
  row. Pass the `RxVar` the component shows; the new order is written there after `onChange`
  sees it with the key `order`, and each move is announced. With `.withMultiSelect`, a selected
  row moves the whole selection as one block. The keys work by position, so they move a row far
  down a long list, and a drag keeps going after the dragged row scrolls out of the window. A
  table can't be reordered while a column sort is applied; clear the sort to move rows. An AI
  agent can move a row with the `move` action (see [Agents](./agents)).
- **State.** Selection, open tree rows, the sort, and the order are `RxVar`s you can pass in, so
  the app can set them or save them, for example with `Storage.local`.
- **The palette.** While the query is empty, the palette lists commands in order under their
  groups. Once the user types, the best matches come first, with the matched letters marked. It
  closes before running a command, so focus goes back to where it was. uni-ui doesn't bind ⌘K, so
  bind it yourself:

```scala
val paletteOpen = Rx.variable(false)
Keyboard.bindAll("meta+k" -> (() => paletteOpen := true), "ctrl+k" -> (() => paletteOpen := true))

CommandPalette(
  paletteOpen,
  store.conversations.map { cs =>
    Seq(Command("new-chat", "New chat").withGroup("Chat").onRun(() => store.newChat())) ++
      cs.map(c => Command(s"chat-${c.id}", c.title).withGroup("Conversations").onRun(() => store.select(c.id)))
  }
)
```

`wvlet.uni.ui.base` holds the logic they run on, with no DOM: `Windowing` (which rows to render),
`ListNavigation`, `TreeModel` (flattening and the tree keys), and `Fuzzy` (matching and scores).

A reorderable task list, where the selected tasks move together:

```scala
case class Task(id: Int, title: String)

val tasks  = Rx.variable(Seq(Task(1, "Write"), Task(2, "Review"), Task(3, "Ship")))
val picked = Rx.variable(Set.empty[Int])

VirtualList(tasks, rowHeight = 32)(_.id)(t => span(t.title))
  .withLabel("Tasks")
  .withTypeahead(_.title)
  .withMultiSelect(picked)
  .withReorder(tasks)
  .onChange(d => if d.key == "order" && d.source == ChangeSource.Agent then d.cancel())
```
