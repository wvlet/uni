# Publish uni-ui behaviors and add the ones uni-harness needs

## Goals

- Make `wvlet.uni.ui.base` a documented, public behaviors catalog, so app authors and AI agents build new widgets from tested parts instead of re-deriving keyboard, focus, and scroll logic.
- Add six behaviors our own apps lack: `StickToBottom`, `InputHistory`, `Typeahead`, `Selection`, `FocusRecovery`, `Announce`.
- Use them in uni-harness: chat scroll follows the stream, and the composer recalls past prompts with ArrowUp/Down.

## Background

- Step 3 of `plans/2026-11-13-ui-sdk-learnings.md`. Steps 1 (#897, UI skill reference) and 2 (#898, `ChangeGate` / `onChange` / `data-slot`) are merged.
- `base` is already public in Scala terms (no `private[ui]`), but undocumented: `RovingFocus`, `ListNavigation`, `Dismiss`, `FocusTrap`/`FocusReturn`, `Anchored`, `Windowing`, `Fuzzy`, `TreeModel`, `DockModel`.
- Existing behaviors are mostly pure functions (`RovingFocus.nextIndex`, `Windowing.reveal`), which keeps them testable without a DOM.
- `RovingFocus.typeahead` matches only a single key: typing "ca" in a list of "Cat, Car, Cabbage" jumps to "a…" on the second key. Select, Menu, ListCore and Tree all call it.
- uni-harness `ChatView.scrollToBottom` hand-rolls stick-to-bottom with a 96px threshold and a `setTimeout`; the composer has no history.
- `ChangeGate` is `private[ui]`; `base` is inside `ui`, so behaviors can use it directly.

## Design

Lane: Standard — new public API surface and user-visible behavior in uni-harness (lane set by the launch prompt).

- **Shape: a pure model plus a thin DOM binding.** Each behavior is an `object`/`class` in `wvlet.uni.ui.base` with pure state logic (unit-tested without a browser) and, where it needs the DOM, a binding that returns a `Cancelable` (or a `DomNode` modifier) so it composes with `RxElement` mount/unmount.
- **Gated writes.** Behaviors that change app-owned state take the app's `RxVar` and an `onChange` handler and write through `ChangeGate`, with a `ChangeReason`/`ChangeSource` (`Selection` → key `selected`; `InputHistory` → key `value`, reason `NavigationKey`). Behaviors that only touch view state (scroll position, focus, live region) don't go through the gate.
- **StickToBottom** — follows appended content while the reader is within a threshold of the bottom; scrolling up pauses following; returning to the bottom resumes. Exposes `atBottom: Rx[Boolean]` (for a "jump to latest" button) and `scrollToBottom()`. Uses `ResizeObserver` on the content rather than `setTimeout`.
- **InputHistory** — a terminal-style ring: ArrowUp on the first line recalls the previous entry, ArrowDown on the last line goes forward and finally restores the unsent draft. Duplicates of the latest entry aren't stored; capacity is bounded. Persistence stays with the app (the model exposes `entries`).
- **Typeahead** — a buffered matcher: keys typed within ~500ms accumulate ("ca" → Cat/Car), a repeated single letter cycles. `RovingFocus.typeahead` stays as a stateless shim; Select, Menu, ListCore and Tree switch to `Typeahead`.
- **Selection** — a model for multi-select over ordered keys: select, toggle (Ctrl/Cmd), range from an anchor (Shift), select all, clear. Wiring it into `ListBox`/`DataTable` as a `multiple` mode is a follow-up; this PR ships the model, its keyboard/pointer mapping, and docs.
- **FocusRecovery** — when the focused item is removed from a container, moves focus to the next item, else the previous, else the container, so keyboard users aren't dropped to `<body>`. Built on a `MutationObserver`; ListCore and Tree adopt it.
- **Announce** — one shared, visually hidden live region per document: `Announce.polite(msg)` / `Announce.assertive(msg)`. uni-harness announces "Response complete" when a stream ends.
- **Docs** — a new `docs/dom/behaviors.md` catalog page (all existing and new behaviors, one short compiled example each, via `docSnippetsJS`), a sidebar entry, and a "Behaviors" section in the skill's `references/ui.md`; update `docs/guide/for-ai-agents.md` and bump the plugin version.
- **Tests** — pure-model tests for each behavior, plus `DomTestSupport` tests for the DOM bindings (follow/pause, history recall, focus after removal, live-region text).
- **Not in this PR** — `Reorder` (listed in the study note but not in this step's scope), `checkPattern` (step 4), `UiAgent` (step 5).

## Alternatives and Why Not?

- **Move behaviors to uni-dom (`wvlet.uni.dom.behavior`).** They depend on `ChangeGate` and uni-ui's ARIA conventions; moving would split the catalog and require exposing the gate. uni-dom primitives (`Keyboard`, `PointerDrag`, `Focus`, …) get listed on the catalog page instead.
- **Ship behaviors as `RxElement` wrappers (`StickToBottom(div(...))`).** Wrappers fight composition with existing elements; a binding attached to any element composes better.
- **Wire `Selection` into `ListBox`/`DataTable` now.** Changes the components' `selected: RxVar[Option[K]]` API; better as its own PR once the model settles.
