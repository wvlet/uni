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
package wvlet.uni.ui

import wvlet.uni.dom.all.*
import wvlet.uni.dom.all.given
import wvlet.uni.dom.testing.*

import scala.language.implicitConversions

/** Stack, Grid, GridItem, and Separator render their options as attributes and CSS variables. */
class LayoutTest extends DomTestSupport:

  test("a stack is a column by default, with a gap") {
    val m     = mount(Stack(span("a"), span("b")))
    val stack = m.get(".uni-stack")
    stack.getAttribute("data-slot") shouldBe "stack"
    stack.getAttribute("data-direction") shouldBe "column"
    stack.getAttribute("style") shouldContain "--uni-stack-gap: 8px"
    stack.children.length shouldBe 2
  }

  test("a row stack carries its alignment, justification, gap, and wrap") {
    val m = mount(
      Stack
        .row(span("a"), span("b"))
        .withGap(16)
        .withAlign(Alignment.Baseline)
        .withJustify(Justify.Between)
        .withWrap(true)
    )
    val stack = m.get(".uni-stack")
    stack.getAttribute("data-direction") shouldBe "row"
    stack.getAttribute("data-align") shouldBe "baseline"
    stack.getAttribute("data-justify") shouldBe "space-between"
    stack.getAttribute("data-wrap") shouldBe "true"
    stack.getAttribute("style") shouldContain "--uni-stack-gap: 16px"
  }

  test("a negative gap is clamped to zero") {
    val m = mount(Stack(span("a")).withGap(-4))
    m.get(".uni-stack").getAttribute("style") shouldContain "--uni-stack-gap: 0px"
  }

  test("a grid sets its column template and gaps") {
    val m = mount(Grid(span("a"), span("b"), span("c")).withColumns(3).withGap(10).withRowGap(4))
    val style = m.get(".uni-grid").getAttribute("style")
    style shouldContain "--uni-grid-columns: repeat(3, minmax(0, 1fr))"
    style shouldContain "--uni-grid-gap: 10px"
    style shouldContain "--uni-grid-row-gap: 4px"
  }

  test("a grid with a minimum column width fills as many columns as fit") {
    val m = mount(Grid(span("a")).withMinColumnWidth(240))
    m.get(".uni-grid").getAttribute("style") shouldContain
      "repeat(auto-fill, minmax(min(240px, 100%), 1fr))"
  }

  test("a grid item spans columns and rows, or the whole row") {
    val m = mount(
      Grid(
        GridItem(span("wide")).withColumnSpan(2).withRowSpan(3),
        GridItem(span("full")).withColumnSpan(0)
      )
    )
    // The browser may fold grid-column and grid-row into grid-area, so read the properties
    val items = m.queryAll(".uni-grid-item").map(_.asInstanceOf[org.scalajs.dom.html.Element].style)
    items(0).getPropertyValue("grid-column") shouldBe "span 2"
    items(0).getPropertyValue("grid-row") shouldBe "span 3"
    items(1).getPropertyValue("grid-column") shouldBe "1 / -1"
  }

  test("a separator is a separator to assistive technology, unless decorative") {
    val m            = mount(div(Separator(), Separator().vertical, Separator().decorative))
    val Seq(h, v, d) = m.queryAll(".uni-separator")
    h.getAttribute("role") shouldBe "separator"
    h.getAttribute("aria-orientation") shouldBe "horizontal"
    v.getAttribute("aria-orientation") shouldBe "vertical"
    v.getAttribute("data-orientation") shouldBe "vertical"
    d.getAttribute("role") shouldBe "none"
    d.hasAttribute("aria-orientation") shouldBe false
  }

end LayoutTest
