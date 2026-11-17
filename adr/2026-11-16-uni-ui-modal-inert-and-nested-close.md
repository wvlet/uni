# uni-ui: inert page under a modal, nested close, and anchor following

## Context

[2026-11-06-uni-ui-overlays](2026-11-06-uni-ui-overlays.md) and [2026-11-12-uni-ui-overlay-exit-animations](2026-11-12-uni-ui-overlay-exit-animations.md) left four gaps: the page behind a dialog stayed reachable by pointers and by `UiAgent`, overlays opened inside a dialog stayed open after it closed, anchored overlays drifted from a trigger moved by a CSS transition, and an overlay closed before `onMount` unmounted without an exit.

## Decision

- `ModalInert` sets `inert` on the `<body>` children before the topmost modal's portal container. Portals append in opening order, so overlays opened from the dialog come after it and stay live with no bookkeeping. It restores only the `inert` it set. Elements marked `data-uni-keep-live` (the Toaster region) stay live, so toasts can still be closed under a dialog.
- `UiAgent` already hides `inert` subtrees, so the agent reaches only the dialog without agent-side changes.
- When a `Dismiss` layer leaves the stack, it dismisses the layers above it that were opened from inside it (one of their `inside` elements lies inside it, checked transitively), top first, with `ChangeReason.ParentClose`. Each goes through its own `onChange`, so a handler can still cancel; the overlay then keeps `open = true` but unmounts with the dialog.
- An open anchored overlay compares the anchor rect and the viewport once per animation frame and repositions when either changes. This replaces the `resize` and `scroll` listeners; the `ResizeObserver` on the overlay stays.
- `close()` before `onMount` defers the exit to `onMount`, which plays it without joining the stack or moving focus.

## Alternatives

- **Native `<dialog>.showModal()`** for inert: changes Dialog's DOM and styling contract.
- **`aria-hidden` plus `pointer-events: none`**: two mechanisms that `inert` covers in one.
- **Writing `open := false` on nested overlays directly**: skips `onChange`, which [2026-11-13-uni-ui-change-details](2026-11-13-uni-ui-change-details.md) requires for component-driven changes.
- **IntersectionObserver layout-shift detection** (Floating UI's `autoUpdate`): misses transforms and is much more code than a per-frame rect check.

## Consequences

- Elements an app appends to `<body>` while a modal is open stay live, because they come after the dialog.
- Each open anchored overlay reads one bounding rect and the viewport size per frame; browsers pause this in hidden tabs.
