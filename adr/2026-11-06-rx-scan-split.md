# `Rx.scan` and keyed `split`

## Context

`map` and `flatMap` over an `Rx[Seq[A]]` keep no memory between emissions: each one builds a new
`Seq`, so a consumer can't tell a kept item from a new one. uni-dom's `eachRx` (see
`2026-11-05-keyed-list-rendering.md`) solves this inside `DomRenderer` with a key → `RxVar` table,
which only the DOM renderer on Scala.js can use. uni `Rx` also had no operator that carries state
across emissions.

## Decision

- `rx.scan(init)(f)` emits `f(acc, a)` for each upstream value. It doesn't emit `init` (as in
  RxJS and Monix); `startWith(init)` adds it. The accumulator lives in each run, like `LastOp`,
  and an exception in `f` becomes `OnError`.
- `items.split(key)` (an extension on `Rx[Seq[A]]` in the `Rx` companion) gives
  `Rx[Seq[(K, Rx[A])]]`:
  - Each run keeps its own key → `RxVar` table. On each upstream list it sets the new value on
    each kept key's `RxVar` (emitting only when `!=`), creates an `RxVar` for each new key, and
    stops the `RxVar` of each key that left.
  - The outer `Rx` emits only when the key sequence changes. A change inside a row reaches only
    that row's `Rx`.
  - A key keeps the same `RxVar` while it stays, so `(k, rx)` pairs compare equal across
    emissions (reference equality on `RxVar`) and uni-dom's `each(_._1)` never renders a kept row
    again.
  - Removed rows are stopped after the new list is emitted, so a consumer never sees a completed
    row in the current list. Upstream completion and cancellation stop every row; an upstream
    error passes through and the rows stay alive.
  - A repeated key keeps its first item, matching `each`.
- `split` isn't built on `scan`: its state is live `RxVar`s that must be stopped on cancel and
  completion, which a fold can't express.

## Alternatives

- One key table shared by all subscriptions of the same `split` (like `CacheOp`): subscribers
  would see the same row `Rx`s, but stopping rows would need reference counting across
  subscribers, a `share` operator uni doesn't have.
- Emitting the list on every upstream change: still correct with `each`, but every row change
  would make the list consumer diff the whole list.
- `fold` as the name: in Scala collections `fold` returns one final value. `split` follows
  Laminar's Airstream; `groupBy` suggests per-key substreams of single events.

## Consequences

- `eachRx` can be reimplemented as `split` + `each` without changing its signature; its render
  gets the first value from the row's `RxVar.get`.
- A row `Rx` lives as long as its key stays in the list of that subscription.
- A row's `RxVar` keeps replaying its last value to late subscribers after it's stopped, as any
  stopped `RxVar` does.
