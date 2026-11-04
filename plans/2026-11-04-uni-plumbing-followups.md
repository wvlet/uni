# Plumbing follow-ups from uni-harness: enum StorageCodec, ElectronRenderer.installIfAvailable, static files

## Goals

- Turn the three workarounds in `examples/uni-harness` into library APIs, so every Uni app gets them without copying harness code (follow-up #8 of `plans/2026-11-03-uni-dom-workspace-fixes.md`).
- Static file serving must be safe by default. It is a security boundary, and an app that serves its built frontend from `Http.server` should not need to know about path traversal or symlink escapes.
- Switch the harness to the new APIs and delete its copies.

## Background

- `StorageCodec` (in `uni`, `wvlet.uni.dom`) has givens for primitives only, so the harness hand-writes `enumCodec(values)` for three enums.
- `ElectronRenderer.install()` throws when `window.uniRPC` is missing, so a renderer shared by Electron and the browser has to probe `js.typeOf(window.uniRPC)` first.
- The harness's `StaticFiles` is Node-only (`fs` through `js.Dynamic`). It checks traversal with `path.resolve`, but follows symlinks, serves dotfiles, and sends no `nosniff`.
- uni-core's `FileSystem` already runs on the JVM, Node, and Native, and its `info` uses `lstat` on all three. Static serving can therefore be written once, in shared code, with no new dependency.
- `adr/2026-10-14-http-server-secure-defaults.md`: secure by default, with explicit opt-outs. `adr/2026-10-31-http-server-entry-point.md`: a feature joins `Http.server` only when it works on every backend.

## Design

Lane: Standard. Static file serving is a security boundary (always-gate), and it adds public API.

**Split into two PRs**, both covered by this note, so that review of the security boundary stays focused:

- **PR A (small): enum codec and `installIfAvailable`.** Renderer-only, about 50 lines plus docs and the harness switch.
- **PR B: `StaticFiles` for `Http.server`.** Ships second, from a fresh branch off `main`.

### PR A

- **`enum View derives StorageCodec`.** `StorageCodec.derived` uses `Mirror.SumOf`. It encodes a value as its case name, and `decode` returns `None` for unknown names, so `StorageVar` falls back to its default when an enum case is renamed.
  - Only enums whose cases are all singletons compile. Parameterized cases get a compile error that names the case.
  - It is opt-in (`derives`), not an automatic given for every enum, so the stored format stays a visible choice.
- **`ElectronRenderer.installIfAvailable(): Boolean`.** It installs the IPC channel when `window.uniRPC` exists and returns whether it did. The harness picks its transport from the result.

### PR B: user flow

- An app author has a built frontend in `dist/` and an API on the same server. They write `Http.server.withFilter(StaticFiles("dist")).withRxHandler(api)`.
- `GET`/`HEAD` for an existing file under the root returns the file. Anything else falls through to `next`, so the RPC/router handler is unaffected.
- `StaticFiles("dist").withSpaFallback` serves `index.html` when `next` answers 404 for a `GET` that accepts `text/html`. This is the harness's current behaviour.
- The app knows it works when the page loads. A missing or blocked path is a plain 404, the same as any unknown route, so it reveals nothing about the file system.

### PR B: security decisions

- **Path traversal.** The path is percent-decoded once and split on `/`. A segment of `..`, `.`, or empty, or one containing `\`, NUL, or a control character, gives 404 before the file system is touched. The resolved path must sit inside the root, checked by segment rather than by string prefix.
- **Symlinks.** Every component below the root is `lstat`ed, and a symlink anywhere gives 404. The root itself may be a symlink, because the operator chose it. No opt-out for now: bundlers emit real files, and "follow but stay contained" needs a cross-platform `realpath`.
- **Dotfiles.** A segment starting with `.` gives 404, so `.env` and `.git/` are never served. `.well-known/` is allowed by default, and `withDotfiles(true)` allows all dotfiles.
- **Directories.** A directory serves `index.html` only. There is no listing.
- **Content types.** A built-in extension table covers html, css, js/mjs, json, map, svg, png, jpg, gif, webp, avif, ico, woff/woff2, ttf, wasm, txt, and xml, with `charset=utf-8` for text types. Unknown extensions get `application/octet-stream`. Every response sets `X-Content-Type-Options: nosniff`. `withContentType(ext, type)` adds or overrides an entry.
- **Methods.** Only `GET` and `HEAD` are served. Other methods go to `next`, never 405, so the API on the same paths keeps working.
- **Scope.** The filter is meant for app bundles. It reads each file whole (no `Range`, ETag, or streaming) and adds no cache headers. Caching and large-file streaming are later additions.

### PR B: platforms

- Works on the JVM (Netty), Node.js, and Scala Native through uni-core `FileSystem`, so it lives in shared `wvlet.uni.http` code.
- In a browser the filter refuses at construction, because there is no file system.
- Tests run the same traversal, symlink, dotfile, and type cases on all three platforms against a temp directory.

### Docs (both PRs)

- `docs/dom/index.md` (the enum codec), `docs/http/electron.md` (`installIfAvailable`), and a "Static Files" section in `docs/http/server.md`.
- `docs/guide/for-ai-agents.md` and `plugins/uni/skills/uni` get the new APIs, checked by `./sbt docSnippets/test`, with a version bump in `plugin.json`.
- PR B adds a short ADR for the static-file security defaults.

## Alternatives and Why Not?

- **One PR for all three:** puts two trivial renderer changes into the review of a security boundary, and holds them back until that review ends.
- **`HttpServerConfig.withStaticFiles(dir)`:** a config field that a filter does as well. As a filter it also composes with `RxRouter` and `Netty.server`.
- **Encode enums by ordinal:** reordering cases would silently change stored values.
- **Follow symlinks with a `realpath` containment check:** needs a `realpath` on all three platforms that uni-core lacks. Rejecting symlinks is simpler and safe, and it can be relaxed later.
