# Rx `scan` and keyed `split` in uni-core

## Goals

- Let a stream carry state across emissions with a general `rx.scan(init)(f)`, which uni Rx lacks today.
- Add `rx.split(key)`, which turns an `Rx[Seq[A]]` into `Rx[Seq[(K, Rx[A])]]` with one stable `Rx` per key, on JVM, JS and Native.
- Move the key → row `RxVar` table that `eachRx` keeps inside `DomRenderer.renderKeyedList` (#873) into uni-core, so `eachRx` can later be `split` + `each` with no signature change.

## Background

- `map`/`flatMap` over `Rx[Seq[A]]` can't keep rows stable: each emission builds a new `Seq` with no memory of earlier rows.
- `scan` alone can't express `split`: the table holds live `RxVar`s (create, set, stop are side effects), and rows must complete when their key leaves or the stream is cancelled.
- Rx operators are cold: each `run`/`subscribe` builds its own operator chain in `RxRunner`, and stateful operators (`LastOp`, `TakeOp`) keep their state per run. `CacheOp` is the one operator whose state lives on the shared instance.
- `RxVar.set` emits only when the value differs (`!=`), and `stop()` sends `OnCompletion` to its subscribers.
- `each` renders a row again when its value is `!=` the previous one; `RxVar` has reference equality.

## Design

Lane: Standard — new public `Rx` API.

**User flow** — a row-owning list (chat thread, table) in any uni code, not only the DOM:

- `store.messages.split(_.id)` gives `Rx[Seq[(String, Rx[Message])]]`. A consumer renders the list from it and binds each row to its own `Rx`.
- In uni-dom: `store.messages.split(_.id).each(_._1)((id, current) => Bubble(current))`. A changed message updates only its row; a new id adds a row; a removed id removes the row and completes its `Rx`.
- Done when the row's state (an animation, focus) survives updates. Back out by using `each` or `map` again; nothing is persisted.

**`scan(init)(f: (B, A) => B): Rx[B]`**

- Emits `f(acc, a)` for each upstream value; it doesn't emit `init` itself (`startWith(init)` adds it). This matches RxJS and Monix `scan`.
- The accumulator lives per subscription, like `LastOp`. Errors and completion pass through; an exception in `f` becomes `OnError`.

**`split[K](key: A => K): Rx[Seq[(K, Rx[A])]]`** (on `Rx[Seq[A]]`)

- Each subscription keeps its own key → `RxVar` table (recommended; see question 1).
- On each upstream `Seq`: set the new value on each kept key's `RxVar` (it emits only when `!=`), create an `RxVar` for each new key, and stop the `RxVar` of each key that left.
- The outer `Rx` emits only when the key sequence changes (keys added, removed or reordered). A change inside a row reaches only that row's `Rx`, so the list consumer does no work.
- Each key keeps the same `Rx` instance while it stays, so `each(_._1)` sees `(k, rx)` as equal (reference equality on the `RxVar`) and never renders the row again.
- A removed key's `RxVar` is stopped after the new list is emitted, so the consumer never sees a completed row in the current list. A key that returns gets a new `RxVar`.
- A repeated key keeps its first item, as `each` does.
- Upstream completion or cancellation stops every row's `RxVar`. An upstream error passes through to the outer `Rx`, and rows stay alive.
- The row `Rx` is typed `Rx[A]`, so consumers can't write to it.

**Follow-up (after #873 merges):** reimplement `eachRx` on `split`. `eachRx`'s render gets the first value, which the internal `RxVar.get` gives without subscribing.

**Docs:** an ADR, a section in `docs/rx`, and `for-ai-agents.md` and the plugin's `references/rx.md` with compiled snippets. Tests cover scan, and split's add/remove/reorder/duplicate keys, row completion, cancellation, and per-subscription isolation, on JVM/JS/Native.

## Questions for you

1. **Per-subscription table or one shared table?** Per subscription (recommended): consistent with every cold operator, and cancelling one subscriber stops only its rows. Shared: two subscribers see the same row `Rx`s, but rows then need reference counting to decide who stops them, like a `share` operator uni doesn't have yet.
2. **Names.** `scan` (RxJS, Monix) over `fold` (which in Scala collections returns one final value). `split` follows Laminar's Airstream, a library many Scala.js users know; the alternatives are `splitBy`/`keyBy` (`groupBy` suggests per-key substreams of single events).
3. **Should `scan` emit `init` first?** No (recommended; RxJS and Monix behaviour) or yes (`scanLeft` in Scala collections, fs2, Airstream).
4. **Row shape: `(K, Rx[A])` as agreed, or also the first value, `(K, A, Rx[A])`?** `(K, Rx[A])` is simpler; the first value is needed only for synchronous layout decisions, and `eachRx` can read it internally.

## Alternatives and Why Not?

- **`split` built on `scan`.** A fold can't stop rows on cancel or completion, and its state would hold side-effecting `RxVar`s.
- **Emit the outer list on every upstream change.** Correct with `each`, but every row change would make the list consumer diff the whole list.
- **Keep the table in uni-dom only.** Works for the DOM, but other consumers (TUI, tests, uni-ui collections) would reimplement it.
