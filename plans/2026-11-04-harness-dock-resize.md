# Resizable right dock in uni-harness

## Goals

- Let the user drag the right dock's inner (left) edge to widen or narrow it, like the left sidebar.
- Let a wider dock give the terminal more columns.

## Background

- The dock width was fixed per tab: 320px for Details and Activity, 600px for Terminal (about 80 columns).
- The sidebar already resizes through `ResizeHandle` (a styled grip on uni-dom's `PaneResize`), with double-click reset, and persists its width in `Storage.local("uni-harness.sidebarWidth")`.
- `TerminalView` refits to its pane through a ResizeObserver, so a width change should resize the shell's cols/rows with no extra code.

## Design

- **Lane: Standard**: it adds a persisted layout setting (a new localStorage key). The user chose a single width at the design card.
- User flow: hover the dock's left edge and the grip highlights; drag left to widen, right to narrow; double-click resets to the default. The width stays after reload.
- One width for every tab: `SizeRange(260, 320, 960)`, persisted as `uni-harness.dockWidth`. Switching tabs no longer changes the width.
- The Terminal tab now opens at 320px (about 38 columns) until the user widens it; ~600px fits 80 columns.
- One `ResizeHandle` with `Edge.Left` on the dock. No change to uni-dom's `PaneResize`.
- Tests: a drag on the dock's left-edge handle clamps to the range and a double-click resets it.

## Alternatives and Why Not?

- A width per tab kind (Details/Activity vs Terminal): the user preferred one width.
- Not persisting: the sidebar persists its width, so a dock that forgot its width on reload would feel inconsistent.
