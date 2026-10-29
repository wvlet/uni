# StorageVar on LocalStorageBackend

## Goals

- Finish the item deferred in `2026-10-27-setting-store.md`: `dom.StorageVar` and `config.LocalStorageBackend` each read and write `dom.Storage` and listen for `storage` events, so there are two copies of the same logic.
- Make `StorageVar` use `LocalStorageBackend`, with no change to the `Storage.local`/`Storage.session` and `StorageVar` APIs or their behavior.

## Background

- `StorageVar` stores a value under the raw key (`theme`). `LocalStorageBackend` stores it under `<prefix>/<key>`. Existing users have data under raw keys, so the keys must stay the same.
- `StorageVar` works with both localStorage and sessionStorage. `LocalStorageBackend` already accepts any `dom.Storage`.

## Design

- Lane: **Express**. An internal refactor with no change in behavior. The stored keys and values stay the same, so no data moves.
- An empty prefix makes `LocalStorageBackend` use raw keys. Today an empty prefix gives `/key`, which nothing relies on.
- `StorageVar` keeps its constructor and holds a `LocalStorageBackend("", storage)`. It reads, writes, and removes through the backend, and gets cross-tab sync from `backend.watch(key)`.
- When a change arrives from another tab, `StorageVar` reads the key again. Before, it decoded `e.newValue`, which holds the same text, and a cleared storage already worked this way.
- Tests: the existing `StorageTest` stays as it is, and a new test checks that a backend with an empty prefix uses raw keys.

## Alternatives and Why Not?

- Add a separate raw-key backend class: it would add a public class just to keep one behavior.
- Move existing data to prefixed keys: this would change where data is stored and need a migration, for no benefit.
