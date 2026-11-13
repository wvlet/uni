# Multi-select mode for VirtualList and DataTable

## Goals

- Let apps build a file-list style table or list where users select several rows: click, Shift+click for a range, Ctrl/Cmd+click to toggle, and the keyboard equivalents.
- Reuse the `Selection` model from #899 so the range, toggle, and `ChangeGate` reporting logic lives in one place.
- Keep every existing single-select caller compiling and behaving as today.

## Background

- There is no `ListBox` class. The listbox is `VirtualList` (`role="listbox"`). It shares `ListCore` with `DataTable` (`role="grid"`) and `Tree`.
- `ListCore` takes `selected: RxVar[Option[K]]`. Focus stays on the container, and `aria-activedescendant` names the active row. Arrows move only the highlight; Enter, Space, or a click select the row and run `onSelect`.
- `Selection` already does Shift range from an anchor, Ctrl/Cmd toggle, Ctrl/Cmd+A, and Escape, and writes through `ChangeGate` with the key `selected`.
- The behaviors ADR expected that a `multiple` mode would change the `selected: RxVar[Option[K]]` API. This note proposes an additive API instead, so that API stays as it is.

## Design

Lane: Standard. This adds new public API and changes keyboard behavior in two components.

User flow:
- The app passes `withMultiSelect(selectedKeys: RxVar[Set[K]])` to a `VirtualList` or `DataTable`. Calling `noMultiSelect()` returns to single select.
- A click selects one row. Shift+click selects the range from the anchor, and Ctrl/Cmd+click adds or removes a row.
- Arrows move the highlight only, as today. Shift+Arrow (and Shift+Home/End/PageUp/PageDown) moves it and extends the range from the anchor.
- Space toggles the highlighted row, and Shift+Space extends the range to it. Ctrl/Cmd+A selects all rows and Escape clears the selection, both through `Selection.onKey`.
- Enter acts as a plain press: it selects the row alone and runs `onSelect`, so "open on Enter" still works.
- Selected rows show `aria-selected="true"`, and the container gets `aria-multiselectable="true"`. The app reads `selectedKeys` and sees each change in `onChange` (key `selected`, value a `Set[K]`), where it can cancel the change.

Decisions:
- **Additive API.** `withSelected(RxVar[Option[K]])` and its type stay unchanged. Multi mode is a separate `RxVar[Set[K]]`, so no existing caller breaks.
- In multi mode the single `selected` variable is not written. The two variables are separate modes, not mirrors of each other.
- `onSelect` runs on Enter and on a plain click only. Modifier presses, Space, and Shift+Arrow change only the selection; the app reads them from `selectedKeys` or `onChange`.
- `ListCore` takes a selection-mode value (`Single(RxVar[Option[K]])` or `Multi(Selection[K])`) instead of two optional parameters. It stays `private[ui]`.
- A key in the selection that disappears from `items` stays in the set. The app owns the set, and filtering rows shouldn't silently drop the user's selection.
- `Tree` is out of scope. Multi-select of a hierarchy (does selecting a parent select its children?) needs its own design.
- The docs pages (`docs/dom/ui.md`, `docs/dom/behaviors.md`), the skill's `references/ui.md` (with a plugin version bump), and `for-ai-agents.md` gain `withMultiSelect`. The behaviors ADR's consequence line is updated, and the stale `ListBox` names become `VirtualList`.
- Tests cover click, Shift/Ctrl click, Shift+Arrow, Space, Ctrl+A, Escape, a canceled change, and the ARIA attributes, for both components.

## Alternatives and Why Not?

- **Change `selected` to `RxVar[Set[K]]` everywhere.** This breaks every caller, and single-select apps would have to wrap and unwrap a one-element set.
- **A public `SelectionMode` parameter on each component.** It is more general, but apps would see a new ADT for what one `with` method covers.
- **Separate `MultiSelectList` and `MultiSelectTable` components.** They would duplicate the column, sort, and windowing config.
- **Also write `selected` as the "primary" row in multi mode.** Two variables that both change on one press are harder to reason about; `Selection.anchor` already marks the range start.
