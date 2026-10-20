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

import wvlet.uni.rx.Rx

import scala.concurrent.{ExecutionContext, Future}
import scala.scalajs.js
import scala.util.Failure

/**
  * Calls JVM-side Playwright `Page` APIs from Scala.js tests running in a real browser under
  * `uni-jsenv-playwright` (or the `sbt-uni-playwright` plugin).
  *
  * Each call is a round trip through the JSEnv's poll loop, so it returns an `Rx`. The `Rx` is
  * lazy: the command is sent when it runs (once per run), so chained steps run in order. Return it
  * from the test body:
  * {{{
  *   test("submits on Enter") {
  *     mount(form)
  *     for
  *       _ <- Playwright.click("#name")
  *       _ <- Playwright.typeText("uni")
  *       _ <- Playwright.press("Enter")
  *     yield submitted shouldBe true
  *   }
  * }}}
  *
  * Input commands produce trusted browser events (`isTrusted`) with their default actions (focus,
  * text input, form submission), unlike `dispatchEvent`.
  *
  * Outside that JSEnv (e.g. on Node.js) every call fails; check [[isAvailable]] to skip instead.
  */
object Playwright:

  /** The page-command protocol version this client speaks. */
  val protocolVersion: Int = 1

  /** Default wait (ms) for the element of an input command. */
  val defaultTimeoutMillis: Int = 5000

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
  def screenshot(name: String, selector: String = "", fullPage: Boolean = false): Rx[String] = call(
    "screenshot",
    screenshotArgs(name, selector, fullPage)
  ).map(_.toString)

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
  ): Rx[Unit] =
    val a = screenshotArgs(name, selector, fullPage)
    a.updateDynamic("maxDiffPixelRatio")(maxDiffPixelRatio)
    a.updateDynamic("threshold")(threshold)
    call("expectScreenshot", a).map(_ => ())

  /**
    * Click the element matching `selector` (a Playwright selector; CSS by default) with a real
    * mouse click, after waiting up to `timeoutMillis` for it to be visible, stable and enabled.
    * Fails if the selector matches more than one element.
    */
  def click(selector: String, timeoutMillis: Int = defaultTimeoutMillis): Rx[Unit] = call(
    "click",
    js.Dynamic.literal(selector = selector, timeout = timeoutMillis)
  ).map(_ => ())

  /** Move the mouse over the element matching `selector`, so `:hover` and mouse events apply. */
  def hover(selector: String, timeoutMillis: Int = defaultTimeoutMillis): Rx[Unit] = call(
    "hover",
    js.Dynamic.literal(selector = selector, timeout = timeoutMillis)
  ).map(_ => ())

  /** Replace the value of the input, textarea or contenteditable element matching `selector`. */
  def fill(selector: String, text: String, timeoutMillis: Int = defaultTimeoutMillis): Rx[Unit] =
    call("fill", js.Dynamic.literal(selector = selector, text = text, timeout = timeoutMillis)).map(
      _ => ()
    )

  /** Type `text` key by key into the focused element (focus it first, e.g. with [[click]]). */
  def typeText(text: String): Rx[Unit] = call("type", js.Dynamic.literal(text = text)).map(_ => ())

  /**
    * Press a key or key combination on the focused element, e.g. `"Enter"`, `"ArrowLeft"`,
    * `"Control+A"` or `"Shift+Tab"` (Playwright key names).
    */
  def press(key: String): Rx[Unit] = call("press", js.Dynamic.literal(key = key)).map(_ => ())

  /**
    * Resize the viewport, so media queries and `resize` listeners see the new size. The size stays
    * for the rest of the run (all tests share one page); prefer [[withViewportSize]], which
    * restores it.
    *
    * @return
    *   the previous (width, height)
    */
  def setViewportSize(width: Int, height: Int): Rx[(Int, Int)] = call(
    "setViewportSize",
    js.Dynamic.literal(width = width, height = height)
  ).map { v =>
    val prev = v.asInstanceOf[js.Dynamic]
    (prev.width.asInstanceOf[Int], prev.height.asInstanceOf[Int])
  }

  /** Run `body` at the given viewport size, then restore the previous size whatever the outcome. */
  def withViewportSize[A](width: Int, height: Int)(body: Rx[A]): Rx[A] = setViewportSize(
    width,
    height
  ).flatMap { (prevWidth, prevHeight) =>
    // materialize + flatMap rather than transformRx: flatMap waits for the async restore to emit
    body
      .materialize
      .flatMap(result => setViewportSize(prevWidth, prevHeight).flatMap(_ => Rx.fromResult(result)))
  }

  private def screenshotArgs(name: String, selector: String, fullPage: Boolean): js.Dynamic =
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

  /** A lazy `Rx` that sends the command each time it runs. */
  private def call(cmd: String, a: => js.Dynamic): Rx[js.Any] = Rx
    .single(())
    .andThen(_ => send(cmd, a))

  private def send(cmd: String, a: js.Dynamic): Future[js.Any] =
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
        bridge
          .get
          .call(cmd, a)
          .asInstanceOf[js.Promise[js.Any]]
          .toFuture
          .transform {
            // Commands added within the same protocol version are missing from older JSEnvs.
            case Failure(e) if Option(e.getMessage).exists(_.contains("Unknown page command")) =>
              Failure(
                UnsupportedOperationException(
                  s"Playwright.${cmd} requires a newer uni-jsenv-playwright: ${e.getMessage}"
                )
              )
            case other =>
              other
          }

end Playwright
