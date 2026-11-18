# Cancelled pointer drags: pointer capture, Escape restore, and clamping a mounted PaneResize

## Context

`PointerDrag` (uni-dom) is the one helper behind every drag in uni-ui: `PaneResize`/`Splitter`, the Dock separators, Dock floating-group move and resize, Dock tab drag-to-dock, and `Reorder`. It tracked moves with `window` listeners only, so a release outside the browser window went unseen (#853). Escape mid-drag could not undo a resize; only Dock tabs and `Reorder` cancelled, each with its own `window` keydown listener that left the underlying drag running. `PaneResize` clamped its size only at construction (#861). Resizes write live: every move goes through ChangeGate/`onChange` (#898), tagged with a per-drag gesture token, and UiHistory (#916/#933) keeps one entry per drag.

## Decision

- `PointerDrag` captures the pointer on its handle when a drag starts and releases it when the drag ends. Capture is best effort (synthetic events have no active pointer, and some environments lack the method); the `window` listeners stay the single end path, since captured events still bubble to `window`.
- `PointerDrag` takes an optional `onCancel: Option[dom.Event => Unit]`. With it, Escape (caught on `window` in the capture phase) and `pointercancel` end the drag as a cancel; without it, Escape is ignored and `pointercancel` calls `onEnd`, as before. `onCancel` runs while `dragging` is still true, so a component's restore write still carries the drag's gesture token. The cancelling Escape is `preventDefault`ed and `stopPropagation`ed, so `Dismiss` (which skips default-prevented keys) leaves an enclosing dialog open and element-level Escape handlers, such as a list clearing its selection, do not run. After an Escape cancel the capture is kept until the release, and the click from that release is swallowed; the swallower also stops at the next `pointerdown`, so a missed release cannot eat a later click.
- A cancel cannot mean "write nothing": the sizes were already written and reported on every move. So a cancelled resize writes the start value back and reports it through `onChange`, with `ChangeReason.EscapeKey` and the `Keyboard` source for Escape, or `Drag` and `Pointer` for `pointercancel` (`ChangeCause.dragCancel`). A refused restore keeps the dragged value; the drag still ends. UiHistory merges a gesture-tagged `EscapeKey` write into the drag's entry, and because the value is back at `previous`, the entry is dropped. Arrow keys during a drag stay entries of their own.
- `ChangeCause` lives in uni-ui, so `PaneResize` (uni-dom) gets `onCancel` and `onClamp` hooks, like its `onDoubleClick`, and `Splitter` uses them to name the causes.
- While its handle is mounted (an `OnMount` in `bind`), `PaneResize` watches its var and clamps out-of-range values. The clamp runs in a microtask: `RxVar` notifies subscribers from a snapshot, so a write from inside a subscriber would start a nested pass, after which the outer pass hands later subscribers the out-of-range value again. A `Splitter` clamp is reported as `Imperative` with the `Programmatic` source; a refused clamp is not retried.

## Consequences

- Dock tabs and `Reorder` use `onCancel` and lost their own Escape and `pointercancel` listeners; Escape now ends their drag at once, restoring the page cursor.
- Real pointer capture is not exercised in tests: they stub `setPointerCapture`/`releasePointerCapture` on the element and check the calls.
- A late out-of-range write is visible to subscribers for one microtask before the clamp; nothing paints in between.
