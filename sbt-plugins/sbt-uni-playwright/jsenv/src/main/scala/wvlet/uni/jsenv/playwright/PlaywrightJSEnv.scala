/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package wvlet.uni.jsenv.playwright

import org.scalajs.jsenv.{Input, JSComRun, JSEnv, JSRun, RunConfig}

import java.nio.file.{Path, Paths}
import scala.concurrent.Future

/**
  * Configuration for [[PlaywrightJSEnv]].
  *
  * @param browserName
  *   "chromium" (default), "firefox", or "webkit"
  * @param headless
  *   run without a visible window (default true)
  * @param captureArtifactsOnFailure
  *   on an uncaught JS error, save a screenshot and a Playwright trace under [[artifactDir]]
  * @param artifactDir
  *   where failure screenshots/traces are written (default `target/uni-playwright`)
  * @param launchArgs
  *   extra browser CLI flags appended to the defaults
  * @param screenshotDir
  *   where `screenshot` commands and `expectScreenshot` actual/diff images are written
  * @param snapshotDir
  *   where `expectScreenshot` golden images are read from (and written to in update mode)
  * @param updateSnapshots
  *   overwrite golden images instead of comparing against them. Also enabled by the
  *   `UNI_PLAYWRIGHT_UPDATE_SNAPSHOTS=1` environment variable.
  */
case class PlaywrightConfig(
    browserName: String = "chromium",
    headless: Boolean = true,
    captureArtifactsOnFailure: Boolean = true,
    artifactDir: Path = Paths.get("target", "uni-playwright"),
    launchArgs: List[String] = Nil,
    screenshotDir: Path = Paths.get("target", "uni-playwright", "screenshots"),
    snapshotDir: Path = Paths.get("src", "test", "screenshots"),
    updateSnapshots: Boolean = false
):
  def withBrowserName(name: String): PlaywrightConfig  = copy(browserName = name)
  def withHeadless(enabled: Boolean): PlaywrightConfig = copy(headless = enabled)

  def withCaptureArtifactsOnFailure(enabled: Boolean): PlaywrightConfig = copy(
    captureArtifactsOnFailure = enabled
  )

  def withArtifactDir(dir: Path): PlaywrightConfig         = copy(artifactDir = dir)
  def withLaunchArgs(args: List[String]): PlaywrightConfig = copy(launchArgs = args)
  def noArtifactCapture: PlaywrightConfig                  = copy(captureArtifactsOnFailure = false)
  def withScreenshotDir(dir: Path): PlaywrightConfig       = copy(screenshotDir = dir)
  def withSnapshotDir(dir: Path): PlaywrightConfig         = copy(snapshotDir = dir)

  def withUpdateSnapshots(enabled: Boolean): PlaywrightConfig = copy(updateSnapshots = enabled)

  /** Whether update mode is on, via [[updateSnapshots]] or the environment variable. */
  def isUpdatingSnapshots: Boolean =
    updateSnapshots ||
      sys.env.get("UNI_PLAYWRIGHT_UPDATE_SNAPSHOTS").exists(v => v == "1" || v == "true")

end PlaywrightConfig

/**
  * A Scala.js `org.scalajs.jsenv.JSEnv` that runs tests in a real browser via the Java Playwright
  * runtime. Unlike jsdom it supports ES modules and a faithful DOM, and Chromium matches the
  * Electron renderer. Java Playwright bundles its own driver and downloads browsers on demand, so
  * no Node.js or npm package is required.
  *
  * Usage in build.sbt (with the dependency added to `project/plugins.sbt`):
  * {{{
  *   Test / jsEnv := new wvlet.uni.jsenv.playwright.PlaywrightJSEnv("chromium", headless = true)
  * }}}
  */
class PlaywrightJSEnv(val config: PlaywrightConfig) extends JSEnv:

  def this() = this(PlaywrightConfig())

  def this(browserName: String, headless: Boolean) = this(
    PlaywrightConfig(browserName = browserName, headless = headless)
  )

  /** Human-readable environment name (shown in sbt's test output). */
  override val name: String = s"PlaywrightJSEnv(${config.browserName})"

  private val validator = RunConfig.Validator().supportsInheritIO().supportsOnOutputStream()

  /** Start a non-com run (used by `run`). */
  override def start(input: Seq[Input], runConfig: RunConfig): JSRun =
    validator.validate(runConfig)
    PlaywrightRun(config, input, runConfig)

  /** Start a run with a bidirectional message channel (used by `test`). */
  override def startWithCom(
      input: Seq[Input],
      runConfig: RunConfig,
      onMessage: String => Unit
  ): JSComRun =
    validator.validate(runConfig)
    PlaywrightComRun(config, input, runConfig, onMessage)

end PlaywrightJSEnv

private final class PlaywrightRun(config: PlaywrightConfig, input: Seq[Input], runConfig: RunConfig)
    extends JSRun:
  private val engine = PlaywrightEngine(config, input, runConfig, enableCom = false, _ => ())
  override def future: Future[Unit] = engine.future
  override def close(): Unit        = engine.close()

private final class PlaywrightComRun(
    config: PlaywrightConfig,
    input: Seq[Input],
    runConfig: RunConfig,
    onMessage: String => Unit
) extends JSComRun:
  private val engine = PlaywrightEngine(config, input, runConfig, enableCom = true, onMessage)
  override def future: Future[Unit]    = engine.future
  override def close(): Unit           = engine.close()
  override def send(msg: String): Unit = engine.send(msg)
