# uni-ui Reorder: Alt+Arrow keys, a pointer drag that commits once, and one contract shared with the Dock

## Context

Step 3 of `plans/2026-11-13-ui-sdk-learnings.md` lists `Reorder` (pointer and keyboard reordering of list items). The Dock already dragged tabs, but only by pointer and through its own two-dimensional drop logic (move into another group, or split a group). Lists in apps had no shared way to reorder, so each one would have re-derived drop math, focus handling, and screen-reader feedback.

## Decision

- `Reorder[K](order: RxVar[Seq[K]])` writes the app's order through `ChangeGate` (`Drag`/`Pointer` or `NavigationKey`/`Keyboard`), so `onChange` can cancel a move. Every committed move is announced with `Announce.polite`, and focus returns to the moved item when it had focus before.
- Keyboard: Alt+Arrow along the list's axis moves one step, and Alt+Home/End moves to an end. There is no grab mode, so Space, Enter, and letters stay with `Selection`, activation, and `Typeahead` on the same items. The handler calls `preventDefault` for these keys even when nothing moves, because Alt+ArrowLeft is browser Back on Windows and Linux. Keys typed in a text field inside an item are left to the field.
- Pointer: built on uni-dom `PointerDrag` (touch and pen work), not native HTML5 drag and drop. Items stay in place, and data attributes (`data-dragging`, `data-drop`) mark the drag; the order changes once, on release. Escape cancels. Only one drag runs at a time. `PointerDrag` gained an optional `canStart` predicate so `Reorder.handle` can limit drags to a grip.
- The drop index comes from the midpoints of the rendered items, so a windowed list can drop among its visible rows.
- The Dock keeps its own drag (moves across groups and splits fall outside a one-list model), but uses the same keys on tabs (Alt+ArrowLeft/Right/Home/End) and the same index math (`Reorder.landing` inside `DockModel.move`).

- Follow-up (same day): `withSelection(selected)` moves the selected items as one block. The block keeps the items' current order and lands contiguous; Alt+Arrow positions it among the other items, counting from where the pressed item is, so a scattered selection gathers there on the first step. A drag or key press commits one `ChangeGate` write. The selection is passed as the app's `RxVar[Set[K]]` rather than a `Selection`, because `VirtualList` and `DataTable` keep their `Selection` inside and share only that variable.
- Follow-up: a drag near the edge of the nearest scrollable ancestor (below the page) scrolls it once per animation frame, faster closer to the edge, and recomputes the drop index after each step, so windowed lists that render new rows keep working. The page itself never scrolls, so a drag can't run away with the document.
- Follow-up: in a horizontal list whose computed `direction` is `rtl`, Alt+ArrowLeft moves toward the end and Alt+ArrowRight toward the start, in `Reorder` and on Dock tabs. Reading the computed style finds `dir` on any ancestor and CSS `direction`. Pointer drops negate the x positions in RTL, so "before" is the right edge, and uni-ui.css draws the line there with `:dir(rtl)`.

## Alternatives

- **Grab mode (Space to grab, arrows to move, Space to drop)**: Space collides with `Selection` and activation, and it adds a modal state a user can get stuck in.
- **Live shuffle while dragging**: it causes layout on every pointer move, conflicts with `Windowing`, and fires `onChange` many times per gesture.
- **Native `DragDrop`**: no touch support, a ghost image that can't be styled, and no continuous pointer position.
- **Rebuild the Dock's drag on `Reorder`**: its group and split targets would make `Reorder` grow beyond a single list.

## Consequences

- Selected items move together only through `withSelection`; `Tree` has no multi-select yet, and neither `VirtualList` nor `DataTable` wires `Reorder` in, so apps add `reorder.item(k)` to their rows.
- Only the Alt+Arrow move keys are mirrored in RTL. Plain arrow navigation in `RovingFocus`, `Tree`, `Panes`, and `DataTable` still reads Left/Right as left-to-right, which is a separate follow-up.
