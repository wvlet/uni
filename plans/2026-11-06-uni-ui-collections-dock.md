# uni-ui phase 3: collections and Dock

## Goals

- Give uni-ui apps the list-heavy components desktop tools need: a long list that stays fast, a file-style tree, a ⌘K command palette, and a sortable table.
- Give apps a Dock: panels in tab groups and splits that users rearrange, with a layout the app saves and restores.
- Move uni-harness onto them, so each one has a real in-repo consumer.

## Background

- `each(items)(key)(render)` already keeps row DOM across updates, but it renders every item; a 10k-row log or table needs windowing on top.
- Phase 2 (#869) gives the pieces a palette needs: `Dialog` (portal, focus trap, focus return) and the `Dismiss` stack, so Escape and outside presses already behave.
- The harness dock (`examples/uni-harness/.../Dock.scala`) is one `FoldablePane` with a `TabList` (Details, Activity, Terminal); its widths and active tab persist through `Storage.local`. Its ⌘K focuses the chat search box.
- `StorageCodec` only covers primitives and enums, so a nested layout needs another encoding; `Weaver` (uni JSON) already derives codecs for case classes and enums.

## Design

Lane: **Standard**, because it adds public API (components, data types, CSS classes) and a persisted layout format.

**Split (approved): two PRs.** PR A = collections (VirtualList, Tree, CommandPalette, DataTable), PR B = Dock. They share little code, and Dock's layout format deserves its own review.

Shared decisions:
- Same layering as phases 1–2: keyboard and ARIA logic in `wvlet.uni.ui.base`, styled components in `wvlet.uni.ui`, styles in `uni-ui.css` under `@layer uni` with `uni-<component>` classes.
- State comes in as `RxVar`s owned by the app (selection, expanded keys, sort, layout), so apps can persist or drive it, like `TabList` today.
- Every row-based component renders through `each` with an app-supplied key.

**VirtualList**
- `VirtualList(items, rowHeight)(key)(render)`: renders only the rows in view plus a small overscan, inside one scroll container sized to the full height.
- Fixed row height only in v1; variable heights would need measuring and are left for later.
- Optional active-row and selection state with arrow, Home/End, PageUp/PageDown, and scroll-into-view; `role="listbox"` with `aria-activedescendant`, so focus stays on the container.

**Tree**
- `Tree(roots, children, key)(render)` over app data, with `expanded: RxVar[Set[K]]` and `selected: RxVar[Option[K]]`.
- WAI-ARIA treeview keyboard: Up/Down, Right expands or enters, Left collapses or goes to parent, Home/End, Enter activates, typeahead on the first letter.
- Children load synchronously in v1. The visible rows are flattened into one list, so a large tree can render through VirtualList.

**CommandPalette**
- Built on `Dialog`: a search input and a result list, with `role="combobox"` on the input and `aria-activedescendant` on the list. ↑/↓ move, Enter runs, Escape closes, and focus returns to the opener.
- Commands are plain values: `Command(id, label).withGroup(..).withShortcut(..).onRun(..)`; the app passes an `Rx[Seq[Command]]` so the list can change (for example, open conversations).
- Matching is a built-in subsequence (fuzzy) score with matched letters highlighted; apps can swap in their own filter.
- uni-ui doesn't bind ⌘K itself; apps bind it with `Keyboard.bindAll`.

**DataTable**
- `DataTable(rows, key)(columns*)`, where `Column(id, header)(cell)` adds `.sortable(ordering)` and `.withWidth(..)`.
- Sort state is `RxVar[Option[(columnId, Asc|Desc)]]`; a header click cycles asc → desc → none. Row selection works like VirtualList.
- Body rows virtualize through VirtualList (fixed row height), with a sticky header; `role="grid"` with row/column ARIA counts.
- v1 leaves out column resize, reorder, and inline editing.

**Dock (PR B)**
- A layout is plain data: a tree of `Split(direction, children, sizes)` and `Group(panelIds, active)`. It `derives Weaver`, so apps save it as JSON wherever they like; uni-ui ships a `Storage.local` helper for the common case.
- Panels are registered by id with a title and a render function, and the layout refers only to ids. A saved layout that names an unknown panel drops it, and a panel missing from the layout can be reopened, so old saves never break the app.
- Users drag a tab to another group, or to a group's edge to split it; split borders resize with the existing `Splitter`. Groups that become empty disappear.
- Keyboard: tabs reuse `TabList`'s roving focus, and each splitter is a separator with arrow-key resizing.

**Harness**
- PR A: one unified palette replaces the sidebar search: ⌘K (or clicking the search field) opens a CommandPalette that matches commands (new chat, toggle sidebar/dock, theme) and conversations together. The Activity tab moves onto VirtualList.
- PR B: the right dock becomes a `Dock` with Details/Activity/Terminal as panels, persisted in localStorage, so a user can split Terminal under Details.

**Tests and docs**
- `mount`/`fireEvent` tests cover keyboard, ARIA, windowing math, sort, fuzzy matching, and layout operations (move, split, drop unknown ids). Screenshot goldens cover each new component in light and dark.
- Update `docs/dom/ui.md`, `docs/guide/for-ai-agents.md`, and the `uni` plugin skill (with a version bump), and add an ADR for each PR's main decisions.

## Alternatives and Why Not?

- **Variable-height virtualization now**: needs a measure-and-correct loop and scroll anchoring; fixed height covers lists, logs, and tables first.
- **Native `<table>` without virtualization**: simpler, but thousands of rows make it slow; the grid ARIA roles keep it accessible anyway.
- **Layout as a mutable widget tree (like GoldenLayout)**: harder to save, diff, and test than an immutable data value in an `RxVar`.
- **Persisting Dock layout through `StorageCodec`**: it only handles flat values; extending it to case classes would duplicate `Weaver`.
