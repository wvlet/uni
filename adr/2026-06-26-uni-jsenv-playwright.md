# 2026-06-26: uni-owned Playwright Scala.js JSEnv + sbt 2.x plugin

## Context

PR #612 made `uni-dom-test` run in headless Chromium via the third-party
`io.github.gmkumar2005:scala-js-env-playwright`. That dependency has three problems:

1. **Unmaintained** (latest activity is a README edit).
2. **Scala 2.12 only** — so it cannot be loaded in an sbt 2.x metabuild, which runs on **Scala 3**.
   This blocks uni's eventual sbt 2.x migration for its Scala.js tests.
3. **Heavy deps** — it pulls **cats-effect** and **scribe**, against uni's minimal-dependency rule.

We want Playwright support owned by uni.

## Decision

Add a new standalone sbt 2.x build, `sbt-uni-playwright/`, publishing two artifacts:

- **`uni-jsenv-playwright`** — a `org.scalajs.jsenv.JSEnv` backed by the **Java** Playwright runtime
  (`com.microsoft.playwright`), reimplemented **without cats-effect** (one worker thread + a
  `Promise[Unit]` + a cleanup stack; `ConcurrentLinkedQueue` + `AtomicBoolean` for cross-thread
  `send`/`close`). Depends only on `scalajs-js-envs` + `playwright`.
- **`sbt-uni-playwright`** — an sbt 2.x `AutoPlugin` that wires `Test / jsEnv` to a
  `PlaywrightJSEnv` (configured via `uniPlaywrightBrowser` / `uniPlaywrightHeadless`) and adds a
  `uniPlaywrightInstall` task to pre-download and pin the browser binaries.

**Scope: sbt 2.x / Scala 3 only** (confirmed with the user; no sbt 1.x / Scala 2.12 cross-publish).
Feasibility verified: `sbt-scalajs_sbt2_3` 1.22.0 and `scalajs-js-envs_3` are published.

**v1 features**: screenshot + Playwright trace captured on an uncaught JS error (under
`target/uni-playwright`, with the `show-trace` hint logged); `uniPlaywrightInstall`. Cross-browser /
device matrix and visual regression are fast-follows.

### Mechanism (polling, not CDP)

A JS shim (injected as the first `<script defer>`) buffers `console.*`, `window.onerror`, and
com-messages into `window` arrays and defines the global `scalajsCom` the Scala.js test runtime
talks to. A worker thread navigates a Playwright page to a generated launcher HTML (ESModule inputs
load as `type="module"`), then every ~100ms `page.evaluate`s a `fetch()` to drain those buffers
(delivering com-messages to `onMessage`, writing console to the RunConfig streams, throwing on JS
errors) and pushes queued outbound messages back in. `close()` (harness-driven) stops the loop;
cleanup runs in `finally`.

## Worked examples / gotchas (the non-obvious parts)

- **sbt 2.x dropped `%%%`** — on sbt 2.x, `%%` encodes both the Scala version *and* the Scala.js
  platform suffix (`_sjs1`). The scripted test uses `"org.scala-js" %% "scalajs-dom"`, not `%%%`.
- **sbt 2.x setting caching** — assigning `Test / jsEnv` fails with "given evidence
  `sjsonnew.JsonFormat[JSEnv]` is not found" because sbt 2.x caches setting values. A `JSEnv` is not
  serializable, so the plugin wraps it in `Def.uncached(...)`. Direct users must do the same.
- **Playwright driver classloader** — `Playwright.create()` failed under sbt with a NullPointer in
  `DriverJar.getDriverResourceURI`: Playwright resolves the `driver/` resources (in the
  `driver-bundle` jar) via the **thread context classloader**, which under sbt's task/test execution
  does not see the plugin's dependency jars. `BrowserSession.createPlaywright()` temporarily sets the
  TCCL to `classOf[BrowserSession].getClassLoader` around `create()`. (The third-party worked around
  the same issue by vendoring a whole modified `DriverJar`; the TCCL swap is far smaller.)
- **CI** pins `playwright@1.49.0 install --with-deps chromium` to match the Java Playwright runtime
  version so the cached Chromium build matches and the OS libraries are present.

## Consequences

- uni's own `uni-dom-test` stays on the third-party env for now (root build is still sbt 1.x). Once
  uni migrates to sbt 2.x, switch it to `sbt-uni-playwright` and drop the third-party dep entirely.
- A new release path is needed for the `sbt-uni-playwright` artifacts (mirror `release-sbt-plugin`).
- Scaladoc on the `JSEnv` overrides carries our own doc comments so it does not inherit the parent
  trait's `[[...]]` links (which don't resolve outside scalajs-js-envs).
