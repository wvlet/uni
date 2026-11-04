# uni-dom testing kit

## Goals

- Apps like `examples/uni-harness` can test uni-dom components with only `uni-test`, without copying helpers.
- Tests for components with `onMount` logic no longer need a hand-written "wait a tick" helper.
- Pointer drags and IME (`isComposing`) key presses can be simulated with `fireEvent`, like clicks and input.

## Background

- The toolkit (`mount`, `Mounted`, queries, `fireEvent`, `DomTestSession`) already ships in `uni.js` and has no dependency on `uni-test`.
- Only the `UniTest` glue (`DomTestSupport`, 3 lines: unmount after each test) is test-scope in `uni-dom-test`. A `UniTest`-coupled trait cannot live in `uni` (production artifact) or `uni-test` (it would depend on `uni`, a cycle), so apps copy it. The 2026-06-27 testing plan already named `uni-dom-testkit` as the follow-up.
- `onMount` of an `RxElement` runs from a `MutationObserver` callback (and, for an element with an id not yet in the document, a further `setTimeout(0)`), so it fires after `mount` returns.
- `awaitValue` (wait for an Rx value with a timeout) is also test-scope only, though it depends on nothing but `Rx`.
- This work does not need #849; it branches from `origin/main`.

## Design

Lane: **Standard** — moves code between published artifacts (packaging, an always-gate area) and adds public API. Revised after review: the user chose moving DOM into uni-core over a separate `uni-dom-testkit` artifact.

User flow (an app author testing a Scala.js UI):
- Keep the usual `uni-test % Test` dependency and enable `UniPlaywrightPlugin` for a headless Chromium.
- Write `class MyTest extends DomTestSupport`, `import wvlet.uni.dom.testing.*`, then `mount`, `fireEvent`, and query; mounts are removed after each test.
- When the component does work in `onMount`, return `settle().map { _ => ...assertions }` from the test.

Decisions:
- **Move `wvlet.uni.dom` from `uni.js` to `uni-core.js`**, except `all.scala` (it re-exports `Storage`) and `Storage.scala` (it needs `wvlet.uni.config`). It depends only on `rx` and `log`, and `scalajs-dom` is already a uni-core.js dependency. Package names stay; `uni` depends on `uni-core`, so apps see no change.
- **`DomTestSupport` ships in `uni-test.js`** (package `wvlet.uni.dom.testing`, unchanged). No new artifact.
- **`settle(): Rx[Unit]`** in `wvlet.uni.dom.testing`: completes after pending mutation observers and the zero-delay timers they queue have run (two macrotask hops). Returns `Rx` to match `awaitValue`.
- **Move `awaitValue`** from test scope next to `settle`.
- **Pointer events in `fireEvent`**: `pointerDown/Move/Up/Cancel/Over/Out/Enter/Leave` with `clientX`, `clientY`, `pointerId = 1`, `pointerType = "mouse"`, `button = 0`; enter/leave don't bubble.
- **`isComposing: Boolean = false`** on `keyDown` / `keyUp` / `keyPress`. Source-compatible; binary-incompatible for those 3 methods (MiMa is not enabled).
- uni-harness deletes its `DomTestSupport` copy and its `keyDown`/`pointer`/`afterMount` helpers. No UI changes.
- Docs: DOM-testing section in the skill's `references/testing.md`, a row in `docs/guide/for-ai-agents.md`, a section in `docs/build/sbt-uni-playwright.md`, and the uni-core line in `AGENTS.md`; bump the plugin version.

## Alternatives and Why Not?

- A new `uni-dom-testkit` artifact (first implementation): one more dependency for apps to find and keep in sync.
- `uni-test` depending on `uni`: a project cycle, since `uni` uses `uni-test` for its own tests.
- A lifecycle hook trait in uni-core so `DomTestSession` mixes into `UniTest`: smaller diff, but apps still mix in two traits and DOM stays out of the test framework.
- `settle(): Future[Unit]`: also fine for `UniTest`; `Rx` keeps one style with `awaitValue` and chains with other Rx helpers.
- An `onMount` that fires synchronously in `DomRenderer`: changes runtime behaviour for every app; out of scope here.
