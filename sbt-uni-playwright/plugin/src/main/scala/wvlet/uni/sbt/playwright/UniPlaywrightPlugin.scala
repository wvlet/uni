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
package wvlet.uni.sbt.playwright

import org.scalajs.sbtplugin.ScalaJSPlugin
import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport.jsEnv
import sbt.*
import sbt.Keys.*
import wvlet.uni.jsenv.playwright.{PlaywrightBrowsers, PlaywrightConfig, PlaywrightJSEnv}

/**
  * sbt 2.x plugin for running Scala.js tests in a real browser via Playwright.
  *
  * It wires `Test / jsEnv` to a [[wvlet.uni.jsenv.playwright.PlaywrightJSEnv]] (configured via the
  * `uniPlaywrightBrowser` / `uniPlaywrightHeadless` settings) and adds a `uniPlaywrightInstall`
  * task to pre-download and pin the browser binaries. Java Playwright bundles its own driver and
  * downloads browsers on demand, so no Node.js or npm package is required.
  *
  * Usage in build.sbt:
  * {{{
  * lazy val app = project
  *   .enablePlugins(ScalaJSPlugin, UniPlaywrightPlugin)
  *   .settings(
  *     uniPlaywrightBrowser  := "chromium", // or "firefox" / "webkit"
  *     uniPlaywrightHeadless := true
  *   )
  * }}}
  */
object UniPlaywrightPlugin extends AutoPlugin:

  // Only meaningful for Scala.js projects, and we set ScalaJSPlugin's `jsEnv` key.
  override def requires = ScalaJSPlugin
  override def trigger  = noTrigger

  object autoImport:
    // Re-exported so consumers can reference the JSEnv with only this plugin on the classpath.
    type PlaywrightJSEnv  = wvlet.uni.jsenv.playwright.PlaywrightJSEnv
    type PlaywrightConfig = wvlet.uni.jsenv.playwright.PlaywrightConfig
    val PlaywrightConfig: wvlet.uni.jsenv.playwright.PlaywrightConfig.type =
      wvlet.uni.jsenv.playwright.PlaywrightConfig

    val uniPlaywrightBrowser = settingKey[String](
      "Browser for Test/jsEnv: chromium (default), firefox, or webkit"
    )

    val uniPlaywrightHeadless = settingKey[Boolean]("Run the browser headless (default true)")

    val uniPlaywrightBrowsers = settingKey[Seq[String]](
      "Browsers to install via uniPlaywrightInstall"
    )

    val uniPlaywrightInstall = taskKey[Unit](
      "Download and pin the configured Playwright browser binaries"
    )

  import autoImport.*

  override lazy val projectSettings: Seq[Setting[?]] = Seq(
    uniPlaywrightBrowser  := "chromium",
    uniPlaywrightHeadless := true,
    uniPlaywrightBrowsers := Seq(uniPlaywrightBrowser.value),
    // A JSEnv is not serializable, so opt out of sbt 2.x's setting-value caching.
    Test / jsEnv :=
      Def.uncached(
        PlaywrightJSEnv(
          PlaywrightConfig(
            browserName = uniPlaywrightBrowser.value,
            headless = uniPlaywrightHeadless.value
          )
        )
      ),
    uniPlaywrightInstall := {
      val log = streams.value.log
      uniPlaywrightBrowsers
        .value
        .foreach { browser =>
          log.info(s"Installing Playwright browser: ${browser} ...")
          val version = PlaywrightBrowsers.install(browser)
          log.info(s"Installed ${browser} ${version}")
        }
    }
  )

end UniPlaywrightPlugin
