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
