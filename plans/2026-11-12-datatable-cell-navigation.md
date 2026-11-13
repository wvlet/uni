# Add cell navigation to uni-ui DataTable

## Goals

- Let keyboard and screen-reader users move between the cells of a `DataTable`, not only between rows. `role="grid"` tells assistive tech that cells are navigable, and today they aren't.
- Keep the table one tab stop, and keep row selection (Enter, click, `withSelected`, `onSelect`) as it is.

## Background

- The collections ADR (2026-11-06) chose focus on the scroll container plus `aria-activedescendant`, because a focused row can be windowed out and unmounted. The same reason rules out roving tabindex on cells.
- `ListCore` already tracks the active row and lets a component handle keys first (`extraKey`); Tree uses this for Left and Right.
- The WAI-ARIA grid pattern: Left/Right move by cell, Home/End go to the first/last cell in the row, Ctrl+Home/Ctrl+End go to the first/last row.

## Design

Lane: Standard — new keyboard behaviour on a public component.

- **User flow.** Tab into the table; the selected row (or the first) is active, at its first cell. Up/Down keep the column, Left/Right move along the row and stop at its ends. The active cell gets the focus ring, and the screen reader reads that cell. Enter selects the row as today.
- **On by default.** Cell navigation is DataTable's only keyboard model; Left and Right do nothing today, so the only behaviour change is Home/End.
- **Home/End follow the grid pattern.** Home/End go to the first/last cell of the row; Ctrl (or ⌘) + Home/End go to the first/last row. PageUp/PageDown still move by a page.
- **`aria-activedescendant` names the active cell**, which gets an id and `data-active`. The row keeps `data-active` too, so the row highlight still shows where you are.
- **A click on a cell makes it active**, and still selects its row.
- **Column state is internal.** No new public setting; the table remembers the active column while it is mounted.
- `ListCore` gets two small internal hooks: the key handler sees modifier keys, and the component can name a different active descendant. VirtualList and Tree are unchanged.
- Docs (`docs/dom/ui.md`), the ADR consequence ("no cell navigation"), and tests (arrow/Home/End moves, ARIA ids, click) are updated.
- Out of scope: header cells in navigation (sort buttons stay their own tab stops), moving focus into a widget inside a cell (Enter/F2), and cell selection.

## Alternatives and Why Not?

- **Opt-in `withCellNavigation()`.** Keeps Home/End as row moves, but leaves the default grid not working the way its role says it should, with one more setting to document.
- **Roving tabindex on cells.** Breaks when the row is windowed out, as the ADR records.
- **Keep Home/End as first/last row.** Familiar from lists, but goes against the grid pattern that screen-reader users expect inside `role="grid"`.
