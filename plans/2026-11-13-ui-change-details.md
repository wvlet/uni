# ChangeDetails and data-slot for uni-ui

## Goals
- Every change a uni-ui component makes to app state says why it happened, who made it, and can be refused before it lands.
- Every part of every component has a stable `data-slot` name that CSS, tests and (later) agents can target.
- This is step 2 of [2026-11-13-ui-sdk-learnings.md](2026-11-13-ui-sdk-learnings.md). The agent bridge (step 5) builds on both pieces.

## Background
- Components write straight into the `RxVar`s the app passes in: about 80 writes across 15 files, such as `open := false` in Dismiss and `selected := v` in Select and Tabs.
- Callbacks are `() => Unit` or `A => Unit` (`onClick`, `onSelect`, `onDismiss`, `onRun`, `onConfirm`). They don't say what caused them, and nothing can veto a write.
- Styling and tests target BEM classes (`uni-button--primary`) and ad-hoc selectors such as `[role=separator]`. Class names mix part names with variants, so they make poor stable ids.
- UI SDK's equivalents: `ChangeDetails { reason, source, cancel() }` on every change, and `data-slot="<primitive>-<slot>"` on every part.

## Design
Lane: Standard. This adds public API to every component and changes the DOM of every part.

**User flow (app developer):**
- An app that wants to refuse or log a change adds `.onChange(d => ...)` to a component. `d` carries `value`, `previous`, `reason` and `source`. `d.cancel()` keeps the old value.
- Existing code keeps compiling and behaves as before. Callbacks such as `onClick` and `onSelect` keep their signatures. Inside one, `ChangeCause.current` gives the reason and source. Overloads were tried and dropped: Scala 3 doesn't adapt a non-`Unit` lambda body when choosing between overloads, so `onRun { () => future }` stopped compiling.
- CSS and tests can write `[data-slot="menu-item"]` instead of guessing class names.

**Decisions:**
- **`ChangeDetails[A](value, previous, reason, source)` with `cancel()` and `canceled`.** The `key` is the component's state name (`open`, `selected`, `value`, `expanded`, `sort`, `layout`).
- **`ChangeSource` enum:** `Pointer`, `Keyboard`, `Programmatic` or `Agent`. Mouse, touch and pen are all `Pointer`; split them later if anyone needs to.
- **`ChangeReason`:** a small open type (a name wrapped in a value class) with constants: `Press`, `ItemPress`, `EscapeKey`, `OutsidePress`, `CloseButton`, `NavigationKey`, `Typeahead`, `Input`, `Drag`, `Timeout`. Apps and the later agent bridge can add their own.
- **One internal write path:** components go through `ChangeGate.write(rxVar, newValue, reason)` instead of `:=`. It builds the details, runs the component's `onChange` handler, and writes only if nothing canceled. No change is reported when the value didn't change.
- **Where the source comes from:** components already know it from the handler that fired (a key handler or a pointer handler). Code that wraps a block in `ChangeSource.withSource(Agent) { ... }` overrides it. Scala.js is single-threaded, so a scoped variable is enough. Step 5 uses this.
- **Text inputs:** `Input` and `Textarea` report `Input` changes, and a refused edit puts the field back. IME composition text stays a draft and is gated once, when the composition ends.
- **Scope of `onChange`:** only state the component itself writes. When the app writes `open := true` directly, nothing is reported, because the app already knows.
- **`data-slot`:**
  - The root gets the component name in kebab case (`button`, `menu`, `data-table`). Parts get `<component>-<part>` (`menu-item`, `dialog-title`, `dock-tab`, `splitter`).
  - Existing classes and `data-state` stay. `uni-ui.css` is not rewritten in this PR.
- **Tests:**
  - `onChange` reason and source for a pointer and a keyboard path per component family.
  - `cancel()` keeps the old value.
  - Every rendered part has a `data-slot`.

**Out of scope (later steps):** a page-wide change feed, the agent bridge, and history.

## Alternatives and Why Not?
- **Wrap the app's `RxVar` in a vetoing `RxVar` instead of using a handler:** it would hide the reason and source, and would surprise apps that share the var.
- **Replace the existing callbacks with detail-taking ones, or add overloads:** both break existing app code. The ambient `ChangeCause.current` doesn't.
- **`ChangeReason` as a closed enum:** apps and the agent bridge need their own reasons.
