# uni-ui overlay correctness

## Goals

- Close four known overlay limitations from #869 and #896 in one PR:
  1. The page behind a modal dialog stays interactive for pointers and agents.
  2. A Popover or Select opened inside a dialog stays `open` after the dialog closes.
  3. An overlay drifts away from an anchor that moves without scroll or resize (CSS transition, layout shift).
  4. An overlay closed before it reached `onMount` unmounts without its exit animation.

## Background

- Every overlay renders an `OverlaySurface` in its own `Portal.toBody` container, appended to `<body>` in opening order. A nested overlay's container always comes after its dialog's.
- `onMount` fires from a MutationObserver (a microtask after rendering). `close()` before that has no root element, so it calls `exit.done()` at once.
- Placement repositions on window `resize`, capture-phase `scroll`, and a `ResizeObserver` on the overlay itself; nothing watches the anchor.
- `Dismiss` keeps the stack of open overlays; it already lets a modal stop outside-press walks.
- UiAgent already treats `inert` on an element or any ancestor as hidden, so snapshot and act skip it. Making the background `inert` hides it from agents with no agent change.

## Design

Lane: Standard — changes overlay behavior that apps and agents can observe.

**1. Inert background under a modal**
- While a modal (Dialog, AlertDialog, CommandPalette) is open, the `<body>` children before the topmost modal's portal container get `inert`. Overlays opened later sit after it and stay live.
- Only elements uni-ui marked are restored; an app's own `inert` is left alone. Nested dialogs: only the topmost one's predecessors are inert, so the dialog underneath goes inert too.
- The Toaster region stays live, so a toast's close button and hover pause keep working under a dialog (see Q1).
- Tests: background buttons get `inert` and come back; UiAgent `snapshot()` omits background controls and `act` on a stale background ref is refused, while dialog controls stay reachable.

**2. Nested overlays close with their dialog**
- When a layer leaves the `Dismiss` stack, every layer above it whose trigger lies inside it is dismissed too, top first.
- Each goes through its own `onChange` / ChangeGate with a new `ChangeReason.ParentClose` ("parent-close"), keeping the source of the closing change. A handler that cancels keeps its `open` true; the overlay still unmounts with the dialog.
- Covers Popover, Select, Menu, and ContextMenu inside Dialog/AlertDialog, and a menu inside a popover.

**3. Follow a moving anchor**
- While an anchored overlay is open, a `requestAnimationFrame` loop reads the anchor rect each frame and repositions only when it changed. This replaces the `resize` and `scroll` listeners; the `ResizeObserver` on the overlay stays.
- The cost is one `getBoundingClientRect` per open anchored overlay per frame; browsers pause rAF in hidden tabs.

**4. Exit animation for an overlay closed in its opening frame**
- `close()` before `onMount` marks the surface as closing; `onMount` then plays the exit animation (no Dismiss layer, no focus move) and calls `exit.done`.
- If the element never reaches the document, `exit.done` still runs at once, as today.

**Docs**
- Remove the four limitations from `docs/dom/` and update `plugins/uni/skills/uni/references/ui.md` and `docs/guide/for-ai-agents.md` (inert background, `ParentClose`); bump the plugin version.

## Alternatives and Why Not?

- Native `<dialog>.showModal()` for inert: top-layer rendering would change Dialog's DOM and styling contract; out of scope.
- `aria-hidden` plus `pointer-events: none` instead of `inert`: two mechanisms that `inert` already covers, and agents already honor `inert`.
- Closing nested overlays by writing `open := false` directly: skips `onChange`, which #898 requires for every component-driven change.
- Floating UI style IntersectionObserver layout-shift detection: misses CSS transforms and costs much more code than a rAF check.
