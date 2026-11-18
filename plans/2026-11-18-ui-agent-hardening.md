# Harden UiAgent after UI SDK's agent fixes

## Goals

- Close the gaps UI SDK found in its agent bridge (betas 0.1.0-beta.6–9) where they also exist in uni-ui's `UiAgent`.
- An agent should never be able to change what a person can't (read-only fields), slip a guarded change past `UiAgent.confirm`, or misread the snapshot (components named by their items' text).
- Give agents the two surfaces they still can't reach: a toast's close and running a CommandPalette command.

## Background

- `UiAgent` (ADR `2026-11-13-ui-agent.md`) lists bound components, refuses actions on disabled ones and on anything while a confirmation waits, and runs every change through `ChangeGate` with `ChangeSource.Agent`. That's how `onChange` can cancel it and `UiHistory` can undo it.
- `act` takes one `value: Option[String]`. MCP decodes arguments into that type, so an array can't arrive where one value is expected. That's the root of UI SDK's #587 and #814 multi-value problems, and it can't happen here.
- What I checked, and what already holds (dropped from this PR):
  - Select value not among its options (#589, #597): `AgentAction.choice` refuses a label that isn't listed. An empty option list refuses every value.
  - Multiple values where one is expected (#587, #814): `act` takes a single string (see above).
  - Empty toast region listed (#661): `Toaster`'s region has no binding, so it never shows up.
  - Closed tooltips listed (#771): `Tooltip` has no binding, and a closed one isn't in the DOM.
  - Input inside a group listed twice (#666): uni-ui has no InputGroup. `Input` binds once.
- Gaps that do exist:
  - Grouped digits (#587): `setText` on an `Input` of `type="number"` takes `"1,499.5"`. The `RxVar[String]` stores it, and the browser shows the field empty.
  - Read-only (#586): `Input`/`Textarea` have no read-only option at all, so an app can't show a field that people can select and copy but not edit.
  - Confirm on removal (#812): `UiAgent.confirm` is checked only on the component and its ancestors. On a multi-select list, an agent's `select` (a plain click) replaces the whole selection. That drops a row the app meant to guard, and nothing asks. Rows can't carry a mark today.
  - Toast (#651): each toast's close is an anonymous `button:dismiss`, `button:dismiss-2`, …, with no tie to its message. uni-ui toasts have no action button (no "Undo"), so the action half doesn't apply.
  - CommandPalette (#769): when the palette is open, the agent sees a dialog with only `close`. Its input and rows have no binding, so an agent can't list or run a command.
  - Naming (#669, our version): when a list, tree, table, or dialog has no label, its name falls back to its whole text content. That's every row's text run together, and it changes as rows change.

## Design

Lane: **Standard** — it changes what agents can see and do (the tool surface), and it adds public options to `Input`, `Textarea`, and the list components.

- **Read-only fields.**
  - `Input` and `Textarea` get `withReadOnly(Rx[Boolean])` / `withReadOnly(Boolean)`, which sets the `readonly` attribute.
  - The agent treats a read-only field like a disabled one: it lists no actions, `act` refuses with "'ref' is read-only", and the snapshot shows `readOnly: true`.
  - The refusal applies to text edits (`setText`) on text fields only. A `role="grid"` table with `aria-readonly` can still be selected and sorted.
- **Number fields refuse text that isn't a number.**
  - `setText` on an `Input` of type `number` refuses a value that isn't an HTML "valid floating-point number" (`1499.5`, `-0.5`, `1e3`). The message says why: "1,499.5" has a grouping separator.
  - Empty text stays allowed, since it clears the field.
  - Other types stay free text.
- **Confirm before adding or removing a guarded row.**
  - Lists (`VirtualList`, `Tree`, `DataTable`) get `withAgentConfirm(row => Option[String])`: a message for rows an agent may select or deselect only after a person allows it.
  - An agent step that adds or removes such a row asks first, using the same dialog and lock as `UiAgent.confirm`. That covers selecting it, and selecting another row that drops it, in single and multiple selection alike.
  - This is data, not a DOM mark, because rows outside the virtual window aren't rendered.
- **Toasts.**
  - Each toast becomes a `toast` node, labeled by its message, with the variant as its value and a `dismiss` action.
  - Its inner close button is hidden from agents, so the toast is listed once.
  - The empty region stays unlisted.
- **CommandPalette.**
  - While the palette is open, it's a `commandpalette` node. Its items are every command (capped at 100 like any snapshot, with `moreItems`), and it has a `run` action that takes the label of any command in `commands`, not only the listed ones, so the query box isn't needed.
  - `run` goes through the existing `run(command, cause)` with `ChangeSource.Agent`. The palette's `onChange` sees the close with `source == Agent` and can cancel it, and then the command doesn't run.
  - One command per `act` call, as a click or Enter runs one.
  - The agent opens the palette the way the app lets a person open it. Apps usually bind ⌘K, which an agent can't press, so the docs tell apps to give agents an opener, such as a Button that sets `open` (see the open question).
- **Naming.**
  - A container (list, tree, table, dialog, palette) or a select is named only by its own `aria-label`, `aria-labelledby`, `<label>`, or `title`, never by its text content. A select's text is its current value, not its name.
  - An unlabeled one reads as its kind (`list`, `list-2`).
  - Buttons, menu triggers, menu items, and fields keep the text fallback; their text is their name.
- **Undo.** Row selection and text edits go through `ChangeGate`, so `UiHistory` reverts them as before.
  - A palette `run` gates only `open`, which `UiHistory` doesn't revert. Undo reverts the gated writes the command itself makes.
  - Dismissing a toast isn't gated and can't be undone, as for a person.
- Docs: `docs/dom/agents.md`, `docs/guide/for-ai-agents.md`, the plugin skill's `references/ui.md`, a plugin version bump, and an addendum to the UiAgent ADR. No new ADR: this follows the existing design.
- Tests (`UiAgentTest`) cover each refusal, the row confirmation (allow and deny), toast dismiss, and palette run (including a command past `limit`) and cancel, and undo of a command's gated write.

## Alternatives and Why Not?

- Read row marks from the DOM (`UiAgent.confirm` inside a row's content): misses rows outside the virtual window, so a guarded row could be dropped without asking.
- Toast action API ("Undo" buttons): a new Toaster feature, not hardening. It's a follow-up if wanted.
- Let `run` work while the palette is closed: lets an agent reach every command without the app's way of opening the palette. It's also more than a person can do.
- Keep the text-content fallback for containers but cap it: the name still changes with the rows, and so does the ref when there isn't one yet.
