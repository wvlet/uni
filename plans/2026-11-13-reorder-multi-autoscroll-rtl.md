# Reorder: move selected items together, autoscroll while dragging, RTL keys

## Goals

- Close the three limitations #901 left open in `Reorder` (see `adr/2026-11-13-uni-ui-reorder.md`, Consequences):
  - Moving five selected files means five separate drags today. Dragging or pressing Alt+Arrow on a selected item should move every selected item.
  - A long list can't be reordered by drag past its visible edge. Holding the pointer near the edge should scroll it.
  - In an RTL layout, Alt+ArrowLeft moves a tab toward the *end* of the list, which looks like moving right. The keys should follow what the user sees.
- Every move still goes through `ChangeGate`/`onChange` (cancelable), is announced, and keeps focus on the moved item(s).

## Background

- `Reorder[K](order: RxVar[Seq[K]])` moves one key at a time (`moveTo`, Alt+Arrow/Home/End, a pointer drag that commits once on release).
- `Selection[K](selected: RxVar[Set[K]])` and `VirtualList`/`DataTable.withMultiSelect(s: RxVar[Set[K]])` (#900) keep the multi-selection as an app-owned `RxVar[Set[K]]`. Neither list wires up `Reorder` today; apps put `reorder.item(k)` on their own rows.
- No uni-ui widget handles RTL yet: `RovingFocus`, `Tree`, `DataTable`, and `Panes` all read ArrowLeft/Right as LTR.
- The Dock reuses `Reorder.isMoveKey`/`keyTarget`/`landing` for its tab keys but runs its own pointer drag (cross-group moves, splits).

## Design

Lane: Standard — new user-facing behavior and a public API change in `Reorder`.

**Moving several items**

- `Reorder.withSelection(selected: RxVar[Set[K]])` links the same variable an app passes to `Selection` or `withMultiSelect`. With no selection linked, behaviour is unchanged.
- The group that moves is the selection when the pressed/focused item is in it, otherwise just that item. Moving an unselected item leaves the selection alone.
- The group keeps its relative order and lands as one contiguous block. Alt+ArrowUp/Down moves the block one step past its neighbour (a scattered selection first collapses around the focused item); Alt+Home/End moves it to an end.
- A drag of a selected item marks every group member `data-dragging`; the drop line works as today. Release commits one write to `order`, so `onChange` sees and can cancel the whole move once.
- Announce: "Moved 3 items to position 2 of 10" for a group; the single-item text stays as is.
- Focus stays on the item that had it (the one the key or drag started from); the selection is not changed by the move.
- Pure helper `Reorder.movedGroup(order, keys, to)` alongside `moved`, so widgets with their own drag (Dock later) can reuse it.

**Autoscroll during a drag**

- While a drag is active, if the pointer is within an edge zone (about 40 px, or 20% of a small container) of the nearest scrollable ancestor along the list's axis, scroll it on each animation frame, faster the closer to the edge.
- The drop index is recomputed after each scroll step, so windowed lists that render new rows as they scroll keep working.
- Autoscroll stops on release, Escape, pointer cancel, or when the container can't scroll further. Only the list's own scroll container scrolls, not the page.

**RTL keys**

- Horizontal `Reorder` and Dock tab moves read the item's computed `direction` at key time: in RTL, Alt+ArrowLeft moves toward the end and Alt+ArrowRight toward the start. Home/End stay start/end of the list.
- `Reorder.keyTarget` gains an `rtl: Boolean = false` parameter for the pure mapping; the drop index in a horizontal RTL drag uses the item rectangles the same way, with "before" meaning visually right.
- Vertical lists are unaffected. Plain arrow navigation in other widgets (`RovingFocus`, `Tree`, `Panes`) is out of scope here; noted as a follow-up in the ADR.

**Docs and tests**

- Update `docs/dom/behaviors.md`, the skill's `references/ui.md` (plugin version bump, `claude plugin validate`), `docs/guide/for-ai-agents.md` if the API list changes, and the ADR's Decision/Consequences.
- Tests cover group move math (contiguous and scattered), a cancelable group drag/keys with one `onChange`, the group announcement, focus retention, autoscroll stepping a scroll container, and RTL key mapping for Reorder and Dock tabs.

**Out of scope**: Tree multi-select, DataTable Shift+Home/End, a built-in `withReorder` on `VirtualList`/`DataTable`.

## Alternatives and Why Not?

- **Take a `Selection[K]` instead of the `RxVar[Set[K]]`**: `VirtualList`/`DataTable` own their `Selection` internally; the shared variable is what apps already hold.
- **Keep scattered items in place and only shift them**: no common UI does this; contiguous landing matches file managers and Trello-like lists.
- **Global `dir` attribute instead of computed `direction`**: misses `dir` set on a subtree or via CSS.
- **Mirror plain arrows in every widget now**: wider change touching RovingFocus/Tree/DataTable; better as its own PR.
