# uni-dom: IME-aware Enter and pane resizing

## Goals

- Give uni-dom apps a one-liner for "Enter submits" that never fires on the Enter that confirms an IME conversion (Japanese, Chinese, Korean input). Today each app must know about `isComposing` and the `keyCode == 229` quirk.
- Give uni-dom a drag-to-resize binding, so a resizable sidebar or split pane doesn't need hand-written window `pointermove`/`pointerup` wiring.
- Switch examples/uni-harness to both and delete its copies, with no visible UI change. This is the "Widgets" follow-up (#8) of plans/2026-11-03-uni-dom-workspace-fixes.md.

## Background

- The harness Composer reads `isComposing` through `js.Dynamic` (Chat.scala:304). scalajs-dom 2.8 already exposes `KeyboardEvent.isComposing`, so the library needs no dynamic access.
- `Keyboard.scoped("enter" -> ...)` exists but ignores composition, so it sends on IME Enter: a real bug for CJK users of every scoped shortcut, not just Enter.
- The harness `ResizeHandle` mixes three things: the pointer-drag mechanics (window listeners, body cursor, cleanup), the size math (direction, clamp, double-click reset), and Tailwind look (grip line, hover opacity). `FoldablePane` is pure app layout and styling.
- Code in `wvlet.uni.dom` lives in uni-core/.js and must not import from `uni` (adr/2026-11-04-uni-dom-in-uni-core.md). Neither helper needs anything outside uni-core.

## Design

Lane: Standard — adds new public uni-dom API (and changes `Keyboard.scoped` behaviour during IME composition).

- **`Keyboard.onEnter(handler)`**: a modifier for an input or textarea. Plain Enter calls the handler and prevents the default; Shift+Enter (newline) and Enter during IME composition pass through untouched.
  - Usage: `textarea(value.bind(draft), Keyboard.onEnter(() => submit()))`.
  - Other modifiers (Ctrl/Cmd/Alt+Enter) also pass through, so an app can bind "cmd+enter" separately.
- **`Keyboard.isComposing(e)`**: public helper, `e.isComposing || e.keyCode == 229` (229 covers Safari's keydown that arrives after compositionend). Apps writing their own `onkeydown` can reuse it.
- **`Keyboard.scoped` and global `Keyboard.bind` skip composing key events.** No shortcut should fire while the IME owns the keyboard. This is a behaviour change, but only for events that are currently misfiring.
- **`PointerDrag`** (new, in `wvlet.uni.dom`): the generic drag mechanics. A modifier for `onpointerdown` that reports the delta from the start point on each move and ends on `pointerup` or `pointercancel`, tracking moves on `window` so the drag survives leaving a thin handle.
  - Exposes `dragging: Rx[Boolean]`, so a pane can turn off its width transition during a drag.
  - Optional `cursor` sets the body cursor for the drag's duration and restores it after.
- **`Resize`** (new, built on `PointerDrag`): binds a size `RxVar[Int]` to a handle. Takes `min`/`default`/`max` and a direction (grow when the pointer moves right/down, or left/up); clamps every update and resets to `default` on double-click.
  - Horizontal and vertical, so the same binding works for split panes and bottom docks.
  - Adds `role=separator` and `aria-orientation`; the label stays the caller's.
- **Stays in the example**: `FoldablePane`, `Edge`, the handle's look (grip line, hover/active opacity, Tailwind classes). `ResizeHandle` shrinks to a styled `div` that applies `Resize`; `SizeRange` is replaced by the library's range type.
- **Docs**: a "Pointer drag and resize" and "Enter in text fields" entry in docs/dom/index.md's Browser Integrations table/sections, and a row in docs/guide/for-ai-agents.md. The uni skill covers uni-dom only for testing, so it stays unchanged unless a snippet there uses these.
- Tests (uni-dom, DomTestSupport) cover Enter vs Shift+Enter vs composing Enter, scoped shortcuts during composition, drag deltas, clamping, pointerup/pointercancel cleanup, both directions, double-click reset, and `dragging`. Harness tests keep their current assertions.

## Alternatives and Why Not?

- **Ship a `ResizablePane` component.** Panes vary too much in look (border, folding, transitions); a component would either impose Tailwind classes or grow many style options. A binding composes with any markup.
- **Only `PointerDrag`, no `Resize`.** Clamp, direction, and double-click reset are the part every resizable pane rewrites; leaving them out keeps most of the harness code in apps.
- **Use `setPointerCapture` instead of window listeners.** Cleaner, but synthetic `fireEvent` pointers have no active pointer to capture, so tests would need extra plumbing; the harness's window tracking is proven. Can be revisited without API change.
- **Add `onEnter` as an `HtmlEvents` attribute (`onenter -> ...`).** It reads like a native event that doesn't exist; keeping it under `Keyboard` signals it's a helper.
