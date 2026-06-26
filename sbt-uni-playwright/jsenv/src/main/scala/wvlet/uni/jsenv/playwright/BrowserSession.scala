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

import com.microsoft.playwright.{Browser, BrowserContext, BrowserType, Page, Playwright, Tracing}

import java.nio.file.Path
import java.util.{List as JList}
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/**
  * Owns the Playwright runtime, browser, context, and page for a single run, and closes them in
  * reverse order. All Playwright objects are touched only from the engine's worker thread, since
  * Playwright is not thread-safe.
  */
private[playwright] class BrowserSession(
    val playwright: Playwright,
    val browser: Browser,
    val context: BrowserContext,
    val page: Page,
    tracingEnabled: Boolean
):
  /** Save the current page as a screenshot (best-effort). */
  def screenshot(path: Path): Unit =
    try page.screenshot(Page.ScreenshotOptions().setPath(path).setFullPage(true))
    catch
      case NonFatal(_) =>

  /** Stop tracing and write the trace zip if tracing was enabled (best-effort). */
  def stopTracing(path: Path): Unit =
    if tracingEnabled then
      try context.tracing().stop(Tracing.StopOptions().setPath(path))
      catch
        case NonFatal(_) =>

  def close(): Unit =
    closeQuietly(() => page.close())
    closeQuietly(() => context.close())
    closeQuietly(() => browser.close())
    closeQuietly(() => playwright.close())

  private def closeQuietly(f: () => Unit): Unit =
    try f()
    catch
      case NonFatal(_) =>

end BrowserSession

private[playwright] object BrowserSession:

  // Flags that let Chromium load ES modules and sibling files over file://.
  private val chromiumArgs =
    List(
      "--disable-extensions",
      "--disable-web-security",
      "--allow-running-insecure-content",
      "--disable-site-isolation-trials",
      "--allow-file-access-from-files",
      "--disable-gpu"
    )
  private val firefoxArgs = List("--disable-web-security")
  private val webkitArgs  = chromiumArgs.filterNot(_ == "--disable-gpu")

  /**
    * Create the Playwright runtime with the thread context classloader temporarily set to the one
    * that loaded this class. Playwright's bundled driver loader resolves the `driver/` resources
    * (in the `driver-bundle` jar) via the TCCL, which under sbt's task/test execution does not see
    * the plugin's dependency jars — without this, `Playwright.create()` fails with a NullPointer in
    * `DriverJar.getDriverResourceURI`.
    */
  private def createPlaywright(): Playwright =
    val loader = classOf[BrowserSession].getClassLoader
    val thread = Thread.currentThread()
    val prev   = thread.getContextClassLoader
    thread.setContextClassLoader(loader)
    try Playwright.create()
    finally thread.setContextClassLoader(prev)

  private def defaultArgs(browserName: String): List[String] =
    browserName.toLowerCase match
      case "chromium" | "chrome" => chromiumArgs
      case "firefox"             => firefoxArgs
      case "webkit"              => webkitArgs
      case other => throw IllegalArgumentException(s"Unknown Playwright browser: ${other}")

  /** Create a fresh Playwright session. Java Playwright downloads the browser on first use. */
  def launch(
      browserName: String,
      headless: Boolean,
      extraArgs: List[String],
      tracingEnabled: Boolean
  ): BrowserSession =
    val playwright = createPlaywright()
    var browser: Browser       = null
    var context: BrowserContext = null
    try
      val browserType: BrowserType =
        browserName.toLowerCase match
          case "chromium" | "chrome" => playwright.chromium()
          case "firefox"             => playwright.firefox()
          case "webkit"              => playwright.webkit()
          case other => throw IllegalArgumentException(s"Unknown Playwright browser: ${other}")

      val args: JList[String] = (defaultArgs(browserName) ++ extraArgs).asJava
      browser = browserType.launch(BrowserType.LaunchOptions().setHeadless(headless).setArgs(args))
      context = browser.newContext()
      if tracingEnabled then
        context
          .tracing()
          .start(Tracing.StartOptions().setScreenshots(true).setSnapshots(true).setSources(true))
      val page = context.newPage()
      BrowserSession(playwright, browser, context, page, tracingEnabled)
    catch
      case e: Throwable =>
        // Unwind anything already created before rethrowing.
        if context != null then
          try context.close()
          catch { case NonFatal(_) => }
        if browser != null then
          try browser.close()
          catch { case NonFatal(_) => }
        try playwright.close()
        catch { case NonFatal(_) => }
        throw e
  end launch

end BrowserSession
