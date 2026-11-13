# uni-ui collections share one windowed list with fixed row heights and focus on the container

## Context

uni-ui phase 3 adds VirtualList, Tree, DataTable, and CommandPalette. A log, a file tree, or a table can hold thousands of rows, and `each` alone renders all of them. The three row-based components need the same scrolling, keyboard, and selection behavior, and a tree or table should stay fast when it grows.

## Decision

- One internal `ListCore` renders VirtualList, Tree (its visible rows, flattened by `TreeModel.flatten`), and DataTable's body. It renders the rows in view plus an overscan of 8 inside a spacer as tall as the whole list (`height` plus `padding-top`), and keys them with `each`, so scrolling only adds and removes rows at the edges.
- Every row has one fixed height in pixels, which the component sets as `--uni-row-height`. Windowing is then plain arithmetic (`Windowing.inView`, `range`, `reveal`) with no measuring.
- The scroll container keeps focus as one tab stop, and `aria-activedescendant` names the active row. The arrow keys don't wrap (`ListNavigation`), and PageUp and PageDown move by a page. Keys from controls inside a row are ignored, so a button in a cell keeps Enter.
- Selection, open tree rows, and the table sort are app-owned `RxVar`s, like `TabList`'s selection.
- DataTable is `role="grid"` with a sticky header inside the scroll container and first moved by row, not by cell. Since 2026-11-12 it moves by cell: `aria-activedescendant` names the active cell (the row stays the unit of selection), and Home and End follow the grid pattern (row ends; Ctrl or ⌘ for the first and last row). Columns share a CSS grid track list in `--uni-table-columns`.
- CommandPalette is a `Dialog` (two `private[ui]` hooks: a `--palette` class modifier and an `aria-label`) holding a combobox input and a listbox. It doesn't use `ListCore`: focus stays in the input, results are capped (50 by default), and arrow keys wrap. `Fuzzy` ranks contiguous and word-start matches first, and falls back to the plain leftmost subsequence when jumping to word starts would miss a letter. It lowercases character by character, not with `String.toLowerCase`, which can lengthen text ("İ" becomes two characters) and shift match positions off the original label.

## Alternatives

- **Variable row heights** (measure rows, correct the offsets, anchor the scroll position): more code and layout work on every scroll; fixed heights cover logs, file trees, and tables first.
- **Roving tabindex on rows** (as `TabList` does): the focused row can scroll out of the window and be unmounted, which drops focus to `<body>`.
- **Nested `role="group"` markup for Tree**: it can't be windowed. The flat rows carry `aria-level`, `aria-posinset`, and `aria-setsize` instead.
- **A native `<table>`**: rows can't be windowed inside `<tbody>` without breaking column sizing.

## Consequences

- A collection needs a bounded height (a `height`, or a flex parent); otherwise it grows to fit and renders every row. App styles must use `styleAttr += ...`, because a plain `styleAttr ->` replaces the component's `--uni-row-height`.
- Rows that scroll back into view render again, so per-row enter animations replay; avoid them on windowed rows.
- DataTable has no column resize or reordering yet, and keys don't move into widgets inside a cell.
- `uni-list`, `uni-list__row`, `uni-tree`, `uni-table`, `uni-palette`, and `--uni-row-height`, `--uni-tree-level`, and `--uni-table-columns` are public API.
