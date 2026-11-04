# uni-dom testing kit

## Goals

- Apps like `examples/uni-harness` can test uni-dom components by adding one dependency, without copying helpers.
- Tests for components with `onMount` logic no longer need a hand-written "wait a tick" helper.
- Pointer drags and IME (`isComposing`) key presses can be simulated with `fireEvent`, like clicks and input.

## Background

- The toolkit (`mount`, `Mounted`, queries, `fireEvent`, `DomTestSession`) already ships in `uni.js` and has no dependency on `uni-test`.
- Only the `UniTest` glue (`DomTestSupport`, 3 lines: unmount after each test) is test-scope in `uni-dom-test`. A `UniTest`-coupled trait cannot live in `uni` (production artifact) or `uni-test` (it would depend on `uni`, a cycle), so apps copy it. The 2026-06-27 testing plan already named `uni-dom-testkit` as the follow-up.
- `onMount` of an `RxElement` runs from a `MutationObserver` callback (and, for an element with an id not yet in the document, a further `setTimeout(0)`), so it fires after `mount` returns.
- `awaitValue` (wait for an Rx value with a timeout) is also test-scope only, though it depends on nothing but `Rx`.
- This work does not need #849; it branches from `origin/main`.

## Design

Lane: **Standard** — publishes a new artifact (packaging, an always-gate area) and adds public API.

User flow (an app author testing a Scala.js UI):
- Add `"org.wvlet.uni" %% "uni-dom-testkit" % version % Test` next to `uni-test`, with the Playwright JS env as today.
- Write `class MyTest extends DomTestSupport`, `import wvlet.uni.dom.testing.*`, then `mount`, `fireEvent`, and query; mounts are removed after each test.
- When the component does work in `onMount`, return `settle().map { _ => ...assertions }` from the test.

Decisions:
- **New module `uni-dom-testkit`** (sbt project `domTestkit`, Scala.js only, dir `uni-dom-testkit/`), published with the other JS projects. It depends on `uni.js` and `uni-test.js` and holds only `DomTestSupport`, in package `wvlet.uni.dom.testing` (unchanged, so existing imports keep working).
- `uni-dom-test` (unpublished tests) depends on the new module instead of defining its own trait.
- **`settle(): Rx[Unit]`** in `uni.js` (`wvlet.uni.dom.testing`, top-level, so any framework can use it): completes after pending mutation observers and the zero-delay timers they queue have run (two macrotask hops). Returns `Rx` to match `awaitValue`; `UniTest` accepts an `Rx` test body.
- **Move `awaitValue` to `uni.js`** next to `settle`, since it is framework-agnostic.
- **Pointer events in `fireEvent`**: `pointerDown`, `pointerMove`, `pointerUp`, `pointerCancel`, `pointerEnter`, `pointerLeave`, `pointerOver`, `pointerOut`, each with `clientX`, `clientY`, `pointerId = 1`, `pointerType = "mouse"`, `button = 0` defaults.
- **`isComposing: Boolean = false`** added to `keyDown` / `keyUp` / `keyPress`. Source-compatible; binary-incompatible for those 3 methods (MiMa is not enabled).
- uni-harness renderer depends on `domTestkit % Test`, deletes its `DomTestSupport` copy and its `keyDown`/`pointer`/`afterMount` helpers. No UI changes.
- Docs: a DOM-testing section in the skill's `references/testing.md` and an entry in `docs/guide/for-ai-agents.md` (snippets compile-checked; Scala.js fences marked `skip` where needed); bump the plugin version.
- Tests cover `settle` with an `onMount` component, each pointer helper's fields, and `isComposing`.

## Alternatives and Why Not?

- Put `DomTestSupport` in `uni-test.js`: `uni-test` would depend on `uni`, which depends on `uni-test` for its own tests — a project cycle.
- Name it `uni-test-dom`: reads as a part of the test framework; `uni-dom-testkit` matches the earlier plan and the existing scaladoc.
- `settle(): Future[Unit]`: also fine for `UniTest`; `Rx` keeps one style with `awaitValue` and chains with other Rx helpers.
- An `onMount` that fires synchronously in `DomRenderer`: changes runtime behaviour for every app; out of scope here.
