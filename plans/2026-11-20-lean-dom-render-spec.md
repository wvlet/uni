# Lean model of DOM rendering from Rx

## Goals

- Answer "is `DomRenderer`'s Rx handling worth a Lean model?" with evidence. The answer is yes: reading the code with the model's invariant in mind found four bugs, all confirmed in Chromium.
- Fix those bugs, and state the rule they break as a Lean model, so the next change to sections or keyed lists is checked against it.
- Answer the same question for `RxRunner`. The answer is yes, but as the next model, not this one. Three Lean models already cover its trace rules and found two bugs (`RxRecover`). The gaps left are the ones UI code depends on most, listed below. No bug has been found in them yet, so the case for them is weaker than the renderer's.

## Background

- `DomRenderer` keeps each `Rx` in a **section**: two empty text nodes in the parent, with the content between them. On each emission it clears the section and renders the new value at the section's anchor. `each`/`eachRx` keyed lists use the same idea, one pair of markers per item, and move the items' nodes into the new order (`2026-11-05-keyed-list-rendering.md`).
- The whole design depends on one invariant: **everything a value renders lands at its anchor, inside its section.** Nothing checks it today. The tests cover common shapes, such as an element per row.
- Four bugs, found with a probe test in `domTest` (Chromium):
  - An `RxElement` whose render produces two nodes, inside an `Rx` section, renders `b0a0tail`, then `b1a0taila1`, then `b2a0taila1a2`. Stale nodes pile up outside the section.
  - The same element as a keyed-list item renders `b1b2a1a2tail` instead of `a1b1a2b2tail`.
  - An `RxElement` that renders an `Rx` inside an outer `Rx` section throws `removeChild: parameter 1 is not of type 'Node'` on the next outer update.
  - `RxElement(rx: Rx[A])` (public API) overflows the stack. Its `LazyRxElement` renders itself, and `renderTo` has no case for it. `createNode`'s case passes `l.value` (an `Rx`) to a `traverse` with no `Rx` case, so it throws "Unsupported top level element" instead.
- One root cause for the first three: the `RxElement` case renders into the parent with no anchor (appending at the end), then moves only `node.lastChild` to the anchor. A component that renders a group, a keyed list, or an `Rx` leaves nodes or markers behind.
- The existing models (`Rx`, `RxNested`, `RxRecover`) are traces of events. Rendering is a different shape of problem: a list of nodes changed in place by marker-relative inserts and removals. It fits Lean well, because the state is small, discrete, and has an obvious denotation, "the text the current values should show."

## Design

Lane: Standard — a new spec model, and a renderer fix that changes where some components' nodes land.

- **Fifth bug, found in review of the model** (fixed in this PR): an `RxVar` notifies the subscribers it had before an update. A section directly inside another section, or a keyed item, on the same variable got the update after the outer one removed its markers, and threw `removeChild(null)`. Sections and keyed lists now ignore updates once cancelled, which the model already assumed. Shared-variable cases cover this.
- **Fix:** an `RxElement` renders at its anchor, never at the end of the parent.
  - Its modifiers and `onMount` still go to the last node it rendered, as today, so single-element components keep the same behaviour. That node is found next to the anchor (`anchor.previousSibling` before and after), not with `lastChild`.
  - `onMount`'s `MutationObserver` sees the node only through the final re-insert at the anchor. The re-insert stays on purpose, with a comment, and a replay case checks that `onMount` fires.
  - `renderTo` passes a `LazyRxElement`'s value to the `Rx` case. `createNode` gets the same fix, so `RxElement(rx)` works at the top level too.
- **Model** (`UniSpec/DomRender.lean`):
  - **View:** text, group, component (`RxElement` wrapping a view), `Rx` section (holds the current value of a numbered variable), keyed list (keys of a numbered variable, rendered by an item view).
  - **Parent:** a flat list of nodes, each with an id: text leaves and section/item markers.
  - **Renderer:** the same steps as `DomRenderer`: insert before an anchor, the keyed walk that moves kept items behind a cursor, and the clear exactly as written. A section is `[end, content…, start]`, and the clear walks `previousSibling` from `start` until it reaches `end`, with no bound. When markers are out of order, the walk runs off the front of the parent. That is the `removeChild` crash, so the model must be able to express it.
  - **Update:** set one variable, as `RxVar :=` does.
- **Theorems** (proved for all inputs):
  - Inserting before an anchor puts the node right before it and keeps every other node in order.
  - With its markers paired, the clear walk removes exactly the section's content. Without its front marker, the walk throws, which is the old `removeChild` crash.
  - A keyed list keeps one item per key.
  - On the four-bug shape, the old strategy disagrees with the spec and the fix agrees (`decide`).
- **Checked, not proved:** agreement with the spec after every update, the main invariant. The generator runs every case through the renderer model and fails on any disagreement. A general proof would need an invariant that ties the flat list to the tree of live sections, which costs far more than it adds over exhaustive small cases.
- **Generated cases** (`DomRenderSpecCases.scala`): 370 views, made of every shape up to depth 2 plus components nested in sections at depth 3. Each shape appears with its own variables and again with shared ones. Each view comes with a script of variable updates that includes a repeated key and an empty list. Each case records every leaf's model node id and text after every step. 24 of them fail with the old strategy.
- **Replay** (`DomRenderSpecTest` in `domTest`): builds each view with real uni-dom nodes (`RxVar`, `each`, a group-rendering `RxElement`), applies the script, and compares `textContent` and node identity. The four bugs above become cases that fail before the fix.
- **Docs:** the Lean README's model table; the lean-specs ADR (a Consequences entry, and "Next candidates" changed to the `RxRunner` list below, keeping `recoverWith` and `parFlatMap`); and the keyed-list ADR, whose rule "nested sections stay in place" the `RxElement` case broke.
- **CI:** the `Lean spec` job builds the new model and checks the generated file; its path filter adds it. `domTest` already runs the Scala side.
- **Out of scope here:** attributes, bindings, events, portals, `onMount` timing, and `eachRx`'s in-place updates (that is `Rx.split`, an `RxRunner` question).
- **`RxRunner`, next model** (separate PR, not this one; the split is my proposal): the gaps that matter most for UI code, on top of the ADR's candidates (`recoverWith`, `parFlatMap` ordering).
  - `flatMap`/`transformRx` with continuous sources: each new input cancels the previous nested stream (switch semantics). `RxNested` covers only synchronous sources that run to the end.
  - `split`: one row per key, a row emits only when its value changes, a removed row completes after the list stops holding it, and an unchanged key list is not re-emitted.
  - `zip`/`join`, which UI code uses to combine state.
  - Downstream `Stop` and cancellation, including the `Stop`-before-nth `take` case the ADR lists as unmodeled.

## Alternatives and Why Not?

- **Only fix the bugs, with regression tests.** It fixes these four, but the next shape (an `Rx` of a keyed list inside a component inside an item) has no test. The model enumerates the shapes for us.
- **A property-based test with a Scala reference renderer.** The reference would repeat `DomRenderer`'s own assumptions; the ADR rejects hand-written oracles for that reason.
- **Model the DOM API itself (`insertBefore`, `removeChild`) in full.** It is more faithful, but the renderer uses only marker-relative insert and remove on one parent, and a list model keeps the proofs short.
- **Model `RxRunner` first.** Its trace models already exist and found smaller bugs. The renderer has no model, and just reading it with the invariant in mind found four bugs.

