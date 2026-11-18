# Pointer drag robustness in uni-dom and uni-ui

## Goals

- A drag that leaves the browser window keeps tracking and ends on the real release, not on the next `pointerup` the page happens to see (#853 known limitation).
- Escape during a Splitter, PaneResize, or Dock drag puts things back as they were before the drag. It leaves no UiHistory entry, and the Escape does not also close the enclosing Dialog or Popover (UI SDK: vercel-labs/ui#801).
- A `PaneResize` size written from outside a drag, such as a storage event from another tab on an older build, is clamped into `range`, not only at construction (#861 known limitation).

## Background

- Every drag goes through one helper, `PointerDrag` in uni-core (uni-dom). Its users are `PaneResize` (and through it `Splitter`), the Dock split separators, Dock floating-group move and resize, Dock tab drag-to-dock, and `base/Reorder`.
- `PointerDrag` tracks moves with `window` listeners only. It never calls `setPointerCapture`, so a release outside the window goes unseen.
- Resizes write live on every move: each move goes through ChangeGate/`onChange` with `ChangeReason.Drag`, tagged with a per-drag gesture token. UiHistory merges a drag into one entry and drops the entry when the drag ends where it started.
- So "cancel" cannot mean "write nothing". It means one more write, back to the start value, in the same gesture. UiHistory then removes the entry because the value is back to `previous`.
- Dock tab drags and Reorder already cancel on Escape and on `pointercancel`, each with its own `window` keydown listener. They commit only on drop, so their cancel just clears the drop target. The `PointerDrag` keeps running until release, and so does the drag cursor.
- `Dismiss` (Dialog, Popover, Menu) closes on Escape unless the event was `defaultPrevented`.

## Design

Lane: Standard. This changes drag behaviour in shared uni-dom and uni-ui helpers (the user set this lane).

- **Pointer capture.** On drag start, `PointerDrag` captures the pointer on the handle and releases it when the drag ends. This is best effort: environments without capture, such as jsdom and synthetic test events, keep the current `window` listeners. Those listeners stay as the single end path, since captured events still bubble to `window`.
- **Cancel in `PointerDrag`.** Add an optional `onCancel` callback (a default parameter, like `onDoubleClick` on `PaneResize`; `PointerDrag` is a uni-dom helper, not a #913 component). While a drag with `onCancel` is in progress, Escape ends it at once. Escape is caught on `window` in the capture phase, then `preventDefault`ed and `stopPropagation`ed. So an enclosing Dialog stays open, and a list's Escape-clears-selection does not fire. `onCancel` runs instead of `onEnd`, and it runs while `dragging` is still true, so its restore write still carries the drag's gesture token. Then the drag cursor and pointer capture are released.
- **`pointercancel` cancels too** when `onCancel` is given, since the browser aborted the gesture. This matches what Dock tabs and Reorder already do. Without `onCancel`, both events keep today's behaviour: `pointercancel` calls `onEnd`, and Escape is ignored.
- **Resizes restore the start value.** `PaneResize`/`Splitter`, Dock separators, and Dock floating move and resize write the start size or box back in the same gesture. The sizes were already written live, so the restore must be reported too, or apps that track `onChange` would drift. It is reported through `onChange` with `ChangeReason.EscapeKey` (or `Drag` for `pointercancel`), so an app can tell a cancel from a drag. `ChangeCause` lives in uni-ui, so `PaneResize` gets hooks like its existing `onDoubleClick`, and `Splitter` uses them to name the cause of the restore and of the clamp. UiHistory then drops the drag's entry. To allow that, UiHistory treats any write carrying the drag's gesture token as part of that drag, not only `Drag` writes.
- **If the app's `onChange` refuses the restore,** the dragged value stays and so does its history entry. The drag still ends.
- **Dock tabs and Reorder move onto `onCancel`.** Their own Escape and `pointercancel` listeners go away, and Escape now also ends the drag at once (cursor restored) rather than leaving it running until release. Their cancel runs the same cleanup as a release without committing the drop. It still swallows the click that follows the release, so that click does not select the tab or row.
- **Clamp while mounted.** While a `PaneResize` handle is mounted (an `OnMount` in `bind`), it watches the var it was given and writes back the clamped value when an out-of-range value arrives. For `Splitter` that var is the gated stand-in, which follows the app's var. If `onChange` refuses the clamp, the value stays and is not retried, so it cannot loop. The construction-time clamp stays. For `Splitter`, the write goes through ChangeGate as `ChangeReason.Imperative` with the `Programmatic` source, like other writes that no user event caused.
- **Tests** cover capture and release on supported environments, Escape and `pointercancel` cancel (start value restored, no UiHistory entry, Dialog not dismissed, selection kept), the refused restore, Dock separator and float cancel, and a late out-of-range write being clamped.
- **Docs.** Update `docs/dom/` (PointerDrag, PaneResize), the uni-ui Splitter and Dock pages, `docs/guide/for-ai-agents.md` if it lists the `PointerDrag` signature, and `plugins/uni/skills/uni/references/ui.md`, and bump the plugin version. Remove the two known limitations.

## Alternatives and Why Not?

- **Hold resize writes until release, then commit once.** The pane would not follow the pointer through `onChange`-driven layouts, and it would change the per-move `onChange` contract that #898 apps rely on.
- **Escape handling in each component.** That is how Dock tabs and Reorder do it today. It gives three copies of the same listener, and none of them ends the underlying drag.
- **Leave `pointercancel` as a plain end for resizes.** That is consistent with today, but inconsistent with Dock tabs and Reorder. A cancelled touch gesture that leaves a half-dragged pane is the less expected outcome.
- **Clamp inside `GatedVar` or in `Splitter` only.** A bare `PaneResize` user would keep the bug. Clamping belongs with the range, in `PaneResize`.
