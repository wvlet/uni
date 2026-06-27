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
package wvlet.uni.dom.testing

import org.scalajs.dom
import wvlet.uni.dom.{DomRenderer, RxElement}

/**
  * UI (component) testing toolkit for `uni-dom` — the second layer of Uni's testing pyramid (unit →
  * UI → Electron → plugin).
  *
  * Renders reactive elements into a real browser DOM, simulates user interaction via [[fireEvent]],
  * and queries the result via [[Query]] helpers. Designed to run in a real headless browser
  * (Chromium via Playwright), which is also the Electron renderer, so the same tests cover web and
  * desktop UI.
  *
  * {{{
  *   import wvlet.uni.dom.all.*
  *   import wvlet.uni.dom.testing.*
  *
  *   val m = mount(MyCounter())
  *   fireEvent.click(m.getByText("+1"))
  *   m.text shouldContain "count=1"
  *   m.unmount()
  * }}}
  *
  * In a `UniTest`, mix in `wvlet.uni.dom.testing.DomTestSupport` (in `uni-dom-test`) so mounted
  * elements are cleaned up automatically after each test.
  */
object DomTesting:

  /**
    * Render a reactive element into a fresh container in `document.body` and return a [[Mounted]]
    * handle for querying and interacting with it. Remember to [[Mounted.unmount]] when done (or use
    * [[DomTestSession]] / `DomTestSupport` for automatic cleanup).
    */
  def mount(element: RxElement): Mounted =
    val container = dom.document.createElement("div")
    container.setAttribute("data-uni-test-container", "true")
    dom.document.body.appendChild(container)
    val cancelable = DomRenderer.renderTo(container, element)
    Mounted(container, cancelable)

end DomTesting

/** Convenience top-level `mount` so `import wvlet.uni.dom.testing.*` exposes it directly. */
def mount(element: RxElement): Mounted = DomTesting.mount(element)
