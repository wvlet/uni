# `checkPattern` checks widgets against WAI-ARIA patterns in the DOM testing kit

## Context

uni-ui components set ARIA roles, states and keyboard handling, but tests only spot-checked a few attributes. Nothing verified that `aria-controls` resolves, that a tablist has one selected tab, or that the arrow keys work. Apps that build widgets from the behaviors catalog had no way to check them either. UI SDK ships `checkPattern(root, "tabs")` for this (step 4 of `plans/2026-11-13-ui-sdk-learnings.md`).

## Decision

- `checkPattern(root, pattern, keyboard = true): Rx[Seq[AriaViolation]]` lives in `wvlet.uni.dom.testing` in `uni-core` (JS). It reads the DOM only, so it knows nothing about uni-ui, and apps can run it without depending on uni-ui.
- It returns violations (`rule` such as `tabs/one-selected`, element description, message) rather than throwing, so a test asserts `.map(_ shouldBe empty)` and sees every violation at once.
- It returns `Rx`, not `Seq`. Overlays focus their first item and join the `Dismiss` stack in `onMount`, a step after render, so the keyboard checks `settle()` between keys. A test returns the `Rx`, as with `settle()`.
- Structure checks run first and have no side effects. Then keyboard checks press the APG keys through `fireEvent`. They stop at the first keyboard violation, because later keys would start from the wrong place. `keyboard = false` skips them.
- Two rules are stricter than the APG. Every widget except a menu needs an accessible name (a menu takes its name from its trigger). Every id reference (`aria-controls`, `aria-labelledby`, `aria-activedescendant`, ...) must point to an element in the document.
- Optional APG behaviour is accepted either way: wrapping at list ends, and skipping or focusing disabled menu items.
- `Menu` checks that Escape closes the menu, but not where the focus goes. A menu opened with `open := true` correctly gives focus back to the element that had it. `MenuButton` opens from its trigger, so it checks that the focus returns to the trigger.
- For a native `<button>`, `MenuButton` uses `click()` in place of Enter and Space: a synthetic keydown doesn't activate a native button.
- uni-ui triggers (`Menu`, `Select`, `Popover`) set `aria-controls` only while their popup is open, so a closed trigger has no dangling reference. A `Select` without a label names its listbox after its trigger.

## Alternatives

- **Wrap axe-core**: adds a JS dependency to the test kit, and axe checks generic rules, not per-pattern keyboard contracts.
- **Throw on the first violation**: hides the rest.
- **A separate `checkKeyboard`**: two calls for one question; the `keyboard` flag covers the structure-only case.
- **Synchronous checks that skip overlay keys**: would leave the menu button's open/Escape contract unchecked.

## Consequences

- Keyboard checks change widget state (a dialog closes on Escape), so `checkPattern` goes last in a test.
- `uni-ui`'s `PatternTest` runs every pattern component through it. A new component that implements an APG pattern should be added there.
