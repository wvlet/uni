# 2026-10-19: Page commands — Scala.js tests call JVM-side Playwright

## Context

`uni-jsenv-playwright` runs Scala.js tests inside a Playwright page, but the Playwright `Page`
lives on the JVM in `PlaywrightEngine`. Test code had no way to reach it, so the only screenshot
was the one taken after an uncaught error. Java Playwright also lacks `toHaveScreenshot`, which
exists only in Node's `@playwright/test`. Design note: `plans/2026-10-19-playwright-command-bridge.md`.

## Decision

- **Channel:** the JS shim (`JsBridge`) exposes `globalThis.__uniPlaywright = {version, call}`.
  `call(cmd, args)` queues `{id, cmd, args}` and returns a Promise. The engine drains the queue in
  the same `fetch()` it already polls, runs each command through `PageCommands` on its worker
  thread, and settles Promises with one `resolveBatch` evaluate. Polling drops to 10ms for 1s after
  a command, so chained commands do not each wait 100ms.
- **Not `page.exposeFunction`:** its callback runs inside a Playwright dispatch, and calling
  `page.screenshot` from there is reentrant. Reusing the poll loop keeps every Playwright call on
  one thread, as the original JSEnv design requires.
- **Errors reject, they do not fail the run.** A failed `expectScreenshot` fails only its test.
- **Protocol version** (`JsBridge.protocolVersion`, now 1): the client requires an exact match, so
  a client and JSEnv from incompatible releases fail with a clear error instead of hanging or
  misreading results. Bump it on any
  incompatible command or result change.
- **Path safety:** page code can make the JVM write files. `name` must match `[A-Za-z0-9._-]+`
  without `..`, and `PageCommands.within` rejects any path that escapes its directory.
- **Visual regression:** goldens are `<snapshotDir>/<name>-<browser>-<os>.png`, using
  `@playwright/test`'s OS names, because font rendering differs per browser and OS. `ImageDiff`
  uses only JDK `ImageIO`, with a per-channel threshold (simpler than pixelmatch's YIQ metric, but
  deterministic and dependency-free). A **missing golden is written and fails**, so it is never
  accepted silently. Update mode (setting or `UNI_PLAYWRIGHT_UPDATE_SNAPSHOTS=1`) overwrites.
  The context pins a 1280×720 viewport and device scale factor 1. `expectScreenshot` captures until
  two consecutive shots are identical (up to 10), like `toHaveScreenshot`, to avoid flakiness from
  late-loading fonts or images.
- **Client in `uni-test` (`.js`):** `wvlet.uni.test.playwright.Playwright`, a thin `Future`
  wrapper. It reads the bridge via `globalThis` (a bare global reference throws a ReferenceError
  under ES modules when the bridge is absent).

## Consequences

- The main build loads the **released** `uni-jsenv-playwright`, so `uni-dom-test` can use the
  client only after the next release bumps `UNI_PLUGIN_VERSION`. Until then the protocol is
  covered by the `sbt-uni-playwright` scripted test and the client's "unavailable" path by a
  Node.js test in `uni-test`.
- New commands (trusted input, viewport) go into `PageCommands.run` plus a client method.
