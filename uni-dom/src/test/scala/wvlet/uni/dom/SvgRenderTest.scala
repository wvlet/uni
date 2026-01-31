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

import wvlet.uni.test.UniTest
import wvlet.uni.dom.all.*
import wvlet.uni.dom.all.given
import org.scalajs.dom

class SvgRenderTest extends UniTest:

  test("SVG element renders with SVG namespace"):
    val container = dom.document.createElement("div")
    container.id = "test-svg-ns"
    dom.document.body.appendChild(container)

    val s        = svg(viewBox -> "0 0 100 100", circle(cx -> 50, cy -> 50, r -> 40))
    val (_, c)   = DomRenderer.renderToNode("test-svg-ns", s)
    val svgElem  = container.querySelector("svg")
    val circleEl = container.querySelector("circle")

    svgElem.namespaceURI shouldBe DomNamespace.svg.uri
    circleEl.namespaceURI shouldBe DomNamespace.svg.uri

    c.cancel
    dom.document.body.removeChild(container)

  test("foreignObject children render with XHTML namespace"):
    val container = dom.document.createElement("div")
    container.id = "test-foreignobject"
    dom.document.body.appendChild(container)

    val mixed = svg(
      foreignObject(
        x      -> 10,
        y      -> 10,
        width  -> 80,
        height -> 80,
        div(cls -> "html-content", p("HTML inside SVG"))
      )
    )
    val (_, c)     = DomRenderer.renderToNode("test-foreignobject", mixed)
    val foreignObj = container.querySelector("foreignObject")
    val divElem    = container.querySelector("div.html-content")
    val pElem      = container.querySelector("p")

    // foreignObject should be SVG namespace
    foreignObj.namespaceURI shouldBe DomNamespace.svg.uri
    // Children of foreignObject should be XHTML namespace
    divElem.namespaceURI shouldBe DomNamespace.xhtml.uri
    pElem.namespaceURI shouldBe DomNamespace.xhtml.uri

    c.cancel
    dom.document.body.removeChild(container)

  test("title inside svg inherits SVG namespace at render time"):
    val container = dom.document.createElement("div")
    container.id = "test-title-ns"
    dom.document.body.appendChild(container)

    val svgIcon  = svg(title("Tooltip text"), circle(cx -> 50, cy -> 50, r -> 40))
    val (_, c)   = DomRenderer.renderToNode("test-title-ns", svgIcon)
    val titleEl  = container.querySelector("title")
    val circleEl = container.querySelector("circle")

    // Title should inherit SVG namespace from parent
    titleEl.namespaceURI shouldBe DomNamespace.svg.uri
    circleEl.namespaceURI shouldBe DomNamespace.svg.uri

    c.cancel
    dom.document.body.removeChild(container)

  test("nested SVG groups render with correct namespace"):
    val container = dom.document.createElement("div")
    container.id = "test-nested-svg"
    dom.document.body.appendChild(container)

    val nested = svg(
      g(rect(x -> 0, y -> 0, width -> 50, height -> 50), g(circle(cx -> 25, cy -> 25, r -> 10)))
    )
    val (_, c)   = DomRenderer.renderToNode("test-nested-svg", nested)
    val gElems   = container.querySelectorAll("g")
    val rectElem = container.querySelector("rect")
    val circleEl = container.querySelector("circle")

    (0 until gElems.length).foreach { i =>
      gElems(i).namespaceURI shouldBe DomNamespace.svg.uri
    }
    rectElem.namespaceURI shouldBe DomNamespace.svg.uri
    circleEl.namespaceURI shouldBe DomNamespace.svg.uri

    c.cancel
    dom.document.body.removeChild(container)

end SvgRenderTest
