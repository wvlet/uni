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

/**
  * Public helpers for managing Playwright browser binaries, used by the sbt plugin's install task.
  */
object PlaywrightBrowsers:

  /**
    * Download and cache the given browser at the version pinned by this library's Playwright
    * dependency. Implemented by launching the browser once (Java Playwright downloads it on demand)
    * and closing it immediately. Idempotent: a cached browser is reused, not re-downloaded.
    *
    * @return
    *   the resolved Chromium/Firefox/WebKit version string, for logging.
    */
  def install(browserName: String): String =
    val session = BrowserSession.launch(browserName, headless = true, extraArgs = Nil, tracingEnabled = false)
    try session.browser.version()
    finally session.close()

end PlaywrightBrowsers
