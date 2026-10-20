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
| `uniPlaywrightScreenshotDir`   | `File`    | `target.value / "uni-playwright" / "screenshots"` | Where `screenshot` files and `expectScreenshot` actual/diff images are written |
| `uniPlaywrightSnapshotDir`     | `File`    | `src/test/screenshots` | Golden images for `expectScreenshot` |
| `uniPlaywrightUpdateSnapshots` | `Boolean` | `false` | Overwrite goldens instead of comparing (also `UNI_PLAYWRIGHT_UPDATE_SNAPSHOTS=1`) |

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

## Screenshots and visual regression

Tests running in the browser can call JVM-side Playwright through
`wvlet.uni.test.playwright.Playwright` (in `uni-test`, Scala.js only). Each call
returns a lazy `Rx`: the command is sent when the `Rx` runs, so return it from
the test body and chain steps with `flatMap` or a `for` comprehension:

```scala
import wvlet.uni.test.UniTest
import wvlet.uni.test.playwright.Playwright

class DialogTest extends UniTest:
  test("dialog renders") {
    mountDialog()
    Playwright.expectScreenshot("dialog-open", selector = "#dialog")
  }
```

| Method | Description |
| ------ | ----------- |
| `screenshot(name, selector = "", fullPage = false): Rx[String]` | Write `<name>.png` to the screenshot dir; returns its absolute path |
| `expectScreenshot(name, selector = "", fullPage = false, maxDiffPixelRatio = 0.0, threshold = 0.1): Rx[Unit]` | Compare with the golden `<name>-<browser>-<os>.png` in the snapshot dir |
| `isAvailable: Boolean` | Whether the page runs under a compatible Playwright JSEnv |

- `name` may contain only letters, digits, `.`, `_`, and `-`.
- A pixel differs when any color channel differs by more than `threshold × 255`;
  the test fails when the differing-pixel ratio exceeds `maxDiffPixelRatio` or
  the image size changed. On failure, `<name>-actual.png` and `<name>-diff.png`
  (differences in red) are written to the screenshot dir.
- A **missing golden** is written from the actual screenshot and the test
  **fails**, so review and commit it, then re-run.
- Golden names include the browser and OS (`linux`, `darwin`, `win32`) because
  font rendering differs across them. Generate the goldens CI compares against
  on the CI OS.
- To accept intended changes, run once with `uniPlaywrightUpdateSnapshots := true`
  or `UNI_PLAYWRIGHT_UPDATE_SNAPSHOTS=1`.
- The viewport is fixed at 1280×720 with device scale factor 1.

Outside this JSEnv (e.g. on Node.js) the calls fail with an explanatory error;
use `Playwright.isAvailable` to skip instead.

## Trusted input and viewport

Events created with `dispatchEvent` have `isTrusted = false` and skip default
actions such as moving focus, inserting text, or submitting a form on Enter.
These methods drive the browser through Playwright instead, so the page gets
real input:

```scala
test("submits on Enter") {
  mountForm()
  for
    _ <- Playwright.click("#name")
    _ <- Playwright.typeText("uni")
    _ <- Playwright.press("Enter")
  yield submitted shouldBe true
}

test("collapses the menu on narrow screens") {
  mountMenu()
  Playwright.withViewportSize(500, 800) {
    Playwright.expectScreenshot("menu-narrow", selector = "#menu")
  }
}
```

| Method | Description |
| ------ | ----------- |
| `click(selector, timeoutMillis = 5000): Rx[Unit]` | Click the matching element |
| `hover(selector, timeoutMillis = 5000): Rx[Unit]` | Move the mouse over the matching element (`:hover` applies) |
| `fill(selector, text, timeoutMillis = 5000): Rx[Unit]` | Replace the value of an input, textarea, or contenteditable element |
| `typeText(text): Rx[Unit]` | Type `text` key by key into the focused element |
| `press(key): Rx[Unit]` | Press a key or combination on the focused element, e.g. `"Enter"`, `"Control+A"` |
| `setViewportSize(width, height): Rx[(Int, Int)]` | Resize the viewport; returns the previous size |
| `withViewportSize(width, height)(body: Rx[A]): Rx[A]` | Run `body` at that size, then restore the previous size |

- Selectors are [Playwright selectors](https://playwright.dev/java/docs/locators)
  (CSS by default). A selector matching more than one element fails rather than
  picking one.
- Element commands wait up to `timeoutMillis` for the element to be actionable
  (visible, stable, enabled). The default is 5s, shorter than Playwright's 30s,
  because a waiting command pauses the test run's console output.
- All tests in a run share one page, so a viewport change persists. Prefer
  `withViewportSize`, which keeps the 1280×720 baseline that screenshots rely on.
- These commands need a `uni-jsenv-playwright` newer than 2026.1.26; an older one
  fails the call with "requires a newer uni-jsenv-playwright".

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
