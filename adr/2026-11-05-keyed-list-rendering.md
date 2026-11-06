# Keyed list rendering in uni-dom (`each`)

## Context

An embedded `Rx` is rendered by clearing its section and rendering the new value from scratch. For a
list (`items.map(_.map(render))`), every update throws away every row's DOM nodes. In the
uni-harness chat, each prompt updates the message list twice (the draft, then the reply), so the
whole thread was rebuilt twice per send. The scroll container briefly emptied and jumped, and the
thread flickered. Nested sections had a related bug: `createMountSection` always appended its marker
nodes at the end of the parent, so an `Rx` rendered directly inside another `Rx` moved after its
later siblings when the outer one re-rendered.

## Decision

- Add `KeyedList(items, key, render)`, created with `each(items)(key)(render)` and exported from
  `wvlet.uni.dom.all`. The renderer gives the list and each item a pair of empty text nodes. On
  each update it removes items whose key is gone, renders items with a new key, renders an item
  again when its value changed (`!=`), and moves the remaining items' nodes into the new order
  without rebuilding them.
- A repeated key keeps only its first item and logs a warning, so a duplicate key can't leave two
  items that share one set of markers.
- `createMountSection` takes the anchor and places its markers there, so nested sections stay in
  place.

## Update (2026-11-06): items that update in place (`eachRx`)

Re-rendering a changed item is wrong when the row owns state, such as a chat bubble whose entrance
animation plays once. The uni-harness thread worked around it by keying over plain strings and
looking each message up again. `eachRx(items)(key)((first, current) => ...)` (`KeyedRxList`)
shares the renderer with `each` but, for a changed value under the same key, keeps the item's nodes
and sets the new value on the item's own `RxVar`, which emits only when the value differs. The row
binds the parts that change to `current` (typed `Rx[A]`, so it can't write it).

- A separate name, not an `each` overload: a render taking two parameters would capture existing
  `each` calls over tuple items (`(c, active) => ...`), and a render taking `Rx[A]` alone has the
  same arity as `A => DomNode`, so untyped lambdas would be ambiguous.
- `render` gets the first value as well, since an `Rx` can't be read without subscribing; the row
  uses it for its layout and once-only logic.
- When a key leaves the list, the item's subscriptions are cancelled and its `RxVar` is stopped
  (`OnCompletion`), so subscribers outside the row end too. A key that returns renders from scratch.

## Alternatives

- Diffing the old and new rendered trees (a virtual DOM): a much larger change to the renderer, and
  uni-dom renders `Rx` sections directly by design.
- Reusing nodes by position instead of by key: an insert at the top would re-render every row
  below it, and a row's state would follow its position instead of its data.
- A `distinct` operator on `Rx` so callers can skip equal values: it helps only when nothing
  changed, and list updates almost always change something.

## Consequences

- An item's own `Rx` subscriptions live as long as its key does, and are cancelled when it's
  removed.
- An item renders once per key, so side effects in `render` (such as "animate only the new reply")
  run once per item, not once per update.
- Equality decides whether an item renders again: a value with reference equality (a plain class)
  renders again whenever the list emits a new instance.
