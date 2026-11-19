# uni-ui Dock keeps its layout as versioned plain data, cleaned up on load

## Context

uni-ui phase 3 adds a Dock: panels in tab groups and splits that the user drags around, with a layout the app saves (uni-harness keeps it in localStorage). A saved layout outlives the code that wrote it. Panels get renamed or removed, and the format may change, so an old save must never break the app. `derives Weaver` doesn't handle a recursive layout type yet: a parameterized enum case encodes as its bare name, and a recursive sealed trait hung the compiler in a probe.

## Decision

- The layout is immutable data in an app-owned `RxVar[DockLayout]`: a tree of `DockNode.Split(direction, children, sizes)` and `DockNode.Group(panels, active)`. It names panels only by string id, and the content comes from `DockPanel`s registered with the `Dock`.
- `sizes` are fractions that sum to 1, applied as flex-grow shares, so proportions survive window resizes. A pixel minimum (`withMinPanelSize`, 120 by default) applies only while resizing, and a resize changes only the two children beside the border.
- The JSON has an explicit `"version"`. Version 1 wrote a node per `"type"` (`"split"` / `"group"`) with a hand-written codec. Since #878, version 2 stores `root` in the derived `DockNode` format (`{"@type":"Split","direction":"Row",...}`). The envelope encodes it with a fixed `WeaverConfig`, so an app's Weaver settings don't change what gets saved. `Weaver[DockLayout]` and `StorageCodec[DockLayout]` are givens, so `Storage.local(key, default)` works. A save that is unreadable or has a newer version decodes to `None`, and the app's default is used. `DockLayout.fromJSONValue` migrates version 1 by renaming `type` to `@type` and using the declared case names, then decodes with the derived codec.
- Since version 3, `floating` lists the groups floating over the grid (`DockFloat(panels, active, box)`), left out when empty. A `DockBox` is shares of the dock (0 to 1), like split sizes, so a float keeps its place across window sizes, and normalization keeps it inside the dock. Floats are addressed by the path `List(-1 - i)`, so the same operations (`move`, `activate`, `open`) work on them. Raising a float only restacks it at runtime: reordering `floating` would change the paths and rebuild the DOM under an ongoing drag.
- A group of the grid can be maximized to fill the dock (a follow-up to #877). The layout keeps it as `maximized: Option[String]`, the id of a panel in that group, since paths are never saved. It is an optional field of version 3, not a version 4: today's decoder ignores keys it doesn't know, so an app from before still loads a newer save (just not maximized), where a version bump would make it throw the whole layout away. So a new optional field that older readers can safely ignore needs no new version. Normalization clears it unless the panel is in the grid, and an operation that has to show something outside the group (activating or opening a panel elsewhere, moving a tab out, splitting, floating, docking) restores first, so nothing changes out of sight. The other groups and the floats stay mounted under `hidden`, and `shape` leaves `maximized` out, so a toggle never rebuilds the DOM. Floats can't be maximized.
- A panel detached into its own window (`Dock.withDetach`) is runtime state, not saved: a browser blocks `window.open` without a user gesture, so it couldn't be reopened on load. The panel keeps its tab in the layout and returns there when the window closes.
- `DockModel.normalize` runs on load and after every operation. It drops unknown and repeated ids and removes empty groups, collapses single-child splits, and flattens a nested split in its parent's direction. It resets invalid sizes, fixes `active`, and adds back panels registered with `withClosable(false)`. The Dock writes the cleaned layout back on mount.
- Operations (`move`, `split`, `close`, `open`, `activate`, `resize`) are pure functions in `wvlet.uni.ui.base.DockModel` that address groups by path. Paths are found again for each gesture and never saved.
- The Dock rebuilds its DOM only when the structure changes. That is the tree without sizes or active tabs (`DockModel.shape`). Sizes and active tabs update in place, and panel content is cached by id, so a moved panel re-mounts the same element.
- Tabs move by pointer events (`PointerDrag`), not HTML5 drag-and-drop. A tab's context menu does the same moves and splits from the keyboard.

## Alternatives

- **Derive the codec with `derives Weaver`**: blocked at first by the recursion limits above. Adopted in version 2 once #878 fixed derivation.
- **Keep the version 1 shape through a case-name option in `WeaverConfig`**: the config is per call, so a Dock nested in an app's own state would follow that app's settings, and the saved shape wouldn't be fixed.
- **Pixel sizes in the layout**: they break when the window size changes between sessions.
- **Stable group ids in the format**: considered for a "focus group N" API, but `Dock#focusGroup(i)` numbers groups by their 0-based reading order instead, the same order as the "group N" accessible names, so the saved JSON stays minimal.
- **A mutable widget tree (as in GoldenLayout)**: harder to save, compare, and test than a value in an `RxVar`.
- **Floats in viewport pixels (`position: fixed`)**: a float could leave a narrow dock, but pixels break across window sizes and the float could end up out of reach.
- **Save detached windows and reopen them on load**: popup blockers reject `window.open` without a gesture.
- **HTML5 drag-and-drop**: drag images and drop rules vary in Electron, `fireEvent` can't drive it, and it offers no keyboard path anyway.

## Consequences

- Any JSON format change needs a new `version` and a migration in `fromJSONValue`. A downgraded app sees a newer save as unreadable and falls back to its default.
- Panel ids are part of an app's saved data. Renaming one moves that panel back to its default place (or hides it, if it is closable).
- `uni-dock`, `uni-dock__group`, `uni-dock__bar`, `uni-dock__tab`, `uni-dock__separator`, `uni-dock__drop`, `uni-dock__float`, `uni-dock__float-grip`, `uni-dock__float-resize`, `uni-dock__detached`, `uni-dock__window`, `uni-dock__maximize`, and the `data-top`, `data-path`, `data-direction`, `data-detached`, and `data-maximized` attributes are public API.
