# uni-ui components write app state through one gate that reports and can refuse each change

## Context

uni-ui components wrote user input straight into the `RxVar`s apps pass them (`open`, `selected`, text, `expanded`, `sort`, `size`, `layout`). Apps could not tell why a value changed or who changed it, and could not refuse a change before it landed. An agent bridge (planned in `plans/2026-11-13-ui-sdk-learnings.md`) needs both: it must tag its own changes and let apps veto them. UI SDK solves this with `ChangeDetails { reason, source, cancel() }` on every change. Its parts also carry `data-slot` names that styles, tests, and tools can target.

## Decision

- Every write a component makes to app-owned state goes through `ChangeGate.write` (`private[ui]`). It builds a `ChangeDetails(key, value, previous, reason, source)` and runs the component's `onChange` handler. It writes only if the handler did not call `cancel()`. Nothing is reported when the value doesn't change, and nothing is reported for writes the app makes itself. State a component owns internally (a Select's open flag, highlight, drag preview) is not reported.
- `ChangeSource` is `Pointer | Keyboard | Programmatic | Agent`. Components detect it from the event (`ChangeSource.of`; a click with `detail == 0` is the keyboard). `ChangeSource.withSource(src) { ... }` overrides it in a scope. Scala.js is single-threaded, so a scoped variable is safe.
- `ChangeReason` is an open case class with constants, so apps and agent bridges can add reasons.
- Changes whose trigger is far from the write get their cause from an ambient `ChangeCause.during` scope. `Dismiss` sets EscapeKey/Keyboard or OutsidePress/Pointer around `onDismiss`, and a menu item sets ItemPress around its close and action.
- Action callbacks (`onClick`, `onSelect`, `onRun`, `onConfirm`, `onCancel`) keep their signatures and run inside that scope. They read the cause with the public `ChangeCause.current`.
- Code that writes the variable itself (`value.bind`, `PaneResize`) gets a `GatedVar` stand-in. Writes go through the gate. A refused write leaves both variables unchanged, and a text field is put back. `mirror` keeps the stand-in in step with app writes while it is rendered.
- Text fields hold IME composition text as a draft and gate it once, on `compositionend`. Before this change, `value.bind` wrote each intermediate composition step.
- Every rendered part carries `data-slot`: the component name for the root and `<component>-<part>` inside. Existing classes and `data-state` stay.
- The DOM testing kit's `fireEvent.click`/`mousedown`/`mouseup` now send `detail = 1` and `dblclick` sends `detail = 2`, as a real pointer does. Before, they looked like keyboard-made clicks.

## Alternatives

- **Overloads that pass the cause** (`onClick(c => ...)` next to `onClick(() => ...)`): rejected after trying them. With overloads, Scala 3 doesn't adapt a lambda whose body isn't `Unit` (`onRun { () => future }`), so existing app code stopped compiling.
- **Wrap the app's `RxVar` in a vetoing variable**: hides the reason and source, and surprises apps that share the variable.
- **A closed `ChangeReason` enum**: apps and the agent bridge need their own reasons.

## Consequences

- `TabList.onChange` and `Splitter.onChange` set the handler on the instance and return it, rather than making a copy. A `TabPanel` or `FoldablePane` built from the same instance stays linked.
- A splitter's double-click reset is reported as `Drag`, because `PaneResize` handles it internally. Keyboard resets are reported as `Reset`.
- Apps that filtered on IME composition steps through `Input`/`Textarea` now see only the committed text.
