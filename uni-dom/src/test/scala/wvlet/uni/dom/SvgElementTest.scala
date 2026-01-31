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

class SvgElementTest extends UniTest:

  test("svg element has SVG namespace"):
    val s = svg
    s.namespace shouldBe DomNamespace.svg

  test("circle element has SVG namespace"):
    val c = circle
    c.namespace shouldBe DomNamespace.svg

  test("rect element has SVG namespace"):
    val r = rect
    r.namespace shouldBe DomNamespace.svg

  test("path element has SVG namespace"):
    val p = path
    p.namespace shouldBe DomNamespace.svg

  test("create basic SVG with attributes"):
    val icon = svg(viewBox -> "0 0 100 100", circle(cx -> 50, cy -> 50, r -> 40, fill -> "blue"))
    icon.name shouldBe "svg"
    icon.namespace shouldBe DomNamespace.svg
    icon.modifiers.flatten.size shouldBe 2

  test("nested SVG elements"):
    val complex = svg(
      defs(
        linearGradient(
          id -> "grad1",
          stop(offset -> "0%", stopColor   -> "red"),
          stop(offset -> "100%", stopColor -> "blue")
        )
      ),
      rect(x -> 10, y -> 10, width -> 80, height -> 80, fill -> "url(#grad1)")
    )
    complex.modifiers.flatten.size shouldBe 2

  test("SVG group element"):
    val grouped = svg(g(circle(cx -> 25, cy -> 25, r -> 20), circle(cx -> 75, cy -> 75, r -> 20)))
    grouped.modifiers.flatten.size shouldBe 1

  test("SVG path with d attribute"):
    val pathElem = path(
      d           -> "M 10 10 L 90 90",
      stroke      -> "black",
      strokeWidth -> "2",
      fill        -> "none"
    )
    pathElem.modifiers.flatten.size shouldBe 4

  test("SVG polygon"):
    val tri = polygon(
      points      -> "50,10 90,90 10,90",
      fill        -> "green",
      stroke      -> "darkgreen",
      strokeWidth -> "2"
    )
    tri.modifiers.flatten.size shouldBe 4

  test("SVG line"):
    val l = line(x1 -> 0, y1 -> 0, x2 -> 100, y2 -> 100, stroke -> "red")
    l.modifiers.flatten.size shouldBe 5

  test("SVG ellipse"):
    val e = ellipse(cx -> 50, cy -> 50, rx -> 40, ry -> 20, fill -> "yellow")
    e.modifiers.flatten.size shouldBe 5

  test("SVG text element"):
    val t = svgText(x -> 50, y -> 50, textAnchor -> "middle", "Hello SVG")
    t.modifiers.flatten.size shouldBe 4

  test("xlink:href uses correct namespace"):
    val useElem = use(xlinkHref -> "#mySymbol")
    useElem.modifiers.flatten.head shouldMatch { case a: DomAttribute =>
      a.ns shouldBe DomNamespace.svgXLink
    }

  test("SVG filter elements"):
    val blur = svgFilter(id -> "blur", feGaussianBlur(in -> "SourceGraphic", stdDeviation -> "5"))
    blur.name shouldBe "filter"
    blur.modifiers.flatten.size shouldBe 2

  test("SVG gradient"):
    val grad = linearGradient(
      id -> "myGradient",
      x1 -> "0%",
      y1 -> "0%",
      x2 -> "100%",
      y2 -> "0%",
      stop(offset -> "0%", stopColor   -> "red"),
      stop(offset -> "100%", stopColor -> "blue")
    )
    grad.modifiers.flatten.size shouldBe 7

  test("SVG clipPath"):
    val clip = clipPath(id -> "myClip", circle(cx -> 50, cy -> 50, r -> 40))
    clip.modifiers.flatten.size shouldBe 2

  test("SVG animation"):
    val anim = circle(
      cx -> 50,
      cy -> 50,
      r  -> 40,
      animate(
        attributeName -> "r",
        svgFrom       -> "40",
        svgTo         -> "20",
        dur           -> "1s",
        repeatCount   -> "indefinite"
      )
    )
    anim.modifiers.flatten.size shouldBe 4

  test("foreignObject for HTML inside SVG"):
    val mixed = svg(
      foreignObject(
        x      -> 10,
        y      -> 10,
        width  -> 80,
        height -> 80,
        div(cls -> "html-content", p("HTML inside SVG"))
      )
    )
    mixed.modifiers.flatten.size shouldBe 1

  test("title inside svg uses XHTML namespace at definition"):
    // At construction time, title has XHTML namespace
    // but during rendering it will inherit SVG namespace
    val svgIcon = svg(title("Tooltip text"), circle(cx -> 50, cy -> 50, r -> 40))
    svgIcon.modifiers.flatten.size shouldBe 2
    // The title element is defined with XHTML namespace
    svgIcon.modifiers.flatten.head shouldMatch { case t: DomElement =>
      t.name shouldBe "title"
      // At definition time it's XHTML, but will inherit SVG at render time
      t.namespace shouldBe DomNamespace.xhtml
    }

  test("explicit svgTitle has SVG namespace"):
    val t = svgTitle("SVG Title")
    t.namespace shouldBe DomNamespace.svg

  test("explicit svgA has SVG namespace"):
    val a = svgA(xlinkHref -> "#target", "Link")
    a.namespace shouldBe DomNamespace.svg

  test("marker element"):
    val m = marker(
      id           -> "arrow",
      markerWidth  -> 10,
      markerHeight -> 10,
      refX         -> 5,
      refY         -> 5,
      orient       -> "auto",
      path(d -> "M 0 0 L 10 5 L 0 10 Z", fill -> "black")
    )
    m.modifiers.flatten.size shouldBe 7

  test("SVG symbol and use"):
    val s = svg(
      defs(symbol(id -> "icon", viewBox -> "0 0 100 100", circle(cx -> 50, cy -> 50, r -> 40))),
      use(xlinkHref -> "#icon", x -> 10, y -> 10)
    )
    s.modifiers.flatten.size shouldBe 2

end SvgElementTest
