# 2026-06-26: ES-module DOM tests in a headless browser (Playwright)

## Context

`uni-dom` is the Scala.js DOM/Rx UI layer, and its tests (`uni-dom-test`) need a real DOM —
9 of them call `document` / `window` / `getBoundingClientRect`, render reactive elements, mount
portals, etc. The rest of the Scala.js build already runs under `ModuleKind.ESModule`
(`jsBuildSettings`), because Uni imports npm/ESM packages and targets web + Electron apps.

`uni-dom-test` was the one holdout: it ran under `JSDOMNodeJSEnv` (jsdom), and jsdom only supports
plain scripts, so the module forced `ModuleKind.NoModule`. jsdom is also stuck on an old line, never
shipped real ESM support, and is an imperfect emulation of a browser. That split meant DOM code was
exercised under a *different* linker/module configuration than it ships in — and any test reaching
for ESM-only behavior (dynamic `import`, ES-module npm deps) could not run there at all. This blocks
using Scala.js unit tests to develop web and Electron front-ends, which is the whole point of
`uni-dom`.

## Decision

Run `uni-dom-test` in a **real headless Chromium via Playwright**, dropping jsdom entirely.

- **`project/plugin.sbt`**: replace `scalajs-env-jsdom-nodejs` with
  `"io.github.gmkumar2005" %% "scala-js-env-playwright"`. This JSEnv is backed by the **Java**
  Playwright (`com.microsoft.playwright` 1.49.0) — it bundles its own driver and downloads browsers
  on demand, so running the tests needs **no Node.js and no npm packages**.
- **`build.sbt` (`domTest`)**: `Test / jsEnv := new jsenv.playwright.PWEnv(browserName = "chromium",
  headless = true, showLogs = true)`, and *delete* the `Test`-scoped `NoModule` override so the
  module inherits `ModuleKind.ESModule` from `jsBuildSettings`. DOM code is now tested under the same
  module config it ships in.
- **Chromium, specifically**: the Electron renderer *is* Chromium, so these tests exercise web and
  Electron app code in the same engine they run in production. (`firefox` / `webkit` are also
  available via `browserName` if cross-engine coverage is ever wanted.)
- **`package.json`**: remove the `jsdom` dependency — nothing else used it (the docs build is
  VitePress only).
- **CI (`.github/workflows/test.yml`)**: before `projectJS/test`, run
  `pnpm dlx playwright@1.49.0 install --with-deps chromium`. Pin to **1.49.0** to match the Java
  Playwright runtime so the installed Chromium build (revision 1148) is the one
  `com.microsoft.playwright` expects, and `--with-deps` pulls the OS libraries Chromium needs on the
  runner. Java Playwright then finds the cached browser and skips its own download.

## Consequences

- `./sbt domTest/test` (and `projectJS/test`, which aggregates it) launches a headless Chromium. The
  browser is downloaded once to `~/.cache/ms-playwright` (`~/Library/Caches/ms-playwright` on macOS)
  and cached; the first run pays a one-time download (~tens of seconds), later runs are fast.
- Playwright's logs are off by default; set `PLAYWRIGHT_SHOW_LOGS=true` to surface the browser
  bring-up logs when debugging a flaky DOM test locally.
- Tests that needed a real browser API (e.g. `MediaQueryTest`, `AnimationFrameTest`) were upgraded
  from compile-only "API surface" stubs to actual runtime assertions, since `window.matchMedia` /
  `requestAnimationFrame` now exist. Async DOM tests return a `Future` (UniTest auto-awaits) and use
  a `setTimeout` fallback so they fail fast instead of hanging if a browser callback never fires.
- All 366 `uni-dom-test` tests pass under ESM + Chromium; the full `projectJS/test` suite is green.
- **Version coupling**: the CI `playwright@<ver>` install must track the Java Playwright version that
  `scala-js-env-playwright` pulls in. When bumping `scala-js-env-playwright`, check the transitive
  `com.microsoft.playwright` version and update the pin in `test.yml`. (Mismatch → Java Playwright
  re-downloads its own matching build, so it still works, just slower / not cached by the npm step.)
- No Node.js is required to run the DOM tests, but CI keeps the existing `pnpm` setup because the
  other Scala.js modules run under `NodeJSEnv` and the `playwright install` CLI is convenient for
  fetching system deps.
