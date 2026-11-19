# uni-dom and uni-ui: type checks and documents in a detached Dock window

## Context

`Dock.withDetach` (#960) moves a panel into a `window.open` popup on the same origin. The popup is a separate JS realm with its own `KeyboardEvent`, `HTMLElement`, and other classes, and a document of its own. Three kinds of code failed there, silently:

- **Type checks.** Scala.js compiles `case e: dom.KeyboardEvent` to `instanceof` against the main window's class. Every popup event fails it, and so do the popup's `<html>`, `<body>`, and the Dock's host `div`, which parent walks reach. In Firefox, nodes adopted into the popup also move to its realm. #960 duck-typed only `ChangeSource.of` and `dragCancel`.
- **Main-document reads.** `dom.document.activeElement`, `.contains`, and `.body` never see the popup. Menu, Reorder, `FocusReturn`, and `Dismiss` all depend on them.
- **Main-window overlays and frames.** Tooltip, Menu, Popover, and Select portaled into the main `<body>`. `Dismiss` listened only on the main `document`. Placement read the main viewport and ran on the main window's `requestAnimationFrame`. A `ResizeObserver` from the main window observing a popup element reported a "loop completed with undelivered notifications" error every frame.

## Decision

- **`AnyRealm` (`private[uni]`, in `wvlet.uni.dom`)** replaces `case x: dom.T` with `case AnyRealm.T(x)`, which matches a value from any window. It tries `instanceof` against the main window first, so the common case costs the same as before. Only when that fails does it compare constructor names along the prototype chain, which handles subclasses (`PointerEvent` is a `MouseEvent`). `documentOf`, `windowOf`, and `activeElementOf` give a node's own document, window, and focused element. `construct(node, "ResizeObserver", ...)` builds an object from the node's window.
- **The document comes from the node.** Code with a node in hand reads focus, containment, `<body>`, computed style, frames, and timers from that node's document and window.
- **Overlays open in their trigger's window.** `OverlaySurface.portal(triggerRef)` renders into the trigger's `<body>` through `Portal.toElement`. The public `Portal.toBody` is unchanged. `Dismiss` listens on the page's document and on the document of each open layer, and Escape goes to the topmost layer in the window where it was pressed. Reorder announces in the list's own document (`Announce.politeNear`).
- **`Windows` (`private[ui]`)** lists the Dock's popup documents and follows `focusin` across them. Code with no node to start from, such as `FocusReturn` saving the focused element as an overlay opens, asks it for the focused document. With no detached window it is always the page's document, so the main window behaves exactly as before.

## Alternatives

- **Duck typing at each site**, as #960 did: about 80 hand-written field probes, each a guess at which fields identify the type.
- **Checking against the popup's constructors** (`win.KeyboardEvent`): each site would have to know which window the value came from.
- **Making `Portal.toBody` follow the portal's document for everyone**: a public change in uni-dom's semantics. It also can't always be decided at render time, because DomRenderer builds a subtree before it mounts it.
- **`document.hasFocus()` to find the focused window**: unreliable for popups in headless Chromium, and true for a page whose focus is inside an iframe.

## Consequences

- Type checks that only see values the renderer just created stay as they are: `DomRenderer`'s matching of rendered values and the `ClickOutside` listener.
- Still main-window only: `Dialog` and `Toaster` (page-level, with no trigger), `Keyboard.bind` global shortcuts, uni-dom's `Focus` tracking, `Portal.to(id)`, `ClickOutside`, DomRenderer's resize and intersection bindings (built before the node is mounted), and `TerminalView`'s selection and frame loop.
- `uni-ui`'s `DetachedWindowTest` opens a real Dock popup in headless Chromium and drives InputHistory, Reorder, Menu, and Tooltip with events built from the popup's own constructors.
