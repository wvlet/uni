# `checkPattern`: WAI-ARIA pattern checks in the DOM testing kit

## Goals

- Let a test assert that a widget follows its WAI-ARIA APG pattern: `checkPattern(root, AriaPattern.Tabs).map(_ shouldBe empty)`.
- Run it on every uni-ui component that implements a pattern, so ARIA regressions fail CI instead of reaching screen-reader users.
- Ship it to apps, so a custom widget built from the behaviors catalog (#899) can be checked the same way.

## Background

- Step 4 of `plans/2026-11-13-ui-sdk-learnings.md`; UI SDK's `checkPattern(root, "tabs")` checks against the APG and is stricter in places.
- uni-ui already emits roles, `aria-*`, `data-slot` and `data-state`, but tests only spot-check a few (`[role=alertdialog]` exists). Nothing verifies that `aria-controls` resolves, that a tablist has exactly one selected tab, or that arrow keys work.
- The testing kit lives in `uni-core` (JS) so `uni-test` can ship `DomTestSupport` without depending on uni-ui. `checkPattern` must stay there too: it reads the DOM only and knows nothing about uni-ui classes.

## Design

Lane: Standard — a new public testing API (user-specified).

**User flow:** a developer mounts a widget, opens it if needed (`open := true`), calls `checkPattern(element, AriaPattern.X)`, and gets back a list of violations. An empty list means the widget conforms; each violation names the rule, the offending element (short selector such as `div[role=tab]#t-2`) and what to fix.

- **API:** `checkPattern(root, pattern, keyboard = true): Rx[Seq[AriaViolation]]` in `wvlet.uni.dom.testing`, next to `fireEvent`. It returns data rather than throwing, so tests assert `shouldBe empty` or filter by rule.
- **Why `Rx`:** overlays set initial focus and register Escape handling in `onMount`, a step after render, so the keyboard checks `settle()` between keys; a test returns the `Rx`, as with `settle()`.
- **`AriaViolation`:** `rule` (stable id such as `tabs/one-selected`), `element` description, `message`. `toString` prints one line, so a failing `shouldBe empty` reads as a checklist.
- **Two kinds of checks, one call:**
  - *Structure* (side-effect free): required roles and owned roles, required states (`aria-selected`, `aria-expanded`, `aria-level`…), id references (`aria-controls`, `aria-labelledby`, `aria-activedescendant`) resolve to existing elements, accessible name present, focus model is consistent (exactly one tabbable item for roving focus, or a valid `aria-activedescendant`).
  - *Keyboard contract*: dispatches the APG keys with `fireEvent` and checks focus/active-descendant and state moved as the APG says (Arrow keys, Home/End, Escape closes a dialog/menu). These change widget state, so they run after the structure checks and only when `keyboard = true` (default `true`; pass `false` for a snapshot-only check).
- **Patterns (the ones uni-ui ships):** `Tabs`, `Menu`, `MenuButton`, `Listbox`, `Combobox`, `Dialog`, `AlertDialog`, `Tree`, `Grid`, `Tooltip`. Each is a small rule set in one file; no general ARIA engine.
- **Stricter than the APG in two places (as UI SDK does):** every composite widget (except a menu, which its trigger labels) must have an accessible name, and every id reference must resolve. APG "may" items are not checked.
- **uni-ui tests:** one `PatternTest` mounts each component in its open state and runs `checkPattern`. Real a11y bugs it finds in uni-ui are fixed in this PR when small (for example a missing label default); larger ones are listed in the PR and filed as follow-ups.
- **Docs:** a "Checking ARIA patterns" section in `docs/dom/` testing docs and the `ui.md` skill reference Testing section (compiled by `docSnippetsJS`), `for-ai-agents.md` entry, plugin version bump.

## Alternatives and Why Not?

- **Wrap axe-core.** A JS dependency in the test kit, and axe checks generic rules, not per-pattern keyboard contracts.
- **Throw on the first violation (`assertPattern`).** Hides the other violations; a returned list composes with `shouldBe empty` just as easily.
- **Separate `checkKeyboard` call.** Two calls for one question; a `keyboard = false` flag covers the snapshot-only case.
- **Put it in uni-ui.** Apps checking their own widgets would then depend on uni-ui, and the checks need only the DOM.
