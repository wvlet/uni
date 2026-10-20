# Trusted input and viewport page commands

## Goals

- Let Scala.js tests running in the Playwright page drive **real browser input**: `click`,
  `hover`, `fill`, and keyboard `type` / `press`. Events made with `dispatchEvent` have
  `isTrusted = false`, skip default actions (for example, focus moves, text insertion, form
  submission on Enter, `:hover` styles), and are ignored by code that checks `isTrusted`.
- Let tests **resize the viewport** (`setViewportSize`), so media-query code (for example
  `uni-dom-test` `MediaQueryTest`) runs against real `matchMedia` changes instead of stubs.
- Reuse the page-command bridge from #714 unchanged. Add no dependencies, and use only Java
  Playwright 1.49.0 APIs.

## Background

- #714 added `globalThis.__uniPlaywright.call(cmd, args)`. The engine drains commands in its
  100ms poll, runs them in `PageCommands.run` on its single worker thread, and settles the JS
  Promises (see `adr/2026-10-19-playwright-page-commands.md`). The ADR already names trusted
  input and viewport as the next commands.
- One JSEnv run uses **one page** (`BrowserSession.launch`) for every test class in that run,
  with the viewport pinned to 1280×720 so screenshots are reproducible. A viewport change
  therefore leaks into later tests and screenshots unless it is restored.
- Java Playwright input calls (`page.click`, `locator.fill`, …) wait for the element to be
  actionable, with a 30s default timeout. They block the engine's worker thread while they wait,
  which also pauses console and test-protocol draining.
- Release coupling: the main build loads the **released** `uni-jsenv-playwright`
  (`UNI_PLUGIN_VERSION` = 2026.1.26). `uni-dom-test` can use these commands only after the next
  release, so this PR covers them in the `sbt-uni-playwright` scripted test.

## Design

**Lane: Standard.** It adds new public test APIs (client methods and protocol commands).

1. **New commands in `PageCommands.run`** (selectors are Playwright selectors, CSS by default):

   | cmd | args | Playwright call | result |
   |---|---|---|---|
   | `click` | `selector`, `timeout?` | `page.locator(sel).click(timeout)` | `null` |
   | `hover` | `selector`, `timeout?` | `locator.hover(timeout)` | `null` |
   | `fill` | `selector`, `text`, `timeout?` | `locator.fill(text, timeout)` | `null` |
   | `type` | `text` | `page.keyboard().type(text)` | `null` |
   | `press` | `key` (e.g. `Enter`, `Control+A`) | `page.keyboard().press(key)` | `null` |
   | `setViewportSize` | `width`, `height` | `page.setViewportSize(w, h)` | previous `{width, height}` |

   - `type` and `press` act on the focused element, like Playwright's `keyboard`. Tests focus
     with `click` first. `press` with a selector is left out; `click` + `press` covers it.
   - Locator commands use **strict mode** (Playwright's default), so a selector that matches
     several elements rejects instead of clicking an arbitrary one.
   - **Timeout:** the default is **5s**, not Playwright's 30s, because a wait blocks the whole
     poll loop. A caller can pass `timeout` (ms). A timeout rejects the Promise with
     Playwright's message, which names the selector.
   - Missing or wrongly typed arguments (`selector`, `text`, `key`, positive integer
     `width`/`height`) reject with a clear message before calling Playwright.
   - Effects are visible to the test when the Future completes: Playwright dispatches the input
     before the command result is delivered, and the page's event handlers run synchronously.
2. **Viewport restoration.** `setViewportSize` returns the previous size. The client adds
   `withViewportSize(w, h)(body: => Future[A])`, which sets the size, runs `body`, and restores
   the previous size whether `body` succeeds or fails. This is the recommended form, so the
   1280×720 baseline for screenshots holds across tests. The raw `setViewportSize` stays
   available.
3. **Client (`uni-test` `.js`, `wvlet.uni.test.playwright.Playwright`) returns `Rx`, not
   `Future`.** Uni's own async APIs (`HttpClient.send`, `FetchChannel`, WebSocket) return `Rx`,
   and UniTest already runs an `Rx` test body (`compat.runRxAsFuture`). `Rx` is also **lazy**:
   a page command is sent only when the `Rx` is run, so a step sequence such as
   `click(...).flatMap(_ => press("Enter"))` is a description that runs in order. A `Future`
   starts when it is created, which makes `withViewportSize(...)(body)` and step ordering easy
   to get wrong.
   ```scala
   def click(selector: String, timeoutMillis: Int = 5000): Rx[Unit]
   def hover(selector: String, timeoutMillis: Int = 5000): Rx[Unit]
   def fill(selector: String, text: String, timeoutMillis: Int = 5000): Rx[Unit]
   def typeText(text: String): Rx[Unit]   // `type` is a Scala keyword
   def press(key: String): Rx[Unit]
   def setViewportSize(width: Int, height: Int): Rx[(Int, Int)]  // previous size
   def withViewportSize[A](width: Int, height: Int)(body: Rx[A]): Rx[A]
   ```
   - Each method wraps the bridge Promise in a deferred `Rx` (the call happens on run, once per
     run), so re-running an `Rx` re-sends the command.
   - **`screenshot` / `expectScreenshot` change to `Rx` too**, for one consistent API. This is a
     source-breaking change to a client that first shipped in 2026.1.26. Its only in-repo user
     is `uni-dom-test` `VisualTest` (3 call sites, updated in this PR, because `uni-dom-test`
     uses `uni-test` from source). The branch uses the `breaking/` prefix. Existing `Future`
     code still composes through `Rx.future(f)` and `rx.andThen(a => future)`.
   - The client always sends `timeout`, so the 5s default is decided in one place (the JVM side
     applies the same default for raw-protocol callers).
4. **Protocol version stays at 1.** The additions are backward compatible: existing commands and
   result shapes do not change. A new client against an older JSEnv gets a rejection with
   `Unknown page command: click`. The client rewrites that message to add "requires a
   newer uni-jsenv-playwright (released after 2026.1.26)", so the user sees the fix. A
   version bump would instead break every existing `screenshot` user on an older JSEnv for no
   gain.
5. **Tests**
   - Scripted test (`plugin/src/sbt-test/playwright/basic`), new `InputTest` on the raw
     protocol: `click` fires a listener with `isTrusted == true` and focuses an input; `type`
     inserts text into the focused input; `press("Enter")` submits a form; `fill` replaces the
     value; `hover` makes `:hover` match (`el.matches(":hover")`); `setViewportSize` changes
     `innerWidth` and a `matchMedia("(max-width: 600px)")` query, returns the previous size, and
     the test restores it; a missing selector rejects with a timeout; a bad width rejects.
   - jsenv unit tests (munit) for argument validation, which needs no browser.
   - `uni-test` Node test: the new client methods fail with the "requires uni-jsenv-playwright"
     message when the bridge is absent.
   - `uni-dom-test` adoption (for example, a real `MediaQueryTest`) waits for the release.
6. **Docs:** extend the ADR's command list and the Playwright section of the docs with the new
   API and the `withViewportSize` recommendation.

## Alternatives and Why Not?

- **Keep `Future` (as #714 shipped).** It needs no breaking change, but it is out of step with
  uni's `Rx`-based async APIs, and its eagerness makes ordered input sequences and
  `withViewportSize` error-prone.
- **`Rx` for new commands only, keep `Future` for screenshots.** This avoids the break but leaves
  two styles in one small object, and tests that mix input and screenshots must convert between
  them.

- **Bump the protocol to 2.** Exact matching would make a new `uni-test` refuse an older JSEnv
  even for `screenshot`, which still works. The "Unknown page command" rejection already fails
  the one test that needs the new command, and the rewritten message names the fix.
- **Reset the viewport automatically between test classes.** The engine does not know where test
  boundaries are; only the Scala.js test runner does. `withViewportSize` keeps the restore next
  to the change. A future runner hook can call the same command.
- **Feature detection (`__uniPlaywright.commands` list).** This is useful only if clients branch
  on it. Tests either need a command or skip themselves, and the rejection serves both cases.
  Add it later if needed.
- **Use Playwright's 30s default timeout.** A mistyped selector would stall the whole run,
  including its console output, for 30s per call.
- **`page.mouse` / coordinate-based input, `dragAndDrop`, `selectOption`, `check`.** These are
  useful but not needed yet. Each is one more row in the table later.
