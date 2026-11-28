# uni-ui DataTable preview rows and auto height

## Context

Result grids (the Wvlet playground notebook) show the first rows of a result with a "+N more rows" button, and size each table in pixels from its row count, so a short result has no empty strip below its last row. Apps did this by hand: they sliced the rows, drew the button, and computed `rows × rowHeight + headerHeight + 2`. A table without a height grows to fit and renders every row, so apps also had to cap it. Follow-up (b) of [read-only result grids](2026-11-27-uni-ui-read-only-result-grids.md).

## Decision

- `DataTable.withPreviewRows(n)` sorts every row, then shows the first n, so a header click brings the top rows of the whole result into the preview. When rows exceed n, a footer row reads "+1,234 more rows" and toggles to all rows ("Show fewer rows" folds back). Whether it is expanded is the table's own state, tied to the rows it expanded, compared by value: an `rx.map(...)` gives each subscriber (the footer, the list) its own copy, so identity would never match. A different result starts folded without a side effect on `rows`; an identical one stays expanded.
- The footer is the grid's last row (`role="row"` in its own `rowgroup`, one gridcell with `aria-colspan`), reached with Down from the last shown row, like the header with Up. Its button has `tabindex="-1"`: the grid stays one tab stop, as with sort buttons and cell widgets. `aria-rowcount` counts what the keys reach (header, shown rows, footer); the footer's text tells how many rows the preview leaves out. Agents get `showAllRows` / `showFewerRows` actions.
- The footer lives inside the scroll container, `position: sticky` at the bottom (and left, so it stays in view when a wide table scrolls sideways). Apps keep styling the table through its root, which stays the scroller. ListCore subtracts the footer's height from the rows' space, so windowing and scroll-into-view never leave the active row under it.
- `DataTable.withAutoHeight(maxRows)` writes the box's height to `--uni-list-auto-height`, which `.uni-list--auto-height { height: ... }` reads. The height is the header (measured, fractional, since a subtitle makes it taller), the rows up to `maxRows`, the footer, and `offsetHeight - clientHeight` (borders and a classic horizontal scrollbar strip, which a headless browser hides). An app `height` in its inline style still wins; a `max-height` limits it.
- The height is written in the next animation frame, scheduled when the rows change and when the box is measured. Writing it inside the resize observer's callback is a resize loop ("ResizeObserver loop completed with undelivered notifications"), and observing the header, spacer, and footer as well triggered the same loop.
- This amends the [collections ADR](2026-11-06-uni-ui-collections.md): a table with `withAutoHeight` is bounded by its row count instead of a CSS height.

## Alternatives

- Footer as plain text, with the app adding a button: every result grid wants the same toggle.
- An app-held `withShowAllRows(RxVar[Boolean])`: nothing needs the app to read or keep the state, and a new result should start folded.
- Auto height as a pixel `max-height` option: apps would still compute the header and footer heights; a row count is what they reason in.
- A wrapper element around the table for the footer: changes the element apps style today.
- An inline `height`: would fight the app's own `height` in the same style attribute.

## Consequences

- A header that changes height without the rows changing (a web font loading later) keeps its old auto height until the next resize or rows change.
- After a uni release, Wvlet drops its row slicing, its "+N more rows" button, and `Cell.tableHeight`.
