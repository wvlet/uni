# uni-ui phase 2: overlays

## Goals

- Give uni-ui apps the overlay components of a desktop UI: Popover, Tooltip, Menu, ContextMenu,
  Dialog, AlertDialog, Select, and Toast, styled by `uni-ui.css` like phase 1.
- Put the shared behavior (anchored placement, focus trap and return, dismissal) in
  `wvlet.uni.ui.base`, so every overlay behaves the same and apps can build their own.
- Replace uni-harness's CSS-only `data-tip` tooltips, which never show on keyboard focus and are
  invisible to screen readers.

## Background

- uni-dom already has `Portal.toBody`, `ClickOutside`, and `Focus.trap`/`Focus.saveAndRestore`.
- uni-dom's `FocusTrap` attaches its Tab handler with an `onkeydown` attribute, which an app's own
  `onkeydown` on the same element replaces. That breaks the rule of
  `adr/2026-11-04-uni-dom-helper-listeners.md`.
- `ClickOutside` treats a click on the trigger as "outside" once the content is portaled, so a
  toggle button would close and then reopen the overlay on the same click.
- The harness uses `data-tip` on 6 icon buttons (rail, pane headers, terminal). Its Theme button
  cycles through three schemes on each click, which a small menu would show more clearly.

## Design

Lane: **Standard**, because it adds public API (components, class names, `data-state` values).

- **One PR** ships `base`, all eight components, the harness tooltip migration, and a harness Theme
  menu (chosen over a two-PR split at the design gate).
- **User flow (app author):** hold the open state in an `RxVar[Boolean]` and wrap the trigger, as in
  `Popover(open)(Button("More"))(content)`. The component wires `aria-expanded`/`aria-controls` and
  the toggle onto the trigger. Escape, an outside click, or `open := false` closes it, and focus goes
  back to the trigger.
- **`base.Anchored`**: a pure placement function from anchor rect, overlay size, and viewport to a
  position and final side. It flips to the opposite side when the overlay doesn't fit, then shifts
  along the edge to stay inside the viewport. The overlay is `position: fixed`, placed on open and
  again on window resize or scroll. A pure function can be unit-tested without layout.
- **`base.Dismiss`**: Escape and outside-pointerdown dismissal that treats the anchor as inside. A
  shared overlay stack makes Escape close only the topmost overlay, so a popover inside a dialog
  closes first.
- **Focus**: fix uni-dom's `FocusTrap` to use `EventListenerBinding` and reuse it; add focus return
  to the element that was focused before opening. Dialogs trap focus; popovers and menus don't.
- **Rendering**: overlay content goes through `Portal.toBody`, so pane `overflow: hidden` and
  stacking contexts don't clip it. Layering uses `--uni-z-*` tokens (popover, dialog, toast).
- **Components:**
  - `Popover`: non-modal, `role="dialog"` content placed next to the trigger, with side and align
    options.
  - `Tooltip`: shows on hover after a delay and immediately on keyboard focus, hides on Escape or
    blur, and links the trigger by `aria-describedby`. Text only.
  - `Dialog`: modal with a backdrop, `aria-modal`, title and description wired by
    `aria-labelledby`/`aria-describedby`, focus trap, and Escape/backdrop close (backdrop close can
    be turned off with `noDismissOnBackdrop()`).
  - `AlertDialog`: a `role="alertdialog"` Dialog with Cancel and confirm actions. It focuses Cancel
    first and never closes on a backdrop click.
  - `Menu`: roving focus, typeahead, `menuitem`s and separators; it closes on select.
  - `ContextMenu`: a Menu that opens at the pointer on `contextmenu`.
  - `Select`: a button plus a `listbox` popup, with options rendered through `each`.
  - `Toast`: an `aria-live` region with timed auto-dismiss that pauses on hover.
- **CSS**: classes `uni-popover`, `uni-tooltip`, `uni-dialog`, `uni-menu`, `uni-select`, `uni-toast`, and so on; open state in
  `data-state="open|closed"` and the final side in `data-side`. Enter and exit transitions honor
  `prefers-reduced-motion`.
- **Tests** in headless Chromium cover placement math, Escape and outside-click dismissal, stacked
  Escape, focus trap and return, and ARIA wiring.
- **Docs**: overlays section in `docs/dom/ui.md`, entries in `docs/guide/for-ai-agents.md` and the
  `uni` plugin skill (version bump).

## Alternatives and Why Not?

- **Native `<dialog>.showModal()` and the `popover` attribute**: they give the top layer and inert
  backgrounds for free, but `showModal` and Escape handling are hard to drive from `Rx` state and
  `fireEvent`. Worth revisiting later as an implementation detail behind the same API.
- **CSS anchor positioning** (`anchor-name`, `position-try`): it works in Electron's Chromium, but
  Firefox doesn't support it yet, and uni-ui also targets browsers.
- **Floating UI (JS library)**: a solid choice, but it adds an npm dependency to every uni-ui app for
  roughly 100 lines of flip-and-shift logic.
- **Two PRs** (base, Popover, Tooltip, Dialogs first; then Menu, Select, Toast): smaller reviews, but
  the user preferred one PR.
