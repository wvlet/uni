# uni-ui Dock keeps its layout as versioned plain data, cleaned up on load

## Context

uni-ui phase 3 adds a Dock: panels in tab groups and splits that the user drags around, with a layout the app saves (uni-harness keeps it in localStorage). A saved layout outlives the code that wrote it. Panels get renamed or removed, and the format may change, so an old save must never break the app. `derives Weaver` doesn't handle a recursive layout type yet: a parameterized enum case encodes as its bare name, and a recursive sealed trait hung the compiler in a probe.

## Decision

- The layout is immutable data in an app-owned `RxVar[DockLayout]`: a tree of `DockNode.Split(direction, children, sizes)` and `DockNode.Group(panels, active)`. It names panels only by string id, and the content comes from `DockPanel`s registered with the `Dock`.
- `sizes` are fractions that sum to 1, applied as flex-grow shares, so proportions survive window resizes. A pixel minimum (`withMinPanelSize`, 120 by default) applies only while resizing, and a resize changes only the two children beside the border.
- The JSON has an explicit `"version": 1` and a node per `"type"` (`"split"` / `"group"`). The codec is written by hand on JSON values. `Weaver[DockLayout]` and `StorageCodec[DockLayout]` are givens, so `Storage.local(key, default)` works. A save that is unreadable or has a newer version decodes to `None`, and the app's default is used. A future version 2 migrates version 1 in `DockLayout.fromJSONValue`.
- `DockModel.normalize` runs on load and after every operation. It drops unknown and repeated ids and removes empty groups, collapses single-child splits, and flattens a nested split in its parent's direction. It resets invalid sizes, fixes `active`, and adds back panels registered with `withClosable(false)`. The Dock writes the cleaned layout back on mount.
- Operations (`move`, `split`, `close`, `open`, `activate`, `resize`) are pure functions in `wvlet.uni.ui.base.DockModel` that address groups by path. Paths are found again for each gesture and never saved.
- The Dock rebuilds its DOM only when the structure changes. That is the tree without sizes or active tabs (`DockModel.shape`). Sizes and active tabs update in place, and panel content is cached by id, so a moved panel re-mounts the same element.
- Tabs move by pointer events (`PointerDrag`), not HTML5 drag-and-drop. A tab's context menu does the same moves and splits from the keyboard.

## Alternatives

- **Derive the codec with `derives Weaver`**: blocked by the recursion limits above. The JSON shape can stay the same if derivation learns recursive sum types later.
- **Pixel sizes in the layout**: they break when the window size changes between sessions.
- **Stable group ids in the format**: they're useful for a future "focus group N" API, but paths are enough for now and keep the saved JSON minimal.
- **A mutable widget tree (as in GoldenLayout)**: harder to save, compare, and test than a value in an `RxVar`.
- **HTML5 drag-and-drop**: drag images and drop rules vary in Electron, `fireEvent` can't drive it, and it offers no keyboard path anyway.

## Consequences

- Any JSON format change needs a new `version` and a migration in `fromJSONValue`. A downgraded app sees a newer save as unreadable and falls back to its default.
- Panel ids are part of an app's saved data. Renaming one moves that panel back to its default place (or hides it, if it is closable).
- `uni-dock`, `uni-dock__group`, `uni-dock__bar`, `uni-dock__tab`, `uni-dock__separator`, `uni-dock__drop`, and the `data-top`, `data-path`, and `data-direction` attributes are public API.
