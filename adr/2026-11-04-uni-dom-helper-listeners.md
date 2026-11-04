# uni-dom helpers attach with addEventListener and skip IME composition

## Context

In uni-dom, an `on*` attribute such as `onkeydown -> f` is assigned as a DOM property (`node.onkeydown = f`), so an element has one handler per event and a later one replaces an earlier one. Library helpers that need an event (`Keyboard.scoped`, `Keyboard.onEnter`, `PointerDrag`, `PaneResize`'s double-click reset) used to build on these attributes. That meant a helper and the app's own `onkeydown` on the same element silently replaced each other.

Key events during an IME composition (Japanese, Chinese, Korean input) belong to the IME: the Enter that confirms a conversion must not submit or trigger a shortcut. Apps had to check `isComposing` and Safari's `keyCode == 229` themselves.

## Decision

- Library helpers attach through the internal `EventListenerBinding(eventName, listener)`, which `DomRenderer` registers with `addEventListener` and removes on unmount. They never use `on*` attributes, so they compose with the app's handlers. `value.bind` already followed this rule (#849).
- `on*` attributes keep property semantics: one handler per element and event, owned by the app.
- `Keyboard.isComposing(e)` (`e.isComposing || e.keyCode == 229`) is the single check. `Keyboard.bind`, `Keyboard.scoped`, and `Keyboard.onEnter` ignore composing key events.
- `PointerDrag` tracks moves on `window` (not `setPointerCapture`) and follows only the `pointerId` that started the drag.

## Consequences

- A new helper that listens to DOM events should use `EventListenerBinding`, not `HtmlTags.handler`.
- `Keyboard.scoped` changed: it now runs alongside an app's `onkeydown` on the same element instead of replacing it, and it no longer fires during IME composition.
- Window tracking works with `fireEvent`'s synthetic pointers in tests; switching to pointer capture later would not change the `PointerDrag` API.
