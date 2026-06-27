# sbt-uni-playwright Plugin

`sbt-uni-playwright` runs your **Scala.js tests in a real browser** (headless
Chromium, Firefox, or WebKit) via [Playwright](https://playwright.dev/java/).
Unlike jsdom it supports **ES modules** and a faithful DOM, and Chromium matches
the Electron renderer — so the same tests cover web and Electron app code.

It bundles a Scala.js [`JSEnv`](https://www.scala-js.org/doc/project/js-environments.html)
(`uni-jsenv-playwright`) and wires it into `Test / jsEnv` for you, plus a task to
pre-download the browser binaries.

::: tip No Node.js required
The underlying Java Playwright runtime bundles its own driver and downloads
browsers on demand, so you do **not** need Node.js or an npm `playwright`
package to run the tests.
:::

::: warning sbt 2.x / Scala.js only
The plugin targets **sbt 2.x** (its metabuild runs on Scala 3) and is meant for
Scala.js projects. It is not published for sbt 1.x.
:::

## Installation

In `project/plugins.sbt`, add the Scala.js plugin and this one:

```scala
addSbtPlugin("org.scala-js"  % "sbt-scalajs"        % "1.22.0")
addSbtPlugin("org.wvlet.uni" % "sbt-uni-playwright" % "__UNI_VERSION__")
```

`UniPlaywrightPlugin` is not triggered automatically, so enable it (alongside
`ScalaJSPlugin`) on each Scala.js project that should test in a browser:

```scala
import org.scalajs.linker.interface.ModuleKind

lazy val app = project
  .enablePlugins(ScalaJSPlugin, UniPlaywrightPlugin)
  .settings(
    // Recommended: build ES modules so modern npm/ESM packages work.
    scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.ESModule) }
  )
```

The plugin sets `Test / jsEnv` to a Playwright environment, so `Test` runs
launch a real browser:

```
sbt> app/test
```

::: tip sbt 2.x dependencies
On sbt 2.x, `%%` already encodes the Scala.js platform suffix (`_sjs1`), so use
`%%` (not the old `%%%`) for your Scala.js library dependencies.
:::

## Configuration

Pick the browser and headed/headless mode with these settings:

```scala
uniPlaywrightBrowser  := "chromium" // or "firefox" / "webkit"
uniPlaywrightHeadless := true
```

| Setting                  | Type          | Default      | Description                                              |
| ------------------------ | ------------- | ------------ | -------------------------------------------------------- |
| `uniPlaywrightBrowser`   | `String`      | `"chromium"` | Browser for `Test / jsEnv`: `chromium`, `firefox`, `webkit` |
| `uniPlaywrightHeadless`  | `Boolean`     | `true`       | Run the browser without a visible window                 |
| `uniPlaywrightBrowsers`  | `Seq[String]` | the single `uniPlaywrightBrowser` | Browsers that `uniPlaywrightInstall` downloads |

## Installing browsers

Java Playwright downloads the browser on first use, but you can pre-download and
pin it (handy in CI, to avoid a first-run download mid-test):

```
sbt> uniPlaywrightInstall
```

| Task                    | Description                                              |
| ----------------------- | ------------------------------------------------------- |
| `uniPlaywrightInstall`  | Download and pin the configured Playwright browser(s)   |

::: tip CI on Linux
On a Linux runner, install the browser's OS libraries too. Pin the npm CLI to
the same Playwright version the runtime uses so the cached build matches:

```
pnpm dlx playwright@1.49.0 install --with-deps chromium
```
:::

## Failure artifacts

On an **uncaught JS error**, the environment saves a screenshot and a Playwright
trace under `target/uni-playwright`, and logs a hint to open the trace:

```
npx playwright show-trace target/uni-playwright/trace-<n>.zip
```

## Using the JSEnv directly (without the plugin)

If you only want the environment and prefer to wire `Test / jsEnv` yourself, add
the library to `project/plugins.sbt`:

```scala
libraryDependencies += "org.wvlet.uni" %% "uni-jsenv-playwright" % "__UNI_VERSION__"
```

Then set the environment. sbt 2.x caches setting values and a `JSEnv` is not
serializable, so wrap it in `Def.uncached`:

```scala
import wvlet.uni.jsenv.playwright.PlaywrightJSEnv

Test / jsEnv := Def.uncached(new PlaywrightJSEnv("chromium", headless = true))
```

For finer control, construct it from a `PlaywrightConfig`:

```scala
import wvlet.uni.jsenv.playwright.{PlaywrightConfig, PlaywrightJSEnv}

Test / jsEnv := Def.uncached(
  new PlaywrightJSEnv(
    PlaywrightConfig()
      .withBrowserName("webkit")
      .withHeadless(true)
      .noArtifactCapture
  )
)
```

| `PlaywrightConfig` field      | Type          | Default                | Description                                       |
| ----------------------------- | ------------- | ---------------------- | ------------------------------------------------- |
| `browserName`                 | `String`      | `"chromium"`           | `chromium`, `firefox`, or `webkit`                |
| `headless`                    | `Boolean`     | `true`                 | Run without a visible window                       |
| `captureArtifactsOnFailure`   | `Boolean`     | `true`                 | Save a screenshot + trace on an uncaught JS error |
| `artifactDir`                 | `Path`        | `target/uni-playwright`| Where failure artifacts are written               |
| `launchArgs`                  | `List[String]`| `Nil`                  | Extra browser CLI flags appended to the defaults  |

Each field has a `withXxx(...)` setter, plus `noArtifactCapture` to disable
artifact capture.
