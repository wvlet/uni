# Typeahead Space, per-entry InputHistory edits, Splitter reset reason

Follow-up to [2026-11-13-ui-sdk-learnings.md](./2026-11-13-ui-sdk-learnings.md): three known limitations left by #898 and #899.

## Goals

- Typing "new york" in a Select, Menu, VirtualList, or Tree typeahead lands on "New York" instead of choosing "New…" on the Space.
- Editing a recalled InputHistory entry, moving to another entry, and coming back shows the edit again, as in Bash/readline; submitting reverts the edits.
- A Splitter double-click reports `ChangeReason.Reset`, so `onChange` sees the same reason as keyboard Enter.

## Background

- WAI-ARIA listbox and menu guidance: while a typeahead search is under way, Space is a character of the search, not activation. Today Select, Menu, and ListCore match `" "` before the typeahead ever sees it; `Typeahead.onKey` already handles a Space inside a non-empty buffer correctly.
- `InputHistory` keeps one draft (the text being written) and drops edits to recalled entries; its scaladoc documents that as a limitation.
- `PaneResize` (uni-core) owns the `dblclick` listener and writes the size itself, so Splitter's gated var falls back to its default `Drag`/`Pointer` cause. uni-core cannot depend on uni-ui's `ChangeCause`.

## Design

Lane: Standard — changes keyboard behavior in shared components and one public method signature.

- **Typeahead**: add `typeahead.searching` (a search typed within the timeout). Select, Menu, and ListCore (so VirtualList and Tree too) route Space to the typeahead while it is true, before their select/toggle/activate cases. Space is consumed even when nothing matches, so it never falls through to select.
  - Space with no search under way keeps today's behavior (select, toggle in multi-select, activate a menu item).
- **InputHistory**: keep an edit per recalled entry. Leaving an entry stores the field's text for it when it differs from the entry; returning shows the edit. `add` (submit) and `reset` drop all edits; `entries` never change from edits.
  - `next()` becomes `next(current: String)`, matching `previous(current)`, since it must capture the text being left. A cancelled recall (`onChange` cancels) rolls the edits back too.
- **Splitter**: `PaneResize` gains an `onDoubleClick` parameter, defaulting to `reset()`. Splitter passes one that resets under `ChangeCause(Reset, Pointer)`.
- Docs: drop the "edits are not kept" limitation from InputHistory's scaladoc and `docs/dom/behaviors.md`, mention Space-in-search in the typeahead docs, and update the uni skill's `references/ui.md` if it shows `next()`; bump the plugin version.
- Tests: Space during and outside a search in Select, ListCore, and Menu; per-entry edits kept across moves and dropped on submit or cancel; Splitter double-click reason.

## Alternatives and Why Not?

- **Overload `next()` and keep the old one.** Without the current text it cannot keep the edit, so the old form would silently stay lossy; the project prefers one clear signature.
- **Splitter adds its own `dblclick` listener.** PaneResize's listener still fires and writes a second time with the `Drag` reason.
- **Keep edits in `entries` after submit (Bash's default without `revert-all-at-newline`).** Rewrites history behind the user's back; zsh and most chat composers revert.
