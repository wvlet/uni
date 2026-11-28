# uni-ui read-only result grids: sideways scroll chaining, header subtitles, no selection, and ScrollArea

## Context

Grids of query results (the Wvlet playground notebook) are read-only and often wider than their box. They patched `DataTable` with app CSS: `overscroll-behavior: auto` so sideways scrolls reached their panel, a `"name\ntype"` header string styled into two lines, rules that hid the selected-row background, and an `HScroll` wrapper that hid the native scrollbar strip under the last row.

## Decision

- `.uni-list` (VirtualList, Tree, DataTable) sets `overscroll-behavior-y: contain` only. A list's vertical scroll still stays inside it, and a sideways scroll at its edge chains to an outer scroller. Trade-off: a sideways overscroll can now reach the page, where macOS browsers run the swipe-back gesture; apps that don't want that set `overscroll-behavior-x: none` on `html`. Chaining was chosen over an opt-in option because every app with a scrolling wrapper would otherwise have to remember to turn it on.
- `Column.withSubtitle(text)` renders the label and the subtitle as two lines inside the sort button (so either line sorts). The element the label names gets `aria-label` = the label and `aria-describedby` = the subtitle, which is `aria-hidden`; a type on every header would otherwise make column names noisy while moving through cells. `aria-label` is set (not just `aria-hidden`) so name computations that read `textContent` also leave the subtitle out. The header row has `min-height` instead of `height`; the list measures the header from the spacer's offset, so taller headers don't cover rows.
- `DataTable.noSelection()` maps to `ListSelection.Off` in `ListCore`: no `aria-selected`, the selection variables untouched (focus ignores a `withSelectedCell` variable too), and a click, Enter, or Space only runs `onSelect` as an activation. Agents keep the `select` action, which does the same. It overrides `withSelected`, `withMultiSelect`, and `withSelectedCell`, and VirtualList and Tree can adopt it later. Hiding the selected style instead would still announce `aria-selected` to screen readers.
- `DataTable.withCenteredHeaders()` centers headers over their columns as an option, so existing tables keep headers aligned with their cells.
- `ScrollArea(content)` scrolls sideways with the native scrollbar hidden and a thin overlay thumb shown on hover (draggable). Chrome has no overlay scrollbar for classic-scrollbar systems (`overflow: overlay` is an alias of `auto`), so a custom thumb is the only way to avoid the strip. The view uses `scrollbar-width: none !important`: an important declaration in the `uni` layer beats an app's unlayered `* { scrollbar-width: thin }`, which otherwise wins and brings the strip back (`::-webkit-scrollbar` is ignored once `scrollbar-width` is set). The thumb is `aria-hidden`; the view scrolls by the usual means.

## Alternatives

- Keep `contain` and add an opt-in table option for sideways chaining: safer against swipe-back, but every result grid in a scrolling panel would need it.
- `withSelectable(false)`: uni's options are `withXXX`/`noXXX` pairs, not boolean setters.
- Column widths from content: each windowed row is its own CSS grid, so `auto` tracks would not line up across rows; apps set `withWidth` (the playground estimates from values). Automatic sizing is a follow-up.

## Consequences

- Known gap: keyboard column moves in a table inside a `ScrollArea` scroll the table, not the area, so an off-screen column may stay hidden.
- After a uni release, Wvlet drops its overrides, the `"name\ntype"` trick, and `HScroll`.
