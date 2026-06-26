# 2026-06-26: uni-owned Playwright Scala.js JSEnv + sbt plugin

## Goal

Stop depending on the third-party `io.github.gmkumar2005:scala-js-env-playwright` (used in PR #612
to run `uni-dom-test` in headless Chromium). It is unmaintained, published **only for Scala 2.12**
(so it cannot load in an sbt 2.0 meta-build, which is Scala 3), and drags in **cats-effect + scribe**
(against uni's minimal-dependency rule). Bring Playwright support into uni itself.

## Decisions (confirmed with user)

- **Target sbt 2.0 / Scala 3 only.** No sbt 1.x / Scala 2.12 cross-publishing.
  - Verified feasible: `sbt-scalajs_sbt2_3` 1.22.0 is published (Scala.js works on sbt 2.0), and
    `scalajs-js-envs_3` (the `JSEnv` API) is on Scala 3 (1.6.0).
  - Consequence: uni's own root build is still sbt 1.x, so it keeps the third-party env for now;
    this artifact is for sbt-2.0 consumers and for uni after its eventual sbt 2.0 migration.
- **Two artifacts**: a `JSEnv` library (the real work) + a thin sbt plugin (DX + tasks).
- **v1 features**: (a) screenshot + trace on failure, (b) browser install/pin task.
  Cross-browser/device matrix and visual regression are fast-follows.
- **No cats-effect.** Reimplement with plain JVM (one worker thread, `Promise[Unit]`, a cleanup
  stack, `ConcurrentLinkedQueue` + `AtomicBoolean`).

## Mechanism (distilled from the third-party impl)

Polling design, not CDP:
1. Inject a JS **shim** (a plain `Input.Script`, runs first via `<script defer>`) that:
   - monkey-patches `console.log/error` to push into `window` arrays;
   - registers `window.onerror` → error array;
   - defines `this.scalajsCom` (com mode) with `init(onMsg)` + `send(msg)` (buffers out-messages);
   - defines an internal control interface with `fetch()` (drains console/errors/out-messages) and
     `send(msg)` (Java→JS injection, buffers until `init` flush).
2. Generate an HTML launcher referencing the shim + the linked JS inputs (`type=module` for
   ESModule), navigate the Playwright `Page` to it (`file://`, with permissive Chromium flags for
   module loading; a `Server`/`http://` materialization mode is the escape hatch).
3. Worker thread loops every ~100ms: push queued outbound msgs via `page.evaluate(fn, msg)`, call
   `fetch()` to drain console/errors/inbound msgs, write console to the RunConfig streams, deliver
   inbound msgs to `onMessage`, throw on `window.onerror`. Loop exits on `close()` (harness-driven).
4. `future: Future[Unit]` = a `Promise` completed when the loop ends, failed on exception. Cleanup
   (streams → temp files → page → browser → playwright) runs in `finally`.

Fixes over the original: actually `Thread.sleep` in the readiness + poll loops (original's
`IO.sleep` inside `Resource.pure` is dead code); drop Jimfs/Guava (write shim straight to a temp
file).

## Module layout (new top-level `sbt-uni-playwright/` build, sbt 2.0)

```
sbt-uni-playwright/
  build.sbt                       # jsenv lib + plugin, aggregated
  project/build.properties        # sbt 2.0.0-RC10 (match sbt-uni)
  project/plugin.sbt              # SbtPlugin (builtin) + scripted + dynver + pgp
  jsenv/src/main/scala/wvlet/uni/jsenv/playwright/
      PlaywrightJSEnv.scala       # JSEnv (start/startWithCom)
      PlaywrightRun.scala         # JSRun + JSComRun worker-thread engine
      JsBridge.scala              # the JS shim string + HTML template
      BrowserLauncher.scala       # Playwright create/launch/page + flags
      Materializer.scala          # temp-file materialization + cleanup
  plugin/src/main/scala/wvlet/uni/sbt/playwright/
      UniPlaywrightPlugin.scala   # settings (jsEnv helper) + uniPlaywrightInstall task
  plugin/src/sbt-test/playwright/basic/   # scripted: sbt 2.0 Scala.js project, runs a real test
```

Naming: library artifact `uni-jsenv-playwright`, plugin artifact `sbt-uni-playwright`.

## Testing

- Scripted test: an sbt 2.0 + `sbt-scalajs` 1.22.0 project with a trivial Scala.js test using
  `Test / jsEnv := new PlaywrightJSEnv(...)`; assert it runs green in headless Chromium.
- Local: Java Playwright downloads the browser on first run (Node not required).

## Open / fast-follow

- Cross-browser & device matrix; visual regression; console→err routing; PWDEBUG support.
- Eventually switch uni's own `uni-dom-test` to this once uni migrates to sbt 2.0.
