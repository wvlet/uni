# UiHistory: source-aware history, undo, and staged agent changes

## Goals

- Let an app list the changes made on screen, each with its source, reason, component, and before/after values.
- Let the human revert only what the agent changed (`undoSource(ChangeSource.Agent)`) without losing their own edits made in between.
- Let an agent's suggestions be staged, then accepted or reverted together ("agent suggests, human accepts").

## Background

- Every write a uni-ui component makes to app state already goes through `ChangeGate.write`, which reports a `ChangeDetails` (key, previous, value, reason, source) to `AgentRegistry.report` (ADR 2026-11-13-uni-ui-change-details, 2026-11-13-ui-agent).
- The registry knows which mounted component owns each `RxVar`, and whether it is hidden or secret; the agent feed (`changes`/`subscribe`) already redacts and skips hidden components.
- Button presses, menu-item actions, and dialog confirm callbacks run app code; they are not gated state writes and cannot be undone generically.

## Design

Lane: **Standard** (new public API). The launch prompt asked to proceed with sensible scoped defaults, so this note is shared as an FYI.

User flow:
- The app starts recording with `val history = UiHistory.record()` (any time; usually next to `UiAgent.install`).
- Before the agent's turn it opens `val s = history.stage("Fill shipping")`; the agent's changes join the stage.
- The human reviews the form, possibly editing fields, then presses Accept (`s.accept()`) or Revert (`s.revert()`), or "Undo agent" (`history.undoSource(ChangeSource.Agent)`).
- Each undo returns an `UndoResult` per entry: `reverted`, `conflict`, `canceled`, `missing`, `unsupported`, or `skipped`, so the app can tell the human what stayed.

Decisions:
- **What is recorded.** Committed gated writes from all sources, newest last, up to a limit (500). Hidden components are not recorded, as in the agent feed. Each entry has `seq`, ref, kind, label, key, previous/value text, reason, source, and `describe` ("Agent set Region to Oregon").
- **What is reversible.** Writes to app state of a registered component: `selected`, `value`, `sort`, `expanded`, `order`, and similar. Not reversible (`unsupported`): `open` (overlay visibility), secret values (the raw value is never kept, so the entry text reads `<redacted>`), and changes from components with no agent binding. Presses and app side effects are never recorded.
- **Undo writes back through `ChangeGate`** with reason `undo` and the `Programmatic` source (or the scoped source, so an agent pressing an app's undo button is still seen as the agent). The component's `onChange` can cancel it (`canceled`).
- **Conflict policy.** Undo goes newest first. An entry is reverted only if the target still holds the entry's value and no later live entry from another source touched the same target; otherwise it is a `conflict` and the human's value stays.
- **No feedback loop.** The history ignores its own revert writes and marks the entry `undone` instead of adding a new one. Undo calls made while a revert is running do nothing. Other listeners see the revert as a programmatic `undo` change, never a user edit.
- **Missing targets.** If no mounted component owns the target, the entry is `missing` and nothing is written. An app's own writes outside the gate show up as a value mismatch, hence `conflict`.
- **Stages.** One open stage at a time; only `Agent`-source entries join it, so human edits made meanwhile stay outside and survive `revert()`. `accept()` keeps the changes and excludes them from later `undoSource`. Both close the stage.
- **No agent tool surface change.** `UiAgentApi` is unchanged: undo, redo, and replay are app-side only, so an agent cannot use history to bypass the policy or confirmations. No redo.
- **Disposal.** `history.close()` stops recording, drops entries (releasing the `RxVar`s), and closes an open stage; later calls return no results.
- Tests cover mixed-source undo, conflicts, staging, cancellation, missing components, redaction, and the no-loop rule. Docs: `docs/dom/agents.md` and the skill's `references/ui.md` (compile-checked), plus an ADR.

## Alternatives and Why Not?

- **Snapshot/restore whole screen state.** Would overwrite intervening human edits.
- **Agent-callable undo tool.** Grows the tool surface and lets an agent replay past the human's decisions.
- **Keep secret values privately for undo.** Retains passwords in memory for an edge case.
