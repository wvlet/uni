# SettingStore: an RxVar saved through a StoreBackend, with a Node backend that avoids FileSystem

## Context

Desktop and web apps built on JavaScript often keep user settings with zustand and its `persist` middleware: a reactive store, a storage backend, a stored version, and migrations. Uni had the pieces (`RxVar`, `Weaver`, `FileSystem`, and the browser-only `dom.StorageVar` for primitive types), but nothing that keeps a typed settings value on every platform or handles schema changes.

## Decision

- `SettingStore[A]` (`wvlet.uni.config`, in `uni` because it needs `Weaver`) extends `RxVar[A]`. Subscriptions and selectors are ordinary Rx, so there is no store/action/middleware layer as in zustand. Every `update` writes through synchronously; settings change rarely, and synchronous reads mean no hydration state to track.
- Stored data is `{"version": n, "state": <Weaver JSON>}`, the same shape as zustand's. An older version goes through `migrate(stateJson, fromVersion)` on the untyped `JSONValue`, before decoding, and the result is written back. Missing fields take case-class defaults, which covers zustand's `merge`. There is no `partialize`; transient state goes in a separate `RxVar`.
- Unreadable data is moved aside (`<fileName>.corrupt-<timestamp>`), not overwritten, and the store starts from the default. Write errors are logged; `set` never throws because of storage.
- The default location is `~/.config/<app>/` on every OS (`$XDG_CONFIG_HOME/<app>/` when set). `SettingLocation.OSConfig`, `Project(dir)`, and `Directory(path)` are opt-in. One store uses one location: settings are not merged across layers.
- The API is a builder, like `RPCClient.of[Api]` and `Http.client`: `SettingStore.of[A]` returns a `SettingStoreConfig[A]`, `withXXX(...)` sets options, and `load(fileName = "settings.json")` creates the store. The file name, with its extension, is the storage key (the file name under the setting location, or `<app>/<fileName>` in localStorage), so the call says which file it reads; `withAppName` stays in the builder because it is not needed with `Directory` or `withBackend`, and two positional `String`s would be easy to swap. The config is typed because `withVersion`/`withMigrate` describe one type's schema. The starting value is built with `Surface` from the default parameter values (`None` for an `Option` without one), or set with `withDefault(...)`; a type with a field that has no default fails at `load` with a message pointing to `withDefault`.
- `StoreBackend.watch` only signals that `key` may have changed. The store re-reads the data while holding its own lock, the same lock `update` and `save` hold. Watch callbacks can run on another thread (the JVM file watcher), and a callback that carried the data could apply a stale value after a newer update. Each write fires a watch event for the store's own write, but that reload is a no-op because the value is equal. Unreadable data on reload keeps the current value. `update` writes only when the value changed (or `force` is set).
- File names must match `[A-Za-z0-9_-][A-Za-z0-9_.-]*` (no path separators or leading dot), so a name cannot escape the setting directory.
- **Scala.js code in `wvlet.uni.config` must not reach `wvlet.uni.io.FileSystem`.** `FileSystem` on Scala.js links `NodeFS`, whose static `@JSImport("fs")` becomes a top-level ESM `import "fs"`, so the whole browser bundle fails to load. This is the same problem as `worker_threads` in `2026-05-14-nodejs-sync-http.md`. So on Scala.js:
  - `SettingStoreCompat.defaultBackend` returns `LocalStorageBackend` in a browser and `NodeFileStoreBackend` on Node.js. `NodeFileStoreBackend` loads `fs` at runtime with `NodeModules.builtin`. It duplicates `FileStoreBackend`'s file layout and its temp-file-then-rename write.
  - `SettingLocation` gets the home directory from `SettingStoreCompat.homeDir` (`os.homedir()` loaded at runtime) instead of `IOPath.homeDir`.
  - `FileStoreBackend` stays in shared code for the JVM and Native, and Node users can still choose it explicitly.
- `NodeModules.isNode` now tests `js.typeOf(process)`. Reading an undeclared global threw `ReferenceError` in browsers, so the check could not return false there.

## Consequences

- `uni-dom-test` (Chromium) covers the browser path: the bundle loads and the default backend is localStorage. Shared tests run on the JVM, Scala.js on Node, and Native.
- `dom.StorageVar` is unchanged. It could later be built on `LocalStorageBackend`.
- If `NodeFS` ever switches to runtime loading, `NodeFileStoreBackend` can be dropped in favour of `FileStoreBackend` on every platform.
