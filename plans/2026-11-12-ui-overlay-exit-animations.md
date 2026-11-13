# Exit animations for uni-ui overlays

## Goals

- Popover, Menu, ContextMenu, Select, Tooltip, Dialog, AlertDialog, and Toast fade out on close instead of vanishing. Today they animate in (`uni-overlay-in`) but unmount at once, which reads as a glitch next to the entry animation (#869 known limitation).
- Closing must still *feel* instant to keyboard and screen-reader users: focus, the Escape stack, and outside-press handling all switch over on the close event, not after the animation.

## Background

- Every overlay renders `open.map(o => if o then Portal.toBody(OverlaySurface(...)) else empty)`, so `open := false` removes the DOM in the same tick.
- `OverlaySurface` owns the per-open lifecycle: placement listeners, the `Dismiss` stack layer, initial focus, and focus return (in `beforeUnmount`).
- uni-dom's `Transition` keeps its element mounted with `display: none`. That fits inline content but not overlays, which must leave the Dismiss stack, stop listening to scroll/resize, and drop out of `<body>`.
- Elements already carry `data-state="open"`; nothing ever writes `closed` on the overlay itself.

## Design

Lane: Standard — it changes when overlay DOM leaves the document, which app code and tests can observe.

- **Two phases on close.** When `open` turns false, the overlay first *closes* (instantly): it leaves the Dismiss stack, stops placement listeners, returns focus, becomes `inert`, and its elements switch to `data-state="closed"`. Then it *exits*: the DOM stays until the exit animation ends, and only then unmounts.
- **CSS drives the timing.** New `uni-overlay-out` / `uni-fade-out` keyframes run on `[data-state='closed']`, a bit shorter than the entry (about 0.1s). The script reads the computed `animation-duration` and waits for `animationend`, with a timeout of that duration plus a small margin as a safety net.
- **No animation means no wait.** With `prefers-reduced-motion`, an app that overrides the styles to `animation: none`, or jsdom in tests, the overlay unmounts in the same tick as today. Existing tests keep passing unchanged.
- **Reopen during exit** drops the exiting copy at once and mounts a fresh overlay, which plays the entry animation. No half-reversed states to reason about.
- **Toast:** `toasts` and `dismiss` keep their current meaning (gone from the list right away); the view keeps a dismissed toast on screen, marked closed, until its exit animation ends.
- **No public API change.** The mechanism is a `private[ui]` helper shared by the overlays; apps restyle exits with the same `data-state` selectors they use for entry.
- Tests cover: close-then-exit with a synthetic `animationend`, the timeout fallback, focus returning before the exit ends, reopen during exit, and a toast exiting after dismiss.

## Alternatives and Why Not?

- **Reuse uni-dom `Transition`.** Keeps the overlay mounted with `display: none` forever, so the Dismiss layer, listeners, and DOM would leak per overlay.
- **Clone the node into `<body>` on unmount and animate the clone.** Zero changes in the components, but the clone loses scroll position and live state, and it is a DOM trick that is hard to restyle.
- **Fixed JS timeout instead of reading CSS.** Couples script to stylesheet values; apps that change durations would get clipped or lingering exits.
