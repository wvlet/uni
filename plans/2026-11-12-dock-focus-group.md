# Dock: focus a group by number

## Goals

- Let an app move keyboard focus to "group N" of a `Dock`, like an editor's Ctrl+1 / Ctrl+2 shortcuts, which #877 listed as a known gap.
- Also let it focus a given panel, so a command such as "Show Terminal" can land the keyboard there.
- Keep the saved layout format unchanged.

## Background

- The Dock addresses groups by path (`List[Int]`), which changes whenever the structure changes, so paths are not something an app can hold on to (ADR 2026-11-06).
- The ADR deferred stable group ids in the saved JSON as the way to get a "focus group N" API.
- Groups already have a stable user-facing number: the accessible name of each tab list is "Dock, group N", counted in reading order (`DockModel.groups`, depth first).
- Focus is DOM state: each group's tab list uses a roving tabindex, so "focus a group" means focusing its active tab.

## Design

Lane: **Standard** — it adds public API on `Dock`, though it is small and has no visual change.

- `dock.focusGroup(index)` focuses the active tab of a group by its **0-based** index in reading order, like Scala collections (the accessible name "group N" is `index + 1`). Out of range does nothing and returns `false`.
- `dock.focusPanel(id)` shows the panel (as `open` does) and then focuses its tab.
- `dock.groupCount: Rx[Int]` tells an app how many groups exist, for menus or for wrapping a "next group" command.
- Focusing a group also makes it the target for `open`, since that already follows `focusin`.
- No change to `DockLayout`, its JSON, or `DockModel`; the numbers come from `DockModel.groups` on the current layout.
- The Dock does not bind global keys itself; the app chooses shortcuts (key bindings vary per app and platform). Docs show Ctrl+1..N wiring.
- Tests cover numbering across nested splits, out-of-range, focus after a move/split rebuild, and `focusPanel` on a hidden panel. Docs (`ui.md`, for-ai-agents, `uni` skill) and the ADR's alternatives line get updated.

## Alternatives and Why Not?

- **Stable group ids in the saved JSON**: needs a format version bump and migration for a feature that reading-order numbers already serve.
- **1-based number**: matches the visible "group N" names and Ctrl+N, but not Scala indexing; chosen against at review.
- **Built-in Ctrl+N / F6 handling in the Dock**: would clash with app or Electron shortcuts; leave it to the app.
