# Stable chat bubble keys and a keyed chat list in uni-harness

## Goals

- A sent prompt plays its `rise` entrance (and the glow shadow fades in) once, not again when the reply arrives.
- A conversation rename (the title set by the first reply) updates one sidebar row, not every row.

## Background

- `ChatStore.send` shows the prompt as a draft (`draft-N`), then replaces it with the stored prompt (server id) and the reply, and renames the conversation in the same step.
- The thread keys bubbles by `_.id`, so the id swap removes the draft bubble and mounts a new one: the entrance replays.
- `KeyedList` also re-renders an item whose value changes (`!=`, see `adr/2026-11-05-keyed-list-rendering.md`), so a stable key alone is not enough: the confirmed prompt differs from the draft in `id` and `createdAt`.
- `ChatList` renders `Rx.join(conversations, selected).map(...)`, which rebuilds both day groups and all rows on any change.

## Design

- Lane: **UI** — a visual bug fix on existing screens; no new screen, setting, or persisted state, and no change to uni-dom.
- `ChatStore` keeps an alias from the stored prompt's id to its draft's id, and exposes `keyOf(m)` (alias or own id).
  - The alias is set only when the draft is still in the thread; a thread reloaded mid-send keeps the server ids.
  - Aliases reset on `select`, so they never grow past one thread.
- The thread renders `each` over the keys, not the messages, so an item never changes value and never re-renders.
  - `Bubble` gets its message as an `Rx` by key; only its "Sending…" / time label follows it. The text is the same for draft and stored prompt.
- `ChatList` renders each day group with `each` keyed by conversation id over `(conversation, active)` pairs; a group's header hides when it is empty.
  - A rename or selection change re-renders only the rows whose title, time, or active state changed.
- `messageCount` and the out-of-order handling stay as they are (they work on ids, which don't change).
- Tests cover: the user bubble keeps its DOM node across a send (and its label changes from "Sending…"), and a rename keeps the other sidebar rows' nodes.

## Alternatives and Why Not?

- Keep the draft's id on the stored prompt (rewrite `reply.user.id`): the client would hold ids the server doesn't know, which breaks replace-by-id when a reload races the reply.
- Change `KeyedList` to skip re-rendering equal keys: out of scope, and other callers rely on value changes re-rendering.
- Remove the `rise` class after the first animation: hides the symptom; the node is still rebuilt.
