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

import com.microsoft.playwright.{Locator, Page}
import com.microsoft.playwright.options.ScreenshotAnimations

import java.nio.file.{Files, Path}
import java.util.Map as JMap
import scala.util.control.NonFatal

/**
  * Handles page commands issued by test code through `globalThis.__uniPlaywright.call(cmd, args)`
  * (see [[JsBridge]]). [[PlaywrightEngine]] drains them each poll cycle and runs them here on its
  * worker thread, so every Playwright call stays on one thread.
  *
  * Commands may write files, so names are restricted to `[A-Za-z0-9._-]+` and every path is checked
  * to stay under the configured directory.
  */
private[playwright] class PageCommands(config: PlaywrightConfig, page: Page):
  import PageCommands.*

  /** Run one command. Returns the value to resolve the JS Promise with, or throws to reject it. */
  def run(cmd: String, args: JMap[String, Object]): Object =
    cmd match
      case "screenshot" =>
        screenshot(args)
      case "expectScreenshot" =>
        expectScreenshot(args)
      case other =>
        throw IllegalArgumentException(s"Unknown page command: ${other}")

  /** Run a command and turn the outcome into a `{id, value}` or `{id, error}` result for JS. */
  def runToResult(id: Object, cmd: String, args: JMap[String, Object]): JMap[String, Object] =
    val result = java.util.HashMap[String, Object]()
    result.put("id", id)
    try
      result.put("value", run(cmd, args))
    catch
      case NonFatal(e) =>
        result.put("error", s"${cmd} failed: ${Option(e.getMessage).getOrElse(e.toString)}")
    result

  private def screenshot(args: JMap[String, Object]): Object =
    val name = validName(args)
    val path = within(config.screenshotDir, s"${name}.png")
    write(path, capture(args))
    path.toAbsolutePath.toString

  private def expectScreenshot(args: JMap[String, Object]): Object =
    val name      = validName(args)
    val golden    = within(config.snapshotDir, s"${name}-${config.browserName}-${osName}.png")
    val actual    = stableCapture(args)
    val threshold = number(args, "threshold").getOrElse(0.1)
    val maxRatio  = number(args, "maxDiffPixelRatio").getOrElse(0.0)

    val actualPath = within(config.screenshotDir, s"${name}-actual.png")
    val diffPath   = within(config.screenshotDir, s"${name}-diff.png")
    // Clear artifacts from an earlier failed run, so only the current outcome's files remain.
    Files.deleteIfExists(actualPath)
    Files.deleteIfExists(diffPath)

    if config.isUpdatingSnapshots then
      write(golden, actual)
      "updated"
    else if !Files.exists(golden) then
      write(golden, actual)
      throw AssertionError(
        s"No golden image for '${name}'. Wrote the actual screenshot to ${golden
            .toAbsolutePath}; " + "review and commit it, then re-run"
      )
    else
      val diff = ImageDiff.compare(Files.readAllBytes(golden), actual, threshold)
      if diff.isMatch(maxRatio) then
        "matched"
      else
        write(actualPath, actual)
        val detail =
          if diff.sizeMismatch then
            "image size differs from the golden"
          else
            diff.diffImage.foreach(write(diffPath, _))
            f"${diff.diffPixels}%d of ${diff.totalPixels}%d pixels differ (ratio ${diff
                .diffRatio}%.5f > ${maxRatio}%.5f); diff: ${diffPath.toAbsolutePath}"
        throw AssertionError(
          s"Screenshot '${name}' does not match ${golden.toAbsolutePath}: ${detail}; " +
            s"actual: ${actualPath.toAbsolutePath}"
        )
    end if

  end expectScreenshot

  /**
    * Capture until two consecutive screenshots are identical (as `toHaveScreenshot` does), so a
    * still-loading font or image does not make the comparison flaky. Gives up after a few tries and
    * returns the last capture.
    */
  private def stableCapture(args: JMap[String, Object]): Array[Byte] =
    var prev    = capture(args)
    var current = capture(args)
    var tries   = 2
    while !java.util.Arrays.equals(prev, current) && tries < maxStableCaptures do
      prev = current
      current = capture(args)
      tries += 1
    current

  private def capture(args: JMap[String, Object]): Array[Byte] =
    string(args, "selector") match
      case Some(selector) =>
        page
          .locator(selector)
          .screenshot(Locator.ScreenshotOptions().setAnimations(ScreenshotAnimations.DISABLED))
      case None =>
        page.screenshot(
          Page
            .ScreenshotOptions()
            .setFullPage(bool(args, "fullPage").getOrElse(false))
            .setAnimations(ScreenshotAnimations.DISABLED)
        )

  private def write(path: Path, bytes: Array[Byte]): Unit =
    Files.createDirectories(path.getParent)
    Files.write(path, bytes)

end PageCommands

private[playwright] object PageCommands:
  private val maxStableCaptures = 10
  private val namePattern       = "[A-Za-z0-9._-]+".r

  /** The `name` argument, restricted to a plain file-name stem. */
  def validName(args: JMap[String, Object]): String =
    string(args, "name") match
      case Some(n @ namePattern()) if !n.contains("..") =>
        n
      case Some(n) =>
        throw IllegalArgumentException(
          s"Invalid name '${n}': use only letters, digits, '.', '_', '-' and no '..'"
        )
      case None =>
        throw IllegalArgumentException("Missing 'name' argument")

  /** Resolve `file` under `dir`, rejecting anything that would escape it. */
  def within(dir: Path, file: String): Path =
    val base = dir.toAbsolutePath.normalize()
    val p    = base.resolve(file).normalize()
    if !p.startsWith(base) || p == base then
      throw IllegalArgumentException(s"Path escapes ${base}: ${file}")
    p

  /** OS name as used by `@playwright/test` golden files (process.platform). */
  val osName: String =
    val os = sys.props.getOrElse("os.name", "").toLowerCase
    if os.contains("mac") then
      "darwin"
    else if os.contains("win") then
      "win32"
    else
      "linux"

  private def string(args: JMap[String, Object], key: String): Option[String] = Option(
    args.get(key)
  ).map(String.valueOf)

  private def number(args: JMap[String, Object], key: String): Option[Double] = Option(
    args.get(key)
  ).collect { case n: java.lang.Number =>
    n.doubleValue()
  }

  private def bool(args: JMap[String, Object], key: String): Option[Boolean] = Option(args.get(key))
    .collect { case b: java.lang.Boolean =>
      b.booleanValue()
    }

end PageCommands
