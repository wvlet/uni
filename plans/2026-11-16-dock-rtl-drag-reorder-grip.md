# Mirror Dock separator drag in RTL; Reorder hint and touch grip

## Goals

- In a right-to-left layout, dragging a Dock row separator moves it the way its arrow keys do, so pointer and keyboard agree.
- A sorted `DataTable` (reorder off) no longer tells the user it can move rows.
- On a touch screen, a reorderable list scrolls when swiped, and a visible grip moves a row.

## Background

- #908 mirrored ArrowLeft/Right on Dock row separators via `TextDirection`, but the separator's `PointerDrag` still feeds the physical `dx` into the size change. In RTL the first pane sits on the right, so a leftward drag shrinks it while ArrowLeft grows it.
- #917 floating groups place and resize with physical `left`/`top`, and both their pointer and arrow keys are physical, so they already agree in RTL. Detached windows and splits inside floating groups reuse the same `separator`, so one fix covers them.
- The "move hint" is the row's `aria-description` ("Alt+ArrowUp or Alt+ArrowDown to move"). `DataTable` turns reordering off while sorted through a plain `() => Boolean`, so nothing re-renders the hint when the sort changes.
- `Reorder.handle` already exists (#901): a marker attribute that limits drags to that element, with `touch-action: none` in `uni-ui.css`. What's missing is a ready-made grip, and the policy for a touch press on a row with no grip: today it starts a drag that the browser then cancels as it starts to scroll.

## Design

Lane: Standard — changes pointer behavior on Dock separators and on touch input, and adds a public `Reorder.grip`.

- **Dock separators**: read the direction once at `pointerdown` (`TextDirection.isRtl`) and negate `dx` for a row split in RTL. Column splits, floating group move/resize, and LTR are unchanged.
- **Hint while sorted**: the internal enabled flag becomes an `Rx[Boolean]`. While it is false, a row drops its `aria-description` and gets `data-reorder-off`, which hides any grip in it (`visibility: hidden`, so columns don't shift).
- **Touch without a grip**: a touch press on a row with no `Reorder.handle` never starts a drag, so the swipe scrolls. Mouse and pen still drag from anywhere on the row; keyboard moves are unchanged.
- **`Reorder.grip`**: a new ready-made element: a `⋮⋮` icon marked `Reorder.handle`, `aria-hidden` (the keys and `aria-description` carry the accessible path), class `uni-reorder-grip` with a 24px+ hit area. Apps put it in a row's render function or a `DataTable` cell; `Reorder.handle` stays for custom grips.
- `Reorder.grip` is a value, not a component with arguments, so the #913 one-parameter-list style has nothing to apply to. A custom icon uses `span(Reorder.handle, icon)`.
- Tests: an RTL Dock row separator drag that matches ArrowLeft; a sorted `DataTable` with no hint and a hidden grip; a touch press on a row that doesn't drag; a touch drag from `Reorder.grip` in a `VirtualList` that moves the row.
- Docs: `docs/dom/behaviors.md` and the collections page, `for-ai-agents.md`, the skill's `references/ui.md`, plugin version bump.

## Alternatives and Why Not?

- **Long-press on a row to start a touch drag**: needs a timer, haptics-like feedback, and a scroll-vs-hold heuristic; a grip is explicit and matches the platform pattern. Could come later.
- **Auto-add a grip column in `DataTable.withReorder`**: changes every reorderable table's layout and column indices; the app placing `Reorder.grip` keeps control.
- **Keep touch drags on grip-less rows**: they keep fighting with scroll and flicker a drop line before `pointercancel`.
