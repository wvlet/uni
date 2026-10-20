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
package wvlet.uni.dom

import org.scalajs.dom
import wvlet.uni.dom.all.*
import wvlet.uni.dom.all.given
import wvlet.uni.rx.Rx
import wvlet.uni.test.UniTest
import wvlet.uni.test.playwright.Playwright

import scala.language.implicitConversions

/**
  * Visual regression tests: render with uni-dom, then compare against golden images in
  * `src/test/screenshots`. Shapes only, no text, so rendering does not depend on system fonts.
  * Update goldens with `UNI_PLAYWRIGHT_UPDATE_SNAPSHOTS=1 ./sbt domTest/test`.
  */
class VisualTest extends UniTest:

  private def box(color: String): RxElement = div(
    style := s"width: 40px; height: 40px; background: ${color};"
  )

  /** Mount the element into the page, run the check, then unmount whatever the outcome. */
  private def mounted(elem: RxElement)(check: Rx[Unit]): Rx[Unit] =
    val (node, c) = DomRenderer.createNode(elem)
    dom.document.body.appendChild(node)
    check.transformRx { result =>
      c.cancel
      dom.document.body.removeChild(node)
      Rx.fromTry(result)
    }

  test("runs under the Playwright JSEnv"):
    Playwright.isAvailable shouldBe true

  test("renders a flex layout"):
    val layout = div(
      id -> "visual-flex",
      style(
        style.display    := "inline-flex",
        style.gap        := "8px",
        style.padding    := "8px",
        style.background := "white"
      ),
      box("crimson"),
      box("seagreen"),
      box("royalblue")
    )
    mounted(layout)(Playwright.expectScreenshot("flex-layout", selector = "#visual-flex"))

  test("re-renders a reactive update"):
    val color = Rx.variable("orange")
    val elem  = div(id -> "visual-rx", style := "display: inline-block;", color.map(box))
    mounted(elem) {
      for
        _ <- Playwright.expectScreenshot("rx-before", selector = "#visual-rx")
        _ = color := "purple"
        _ <- Playwright.expectScreenshot("rx-after", selector = "#visual-rx")
      yield ()
    }

end VisualTest
