# sbt-uni-playwright

Run Scala.js tests in a **real browser** (headless Chromium/Firefox/WebKit) via the
[Java Playwright](https://playwright.dev/java/) runtime — with **ES module** support and a faithful
DOM, unlike jsdom. Chromium also matches the Electron renderer, so the same tests cover web and
Electron app code.

No Node.js or npm package is required: Java Playwright bundles its own driver and downloads browsers
on demand.

This build (sbt 2.x / Scala 3) publishes two artifacts:

| Artifact | What it is |
|---|---|
| `org.wvlet.uni::uni-jsenv-playwright` | a plain `org.scalajs.jsenv.JSEnv` (cats-effect-free) |
| `org.wvlet.uni:sbt-uni-playwright` | an sbt 2.x plugin wrapping it |

## Using the plugin (recommended)

`project/plugins.sbt`:

```scala
addSbtPlugin("org.scala-js"   % "sbt-scalajs"        % "1.22.0")
addSbtPlugin("org.wvlet.uni"  % "sbt-uni-playwright" % "<version>")
```

`build.sbt`:

```scala
import org.scalajs.linker.interface.ModuleKind

lazy val app = project
  .enablePlugins(ScalaJSPlugin, UniPlaywrightPlugin)
  .settings(
    scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.ESModule) },
    uniPlaywrightBrowser  := "chromium", // or "firefox" / "webkit"
    uniPlaywrightHeadless := true
  )
```

The plugin sets `Test / jsEnv` for you. Then:

```
sbt uniPlaywrightInstall   # optional: pre-download & pin the browser
sbt test                   # runs in a real browser
```

On an uncaught JS error, a screenshot and a Playwright trace are written under
`target/uni-playwright` (open with `npx playwright show-trace <file>`).

## Using the JSEnv directly (without the plugin)

`project/plugins.sbt`:

```scala
libraryDependencies += "org.wvlet.uni" %% "uni-jsenv-playwright" % "<version>"
```

`build.sbt` — note `Def.uncached`, required because sbt 2.x caches setting values and a `JSEnv` is
not serializable:

```scala
import wvlet.uni.jsenv.playwright.PlaywrightJSEnv

Test / jsEnv := Def.uncached(new PlaywrightJSEnv("chromium", headless = true))
```

## Requirements

- sbt 2.x (the Scala.js sbt plugin on sbt 2.x is `sbt-scalajs_sbt2_3`)
- On Linux CI, install the browser's OS libraries, e.g.
  `pnpm dlx playwright@1.49.0 install --with-deps chromium` (pin to the runtime's Playwright
  version).
