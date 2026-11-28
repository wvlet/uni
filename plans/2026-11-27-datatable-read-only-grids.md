# DataTable options for read-only result grids

## Goals

- Let apps show query results (read-only, often wider than their box) in `DataTable` without CSS overrides.
- Three changes: sideways scrolls chain to an outer scroller, a muted second line under a header (a column's type), and a table whose rows a click does not select.
- After a release, the Wvlet playground drops its `overscroll-behavior: auto` override, its selection-hiding rules, and the `"name\ntype"` header trick.

## Background

- `.uni-list` (VirtualList, Tree, DataTable) sets `overscroll-behavior: contain` so a list's vertical scroll does not leak to the page. It also blocks the horizontal axis, so a wheel or trackpad swipe over a table inside a sideways-scrolling wrapper never reaches the wrapper.
- The header row has a fixed height (`--uni-row-height`) and renders the header text in one `span`; there is no slot for a second line.
- Every DataTable selects something: one row (default), a set of rows, or one cell. Selected rows get `aria-selected="true"` and the accent background, which lingers after a click in read-only grids.
- `ListCore` takes the selection as a `ListSelection` (Single, Multi, Cell); the agent binding (`UiAgent`) reads it for items, value, and the `select` action.

## Design

Lane: Standard, pre-approved — the launch prompt carries the full direction. The prototype gate still applies (user-facing); merge is held ("Do not merge").

- **Scroll chaining**: `.uni-list` gets `overscroll-behavior-y: contain` and leaves the x axis at `auto`. All three list components gain this; none of them needs horizontal containment. A CSS test checks the computed values.
- **Header subtitle**: `Column.withSubtitle(text)` / `noSubtitle()`.
  - Renders a smaller, muted line under the label, inside the sort button for sortable columns so a click on either line sorts.
  - The header row grows to fit two lines (min-height instead of height); rows below are unaffected, since the list already measures the header.
  - The accessible name stays the label: the subtitle is `aria-hidden` and linked as the header's (or sort button's) `aria-describedby`.
  - Lines align to the end for `alignEnd` columns.
- **Read-only selection**: `DataTable.noSelection()` / `withSelection()` (the default).
  - With it, a click or Enter selects nothing: no `aria-selected`, no selected background, and the selection variables stay untouched. It wins over `withSelected`, `withMultiSelect`, and `withSelectedCell`.
  - Hover, the active cell and its focus ring, keyboard moves, and sorting keep working. `onSelect` still runs on Enter or a click, as an activation (to open a row's details).
  - Agents see the rows as items without selection state and get no `select` action.
  - Implemented as a new `ListSelection.None` case in ListCore, so VirtualList and Tree could adopt it later.
- **Gallery**: a "Query results" story on the data-table page — a read-only table with typed headers, wider than its box inside a horizontally scrolling wrapper.
- **Docs**: `docs/dom/collections.md`, the uni skill's `references/ui.md`, and `for-ai-agents.md` if it lists DataTable options.
- Tests cover each option's DOM (subtitle, description link, end alignment, no aria-selected after click/Enter, onSelect still runs) and the overscroll CSS.

## Alternatives and Why Not?

- `withSelectable(false)`: uni's config style is `withXXX`/`noXXX`, not boolean setters.
- Keeping selection but hiding its style: still sets `aria-selected`, which a screen reader announces.
- Subtitle as part of the accessible name: a type like `varchar` on every header makes column names noisy when moving through cells.
