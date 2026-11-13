# Built-in reordering for VirtualList and DataTable

## Goals

- Let an app make a `VirtualList` or `DataTable` reorderable with one builder call, instead of wiring `Reorder` into rows by hand.
- Keep everything `Reorder` already does: Alt+Arrow/Home/End keys, pointer drag with edge autoscroll and Escape, moving a multi-selection as one block, announcements, and `onChange` that can cancel.
- Make it correct under windowing: rows outside the viewport aren't rendered, and the list keeps focus on its container rather than on rows.

## Background

- `Reorder[K]` (#901, #904) writes an `RxVar[Seq[K]]` of keys. The lists show an `Rx[Seq[A]]` of items keyed by `A => K`, so an app today needs a second key-order variable kept in step by hand. `Reorder` is unreleased (last tag v2026.1.41, Nov 6), so it can still change.
- `Reorder.item(k)` handles Alt+Arrow on the focused item. `ListCore` keeps focus on the scroll container and names the active row with `aria-activedescendant`, so per-row key handlers never fire in these lists.
- `PointerDrag` tracks moves on `window`, so a drag survives its row unmounting as the list scrolls. The drop point is computed only among rendered rows, and the viewport plus overscan is always rendered, so the row under the pointer always counts.
- `DataTable` can sort by a column; a manual order means nothing while a sort is applied.
- uni-harness's lists (RPC call log, activity log) are append-only logs, so it has nothing to make reorderable.

## Design

Lane: Standard — new public API on two components and a change to `Reorder`'s constructor surface.

User flow: a todo app shows `tasks: RxVar[Seq[Task]]` in a `VirtualList` with `.withReorder(tasks)`. The user drags a row (the list autoscrolls near its edges, rows window in and out) or presses Alt+ArrowDown on the active row; the row moves, the change is announced, and the list's `onChange` sees `key = "order"` and can `cancel()`. Escape mid-drag backs out.

- **API**: `VirtualList(...).withReorder(order: RxVar[Seq[A]])` and the same on `DataTable`, plus `noReorder()`. The list is expected to show `order` itself; the variable is passed explicitly because the constructor takes a read-only `Rx`.
- **Reorder works on items, not just keys**: add `Reorder.by(items: RxVar[Seq[A]])(key: A => K)`, so `Reorder` reads keys through `key` and writes the reordered items back. Apps with a `Seq[Task]` variable can use it directly too; `Reorder(keys)` stays as is.
- **One onChange**: the move goes through the list's own `onChange` (keys `selected`, `sort`, and now `order`), with reason `Drag`/`Pointer` or `NavigationKey`/`Keyboard`; the value is the new `Seq[A]`.
- **Multi-select**: with `withMultiSelect(s)`, the list passes `s` to `Reorder.withSelection`, so a selected row moves the whole selection as one block (pointer and keys).
- **Keyboard via the container**: `ListCore` handles Alt+ArrowUp/Down/Home/End on the active row by key and index (no DOM needed), keeps the moved row active, and scrolls it into view. Plain arrows, Shift+arrows, Space, and typeahead are unchanged.
- **Pointer**: each rendered row gets `reorder.item(k)` (drag start, `data-dragging`, `data-drop` lines from the existing CSS); `Reorder.handle` inside a row restricts drags to a grip. Autoscroll scrolls the list's container, whose scroll event re-windows the rows, and the drop line is recomputed on each autoscroll frame.
- **DataTable + sort**: reordering is off while a sort is applied (no drag start, Alt+keys do nothing); clearing the sort (third header click) turns it back on.
- **Agents** (added at design approval): a reorderable list or table offers a `move` action, `"<row label> to <position>"` (1-based, or `first`/`last`), through the same `onChange` with the `Agent` source.
- **Docs**: `docs/dom/ui.md`, `for-ai-agents.md`, the plugin's `references/ui.md` (snippets compile in `docSnippetsJS`), plugin version bump, and an update to the Reorder ADR's Consequences rather than a new ADR.
- Tests cover Alt+keys on the active row (single and multi-select, scrolled far down), a pointer drag that autoscrolls across windowed rows, `onChange` cancel, DataTable disabled while sorted, and the agent `move` action.

## Alternatives and Why Not?

- **`.withReorder()` with no argument, matching on `items` being an `RxVar` at runtime**: fails silently when the app passes a derived `Rx`.
- **A hidden key-order variable synced both ways with the items**: two sources of truth, and `onChange` would report keys instead of the app's items.
- **A move clears the DataTable sort and starts from the sorted order**: surprising, and it quietly rewrites the app's data into the sorted order.
- **Per-row keydown with roving focus inside the lists**: would undo `ListCore`'s single-tab-stop `aria-activedescendant` model.
