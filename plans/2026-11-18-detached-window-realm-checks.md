# Make uni-dom and uni-ui work inside a detached Dock window

## Goals

- A Dock panel detached into its own window (#960) should behave like it does in the main window: Tooltip, Menu, InputHistory, and Alt+Arrow reordering included.
- Replace the duck typing #960 added for this in `Change.scala` with one shared helper, so the fix doesn't need to be redone at each site.

## Background

- A detached panel lives in a `window.open` popup on the same origin. The popup is a separate JS realm with its own `KeyboardEvent`, `HTMLElement`, and other classes, and its own `document`.
- Three kinds of code fail there, all silently:
  - **Type checks.** Scala.js compiles `case e: dom.KeyboardEvent` and `isInstanceOf[dom.X]` to `instanceof` against the main window's class. Popup events always fail it. So do the popup's own `<html>`, `<body>`, and the Dock host `div` (reached by parent walks). In Firefox, so do rendered nodes once they are adopted into the popup. The grep finds about 80 such checks across uni-dom and uni-ui.
  - **Main-document reads.** `dom.document.activeElement`, `.contains`, and `.body` never see the popup. Menu's arrow keys and typeahead, Reorder keeping focus on the moved item, FocusReturn, and Dismiss's `escapeFromInside` all depend on them.
  - **Main-window placement.** Tooltip and Menu portal into the main `<body>`. `Dismiss` listens for Escape and outside presses on the main `document`. Placement reads the main viewport and runs on the main window's `requestAnimationFrame`, which Chromium pauses when that window is minimized or hidden.

## Design

- Lane: **Express**. This is an internal fix: no public API changes, and nothing changes in the main window. The only behaviour change is that a detached window works like the main window. No always-gate area is touched.
- **Realm-safe type checks.** A new `private[uni]` object in `wvlet.uni.dom` has one pattern extractor per DOM type used, for example `case AnyRealm.KeyboardEvent(e) =>`.
  - It tries `instanceof` against the main window first, so the common case costs the same as now. Only if that fails does it check the value's prototype chain by constructor name, which also gets subclasses right (a `PointerEvent` is a `MouseEvent`).
  - uni-ui reaches it through the shared `wvlet.uni` package root. `ChangeSource.isKeyboard`/`isMouse` and `dragCancel` in `Change.scala` switch to it.
- **Which checks change.** Every check that can see popup content: events, event targets, `activeElement`, parent walks, and nodes rendered into a panel (so Firefox works too). Checks that only see main-window values stay as they are, for example the command palette, toasts, and the renderer's checks on the node it just created. The PR lists both groups.
- **The document comes from the node.** Code that reads the focused element, containment, or `<body>` uses the node's own `ownerDocument` (or the event target's) instead of `dom.document`. This covers Menu, Reorder, FocusScope/FocusReturn, and Dismiss.
- **Overlays open in their trigger's window.**
  - Tooltip and Menu portal into their trigger's `<body>` through the existing `Portal.toElement`. The public `Portal.toBody` is unchanged.
  - `Dismiss` installs its listeners on each document that has an open layer.
  - Placement, its frame loop, and Reorder's autoscroll use the anchor's own window.
  - Reorder's move announcements go to a live region in the list's own document.
- **Left as is (stated in the PR):** `Keyboard`'s global shortcuts and `Portal.to(id)`/`ClickOutside` in uni-dom still listen on the main window only.
- **ADR.** A new ADR records the rule "realm-safe checks and the node's own document" and updates what `2026-11-06-uni-ui-overlays` says about `Dismiss` listening on `document`.
- **Tests.**
  - A headless-Chromium test (uni-ui's existing environment) renders components into an iframe and drives InputHistory, Reorder (Alt+Arrow), Menu, and Tooltip with events built from the iframe's own constructors.
  - The uni-harness Dock gets a detachable panel with a Menu, an InputHistory field, a Reorder list, and a Tooltip. I'll detach it and drive it over CDP with Playwright, never with OS-level input.

## Alternatives and Why Not?

- **Duck typing at each site, as #960 did**: about 80 hand-written field probes, each a guess at which fields identify the type, and subclasses (`PointerEvent` is a `MouseEvent`) handled by hand.
- **Check against the popup's constructors** (`win.KeyboardEvent`): each site would have to know which window the value came from.
- **Make `Portal.toBody` follow the portal's document for everyone**: a public change in uni-dom's semantics. It also can't always be decided at render time, because a subtree is built before it is mounted.
- **Realm checks only, overlays later**: Tooltip and Menu, which the request asks to verify, would still open in the main window.
