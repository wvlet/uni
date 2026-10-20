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
package wvlet.uni.test.playwright

import scala.concurrent.{ExecutionContext, Future}
import scala.scalajs.js

/**
  * Calls JVM-side Playwright `Page` APIs from Scala.js tests running in a real browser under
  * `uni-jsenv-playwright` (or the `sbt-uni-playwright` plugin).
  *
  * Each call is a round trip through the JSEnv's poll loop, so it returns a `Future`; return it
  * from the test body:
  * {{{
  *   test("dialog renders") {
  *     mount(dialog)
  *     Playwright.expectScreenshot("dialog-open", selector = "#dialog")
  *   }
  * }}}
  *
  * Outside that JSEnv (e.g. on Node.js) every call fails; check [[isAvailable]] to skip instead.
  */
object Playwright:

  /** The page-command protocol version this client speaks. */
  val protocolVersion: Int = 1

  private given ExecutionContext = wvlet.uni.test.compat.executionContext

  /** True when running in a page served by a compatible Playwright JSEnv. */
  def isAvailable: Boolean = bridgeVersion.contains(protocolVersion)

  /**
    * Save a screenshot to the JSEnv's screenshot directory as `<name>.png`.
    *
    * @param name
    *   file-name stem: letters, digits, '.', '_', '-'
    * @param selector
    *   capture only the element matching this CSS selector ("" for the viewport)
    * @param fullPage
    *   capture the full scrollable page instead of the viewport (ignored with a selector)
    * @return
    *   the absolute path of the written file
    */
  def screenshot(name: String, selector: String = "", fullPage: Boolean = false): Future[String] =
    call("screenshot", args(name, selector, fullPage)).map(_.toString)

  /**
    * Compare a screenshot with the golden image `<name>-<browser>-<os>.png` in the snapshot
    * directory, failing on mismatch (with actual and diff images written next to the screenshots).
    * A missing golden is written from the actual screenshot and fails, so it is never silently
    * accepted. In update mode the golden is overwritten instead.
    *
    * @param maxDiffPixelRatio
    *   fraction of pixels allowed to differ (0 to 1)
    * @param threshold
    *   per-channel color tolerance (0 to 1) before a pixel counts as different
    */
  def expectScreenshot(
      name: String,
      selector: String = "",
      fullPage: Boolean = false,
      maxDiffPixelRatio: Double = 0.0,
      threshold: Double = 0.1
  ): Future[Unit] =
    val a = args(name, selector, fullPage)
    a.updateDynamic("maxDiffPixelRatio")(maxDiffPixelRatio)
    a.updateDynamic("threshold")(threshold)
    call("expectScreenshot", a).map(_ => ())

  private def args(name: String, selector: String, fullPage: Boolean): js.Dynamic =
    val a = js.Dynamic.literal(name = name, fullPage = fullPage)
    if selector.nonEmpty then
      a.updateDynamic("selector")(selector)
    a

  private def bridge: js.UndefOr[js.Dynamic] = js
    .Dynamic
    .global
    .globalThis
    .__uniPlaywright
    .asInstanceOf[js.UndefOr[js.Dynamic]]

  private def bridgeVersion: Option[Int] = bridge
    .toOption
    .flatMap(b => b.version.asInstanceOf[js.UndefOr[Int]].toOption)

  private def call(cmd: String, a: js.Dynamic): Future[js.Any] =
    bridgeVersion match
      case None =>
        Future.failed(
          IllegalStateException(
            s"Playwright.${cmd} requires running in a browser via uni-jsenv-playwright " +
              "(sbt-uni-playwright); this environment has no Playwright bridge"
          )
        )
      // Versions change only on incompatible changes, so any mismatch is an error.
      case Some(v) if v != protocolVersion =>
        Future.failed(
          IllegalStateException(
            s"Playwright.${cmd} needs page-command protocol ${protocolVersion}, but the JSEnv " +
              s"provides ${v}; use matching uni-test and uni-jsenv-playwright versions"
          )
        )
      case Some(_) =>
        bridge.get.call(cmd, a).asInstanceOf[js.Promise[js.Any]].toFuture

end Playwright
