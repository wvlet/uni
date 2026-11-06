# UI Components (uni-ui)

`uni-ui` is a set of ready-made components for [RxElement](./) apps on Scala.js: buttons, text
fields, tabs, foldable side panes, splitters, and overlays (popovers, tooltips, menus, dialogs,
selects, and toasts). The same components work in a browser and in an
[Electron](../http/electron) app. A plain CSS file, `uni-ui.css`, styles them with light and dark
themes.

Components keep their behavior and their look apart. Scala code sets ARIA roles, focus, keyboard
handling, and `data-state` attributes. `uni-ui.css` styles those with `--uni-*` CSS variables, so an
app restyles a component by setting variables, not by changing Scala code.

## Setup

Add the artifact to a Scala.js project:

```scala
libraryDependencies += "org.wvlet.uni" %% "uni-ui" % "<version>"
```

Load `uni-ui.css` once. It ships in the jar at `wvlet/uni/ui/uni-ui.css`. The
[sbt-uni plugin](../build/sbt-uni#uni-ui-stylesheet) copies it out of the jar on every compile, so
the file always matches the uni-ui version your app links:

```scala
// project/plugins.sbt
addSbtPlugin("org.wvlet.uni" % "sbt-uni" % "<version>")

// build.sbt
lazy val app = project
  .enablePlugins(ScalaJSPlugin, UniPlugin)
  .settings(uniUiCssFile := baseDirectory.value / "web" / "uni-ui.css")
```

With Vite and `@scala-js/vite-plugin-scalajs`, `vite dev` and `vite build` compile the Scala.js
project, so the copy stays fresh. Import it from your app's stylesheet:

```css
@import './uni-ui.css';
```

Every rule sits in the `uni` cascade layer, so unlayered app CSS always overrides it. If your app
uses other layers, declare their order once, before any import. With Tailwind CSS v4:

```css
@layer theme, base, uni, components, utilities;
@import 'tailwindcss';
@import './uni-ui.css';
```

Tailwind utilities then win over uni-ui's rules, so `Button("Save")(cls += "w-full")` works.

## Components

Each component is an `RxElement` that takes the `Rx` values it reads. Options are `withXXX` methods.
Pass extra attributes as modifiers, and use `cls +=` so the component's own classes stay.

```scala
import wvlet.uni.dom.all.*
import wvlet.uni.dom.all.given
import wvlet.uni.ui.*

val draft = Rx.variable("")
val busy  = Rx.variable(false)

div(
  Textarea(draft).withRows(4).withPlaceholder("Write a message"),
  Button("Send").primary.withDisabled(busy).onClick(() => send(draft.get)),
  IconButton("Close", closeIcon).onClick(() => close())
)
```

| Component | What it does |
|-----------|--------------|
| `Button(content*)` | `<button type="button">`. Variants are `.primary`, `.ghost`, and `.danger`, plus `.small`; set the state with `.withDisabled(rx)` and the action with `.onClick(f)` |
| `IconButton(label, icon)` | A square ghost button; `label` is its accessible name |
| `Input(text)` / `Textarea(text)` | Bound both ways to an `RxVar[String]`. Use `.withPlaceholder`, `.withLabel`, `.withInvalid(rx)` (sets `aria-invalid`), and `.withDisabled` |
| `TabList(selected, label)(Tab(value, content*)*)` | Tabs bound to an `RxVar[A]`. Arrow keys, Home, and End move the selection |
| `TabPanel(tabs)(a => element)` | Renders the selected tab's content for the `TabList` `tabs`, linked to it with `aria-controls` and `aria-labelledby` |
| `FoldablePane(open, width, edge, splitter)(content*)` | A side pane that folds to zero width; its content never reflows |
| `Splitter(size, range, edge, label)` | A resize grip. Drag it, or press the arrow keys (Shift for bigger steps); double-click or Enter resets |
| `Badge(content*)`, `Badge.dot(label)` | A count or a status dot; `.withTone(BadgeTone.Accent)` |
| `Kbd(key)` | A key cap, `<kbd>` |
| `StatusBar(content*)` | A bar along the bottom; `StatusBar.spacer` pushes the rest to the end |

A resizable sidebar:

```scala
val open  = Rx.variable(true)
val width = Rx.variable(272)
val range = SizeRange(min = 208, default = 272, max = 480)

FoldablePane(open, width, Edge.Right, Some(Splitter(width, range, Edge.Right, "Resize sidebar")))(
  ChatList()
)
```

`Edge` names the pane's inner side, the one that faces the main content: `Edge.Right` for a left
sidebar, `Edge.Left` for a right-hand dock.

## Overlays

Popovers, tooltips, menus, dialogs, selects, and toasts render at the end of `<body>`, so a pane's
`overflow: hidden` never clips them. The ones that open from a trigger take the trigger element and
add `aria-expanded`, `aria-controls`, and the toggle to it.

```scala
import wvlet.uni.ui.base.{Align, Side}

val filterOpen = Rx.variable(false)
val menuOpen   = Rx.variable(false)
val confirm    = Rx.variable(false)

div(
  Popover(filterOpen)(Button("Filter"))(Input(query).withLabel("Filter")).withAlign(Align.End),
  Tooltip("Close dock")(IconButton("Close dock", closeIcon)).withSide(Side.Left),
  Menu(menuOpen)(IconButton("More", moreIcon))(
    MenuItem("Rename").onSelect(() => rename()),
    MenuSeparator,
    MenuItem("Delete").onSelect(() => confirm := true)
  ),
  AlertDialog(confirm)("Delete this session?")
    .withDescription("Its messages can't be restored.")
    .withConfirmLabel("Delete")
    .danger
    .onConfirm(() => delete())
)
```

| Component | What it does |
|-----------|--------------|
| `Popover(open)(trigger)(content*)` | A non-modal panel next to `trigger`. `.withSide`, `.withAlign`, `.withLabel` |
| `Tooltip(text)(trigger)` | A text label that shows on hover (after `.withDelay(ms)`, 500 by default) and on keyboard focus; `text` can be an `Rx[String]` |
| `Menu(open)(trigger)(entries*)` | A list of `MenuItem`s and `MenuSeparator`s. Items take `.onSelect`, `.withShortcut`, `.withDisabled`, and `.withChecked(rx)` |
| `ContextMenu(target)(entries*)` | A menu that opens at the pointer when `target` is right-clicked |
| `Dialog(open)(content*)` | A modal dialog with a backdrop. `.withTitle`, `.withDescription`, `.withActions(buttons*)`, `.noDismissOnBackdrop()` |
| `AlertDialog(open)(title)` | A confirm-or-cancel dialog: `.onConfirm`, `.onCancel`, `.withConfirmLabel`, `.danger` |
| `Select(selected, options)(label)` | A button with a list of options, bound to an `RxVar[A]`; `options` can be an `Rx[Seq[A]]` |
| `Toaster()` | Mount one per app, then call `.info`, `.success`, or `.error` on it to show a message |

They all behave the same way:

- **Closing.** Escape, a press outside, or setting `open := false` closes an overlay. When overlays
  are nested, such as a menu inside a dialog, Escape closes only the top one.
- **Focus.** Opening moves focus into the overlay, and closing moves it back to the trigger. A
  dialog keeps Tab and Shift+Tab inside it and focuses the element with `autofocus` first.
- **Keyboard.** In menus and selects, the arrow keys, Home, End, and typed letters move between
  items, and Enter or Space chooses one.
- **Placement.** An anchored overlay flips to the other side of its trigger when there's no room,
  and shifts to stay inside the window. Its final side is in `data-side`.

`wvlet.uni.ui.base` holds the pieces they share, for building your own overlay: `Anchored.place`
(placement math), `Dismiss` (the Escape and outside-press stack), `FocusTrap`, and `FocusReturn`.

## Collections

Lists, trees, and tables render only the rows in view, so a list of ten thousand rows scrolls like
a list of ten. Every row has the same height in pixels, and the component scrolls inside its own
box: give it a height, or put it in a flex column that bounds it. Add styles with `+=` so the
component keeps its own `style`.

```scala
case class FileNode(path: String, name: String, children: Seq[FileNode] = Seq.empty)
case class Call(id: Long, method: String, millis: Double)

val expanded = Rx.variable(Set("src"))
val sort     = Rx.variable(Option(SortBy("millis", SortDirection.Descending)))

div(
  VirtualList(log.lines, rowHeight = 24)(_.id)(line => span(line.text))
    .withLabel("Log")(styleAttr += "height: 320px"),
  Tree(Rx.const(roots), (f: FileNode) => f.children)(_.path)(f => span(f.name))
    .withLabel("Files")
    .withExpanded(expanded)
    .onSelect(f => open(f)),
  DataTable(calls)(_.id)(
    Column[Call]("method", "Method")(c => span(c.method)).sortBy(_.method),
    Column[Call]("millis", "Time")(c => span(f"${c.millis}%.1f ms")).sortBy(_.millis).alignEnd
  ).withLabel("RPC calls")
    .withSort(sort)
)
```

| Component | What it does |
|-----------|--------------|
| `VirtualList(items, rowHeight)(key)(render)` | A long list (`role="listbox"`). `.withSelected(rxVar)`, `.onSelect`, `.withTypeahead(label)`, `.withOverscan(n)` |
| `Tree(roots, children)(key)(render)` | An expandable tree (`role="tree"`) over your own data. `.withExpanded(rxVar)` holds the open keys, plus `.withSelected`, `.onSelect`, `.withTypeahead`, `.withRowHeight` |
| `DataTable(rows)(key)(columns*)` | A table (`role="grid"`) with a sticky header. A `Column(id, header)(cell)` takes `.sortBy(f)`, `.withWidth("120px")`, and `.alignEnd`; `.withSort(rxVar)` holds the `SortBy` |
| `CommandPalette(open, commands)` | A ⌘K search box over `Command`s in a dialog near the top of the window. Typing filters by fuzzy match. `Command(id, label)` takes `.withGroup`, `.withHint`, `.withShortcut`, `.withKeywords`, and `.onRun` |

- **Keys.** Each row is keyed, as with `each`, so a row keeps its DOM node while it stays in view and
  only changed rows render again.
- **Keyboard.** A list, tree, or table is one tab stop, and `aria-activedescendant` names the
  highlighted row. The arrow keys, Home, End, PageUp, and PageDown move it. Enter or a click
  selects. In a tree, Right opens a row or enters it, and Left closes it or goes to the parent.
  Tables move by row, not by cell.
- **State.** Selection, open tree rows, and the sort are `RxVar`s you can pass in, so the app can
  set them or save them, for example with `Storage.local`.
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

## Dock

A `Dock` shows panels in tab groups and splits that the user rearranges, like the side panels of an
editor. Its arrangement is a `DockLayout`: plain data in an `RxVar` that you own. Save it with
`Storage.local`, which stores it as JSON:

```scala
val layout = Storage.local(
  "app.dock",
  DockLayout(DockNode.column(DockNode.group("details", "activity"), DockNode.group("terminal")))
)

Dock(layout, "Dock")(
  DockPanel("details", "Details")(DetailsView()),
  DockPanel("activity", "Activity")(ActivityLog()).withTab(Icons.activity, "Activity", Badge(count)),
  DockPanel("terminal", "Terminal")(TerminalView()).withClosable(false)
).withActions(IconButton("Close dock", Icons.panelRight).onClick(() => dockOpen := false))
```

- **Layout.** A layout is a tree of `DockNode.Split(direction, children, sizes)` and
  `DockNode.Group(panels, active)`. `DockNode.row`, `DockNode.column`, and `DockNode.group` build
  one, and `sizes` are shares of the space that sum to 1. The layout names panels by id only.
- **Rearranging.** Drag a tab onto another group's tabs to move it there, onto the middle of a
  group to add it, or onto one of a group's edges to split the group. A preview shows where it will
  land, and Escape cancels. A group that loses its last tab disappears.
- **Keyboard.** In a tab bar, the arrow keys, Home, and End switch tabs, and Delete closes a
  closable tab. A tab's context menu (right-click, Shift+F10, or the context-menu key) moves it left,
  right, or to the next group, splits it right or down, and closes it. The border between two
  children is a separator: the arrow keys move it, Shift moves it farther, Home and End go to the
  limits, and Enter shares the space equally. No child gets smaller than `.withMinPanelSize(px)`
  (120 by default).
- **Panels.** A panel's content is created the first time it shows and is kept, so moving a panel
  keeps its state. `dock.open(id)` shows a panel, adding it back if the user closed it, and
  `dock.hidden` lists the closed ones. A panel with `.withClosable(false)` can't be closed and is
  added back when a saved layout is missing it.
- **Old saves.** The Dock drops ids it doesn't know, so removing a panel from the app never breaks a
  saved layout. The JSON carries a format `version`; a save that is unreadable or from a newer
  version falls back to your default.
- **Changing it from code.** `DockModel` in `wvlet.uni.ui.base` holds the operations the Dock runs
  (`move`, `split`, `close`, `open`, `resize`) as pure functions on a `DockLayout`, so you can set
  a layout from a command or test one without a DOM. `layout.open(id)` and `layout.close(id)` are
  shortcuts.

## Themes

`uni-ui.css` reads `data-theme` on `<html>`: `light` or `dark` forces a scheme, and no attribute
follows the OS setting. `ColorScheme` sets the attribute from an `Rx`:

```scala
val scheme = Storage.local("app.colorScheme", ColorScheme.System)
ColorScheme.bind(scheme)
scheme := ColorScheme.Dark
```

To change colors, set the variables after `uni-ui.css`:

```css
:root {
  --uni-accent: #6d28d9;
  --uni-accent-2: #8b5cf6;
  --uni-radius: 4px;
}
```

| Variable | Used for |
|----------|----------|
| `--uni-canvas`, `--uni-surface`, `--uni-panel`, `--uni-raised` | Backgrounds, from the window down to raised controls |
| `--uni-fg`, `--uni-muted`, `--uni-faint` | Text, from primary to placeholder |
| `--uni-line`, `--uni-hover` | Borders and hover fills |
| `--uni-accent`, `--uni-accent-2`, `--uni-accent-fg`, `--uni-accent-soft` | The primary button gradient, its text, and focus rings |
| `--uni-danger`, `--uni-danger-soft` | Danger buttons and invalid fields |
| `--uni-success`, `--uni-success-soft` | Success toasts |
| `--uni-overlay-shadow`, `--uni-backdrop` | Overlay shadows and the dialog backdrop |
| `--uni-tooltip-bg`, `--uni-tooltip-fg` | Tooltip colors |
| `--uni-z-popover`, `--uni-z-dialog`, `--uni-z-toast` | Stacking order of popovers and menus, dialogs, and toasts and tooltips |
| `--uni-radius-sm`, `--uni-radius`, `--uni-radius-lg` | Corner radii |
| `--uni-font-sans`, `--uni-font-mono`, `--uni-font-size`, `--uni-font-size-sm` | Fonts |

## Testing

Components render into a real DOM with [uni-dom testing](./#testing-components):

```scala
class SendButtonTest extends DomTestSupport:
  test("send runs once per click") {
    var sent = 0
    val m    = mount(Button("Send").onClick(() => sent += 1))
    fireEvent.click(m.get("button"))
    sent shouldBe 1
  }
```

[`examples/uni-harness`](https://github.com/wvlet/uni/tree/main/examples/uni-harness) builds a full
workspace shell with these components and Tailwind CSS.
