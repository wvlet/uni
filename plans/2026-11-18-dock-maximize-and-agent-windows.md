# Dock maximize, and UiAgent in detached Dock windows

## Goals

- **Maximize:** let the user make one Dock group fill the whole Dock and restore it later, the way an editor's "maximize panel" works. #877 listed "no maximize" as a known limitation.
- **Detached windows for agents:** an agent driving the app (WebMCP, or the uni-harness bridge from #954) should see and act on the components in a panel that the user moved into its own window (#917). Today that panel is invisible to agents.

## Background

- Layout changes already go through one path. `Dock.setLayout` writes `layout` through `ChangeGate` with a `ChangeCause`, so `onChange` can cancel, and `UiHistory` undoes the whole `DockLayout` (#933). Any new state kept in `DockLayout` gets cancel, undo, and persistence for free.
- `DockLayout` is saved JSON with a `"version"` (now 3, which added `floating`). Paths are never saved, only panel ids (ADR `2026-11-06-uni-ui-dock-layout`).
- Agents can't see the Dock. It registers only as a state owner (`AgentRegistry.own`), with no agent binding. The UiAgent ADR left "Dock" as a follow-up, and the undo ADR noted that giving it a binding "is a separate decision". So "reachable by UiAgent" means adding the Dock's first agent binding.
- A detached panel renders into the popup's `document`, from the main window's JS. Its components register in the same global `AgentRegistry`, so `changes()` already reports their edits. The gaps:
  - `snapshot` walks only `root()` (the main `body` by default).
  - `act` and `find` require `root().contains(el)`.
  - `AgentDom.labelOf` resolves `aria-labelledby` with `dom.document.getElementById`, so labels inside a popup come out empty. The labels in `changes()` have the same problem.

## Design

Lane: Standard. Maximize is new user-facing behaviour. Two always-gate areas need your sign-off: it adds a field to the saved layout (storage format), and both items change what agents see (agent tool surface).

**One PR or two:** one PR with two commits. Both items touch `Dock` and the agent layer, and both need the same docs, the same `ui.md` skill digest, one plugin version bump, and one uni-harness check over CDP. The second item is about 50 lines. Each commit stands on its own, so reverting one is easy.

### Maximize: the user flow

- The user is working in a crowded Dock, wants one group (an editor or a terminal) to fill the space, then wants the old arrangement back.
- **Enter:** each grid group's tab bar ends with a maximize toggle, placed before `withActions` controls. The tab's context menu also has "Maximize group". The toggle is a focusable button, so Tab plus Enter or Space works without a pointer, and so does Shift+F10 on a tab. The toggle doesn't show while the Dock has only one group.
- **Floats:** a floating group has no toggle, since it can already be resized to any size. Its menu's "Dock" item comes first, and a group can be maximized once it is back in the grid.
- **While maximized:** the group fills the Dock, and the other groups and floats are hidden (`hidden`, so screen readers and agents skip them too). The group's tabs still switch with the arrow keys. The toggle shows "Restore", and the dock and group get `data-maximized="true"`.
- **Leave:** the toggle, the "Restore group" menu item, or undo, since the change is a layout change. Escape doesn't restore, because terminals, editors, and inline dialogs in the panel need it.
- **Auto-restore:** an action that has to show something outside the group restores first, so nothing changes out of sight:
  - `open`, `focusPanel`, or `focusGroup` (Ctrl+1, Ctrl+2) for a panel or group outside it
  - moving a tab out of the group, split, Float, or Dock
  - closing the group's last tab
- Everything else keeps it, including switching tabs and reordering them with Alt+Arrow inside the group. Every `DockModel` operation carries `maximized` through, and a test covers a reorder while maximized.
- **RTL:** the toggle sits at the inline end of the tab bar (CSS logical properties), so it moves to the left in an RTL layout. No arrow-key meaning changes.

### Maximize: decisions

- The state lives in the layout as `DockLayout.maximized: Option[String]`: a panel id, meaning "the group that holds this panel". It is not a path, since paths are never saved. `normalize` clears it when the panel leaves the layout.
- Every maximize and restore goes through `setLayout`, so `onChange` sees key `layout`. The `ChangeReason`s are new `Maximize` and `Restore`, and the `ChangeSource` is pointer, keyboard, imperative, or agent. Each one is a single `UiHistory` entry.
- The saved format stays at version 3 and gains an optional `"maximized":"editor"` field, left out when empty. Today's decoder ignores unknown keys, so an older app still loads a newer save (just not maximized). Bumping to version 4 would make a downgraded app throw the whole saved layout away. The ADR's "every format change needs a new version" gets an addendum: a new optional field that old readers can safely ignore doesn't need one.
- The hidden groups stay mounted, so their content keeps its state (scroll position, terminal buffer). Toggling doesn't rebuild the DOM, because `DockModel.shape` leaves `maximized` out of the structure it compares.
- Public API, named-argument style as in #913:
  - `DockLayout#maximize(panel)` and `DockLayout#restore`, and the same as `DockModel` functions
  - `Dock#maximize(panel): Boolean`, `Dock#restore(): Unit`, `Dock#maximized: Rx[Option[String]]`
- **Agent binding:** the Dock gets a small binding, kind `dock` and ref `dock:<label>`.
  - Its value is the maximized panel, if any.
  - Its actions are `maximize` (a choice of the panel titles in the layout) and `restore`.
  - Panel components nest under it in the snapshot. The binding takes over the Dock's state-owner role (`states = Seq(layout)`) and replaces `AgentRegistry.own`, so `UiHistory` sees one owner. Other Dock operations (move, split, float) stay off the agent surface for now.

### UiAgent in detached windows: decisions

- While a panel is detached, the Dock registers the popup's body as an extra agent root, owned by the Dock element. It unregisters when the window closes or the panel reattaches.
- `snapshot` walks a window's root as more children of its Dock's `dock` node, after the in-page panels. An agent can tell which Dock a window belongs to, and a `UiAgent.install(root = ...)` scoped to another part of the page never sees another Dock's windows.
- `act` and `find` accept an element under any of those roots. Hidden, secret, and confirm markers work the same as in the main page.
- `labelOf` resolves `aria-labelledby` in the element's own `ownerDocument`. This fixes labels in `snapshot`, `query`, and `changes()` for detached panels.
- `changes()` needs no other change, because the registry is already shared.
- A confirmation for an `act` on a detached panel (`UiAgent.confirm`) still opens in the main window, as menus and popovers from a window do today. The main window comes to the front first so the user sees it, and the docs say so.
- Tests cover: maximize, restore, and auto-restore in `DockModel`, v3→v4 decoding, cancel and undo through `onChange` and `UiHistory`, the Dock agent binding, and snapshot, act, and labels on a detached panel (with a fake popup document). The uni-harness check runs over CDP and Playwright: maximize and restore by pointer and keyboard, and `snapshot` and `act` on a detached panel through the bridge.

## Alternatives and Why Not?

- **Maximize as runtime state (like detached windows):** simpler, with no format bump. But the request asks for it to persist with the layout, and unlike `window.open`, nothing blocks restoring it on load.
- **Store the maximized group as a path:** paths change with every move and are never saved by design.
- **Reach maximize through the toggle as an agent-visible `Button`:** that needs no Dock binding, but agents would see N anonymous "Maximize" buttons without the tabs that give them meaning.
- **Nest a detached panel's nodes at its tab's place in the main tree:** the placeholder renders only while that tab is active, but the window stays visible either way.
- **Version 4 for `maximized`:** a downgraded app would lose its saved layout over a field it could have ignored.
- **Escape to restore:** it would collide with Escape in panel content.
- **Two PRs:** cleaner history, but twice the docs, plugin bumps, and harness runs for a ~50-line second change.
