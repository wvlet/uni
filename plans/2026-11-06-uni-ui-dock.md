# uni-ui phase 3, PR B: Dock

## Goals

- Give uni-ui apps a Dock: panels in tab groups and splits that users rearrange by drag or keyboard, with a layout the app saves and restores.
- Move the uni-harness right dock onto it, so a user can split Terminal under Details and the layout survives reloads without breaking old saves.

## Background

- Direction comes from `plans/2026-11-06-uni-ui-collections-dock.md` ("Dock (PR B)", "Harness"); PR A (collections) merged as #870. `eachRx` (#873) and `Rx.split` (#876) are on main now.
- The harness dock is a `FoldablePane` with a `TabList` (Details, Activity, Terminal); `dockOpen`, `dockWidth`, and `dockTab` persist through `Storage.local` under `uni-harness.*`.
- `Splitter` resizes one side pane in pixels, horizontally only. A Dock split needs vertical splits and proportional sizes between siblings.
- `StorageCodec` covers primitives and parameterless enums. Probe: `derives Weaver` on a recursive layout type doesn't work today. A parameterized enum case encodes as its bare name and loses its fields, and a recursive sealed trait hangs the compiler.

## Design

Lane: **Standard**: new public API (components, data types, CSS classes) and a persisted layout format.

**User flow**
- An app registers panels (`DockPanel(id, title)(render)`) and gives `Dock` an `RxVar[DockLayout]`, usually `Storage.local("app.dock", default)`.
- The user drags a tab onto another group's tab strip (move or reorder), onto its center (move), or onto one of its four edges (split that group). A drop preview shows where the tab will land, and Escape cancels.
- Without a pointer, the user opens a tab's menu (right-click, Shift+F10, or the ContextMenu key) to get Move left/right, Move to next group, Split right, Split down, and Close.
- The user drags or arrow-keys a split border to resize. On reload the same layout comes back; a broken or newer-version save falls back to the app's default.

**Layout format**
- `DockLayout(version, root)`, where `root` is a tree of `DockNode.Split(direction: Row|Column, children, sizes)` and `DockNode.Group(panels, active)`. Panels are referenced only by string id.
- `sizes` are fractions that sum to 1. Flexbox applies them, so proportions hold when the window resizes.
- JSON uses an explicit `"version": 1` and a small hand-written `Weaver[DockNode]` (`{"split": ...}` / `{"group": ...}`), since derivation can't handle the recursion. Other fields use derived codecs.
- A `given StorageCodec[DockLayout]` goes through that JSON. Plain `Storage.local(key, default)` works, and a decode failure returns the default.
- Versioning: the decoder accepts versions up to the current one and migrates older ones. A newer or unreadable version decodes to `None`, so the app's default is used and the bad save is overwritten on the next change.

**Normalization** (pure, in `wvlet.uni.ui.base.DockModel`, run on load and after every operation)
- Unknown panel ids and duplicates are dropped. Empty groups disappear, single-child splits collapse into their child, and a split nested in the same direction flattens into its parent.
- `sizes` with the wrong length or non-positive values reset to equal shares. An `active` that is missing from its group becomes the first panel.
- A panel registered with `closable = false` (the default is closable) that is missing from the layout is appended to the first group. Closable missing panels stay hidden until `dock.open(id)`, which adds the panel to the focused group and activates it.
- An empty root is allowed and renders an empty state.

**Operations** (pure functions on `DockLayout`, each covered by tests)
- `move(panel, toGroup, index)`, `split(panel, targetGroup, side)`, `close(panel)`, `open(panel)`, `activate(panel)`, `resize(split, border, deltaPx, containerPx, minPx)`.
- Groups are addressed by their path in the tree. The component looks the path up again for each gesture, so paths never get stored.

**Rendering and keyboard**
- A Split renders as a flex row or column with a separator between siblings. A Group renders a tab strip (`each` keyed by panel id, roving focus via `RovingFocus`, `role="tablist"`/`tab`/`tabpanel`) and the active panel.
- Each panel's element is created once per id and cached by the Dock. Moving a panel re-mounts the same element, so the terminal's shell and scrollback survive a move as they survive a tab switch today.
- Split borders use a new internal separator (`role="separator"`, `aria-orientation`, `aria-valuenow` in percent) built on the same `PointerDrag` as `Splitter`. Arrows step 16px (64px with Shift), Home and End go to the min and max, and Enter resets to equal shares.
- `Splitter` stays as it is: it resizes one side pane in pixels. The Dock still sits inside a `FoldablePane` + `Splitter` in the harness.
- Minimum size: each child of a split stays at least `minPanelSize` px (default 120, configurable with `withMinPanelSize`). Resizing stops at the minimum and never takes space from other siblings.

**Drag and drop**
- Pointer events through `PointerDrag`, not HTML5 drag-and-drop. A drag starts after 4px of movement, so a click still selects.
- While dragging, the group under the pointer shows an overlay. The outer 25% band on each side means split, the center means move, and over a tab strip an insertion marker shows the index.
- Dropping a group's only tab onto that same group's edge does nothing.

**Harness**
- The right dock becomes a `Dock` inside the existing `FoldablePane`, with Details/Activity/Terminal as non-closable panels; ⌘⇧B still folds it.
- Migration: a new key `uni-harness.dockLayout`. When it's absent, the default layout is one group with all three panels and the old `dockTab` as active. `dockOpen` and `dockWidth` keep their keys, and `dockTab` is only read for that seed.
- The terminal bell badge shows when the Terminal panel isn't visible: the dock is folded, or Terminal isn't the active tab of its group.

**Tests and docs**
- `mount`/`fireEvent` tests cover normalization (unknown ids, empty groups, bad sizes), the operations (move, split, close/open, resize with minimums), JSON round-trip and version fallback, tab keyboard and menu, separator keys, and a pointer drag-to-split.
- Light and dark screenshot goldens of a Dock with a row split containing a column split.
- Updates `docs/dom/ui.md`, `docs/guide/for-ai-agents.md`, and the `uni` plugin skill (version bump, snippets compile), plus an ADR for the layout format.

## Alternatives and Why Not?

- **Reuse `Splitter` for split borders**: it is pixel-based, horizontal, and tied to one pane's width. Generalizing it would change a public API that apps already use.
- **HTML5 drag-and-drop**: its drag image and drop rules vary in Electron and Chromium, it can't be driven with `fireEvent`, and it gives no keyboard path anyway.
- **Pixel sizes in the layout**: they break when the window size changes between sessions; fractions plus a pixel minimum don't.
- **Fix `derives Weaver` for recursive sum types first**: worth doing separately, but it would block this PR on a Weaver change. The hand-written codec is about 30 lines and can be swapped for derivation later without changing the JSON.
- **Group ids in the format**: stable ids help a future "focus group N" API, but paths are enough now and keep the saved JSON minimal.
