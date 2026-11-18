# Finish the DataTable grid: cell selection, header navigation, and cell widgets

## Goals

- Close the three known limitations #891 left in `DataTable`'s grid keyboard model, so it behaves the way `role="grid"` promises (WAI-ARIA grid pattern):
  - Apps that pick a value, not a row (a pivot, a calendar, a copy-a-value table) can let the user **select a cell**.
  - The **header row is part of cell navigation**, so sorting works from the keyboard without leaving the grid, and the table is one tab stop instead of one plus a tab stop per sortable column.
  - **Enter and F2 move focus into a widget inside a cell** (a button, a link, a checkbox), and **Escape** brings it back to the grid.

## Background

- #891 made `aria-activedescendant` name the active cell; focus stays on the scroll container because a focused row can be windowed out and unmounted (collections ADR). That rules out roving tabindex for all three changes.
- The header row is rendered once, outside the windowed rows, and always mounted, so its cells can be active descendants too.
- `ListCore` ignores keys whose target is inside a row, so a cell widget already keeps its own keys once it has focus; only getting in and out is missing.
- Selection, sort, and order already go through `ChangeGate` (`onChange` keys `selected`, `sort`, `order`; `ChangeCause` gives reason and `ChangeSource`), and `UiAgent` sees rows through `withRowLabel`.
- Left/Right already mirror in RTL through `TextDirection`; the header reuses the same column moves.

## Design

Lane: Standard — new keyboard behaviour and a new public selection setting on a public component.

- **User flow — header.** From the first row, Up moves to the header row and keeps the column; Down goes back to the first row. Left/Right/Home/End move along the header. Enter or Space on a sortable header cycles its sort, as a click does. Ctrl (⌘) + Home goes to the header's first cell, as the grid pattern's "first cell of the first row"; Ctrl (⌘) + End to the last cell of the last row. An empty table starts on the header.
- **Header decisions.**
  - The active header cell gets an id, `data-active`, and is named by `aria-activedescendant`; the screen reader reads its `aria-sort`.
  - Sort buttons get `tabindex="-1"`: the table becomes one tab stop. A click still sorts, and also makes that header cell active and returns focus to the grid.
  - Header keys write the sort through `ChangeGate` under key `sort`, with `ChangeReason.Press` and keyboard source, like a click; `onChange` can cancel.
  - With the header active, Enter/Space/Shift-range keys never touch the row selection.
- **User flow — cell widgets.** On a cell that holds a focusable widget, Enter or F2 focuses the first one; the widget's own keys then work as usual. Escape (when the widget didn't handle it, e.g. to close its own popup) or F2 returns focus to the grid on the same cell. On a cell without a widget, Enter selects as today and F2 does nothing.
- **Cell widget decisions.**
  - Widgets in body cells leave the tab order (`tabindex="-1"`, applied whenever a cell's content renders), so Tab never lands inside the grid and stops on rows that happen to be rendered; Enter/F2 and a click still reach them.
  - While focus is inside a cell, Tab and Shift+Tab cycle through that cell's widgets, so a second widget in the same cell is reachable, and focus never leaves the grid by accident.
  - If the row holding the focused widget scrolls out and unmounts, focus returns to the grid instead of `<body>`.
- **User flow — cell selection.** An app opts in with `withSelectedCell(cellVar)`, where the variable holds `Option[CellKey[K]]` (row key plus column id). Enter, Space, or a click then selects the active cell instead of the row; the selected cell has `aria-selected="true"` and a selected style, and `onSelect` still runs with the cell's row.
- **Cell selection decisions.**
  - One cell at a time. Ranges (Shift+Arrow, rectangles) are a later step; they combine with multi-select in ways worth their own design.
  - Cell selection replaces row selection while set: rows drop `aria-selected`, and `withSelected`/`withMultiSelect` variables are left alone, as `withMultiSelect` already leaves `withSelected` alone. `noSelectedCell()` goes back to rows.
  - While set, the row multi-select keys (Shift+Arrow, Ctrl+A, Space toggle, Escape) and `aria-multiselectable` are off.
  - Focusing the grid starts on the selected cell, else as today.
  - Writes go through `ChangeGate` under a new `onChange` key `selectedCell` (keys follow the variable, as `selected`, `sort`, `order`), with the same `ChangeCause` as row selection.
  - `UiAgent`: the snapshot value names the cell as "<row label> · <column header>", using `withRowLabel`; a `selectCell` action takes that text, honours `withAgentConfirm` for the row, and the change feed and `UiHistory` undo see the cell variable. While cell selection is on, the row `select` action is not offered and snapshot items mark the row holding the selected cell.
- `ListCore` gets the hooks this needs, internal only: the table sees Enter and Space before the list's row selection, and can name a header cell (or no row) as the active descendant. VirtualList and Tree are unchanged.
- Named-argument style from #913 for any new constructor parameters; `nextColumn` stays the one RTL-aware column move for body and header.
- Docs: `docs/dom/collections.md` (and `ui.md` where it lists keys), the collections ADR (Decision on Ctrl+Home, and the consequence line), the DataTable scaladoc, and `plugins/uni/skills/uni/references/ui.md` with a plugin version bump.
- Tests cover header moves and keyboard sort (including a cancelled `onChange`), Enter/F2/Escape focus round-trips, `checkPattern` with sortable columns in LTR and RTL (one tab stop), cell selection with ARIA and change keys, and the agent `selectCell` action.

## Alternatives and Why Not?

- **Header reached only by Ctrl+Home, not Up.** Hides the header from users who arrow around; the grid pattern treats it as an ordinary first row.
- **Ctrl+Home to the first data row.** Familiar from spreadsheets, but the grid pattern names the first row, which is the header here; Up-then-Down costs one key.
- **Only F2 enters a widget, Enter always selects.** Keeps Enter's meaning, but the grid pattern uses Enter for both and users of other grids expect it.
- **Cell selection as a mode enum (`withSelectionUnit(Cell)`) on the existing variables.** The row variable's type `Option[K]` can't hold a column, so a cell needs its own variable anyway.
- **Leave cell widgets in the tab order.** Smaller change, but the tab sequence then depends on which rows are windowed in, and the grid stops being one tab stop.
