# uni-ui overlays close in two steps: close at once, then unmount after a CSS-timed exit

## Context

uni-ui overlays (Popover, Menu, ContextMenu, Select, Tooltip, Dialog, AlertDialog, CommandPalette, toasts) faded in with `uni-overlay-in` but were removed in the same tick as `open := false`, so closing looked like a glitch. An exit animation means the DOM must outlive the open state. Focus, the `Dismiss` stack, and outside-press handling still have to switch over at once, so keyboard and screen-reader users never wait on an animation.

## Decision

- `Presence(open)(view)` (`private[ui]`) renders `view` when `open` turns true and keeps it after `open` turns false until the view calls `Exit.done`. Each open gets a new generation, so reopening during an exit drops the exiting copy and mounts a fresh overlay that plays its entry animation. Overlays don't reverse partway through an animation.
- `OverlaySurface` closes on the `open` change, not on unmount. It leaves the `Dismiss` stack, stops placement listeners, and returns focus. Then `ExitAnimation.run` sets `inert` and `data-state="closed"` on the root and its direct children that have a `data-state` (a dialog's backdrop and panel), and calls `done` on `animationend`.
- Exit timing comes from the computed `animation-duration` and `animation-delay` after the state change, plus a 50ms timeout as a safety net. Apps restyle or disable exits with the same `[data-state]` selectors they use for entry. With no animation (`prefers-reduced-motion`, `animation: none`, or a DOM without uni-ui.css), `done` runs synchronously, so behaviour and tests without the stylesheet are unchanged.
- The closed rules use `:is(...)[data-state='closed']`, which outranks each component's single-class entry `animation`. Closed overlays get `pointer-events: none`, so an exit never blocks the next click.
- Toaster keeps `toasts`/`dismiss` semantics (a dismissed toast leaves the list at once). A separate on-screen list keeps it in the view until its exit ends. The exit starts before the list update, so with no animation the toast is already gone when `toasts` drops it.

## Alternatives

- **uni-dom `Transition`**: keeps its element mounted with `display: none`, so the `Dismiss` layer, listeners, and DOM would never be released.
- **Clone the node into `<body>` on unmount and animate the clone**: needs no component changes, but the clone loses scroll position and live state, and it can't be styled as the real element.
- **A fixed JS timeout**: ties the script to stylesheet values, so apps that change durations would get exits cut short or left hanging.

## Consequences

- An overlay closed in the same frame it opened (before `onMount`) unmounts without an exit.
- An overlay portaled from inside a closing dialog (a Popover or Select in its body) isn't closed by the dialog's close. It stays visible during the dialog's exit and unmounts with it.
- Tests that load uni-ui.css and assert that an overlay is gone right after closing must wait for the exit or check `data-state="closed"` instead.
