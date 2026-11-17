# UiHistory: source-aware undo and staged agent changes on the ChangeGate stream

## Context

`UiAgent` lets an agent change app state, and every such change goes through `ChangeGate` with its source and reason (`2026-11-13-uni-ui-change-details.md`, `2026-11-13-ui-agent.md`). Apps also need to revert what the agent did, or let the agent suggest values that the human accepts or reverts as a group, without losing edits the human made in the meantime. UI SDK offers `createHistory`, `undoSource("agent")`, and `stageChanges`. Design notes: `plans/2026-11-14-ui-history-undo.md`, `plans/2026-11-16-ui-history-live-owners.md`.

## Decision

- **Built on the gate.** `ChangeGate.write` reports each committed change to `AgentRegistry.report`, where `UiHistory.record(limit = 500)` registers a recorder. Each change is recorded with its target `RxVar`, ref, kind, label, key, previous/value text, reason, and source. The history keeps no handler.
- **State owners.** A mounted component that writes app state registers as the owner of its `RxVar`s, with an accessor for its current `onChange` handler (`StateOwner`). Agent bindings are owners; `Dock` and `Splitter` are owners without an agent binding (`AgentRegistry.own`), so agents don't see them and the tool surface is unchanged. Every binding must declare its handler, so none can skip the app's `onChange` on undo.
- **Only gated state.** Button presses, menu-item actions, dialog callbacks, and other app code are not changes, so they are not recorded and cannot be undone. There is no rollback of external side effects.
- **Reversible entries.** An entry is reversible when a mounted component owns the target, the key is not `open`, and the value is not secret. Undoing overlay visibility would reopen menus and dialogs. Raw values of secret and password fields are never kept, and their text reads `<redacted>`. Components in `UiAgent.hidden` are not recorded, as in the change feed.
- **Undo writes through the same gate** with `ChangeReason.Undo` and the `Programmatic` source, using the handler of the component that owns the target at revert time. A field remounted on the same `RxVar` with a new handler is asked, not the old one. The `onChange` handler can cancel it, which reports `canceled`. An ambient `ChangeSource.withSource` scope still applies, so an agent that presses an app's own undo button is seen as the agent.
- **Conflict policy: never overwrite a later change.** Undo runs newest first. An entry is reverted only when the target still holds the entry's value and no later entry on the same target is still in effect (live or accepted). Otherwise it reports `conflict`, and the newer value stays. An unmounted owner reports `missing`, and nothing is written.
- **No feedback loop.** While the history writes a revert, it ignores reports, so a revert is not a new entry. The original entry is marked `undone`. An undo requested during a revert, such as from an `onChange` handler, does nothing. Other listeners see a `Programmatic` change with reason `undo`, not a user edit.
- **One drag, one entry.** Pointer drags (Dock separators and floats, `Splitter`) mark their writes with a per-drag token (`ChangeGate.inGesture`). A change in the same drag as the latest entry, on the same target, updates that entry's value instead of adding one. Keyboard steps stay one entry each.
- **Stages.** `history.stage(label)` opens one stage at a time. Only `Agent`-source entries join it, so `revert()` keeps the human's edits. `accept()` marks the entries accepted, and `undoSource` skips them from then on. Either call closes the stage.
- **App-side only.** `UiAgentApi` has no undo, redo, or replay tool, so the agent tool surface and its token cost are unchanged. An agent cannot use history to get around `AgentPolicy` or a confirmation.
- **Disposal.** `close()` stops recording, drops the entries (releasing the `RxVar`s and handlers), and closes an open stage as `closed`. After that, undo calls return nothing.

## Alternatives

- **Snapshot and restore the screen's state.** This would overwrite the human's intervening edits.
- **Conflict on value mismatch only.** A human who edits a field and then types the agent's value again would lose that edit to the undo.
- **An undo tool for the agent.** It grows the tool surface, and an agent could replay changes past the human's decisions.
- **Keep the handler captured with the change.** A remounted component with new rules would not be asked (the first version did this).
- **Give Dock and Splitter full agent bindings to make them reversible.** It adds them to agent snapshots and tools, which is a separate decision.
- **Merge drag steps by a time window.** Two quick separate drags would merge; the drag itself is the boundary.
- **Keep secret values privately so they can be undone.** It keeps passwords in memory to cover a rare case.

## Consequences

- An app that writes a target directly (outside the gate) after the agent changed it makes the agent's entry a `conflict`, which keeps the app's value.
- A Dock layout revert writes the whole previous `DockLayout` (splits, sizes, active tabs, floats). Detached windows are Dock state outside the layout, so a revert never opens one.
- State no mounted component owns (an `RxVar` written only by app code or by a `base` behavior used without a component) reports `unsupported`.
- There is no redo. An app that needs it can re-run the agent's suggestion.
