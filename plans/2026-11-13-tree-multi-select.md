# Multi-select for Tree, and Shift+Home/End in a multi-select DataTable

## Goals

- Let a `Tree` select several rows, as `VirtualList` and `DataTable` can since #900, so file-explorer style UIs (select several files, then delete or move them) work without app-side key handling.
- Settle the one keyboard gap #900 left in `DataTable`: what Shift+Home and Shift+End do when rows are multi-selectable.

## Background

- `Tree`, `VirtualList`, and `DataTable` all render through `ListCore`, which already implements multi-select via `ListSelection.Multi` + `Selection` (clicks with modifiers, Shift+navigation, Space, Ctrl/Cmd+A, Escape, `aria-multiselectable`). `Tree` just always passes `ListSelection.Single`.
- A tree's list order is the flattened *visible* rows (`TreeModel.flatten`), recomputed when `expanded` changes. Rows under a collapsed parent are not in that order at all.
- `DataTable` currently swallows Home/End (with or without Shift) as "first/last cell of the row"; only Ctrl+Shift+Home/End reach the list and extend the row selection.
- The APG grid pattern defines Home/End (cell in row) and Ctrl+Home/End (first/last row), but nothing for Shift+Home/End. The APG listbox and treeview patterns use Ctrl+Shift+Home/End for "select to first/last".

## Design

Lane: Standard — new user-facing behaviour (a new `Tree` option and a changed DataTable key), as requested.

**Tree multi-select**

- `Tree.withMultiSelect(RxVar[Set[K]])` / `.noMultiSelect()`, same shape as `VirtualList`; with it set, `withSelected` is ignored, and Enter / plain click still run `onSelect`.
- All modifier and key behaviour comes from `ListCore` unchanged, so the three components behave identically.
- Ranges (Shift+click, Shift+Arrow, Shift+Space) span the *visible* rows only. Children of a collapsed folder inside the range are not selected — what you see is what you select (Finder, VS Code).
- Ctrl/Cmd+A selects all visible rows, not hidden descendants.
- Left/Right keep their tree meaning (open, close, go to parent/child) and never extend the selection, even with Shift.
- **Collapsing a row deselects its selected descendants**, since they become hidden. This goes through `onChange` as a `selected` change with the collapse's cause, so the app can cancel it. Expanding never changes the selection.
- Only a user collapse prunes. Keys the app puts into the set for hidden rows stay; the tree does not continuously filter the app's state.

**DataTable Shift+Home/End**

- In a multi-select table, Shift+Home/End extend the row selection to the first/last row (as Ctrl+Shift+Home/End already do). Plain Home/End still go to the row's first/last cell.
- Reason: rows are the unit of selection, so a Shift range inside a row has no meaning; Explorer and Finder list views and the APG listbox treat Shift with Home/End as "select to the end". Single-select tables are unchanged.

**Docs and checks**

- Update `docs/dom/ui.md`, `docs/dom/behaviors.md`, the `uni` skill's `references/ui.md` (bump plugin version, validate), and add one line to the behaviors ADR noting `Tree` now also takes `withMultiSelect`.
- Tests: Tree multi-select keys and clicks, collapse pruning (and cancel via `onChange`), DataTable Shift+Home/End; run `checkPattern(…, AriaPattern.Tree)` and the grid pattern on the multi-select variants.

## Alternatives and Why Not?

- **Keep hidden descendants selected on collapse.** The user could then delete or move items they can no longer see; a bulk action on invisible rows is the worse surprise.
- **Collapse moves selected descendants' selection onto the parent.** Changes *what* is selected (a folder instead of files), which a subsequent delete would amplify.
- **Ranges include hidden descendants of collapsed rows.** Needs walking the full tree on each Shift press and selects things the user can't see.
- **Leave DataTable Shift+Home/End as cell moves (strict APG grid).** Shift is then silently ignored, and the more discoverable "select to top" stays behind Ctrl+Shift.
