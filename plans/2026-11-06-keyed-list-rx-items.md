# Keyed list whose items are `Rx` (`eachRx`)

## Goals

- Let a keyed list item follow its own value as it changes, without rendering the item again. Its DOM node, focus, and animation stay put.
- Drop the uni-harness workaround from #871: `messageKeys`, `messageAt` (an O(n) scan per prompt bubble), and the `find` inside the render.

## Background

- `each(items)(key)(render)` renders an item again when its value changes under the same key (`!=`), per adr/2026-11-05-keyed-list-rendering.md. That's right for plain rows, but wrong when a row owns state (an entrance animation, a typing reply).
- The harness chat had to key over plain strings and look each message up again, so a confirmed prompt (draft → stored) only updates its "Sending…" label.
- `RxVar.set` already skips equal values, so a per-item `RxVar` emits only on a real change.
- `each` is called with a 2-arg lambda over tuple items (`each(cs)(_._1.id)((c, active) => …)` in the harness Shell). An `each` overload whose render takes 2 parameters would capture that call and break it.

## Design

Lane: Standard — a new public uni-dom API.

- User flow: an app author with rows that change in place writes `eachRx(store.messages)(_.id)((m, rx) => Bubble(m, rx))`. The row renders once per key from `m`, and the parts that follow the value bind to `rx` (`rx.map(...)`). To get back the current behavior, switch to `each`.
- Name: `eachRx`, a separate function next to `each`, exported from `wvlet.uni.dom.all`. A separate name avoids the tuple-lambda overload clash and says at the call site that the row is live.
- Render shape: `(A, Rx[A]) => DomNode`, the value when the key first appears plus an `Rx` of its later values. The first value lets the row choose a stable layout (a prompt vs a reply) and run once-only logic without subscribing.
- Per-item `Rx`: one `RxVar` per key, typed `Rx[A]` so the row can't write it. When the list emits, an item whose key stays gets `set` to its new value; it emits only when `!=`, and the row's nodes are only moved, never rebuilt.
- Removal: when a key is gone, the item's subscriptions are cancelled, its nodes removed, and its `RxVar` stopped (`OnCompletion`), so subscribers outside the row also end. A key that comes back later starts fresh.
- `each` stays exactly as it is; both share one renderer path that differs only in what happens on a changed value (render again vs `set`).
- Harness: the thread uses `eachRx(store.messages)(store.keyOf)`, and Bubble takes the `Rx[ChatMessage]` for its label. `messageKeys` and `messageAt` go; the draft→stored key alias stays, since the key must survive confirmation.
- Docs: update the ADR, `for-ai-agents.md`, and the `uni` skill's DOM reference with one `eachRx` line and snippet; bump the plugin version.
- Tests cover: an item updates in place under the same key (same node), equal values don't emit, removal cancels and stops the item `Rx`, reorder keeps nodes, plus the existing harness tests from #871.

## Alternatives and Why Not?

- **`each` overload taking `Rx[A] => DomNode`.** Same arity as today's `A => DomNode`, so an untyped lambda `m => …` is ambiguous at every existing call site.
- **Render takes only `Rx[A]`.** `Rx` has no `get`, so the row can't pick a layout or read an id without subscribing, and a top-level `rx.map(...)` re-renders the whole row again.
- **A flag on `each` (`each(items, live = true)`).** The render's type changes with the flag, so it needs two signatures anyway.
- **Keep the harness workaround.** Each app would rebuild it, and the lookup is O(n) per row per update.
