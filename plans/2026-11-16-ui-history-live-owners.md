# Finish UiHistory: live handlers and reversible Dock layout and pane sizes

## Goals

- An undo asks the component that owns the state **now**, through its current `onChange` handler, not the handler captured when the change was made.
- Dock `layout` and Splitter `size` changes become reversible, so `undoSource(Agent)` and `stage.revert()` cover what a person or agent did to the workspace layout.

## Background

- `UiHistory` (#916, ADR `2026-11-14-ui-history-undo.md`) keeps the handler passed to `ChangeGate.write`. A component remounted on the same `RxVar` with a different handler (new rules, closed-over state) is not consulted on revert.
- An entry is reversible only when an `AgentBinding` claims the target through `states`. Dock and Splitter have no binding, so their entries report `unsupported`.
- Giving them full agent bindings would add them to agent snapshots and tools. That grows the agent tool surface and token cost, an always-gate area, and is a separate question.
- `PaneResize` writes the size on every pointer move, so one splitter drag records dozens of entries today. Undoing one step of a drag is useless, and a drag can push older entries past the 500-entry limit.
- Detached Dock windows live in Dock's own `detached` state, not in `DockLayout`, so reverting a layout never opens a window.

## Design

Lane: Standard — changes undo semantics and which changes are reversible.

- **State owners.** A mounted component that writes app state registers as the owner of its `RxVar`s with a live handler accessor. Agent bindings become one kind of owner; Dock and Splitter register as owners without an agent binding, so agents still don't see them.
- **Undo uses the live handler.** The history no longer keeps handlers. At revert time it reads the current owner's handler; with no mounted owner the result stays `missing`, as today.
- **Every binding declares its handler.** The handler accessor is required (no default), so a component cannot silently skip the app's `onChange` on undo.
- **Reversible means owned.** An entry is reversible when a component owns the target, the key is not `open`, and the value is not secret. App code writing an `RxVar` no component owns stays unrecorded, as today.
- **Dock layout.** An undo writes the previous `DockLayout` (splits, sizes, active tabs, floats) through Dock's `onChange`, which can cancel it. Entries read as `Dock layout`, using the dock's label.
- **One drag, one entry.** Consecutive `drag` changes to the same target from the same source within one drag merge into one entry: the first previous value and the last value. Keyboard steps stay one entry each.
- **No new public API.** Apps keep `UiHistory.record`, `undo`, `undoSource`, and stages; Dock and Splitter keep their `onChange`. Entries just become reversible.
- **Docs and ADR.** Update the UiHistory ADR (consequences and the owner rule), `docs/dom/`, `for-ai-agents.md`, the skill's `ui.md`, and bump the plugin version.
- Tests cover a remount with a new handler that cancels the revert, Dock layout undo and conflict, a splitter drag recorded as one entry and undone in one step, and agent snapshots unchanged for Dock and Splitter.

## Alternatives and Why Not?

- **Give Dock and Splitter full agent bindings.** Solves reversibility but adds them to the agent's view and tools; worth its own decision.
- **Fall back to the captured handler when no live one is exposed.** Two sources of truth, and a stale handler is exactly the bug.
- **Merge drag steps by time window.** Two quick separate drags would merge; the drag gesture is the real boundary.
