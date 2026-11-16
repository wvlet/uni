# Dock: floating and detached panels

## Goals

- Let a user lift a panel out of the Dock's grid into a **floating** group that sits over the dock, moves by its title bar, and resizes, and put it back later. #877 listed this as a known gap.
- Let a user **detach** a panel into its own browser (or Electron) window, for a second monitor, and have it come back to the dock when that window closes.
- Keep old saves readable and the keyboard path complete, as the rest of the Dock does.

## Background

- The layout is plain data in an app-owned `RxVar[DockLayout]`, versioned JSON (now version 2), cleaned by `DockModel.normalize` (ADR 2026-11-06). Any format change needs a version bump and a migration.
- Groups are addressed by `DockPath` (`List[Int]` from the root), recomputed for every gesture and never saved.
- Panel content is created once and cached by id, so moving a panel keeps its state (the harness Terminal keeps its shell). A float or a detached window must reuse that cached element.
- uni-dom renders through the global `dom.document`: overlays (Menu, Popover, Tooltip, Dialog) mount in the main window's `body`. A detached window can host moved DOM nodes, but overlays opened inside it would show in the main window.
- The harness's Electron main process denies every `window.open` (`setWindowOpenHandler(() => ({ action: 'deny' }))`). Allowing a same-origin `about:blank` popup is a security-boundary change.

## Design

Lane: **Standard** — new user-facing behaviour, a saved-format change, and (for detach in the harness) an Electron security-boundary change.

### User flow

- **Float**: a tab's context menu has "Float". The panel becomes a floating group over the dock, near its old place, with its tab bar as the title bar.
- Drag the floating group's bar (outside the tabs) to move it, and its bottom-right corner to resize; it stays inside the dock's bounds. Clicking it brings it to the front.
- A float is a normal group: drag tabs into and out of it, split nothing (a float is one group). Its menu has "Dock" to put it back into the grid, in the first group.
- **Detach**: the menu has "Open in new window". The panel's content moves into a popup window titled with the panel's title; the dock shows the tab as detached, and clicking it focuses the popup.
- Closing the popup, or choosing "Dock" on the detached tab, moves the content back to the group it came from.

### Decisions

- `DockLayout` gains `floating: Seq[DockFloat]`, each a `DockNode.Group` plus a box (`x`, `y`, `width`, `height` as fractions of the dock, so it scales with the window). Format version 3; versions 1 and 2 load with no floats.
- Floats render in z-order (the last is on top), clamped so the bar always stays reachable after the dock shrinks. Normalization drops empty floats and fixes bad boxes.
- Detached panels are **not saved as detached**: a browser blocks reopening a popup without a user gesture, so on reload they are back in their group. The Dock keeps detached state at runtime and the layout just keeps the panel in its group.
- The popup is `window.open("", …)` same-origin; the Dock copies the page's stylesheets and theme attributes into it and moves the cached element there. Known limitation: menus and popovers opened *inside* a detached panel appear in the main window.
- Detach is opt-in per Dock (`withDetach(true)`, off by default), since a host may block popups. In the harness, Electron main allows only `about:blank` popups from its own renderer, with no node integration.
- Keyboard: "Float", "Dock", and "Open in new window" are context-menu items; on a float's bar, Alt+arrow keys move it and Alt+Shift+arrow keys resize it, in 16px steps.
- Tests cover the model (float, dock, z-order, normalize, v2→v3 load), the float drag/resize/keys in DockTest, and detach with a stubbed `window.open`. Goldens for a floating group; docs, for-ai-agents, the `uni` skill, and the ADR get updated.

## Alternatives and Why Not?

- **Floats in pixels relative to the viewport (`position: fixed`)**: lets a float leave a narrow dock, but escapes the app's layout and can't be clamped against the dock; pixels also break across window sizes (ADR).
- **Save detached panels and reopen them on load**: popup blockers reject `window.open` without a gesture.
- **Render the detached panel freshly in the popup (a second document renderer)**: loses the panel's state (a new Terminal shell), and uni-dom has no per-document renderer yet.
- **Split this into two PRs (float now, detach next)**: smaller reviews; detach carries the Electron change and the overlay limitation. Offered as an option.
