# Browser UI: uni-dom and uni-ui (Scala.js)

`wvlet.uni.dom` (in `uni`) renders `RxElement` components into the DOM. It has no virtual DOM: an
`Rx` value embedded in the markup updates its own part of the DOM in place. `wvlet.uni.ui` (the
`uni-ui` artifact) adds ready-made components styled by `uni-ui.css`. Both are Scala.js only. They
work in a browser and in an Electron renderer (see [electron.md](electron.md)).

## Setup

```scala sbt
// build.sbt (Scala.js project)
libraryDependencies += "org.wvlet.uni" %% "uni-ui" % "<version>"  // pulls in uni

// project/plugins.sbt: copies uni-ui.css out of the jar on every compile
addSbtPlugin("org.wvlet.uni" % "sbt-uni" % "<version>")
lazy val app = project
  .enablePlugins(ScalaJSPlugin, UniPlugin)
  .settings(uniUiCssFile := baseDirectory.value / "web" / "uni-ui.css")
```

Then `@import './uni-ui.css';` once from the app stylesheet. Every rule is in `@layer uni`, so
unlayered app CSS wins. With Tailwind v4, declare
`@layer theme, base, uni, components, utilities;` before the imports so utilities override uni-ui.

## Components with uni-dom

A component is a class that extends `RxElement` and implements `render`. State lives in `Rx`
variables. Embedding an `Rx` in markup or an attribute keeps that spot up to date.

```scala
import wvlet.uni.dom.all.*
import wvlet.uni.dom.all.given
import wvlet.uni.rx.Rx
import scala.language.implicitConversions

case class Todo(id: Int, title: String, done: Boolean)

class TodoApp extends RxElement:
  private val todos = Rx.variable(Seq.empty[Todo])
  private val draft = Rx.variable("")

  private def add(): Unit =
    val title = draft.get.trim
    if title.nonEmpty then
      todos := todos.get :+ Todo(todos.get.size, title, done = false)
      draft := ""

  override def render: RxElement = div(
    cls -> "todo-app",
    input(value.bind(draft), placeholder -> "What needs doing?"), // two-way binding
    button(onclick -> { () => add() }, disabled -> draft.map(_.isBlank), "Add"),
    // `each` keys rows, so unchanged rows keep their DOM nodes
    ul(todos.each(_.id)(t => li(t.title))),
    span(todos.map(ts => s"${ts.count(!_.done)} left"))
  )

def main(): Unit = TodoApp().renderTo("app") // mounts into <div id="app">
```

- Events are lowercase DOM names: `onclick -> { () => ... }`, `oninput -> { (e: dom.Event) => ... }`.
- Attributes are `name -> value`, and `cls += "x"` appends. Use `styleAttr`, `titleAttr`, `tpe` and
  `forId` for `style`, `title`, `type` and `for`. `data("k") -> v` sets `data-k`.
- Boolean attributes (`disabled`, `hidden`, ...) take a `Boolean` or an `Rx[Boolean]`. `false`
  removes the attribute.
- `rx.each(key)(render)` renders a keyed list. `rx.eachRx(key)((first, current) => ...)` updates a
  row in place through `current: Rx[A]`.
- Helpers in `wvlet.uni.dom` include `Keyboard.bind("meta+k", f)`, `Storage.local(key, default)`
  (an `RxVar` that persists), `Router`, `Portal`, `MediaQuery`, `ClickOutside` and `Focus`.

## uni-ui components

uni-ui components are immutable `RxElement`s. Builder methods return a copy, options are
`withXXX`, and callbacks are `onXXX`. They read `Rx` values and write to the `RxVar`s you pass in.
Apply extra attributes with a second call: `Button("Save")(cls += "w-full", data("testid") -> "save")`.

```scala
import wvlet.uni.ui.*
import wvlet.uni.ui.base.{Align, Side}

def send(text: String): Unit   = ()
def deleteSession(): Unit      = ()
val closeIcon: RxElement       = span("×")

val message    = Rx.variable("")
val busy       = Rx.variable(false)
val view       = Rx.variable("chat")
val menuOpen   = Rx.variable(false)
val confirmDel = Rx.variable(false)
val region     = Rx.variable("tokyo")
val toaster    = Toaster()

val composer = div(
  Textarea(message).withRows(4).withPlaceholder("Write a message"),
  Button("Send").primary.withDisabled(busy).onClick(() => send(message.get)),
  IconButton("Close", closeIcon).onClick(() => busy := false),
  TabList(view, "Views")(Tab("chat", "Chat"), Tab("logs", "Logs")),
  Select(region, Seq("tokyo", "oregon"))(r => r.capitalize),
  Tooltip("More actions")(
    Menu(menuOpen)(IconButton("More", closeIcon))(
      MenuItem("Delete").onSelect(() => confirmDel := true)
    ).withAlign(Align.End)
  ).withSide(Side.Left),
  AlertDialog(confirmDel)("Delete this session?")
    .withConfirmLabel("Delete")
    .danger
    .onConfirm(() => deleteSession()),
  toaster
)
```

| Group | Components |
|-------|------------|
| Controls | `Button` (`.primary`, `.ghost`, `.danger`, `.small`), `IconButton(label, icon)`, `Input(rxVar)`, `Textarea(rxVar)`, `Select(rxVar, options)(label)` |
| Navigation and layout | `TabList(rxVar, label)(Tab(value, content*)*)`, `TabPanel(tabs)(render)`, `FoldablePane(open, width, edge, splitter)(content*)`, `Splitter(size, range, edge, label)`, `Dock(layoutVar, label)(DockPanel(id, title)(content)*)` |
| Display | `Badge`, `Kbd`, `StatusBar` |
| Overlays | `Popover(open)(trigger)(content*)`, `Tooltip(text)(trigger)`, `Menu(open)(trigger)(MenuItem*)`, `ContextMenu(target)(entries*)`, `Dialog(open)(content*)`, `AlertDialog(open)(title)`, `Toaster()` with `.info`/`.success`/`.error` |
| Collections (windowed, fixed row height) | `VirtualList(itemsRx, rowHeight)(key)(render)`, `Tree(rootsRx, children)(key)(render)`, `DataTable(rowsRx)(key)(Column(id, header)(cell)*)`, `CommandPalette(open, commandsRx)` |
| Theme | `ColorScheme.bind(rxVar)` with `ColorScheme.System`/`Light`/`Dark` |

Overlays render at the end of `<body>` and share one behavior:
- Escape or a press outside closes the top overlay only.
- Focus moves into the overlay on open and back to the trigger on close.
- Anchored overlays flip and shift to stay in the window.
- To close one from code, set its `open := false`.

Collections are one tab stop. They track the highlighted row with `aria-activedescendant`, and
render only the rows in view, so give them a bounded height.

```scala
case class Call(id: Long, method: String, millis: Double)

val calls       = Rx.variable(Seq(Call(1, "getUser", 3.2)))
val pickedIds   = Rx.variable(Set.empty[Long])
val paletteOpen = Rx.variable(false)

val table = DataTable(calls)(_.id)(
  Column[Call]("method", "Method")(c => span(c.method)).sortBy(_.method),
  Column[Call]("millis", "Time")(c => span(f"${c.millis}%.1f ms")).sortBy(_.millis).alignEnd
).withLabel("RPC calls")
  // Shift/Ctrl+click, Shift+Arrow, Space, Ctrl+A; withSelected(optionVar) for one row
  .withMultiSelect(pickedIds)(styleAttr += "height: 320px")

val palette = CommandPalette(
  paletteOpen,
  Rx.const(Seq(Command("new-chat", "New chat").withGroup("Chat").onRun(() => send(""))))
)
// uni-ui binds no global keys; bind them in the app
Keyboard.bind("meta+k", () => paletteOpen := true)
```

## Watching and refusing changes

`onChange` on a component sees each change it is about to make to an `RxVar` you passed in
(`open`, `selected`, text `value`, `expanded`, `sort`, `size`, `layout`). `d.cancel()` keeps the
old value. `d.source` is `Pointer`, `Keyboard`, `Programmatic`, or `Agent`. Inside `onClick`,
`onSelect`, and similar callbacks, `ChangeCause.current` gives the same reason and source.

```scala
val unsaved = Rx.variable(false)

val guardedTabs = TabList(view, "Views")(Tab("chat", "Chat"), Tab("logs", "Logs"))
  .onChange(d => if unsaved.get then d.cancel())

val agentSafeRegion = Select(region, Seq("tokyo", "oregon"))(identity).onChange { d =>
  if d.source == ChangeSource.Agent then d.cancel()
}

// Changes made inside this block report ChangeSource.Agent
ChangeSource.withSource(ChangeSource.Agent)(menuOpen := false)
```

Writes the app makes itself (`open := false`) are not reported.

## Behaviors for your own widgets

`wvlet.uni.ui.base` holds the interaction logic uni-ui is built from. Each behavior is a pure model
plus a binding modifier; ones that change an app `RxVar` take an `onChange` handler, like the
components. Full catalog: `docs/dom/behaviors.md`.

```scala
import wvlet.uni.ui.base.*

// A chat thread that follows new content unless the reader scrolled up
val stick = StickToBottom()
val chatLog = div(cls -> "thread", stick.binding)
stick.scrollToBottom()  // after the user sends a message

// A composer with terminal-style ArrowUp/ArrowDown history
val prompt  = Rx.variable("")
val history = InputHistory()
val promptBox = textarea(value.bind(prompt), history.keys(prompt), Keyboard.onEnter { () =>
  history.add(prompt.get)
  prompt := ""
})

// Multi-select in your own list (VirtualList and DataTable have .withMultiSelect)
val picked    = Rx.variable(Set.empty[String])
val selection = Selection(picked)

// Reorder by drag, or Alt+ArrowUp/Down on a focused item; each move is announced.
// withSelection (the same Set var as Selection) moves the selected items together;
// drags near the edge of the list's scroll container autoscroll
val tasks   = Rx.variable(Seq("Write", "Review", "Ship"))
val reorder = Reorder(tasks).withSelection(picked)
val taskList = ul(tasks.each(identity)(t => li(tabindex -> "0", reorder.item(t), t)))

Announce.prepare()                    // once at startup, so the first message is read
Announce.polite("Response complete")  // screen readers only
```

- `Typeahead().onKey(e, labels, activeIndex)`: buffered search ("ca" finds "Cat").
- `FocusRecovery.within("li")` on a list keeps focus in it when the focused item is removed.
- `RovingFocus`, `ListNavigation`, `FocusTrap`/`FocusReturn`, `Dismiss`, `Anchored`, `Windowing`.
- `OnMount(el => cancelable)` (uni-dom) binds your own behavior to any element.

## Styling

- Restyle with `--uni-*` variables set after `uni-ui.css`, not by changing Scala code:
  - colors: `--uni-canvas`, `--uni-surface`, `--uni-panel`, `--uni-raised`, `--uni-fg`,
    `--uni-muted`, `--uni-line`, `--uni-accent`, `--uni-danger`, `--uni-success`;
  - radii: `--uni-radius-sm`, `--uni-radius`, `--uni-radius-lg`;
  - fonts: `--uni-font-sans`, `--uni-font-mono`, `--uni-font-size`;
  - stacking: `--uni-z-popover`, `--uni-z-dialog`, `--uni-z-toast`.
- `data-theme="light"` or `"dark"` on `<html>` forces a scheme. With no attribute, the OS setting
  decides.
- Every part has `data-slot`: the component name for its root (`button`, `menu`, `data-table`)
  and `<component>-<part>` inside (`menu-item`, `dialog-title`, `dock-tab`). Target those in CSS
  and tests. Classes follow `uni-<component>` and `uni-<component>--<variant>` (for example
  `uni-button--primary`). Overlays carry `data-state="open"`/`"closed"` and `data-side`. Style
  those instead of adding Scala code.
- Animations: a closing overlay keeps `data-state="closed"` until its CSS exit animation ends. With
  `animation: none` it unmounts at once.

## Testing

`uni-test` on Scala.js includes the DOM testing kit. Run it in headless Chromium with the
`sbt-uni-playwright` plugin (see [testing.md](testing.md#testing-a-uni-dom-ui-scala-js)).
`DomTestSupport` unmounts everything after each test. `Rx` updates re-render synchronously, so
assert right after `fireEvent`.

```scala
import wvlet.uni.dom.testing.*

class ComposerTest extends DomTestSupport:
  test("Send runs once per click") {
    var sent = 0
    val m    = mount(Button("Send").onClick(() => sent += 1))
    fireEvent.click(m.get("button"))
    sent shouldBe 1
  }

  test("Delete asks for confirmation") {
    val open = Rx.variable(false)
    mount(AlertDialog(open)("Delete?").withConfirmLabel("Delete"))
    open := true
    org.scalajs.dom.document.querySelector("[role=alertdialog]") shouldNotBe null
  }
```

`checkPattern(root, pattern)` checks a widget against its WAI-ARIA APG pattern: roles, required
states, an accessible name, id references that resolve, one focus model, and then the keys the
pattern requires. It returns `Rx[Seq[AriaViolation]]`; settle first when an overlay opened, and
call it last, since the keys change state (`keyboard = false` skips them). Patterns: `Tabs`,
`Menu`, `MenuButton`, `Listbox`, `Combobox`, `Dialog`, `AlertDialog`, `Tree`, `Grid`, `Tooltip`.

```scala
import wvlet.uni.test.empty

class ModelPickerTest extends DomTestSupport:
  test("the model picker follows the listbox pattern") {
    val m = mount(Select(Rx.variable("sonnet"), Seq("haiku", "sonnet"))(identity).withLabel("Model"))
    fireEvent.click(m.get("button"))
    settle()
      .flatMap(_ => checkPattern(org.scalajs.dom.document.querySelector("[role=listbox]"), AriaPattern.Listbox))
      .map(_ shouldBe empty)
  }
```

## Mistakes to avoid

- **A JVM project.** uni-dom and uni-ui are Scala.js only.
- **`new` or a virtual-DOM mindset.** Write `Button("Save")`, not `new Button(...)`. Don't rebuild
  the whole tree on every change: embed `Rx` values where the DOM should change.
- **Rendering a list with `rx.map(_.map(...))`.** That rebuilds every row on each change. Use
  `each`/`eachRx` with a key.
- **Setting a `style` attribute on a component.** That replaces the component's own style. Use
  `styleAttr += ...` and `cls += ...` instead.
- **Forgetting `uni-ui.css`.** The components are unstyled and overlays misplace without it.
- **jsdom.** Test in headless Chromium (`UniPlaywrightPlugin`).
