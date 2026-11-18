# uni-ui: focus onward from a removed opener, inert during the exit, and inline Dialog/Popover

## Context

UI SDK betas 0.1.0-beta.6–9 raised three points for uni-ui's overlays ([2026-11-16-uni-ui-modal-inert-and-nested-close](2026-11-16-uni-ui-modal-inert-and-nested-close.md)):

- A confirm dialog whose action deleted the row that opened it left focus on `<body>` (vercel-labs/ui#847). `FocusReturn` gave focus back only to an opener still in the document.
- Whether the page should stay `inert` under a closing modal until its exit ends. vercel-labs/ui#807 fixed a different bug: its exit cleared an `inert` the element's owner wrote during the exit. uni-ui removes the exiting element when the exit ends, so that bug can't occur here.
- Dialog and Popover always rendered at the end of `<body>`, so they couldn't stay inside a panel, an app preview, or a fixed widget (vercel-labs/ui#827).

## Decision

- **Focus onward.** `FocusReturn` records a `FocusPlace` as the overlay opens: each ancestor of the focused element, and how many focusable elements precede it there. If the element is gone on close, the nearest surviving ancestor gives focus to its focusable element now at that index, else the last one before it. Through the next animation frame after the close (100 ms if frames are paused), a `MutationObserver` repeats this when the element focus went back to is removed and focus fell to `<body>`. Every overlay gets this, with no API change.
- **Inert during the exit.** We keep today's behavior: the page is live once the close starts, and the closing overlay is `inert` with `pointer-events: none`. Focus can then go back at once, which an `inert` page would refuse. A browser test pins this.
- **Inline.** `.withInline(true)`/`.noInline()` on `Dialog`, `AlertDialog`, and `Popover` skip the `<body>` portal. These are builders because the setting is optional ([2026-11-13-uni-ui-construction-api](2026-11-13-uni-ui-construction-api.md)).
  - An inline dialog is modal within the region its backdrop covers (its offset parent, the nearest positioned ancestor). `ModalInert.pushWithin` makes the siblings along the path from the dialog up to that region `inert`. `<body>` children are never marked, so `UiAgent` doesn't list the covered content and the rest of the page stays live.
  - Because the page stays live, an inline dialog leaves out `aria-modal`. Its `Dismiss` layer is not modal, so outside presses walk on to the layers under it, and `escapeFromInside` makes Escape close it only while focus is inside it.
  - `ModalInert` applies every open modal's rule together: the topmost page modal blocks the page, and each inline modal blocks its region. A page dialog over an inline one, or an inline one inside a page dialog, keeps both blocked.
  - An inline popover can't sit inside its trigger, because the trigger's click listener would toggle it. So `span.uni-popover-anchor` wraps the trigger and the panel, and CSS places the panel under the trigger. No script places it and `data-side` is not set, as in UI SDK.
  - `CommandPalette` and UiAgent's confirmation dialog stay portaled.

## Alternatives

- **Keep the page inert until the exit ends**: focus would wait on `<body>` or inside the inert panel for the length of the exit.
- **Record the opener's neighbors**: a plain `Rx[Seq]` list renders every row again, so the neighbors leave with the opener. Only `KeyedList` keeps them.
- **Snapshot the tab order at open**: misses elements added while the overlay is open.
- **Inline dialog with no inert**: `UiAgent` and pointers would reach the panel content under its backdrop.
- **Inline dialog with page inert**: an embedded preview dialog would freeze the host page.
- **Script-placed inline popover**: `position: fixed` coordinates break under a transformed ancestor, which is a main reason to go inline.

## Consequences

- Each overlay open runs one `querySelectorAll` over the page to record the place. It skips the per-element computed-style read; visibility is checked only on the candidates when focus moves.
- An app that renders an inline dialog must give its panel `position: relative`. Without one, the backdrop covers the viewport, but only the dialog's parent is blocked, never the page.
- `DismissLayer` gained an optional `escapeFromInside` parameter.
