# uni-ui overlays share one behavior layer: own placement, a Dismiss stack, and Portal rendering

## Context

uni-ui phase 2 adds Popover, Tooltip, Menu, ContextMenu, Dialog, AlertDialog, Select, and Toaster. Each needs the same behavior: placement next to a trigger, closing on Escape or an outside press, focus moved in and back, and rendering outside panes that clip with `overflow: hidden`. uni-dom already had `Portal`, `ClickOutside`, and `Focus.trap`, but `ClickOutside` counts a press on the trigger as outside once the content is portaled, and it knows nothing about nested overlays.

## Decision

- `wvlet.uni.ui.base` holds the shared pieces: `Anchored.place` (a pure flip-then-shift function over rectangles), `Dismiss` (one stack of open overlays), `FocusTrap`, `FocusReturn`, and `Ids`. The components build on an internal `OverlaySurface` that ties them together.
- Placement is our own code, measured with `offsetWidth`/`offsetHeight` (not the bounding box, which an enter animation scales), written as `position: fixed` with `data-side`, and repeated on resize, scroll (capture), and a `ResizeObserver` on the overlay.
- `Dismiss` installs one `keydown` and one capture-phase `pointerdown` listener on `document` while any overlay is open. Escape closes only the top overlay and is skipped when already `defaultPrevented` or during IME composition. A press walks the stack from the top, closing non-modal overlays until one contains the target; a modal overlay stops the walk. Each layer says which elements count as inside, so the trigger toggles instead of closing and reopening.
- Tooltips stay off the stack. Their trigger's own listeners hide them, so a tooltip shown by hover never takes Escape from an open menu.
- Overlays render through `Portal.toBody`, not native `<dialog>.showModal()` or the `popover` attribute, so open state stays an `RxVar[Boolean]` and tests drive everything with `fireEvent`.
- An overlay inside a modal dialog lives at the end of `<body>`, outside the dialog's trap. When Tab closes a menu or a select's list, `FocusTrap.continueTab` runs the enclosing dialog's trap after focus returns to the trigger, so Tab can't escape the modal.
- Choosing a menu item or option closes the overlay (returning focus) before running the action, so a dialog opened by the action saves the trigger, not a removed item, as its focus-return target.
- uni-dom fixes this relies on: `RxElement.add` forwards `beforeRender`, `onMount`, and `beforeUnmount` to the wrapped element, and an `RxElement` that renders no node in place (only a `Portal`) is mounted without touching its siblings.

## Alternatives

- **CSS anchor positioning** (`anchor-name`, `position-try`): works in Electron's Chromium, but Firefox lacks it and uni-ui targets browsers too.
- **Floating UI**: a solid library, but it adds an npm dependency to every uni-ui app for about a hundred lines of flip-and-shift logic.
- **Native `<dialog>` and `popover`**: they give the top layer and an inert background for free, but their open state and Escape handling live in the browser rather than in `Rx`, and `fireEvent` can't trigger their cancel events. They could later replace `Portal` behind the same API.
- **Extending `ClickOutside`** with an ignore list: still no stack, so nested overlays would all close on one press.

## Consequences

- New overlays should use `Dismiss` and `FocusReturn` (or `OverlaySurface` inside uni-ui) rather than `ClickOutside`, so nesting keeps working.
- The page behind a modal dialog isn't made `inert`; the Tab trap and `aria-modal` cover keyboard and screen-reader users.
- Overlays unmounted immediately on close, with no exit animations. [2026-11-12-uni-ui-overlay-exit-animations](2026-11-12-uni-ui-overlay-exit-animations.md) added them.
- `data-state`, `data-side`, `data-active`, and the `uni-popover`/`uni-menu`/`uni-listbox`/`uni-dialog`/`uni-tooltip`/`uni-toast` class names are public API.
