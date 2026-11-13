# uni-ui behaviors are public: a pure model plus a binding, gated when they write app state

## Context

uni-ui widgets share their interaction logic (roving focus, dismissal, anchoring, windowing) through `wvlet.uni.ui.base`. That package was undocumented, so apps and AI agents re-derived keyboard, focus, and scroll handling for each new widget. uni-harness, for example, scrolled its chat with a hand-written 96px check and a `setTimeout`. UI SDK treats behaviors as the unit that agents compose. Step 3 of `plans/2026-11-13-ui-sdk-learnings.md` publishes ours and adds the behaviors a chat app needs.

## Decision

- `wvlet.uni.ui.base` is public API, documented in `docs/dom/behaviors.md` and in the skill's `references/ui.md`. It stays in uni-ui rather than moving to uni-dom, because behaviors write through `ChangeGate` (`private[ui]`).
- Each behavior is a pure model (testable without a DOM) plus a thin binding. A binding is a modifier on any element, not a wrapper `RxElement`, so it composes with existing markup.
- uni-dom gains `OnMount(el => Cancelable)`, a generic modifier that runs on mount and cancels on unmount. Behaviors that need observers or listeners on their element are built on it, with no special case per behavior in `DomRenderer`.
- Behaviors that change app-owned state (`Selection` → `selected`, `InputHistory` → `value`) write through `ChangeGate`. The app's `onChange` therefore sees the reason and source and can cancel. Behaviors that touch only view state (`StickToBottom` scroll position, `FocusRecovery` focus, `Announce` live regions) don't report.
- `Typeahead` buffers keys typed within 500ms into one prefix search. Select, Menu, and the list core use it. `RovingFocus.typeahead` stays as the stateless single-key form.
- `Announce` uses one hidden polite region and one assertive region per document, and appends a new node per message so that repeats are read again. `Announce.prepare()` creates the regions early, because screen readers skip messages in a region that has just appeared.

## Alternatives

- **Move behaviors to uni-dom**: they would need `ChangeGate` exported, and the catalog would be split across two artifacts.
- **Wrapper components (`StickToBottom(div(...))`)**: these fight composition with existing elements and their modifiers.
- **One renderer binding per behavior (like `ResizeBinding`)**: this grows `DomRenderer` with each behavior. `OnMount` covers them all.

## Consequences

- `Selection` ships as a model. Wiring a `multiple` mode into `ListBox` and `DataTable` changes their `selected: RxVar[Option[K]]` API and is a follow-up.
- In Select and ListBox, Space still selects instead of continuing a typeahead search.
