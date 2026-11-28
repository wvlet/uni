# DataTable preview rows and auto height

## Goals

- Let a result grid show its first N rows with a "+1,234 more rows" footer, without the app slicing rows or drawing the footer.
- Let a table's height follow its row count (header + rows + footer) up to a cap, so a short result ends at its last row and a long one scrolls. Apps no longer compute a pixel height.
- After a release, the Wvlet playground drops its row slicing, its "+N more rows" button, and `Cell.tableHeight`.

## Background

- Follow-up (b) of #1220 (read-only result grids; ADR `adr/2026-11-27-uni-ui-read-only-result-grids.md`). Its feedback settled that result grids are compact, with no empty strip below the last row.
- `ListCore` windows rows by the container's `clientHeight` minus the header (the spacer's `offsetTop`), and re-measures on resize. A table with no height grows to all its rows and renders every one; apps set `height` (or `max-height`, as the gallery's results story does) to get scrolling.
- Apps style the table through its root, the scroll container (`DataTable(...)(styleAttr += "height: 200px")`), so that root must stay the scroller.
- The playground shows 10 rows (sorting still orders all rows), and its footer is a button: "+N more rows" expands to all rows, scrolling past 30, and "Show fewer rows" folds back. It sizes the box as `rows × rowHeight + headerHeight + 2` px.
- On systems with classic scrollbars, a table wider than its box has a horizontal scrollbar strip inside it. A height computed from rows alone puts the last row under the strip and adds a vertical scrollbar; headless Chromium hides this (overlay scrollbars).

## Design

Lane: Standard — new public options and a new footer element on an existing component.

User flow: an app shows a query result. It adds `.withPreviewRows(10).withAutoHeight(maxRows = 30)` and sets no height. A 3-row result is a 3-row box. A 5,000-row result shows 10 rows and "+4,990 more rows"; a click shows all rows in a box 30 rows tall that scrolls; "Show fewer rows" folds back.

- **Preview rows**: `DataTable.withPreviewRows(n)` / `noPreviewRows()` (the default shows all rows).
  - The table sorts all rows, then shows the first n, so a header click brings the top rows of the whole result into the preview.
  - When rows exceed n, a footer below the last row reads "+1,234 more rows" (thousands separators) and is a button that shows all rows; it then reads "Show fewer rows".
  - Expansion is the table's own state, reset to the preview when `rows` changes, so a new result starts folded. No app variable for it, as nothing in the request needs the app to read or set it.
  - Agents see the shown rows as items, and the footer's button as an action (`more rows` / `fewer rows`).
  - The footer is the grid's last row, one cell spanning all columns: Down from the last shown row moves onto it (as Up from the first row moves onto the header), and Enter, Space, or a click toggles. It is not a separate tab stop, as cell widgets aren't.
  - `aria-rowcount` counts what the arrow keys reach (header, shown rows, footer); the footer's text tells how many rows are left out.
- **Auto height**: `DataTable.withAutoHeight(maxRows)` / `noAutoHeight()` (the default: the app's CSS sets the height, as today).
  - The box is as tall as its header, its rows up to maxRows, its footer, and its horizontal scrollbar strip if it has one; past maxRows it scrolls with the header and footer kept in view.
  - It measures the header (which a subtitle makes taller), the footer, and the scrollbar strip, as ListCore already measures the header, so no app pixel math. Windowing and scroll-into-view subtract the footer too, so the active row never hides under it.
  - The table writes the computed height to a CSS variable that the stylesheet's `height` reads, so an app `height` in its own style still wins and `max-height` limits it further.
- The two options are independent: auto height alone suits any growing table, and a preview alone keeps the app's height.
- **Gallery**: the data-table/results story uses both options and drops its `max-height`; it gets a result long enough to show the footer.
- **Docs**: `docs/dom/collections.md`, `for-ai-agents.md` (it lists DataTable options), and the uni skill (`plugins/uni/skills/uni`, version bump), with the snippets compiled by `docSnippets`.
- **ADR**: a new ADR; it amends the collections ADR's rule that a collection needs a bounded height, since auto height bounds it by rows.
- Tests cover the slicing after sort, the footer text and toggle, the reset on new rows, and the computed height for short, long, and subtitled tables. A headed Chromium check covers the classic scrollbar strip.

## Alternatives and Why Not?

- **Footer as plain text, the app adds a button**: Wvlet would keep its expand code; every result grid wants the same toggle.
- **Auto height as a pixel `max-height` option**: apps would still compute header and footer heights; a row count is what they reason in.
- **A wrapper element around the table for the footer**: changes the element apps style today.
- **Preview implies auto height**: couples two options; a preview inside a fixed-height panel is valid.
