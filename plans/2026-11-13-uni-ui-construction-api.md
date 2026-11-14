# uni-ui construction API: one list of named, required arguments

## Goals

- Make every uni-ui call site self-describing: a reader (person or AI agent) sees which argument is the data, the key, the renderer, the trigger, and the entries without opening the source.
- Replace the curried shapes (`VirtualList(items, rowHeight)(key)(render)`, `Popover(open)(trigger)(content*)`, ...) with one rule that holds for every component.
- Ship it as one breaking change while most of uni-ui is pre-release.

## Background

- 16 public constructors in `uni-ui/src/main/scala/wvlet/uni/ui/` use 2–3 parameter lists. The rest (`Button`, `Input`, `Textarea`, `Tab`, `Command`, `CommandPalette`, `Badge`, `Kbd`, `StatusBar`) already take one list plus `withXXX`/`noXXX`.
- Currying was a Scala 2 habit: a later list fixes `A` before the compiler types the `key`/`render` lambdas. Scala 3 types function literals after the other arguments, so it is no longer needed.
- I verified on Scala 3.3 and 3.9 that named lambdas in one list infer without type arguments: `VirtualList(..., key = _.id, render = f => ...)`, `columns = Seq(Column(..., cell = _.name))` (the row type comes from `rows` unless a method is chained on the column), and the overloaded `Select(..., optionLabel = _.name)`.
- Optional settings already follow the AGENTS.md convention (`withXXX`/`noXXX`).

## Design

Lane: Standard. This breaks the public API of 14 components, plus the `Column` and `DockPanel` helpers.

**Rule**
- A component has **exactly one parameter list**, holding everything it cannot work without. A menu without entries or a dialog without content makes no sense, so entries and content are required arguments too.
- Arguments come in the order *state → data → key → render/trigger → entries/content*.
- A multi-valued argument is a named `Seq` (`entries = Seq(...)`, `columns = Seq(...)`, `tabs = Seq(...)`, `panels = Seq(...)`, `content = Seq(...)`). It is never varargs and never a second list.
- Everything optional is a `withXXX`/`noXXX` builder.
- Docs, the skill, and examples name every argument after the first. The first is the obvious subject (`open`, `items`, `rows`, `selected`, `text`).
- `Tree`'s `children: A => Seq[A]` keeps its name. It reads child nodes from the data, and the tree renders only the visible rows.

**Changed**

| Component | Before | After |
|---|---|---|
| VirtualList | `VirtualList(items, 32)(_.id)(f => Row(f))` | `VirtualList(items, rowHeight = 32, key = _.id, render = f => Row(f))` |
| Tree | `Tree(roots, _.children)(_.path)(n => Row(n))` | `Tree(roots, children = _.children, key = _.path, render = n => Row(n))` |
| DataTable | `DataTable(rows)(_.id)(cols*)` | `DataTable(rows, key = _.id, columns = Seq(Column("name", "Name", cell = _.name)))` |
| Select | `Select(sel, opts)(_.title)` | `Select(sel, options = opts, optionLabel = _.title)` |
| Popover | `Popover(open)(Button("Filters"))(Form())` | `Popover(open, trigger = Button("Filters"), content = Seq(Form()))` |
| Menu | `Menu(open)(Button("More"))(items*)` | `Menu(open, trigger = Button("More"), entries = Seq(MenuItem("Copy"), MenuSeparator))` |
| ContextMenu | `ContextMenu(target)(items*)` | `ContextMenu(target, entries = Seq(...))` |
| Dialog | `Dialog(open)(content*)` | `Dialog(open, content = Seq(p("..."), ...))` |
| AlertDialog | `AlertDialog(open)("Delete file?")` | `AlertDialog(open, title = "Delete file?")` |
| Tooltip | `Tooltip("Save")(Button(...))` | `Tooltip("Save", trigger = Button(...))` |
| TabList | `TabList(sel, "Views")(Tab(...)*)` | `TabList(sel, label = "Views", tabs = Seq(Tab(...)))` |
| TabPanel | `TabPanel(tabs) { case ... }` | `TabPanel(tabs, render = { case ... })` |
| FoldablePane | `FoldablePane(open, width, edge, splitter)(content*)` | `FoldablePane(open, width = w, edge = Edge.Left, content = Seq(...)).withSplitter(s)` |
| Dock | `Dock(layout, "Dock")(DockPanel(id, title)(view)*)` | `Dock(layout, label = "Dock", panels = Seq(DockPanel("terminal", title = "Terminal", content = TerminalView())))` |

- `Column(id, header)(cell)` becomes `Column("name", "Name", cell = _.name)`.
- `DockPanel`'s `content` stays by-name, so a panel is still created the first time it shows.
- `FoldablePane`'s optional `splitter` moves to `.withSplitter(s)`, following the rule that everything optional is a builder.
- These keep their current form: `Button(content*)`, `Tab(value, content*)`, `Badge(content*)`, `StatusBar(content*)`. Their content is the component itself, so there is no other required argument to confuse it with.

**Scope and process**
- No deprecated forwarders: the old curried overloads are removed outright.
- The same PR updates `docs/dom/*.md` (rebased onto #912's split if it merges first), `docs/guide/for-ai-agents.md`, and the `uni` skill (`references/ui.md` plus a plugin version bump). It also updates `examples/uni-harness` and the uni-ui tests. All fences compile in `docSnippetsJS`. An ADR records the rule.
- Out of scope: uni-dom's HTML DSL, private helpers (`Presence`, `AgentAction`), and any behavior change.

## Alternatives and Why Not?

- **A second list for children** (`Menu(open, trigger = b)(items*)`): entries are required, and splitting the required arguments across two lists brings back the question of which list holds what.
- **Trailing varargs in the one list** (`Menu(open, trigger = b, MenuItem("Copy"), ...)`): shorter, but the entries are unnamed, and in `Popover(open, trigger = b, Form())` it is hard to see where the trigger ends and the content begins.
- **Builder-only** (`VirtualList(items).withKey(...).withRender(...)`): a missing required argument would surface at runtime, and `K` cannot be inferred until `withKey`.
- **A props case class per component**: an extra type for each component, and still positional unless the caller names the fields.
