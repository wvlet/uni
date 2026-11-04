# Clamp a persisted pane width when it loads

## Goals
- A pane width restored from storage never renders outside its `SizeRange`.
- Example: a dock width saved under the old 960px max now shows at 720px instead of 960px until the next drag.

## Background
- `PaneResize` calls `SizeRange.clamp` only inside a drag; the harness loads `sidebarWidth` and `dockWidth` from `Storage.local` without checking them.
- #858 lowered the dock max from 960px to 720px, so stale values over the new max are likely; values edited by hand are another source.
- Every harness pane builds its `PaneResize` (through `ResizeHandle`) before the pane renders.

## Design
- Lane: **Express**. It is a uni-dom bug fix with no public API change and touches no always-gate area.
- `PaneResize` clamps the `RxVar` it is given when it is constructed, so every `PaneResize` user benefits, not only the harness.
- It writes only when the value is out of range. A `Storage.local` var then saves the corrected width, so the stale value goes away for good.
- The scaladoc says so, and a uni-dom-test checks that an out-of-range start value comes back clamped (above max and below min).
- No harness change is needed, and Dock.scala and TerminalPane.scala are not touched.

## Alternatives and Why Not?
- Clamp in `Layout.persistent`: the smallest change, but it fixes only the harness, and every other app would need the same guard.
- Clamp on read with a derived `Rx`: the stored value would stay stale and the width would read differently in two places.
