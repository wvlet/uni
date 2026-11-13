# Reorder: pointer and keyboard reordering of list items

## Goals

- Give uni-ui a `Reorder` behavior so that any list (playlists, pinned items, task lists, settings order) can be reordered by drag and by keyboard, with screen-reader feedback, without each app re-deriving drop math, focus, and announcements.
- Close the last open item of step 3 in `plans/2026-11-13-ui-sdk-learnings.md` (`createReorder`), left out of #899.

## Background

- Behaviors are a pure model plus a binding modifier; writes to app state go through `ChangeGate` so `onChange` sees reason and source and can cancel (`adr/2026-11-13-uni-ui-behaviors.md`).
- The Dock already drags tabs, but its drag is two-dimensional: drop into another group's strip, onto a group's center, or onto an edge to split. Its tab-strip drop math (insert before the first tab whose middle is past the pointer) and `DockModel.move`'s "removing first shifts later items by one" are exactly what a one-list reorder needs.
- uni-dom's `DragDrop` uses native HTML5 drag-and-drop, which has no touch support and a ghost image we can't style; `PointerDrag` is the pointer-events primitive the Dock and splitters use.
- uni-harness's conversation list is grouped by date (Today / Earlier); a manual order would conflict with that grouping and would need a new persisted field on the server.

## Design

Lane: Standard — a new public behavior with a new keyboard contract.

User flow:

- **Pointer**: press an item (or its handle, when the app marks one) and move past a small threshold. The item gets `data-dragging`, and a drop indicator shows where it would land. Release commits; Escape or `pointercancel` puts it back with no change. A plain click still clicks.
- **Keyboard**: with an item focused, Alt+ArrowUp/Down moves it one step (Alt+ArrowLeft/Right for a horizontal list), and Alt+Home/End moves it to the start or end. Focus stays on the moved item.
- **Screen reader**: each committed move is announced through `Announce`, e.g. "Moved Inbox to position 3 of 5". The item carries an `aria-description` hint naming the keys.
- **Recovery**: the app's `onChange` can cancel any move (reason `Drag` or `NavigationKey`, source `Pointer` or `Keyboard`); a cancelled move leaves the list and focus unchanged.

Decisions:

- `Reorder[K](order: RxVar[Seq[K]])` owns no state of its own beyond the drag in progress; the app's `order` variable is the truth, written only through `ChangeGate`.
- One keyboard contract: Alt+Arrow, with no grab mode. Space and Enter stay free for `Selection` and activation, and letters stay free for `Typeahead`, so `Reorder` composes with both on the same list. `preventDefault` stops Alt+Left/Right from triggering browser Back.
- Pointer drag is built on `PointerDrag`, not native `DragDrop`, so it works with touch and pen and the visuals are plain CSS. The binding exposes the drop target as data attributes; uni-ui supplies default indicator styles that apps can override.
- Items stay in place during a drag and only the indicator moves; the order changes once, on release. This keeps `onChange` to one change per gesture and avoids layout thrash in long or windowed lists.
- The pure model holds the index math (`move(order, from, to)`, drop index from item midpoints along an axis) and is tested without a DOM. The binding tests cover pointer drag, Escape cancel, Alt+Arrow, focus retention, and cancellation through `onChange`.
- Single-item moves only. Dragging a multi-item `Selection` is a follow-up.
- **Dock reuse**: the Dock keeps its own 2-D drag (cross-group and split are outside a one-list reorder), but reuses `Reorder`'s model for the in-strip index math and adopts the same keyboard contract: Alt+ArrowLeft/Right on a tab moves it within its group, with the same announcement. The context menu's Move left / Move right stay.
- **uni-harness**: no change. The date-grouped conversation list has no manual order, and adding one means a server-side field; the docs example and tests cover usage instead.
- Docs: a catalog row and section in `docs/dom/behaviors.md`, the skill's `references/ui.md` (plugin version bump), and `docs/guide/for-ai-agents.md`.

## Alternatives and Why Not?

- **Grab-and-drop mode (Space to grab, arrows to move, Space to drop)**: the WAI-ARIA-era pattern, but Space collides with `Selection` and activation, and it adds a modal state users can get stuck in. Alt+Arrow is one chord, as in VS Code's "move line".
- **Live shuffle while dragging**: feels smoother for short lists, but fires layout on every move, fights `Windowing`, and makes cancel harder. Could be an opt-in later.
- **Native HTML5 `DragDrop`**: no touch, unstyled ghost image, and drop events fire on the target rather than giving us a continuous position.
- **Rewrite the Dock's drag on top of `Reorder`**: the Dock's cross-group and split targets don't fit a one-list model; forcing them in would bloat `Reorder`.
