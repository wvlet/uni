# uni-ui Reorder: Alt+Arrow keys, a pointer drag that commits once, and one contract shared with the Dock

## Context

Step 3 of `plans/2026-11-13-ui-sdk-learnings.md` lists `Reorder` (pointer and keyboard reordering of list items). The Dock already dragged tabs, but only by pointer and through its own two-dimensional drop logic (move into another group, or split a group). Lists in apps had no shared way to reorder, so each one would have re-derived drop math, focus handling, and screen-reader feedback.

## Decision

- `Reorder[K](order: RxVar[Seq[K]])` writes the app's order through `ChangeGate` (`Drag`/`Pointer` or `NavigationKey`/`Keyboard`), so `onChange` can cancel a move. Every committed move is announced with `Announce.polite`, and focus returns to the moved item when it had focus before.
- Keyboard: Alt+Arrow along the list's axis moves one step, and Alt+Home/End moves to an end. There is no grab mode, so Space, Enter, and letters stay with `Selection`, activation, and `Typeahead` on the same items. The handler calls `preventDefault` for these keys even when nothing moves, because Alt+ArrowLeft is browser Back on Windows and Linux. Keys typed in a text field inside an item are left to the field.
- Pointer: built on uni-dom `PointerDrag` (touch and pen work), not native HTML5 drag and drop. Items stay in place, and data attributes (`data-dragging`, `data-drop`) mark the drag; the order changes once, on release. Escape cancels. Only one drag runs at a time. `PointerDrag` gained an optional `canStart` predicate so `Reorder.handle` can limit drags to a grip.
- The drop index comes from the midpoints of the rendered items, so a windowed list can drop among its visible rows.
- The Dock keeps its own drag (moves across groups and splits fall outside a one-list model), but uses the same keys on tabs (Alt+ArrowLeft/Right/Home/End) and the same index math (`Reorder.landing` inside `DockModel.move`).

## Alternatives

- **Grab mode (Space to grab, arrows to move, Space to drop)**: Space collides with `Selection` and activation, and it adds a modal state a user can get stuck in.
- **Live shuffle while dragging**: it causes layout on every pointer move, conflicts with `Windowing`, and fires `onChange` many times per gesture.
- **Native `DragDrop`**: no touch support, a ghost image that can't be styled, and no continuous pointer position.
- **Rebuild the Dock's drag on `Reorder`**: its group and split targets would make `Reorder` grow beyond a single list.

## Consequences

- One item moves at a time. Dragging a multi-item `Selection` is a follow-up.
- No auto-scroll during a drag, and RTL layouts use the same Left/Right mapping as LTR.
