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
import scala.language.implicitConversions

class CssStylesTest extends UniTest:

  test("create style value"):
    val sv = style.display := "flex"
    sv.name shouldBe "display"
    sv.value shouldBe "flex"

  test("grouped style properties (React/Vue style)"):
    val elem      = div(style(style.display := "flex", style.gap := "8px", style.color := "blue"))
    val (node, _) = DomRenderer.createNode(elem)
    val html      = DomRenderer.renderToHtml(node)
    html shouldContain "display: flex"
    html shouldContain "gap: 8px"
    html shouldContain "color: blue"

  test("raw style string"):
    val elem      = div(style := "margin: 10px; padding: 5px;")
    val (node, _) = DomRenderer.createNode(elem)
    val html      = DomRenderer.renderToHtml(node)
    html shouldContain "margin: 10px"
    html shouldContain "padding: 5px"

  test("combine grouped and raw styles"):
    val elem      = div(style(style.display := "flex"), style := "margin: 10px;")
    val (node, _) = DomRenderer.createNode(elem)
    val html      = DomRenderer.renderToHtml(node)
    html shouldContain "display: flex"
    html shouldContain "margin: 10px"

  test("flexbox properties"):
    val elem = div(
      style(
        style.display        := "flex",
        style.flexDirection  := "column",
        style.justifyContent := "center",
        style.alignItems     := "stretch"
      )
    )
    val (node, _) = DomRenderer.createNode(elem)
    val html      = DomRenderer.renderToHtml(node)
    html shouldContain "display: flex"
    html shouldContain "flex-direction: column"
    html shouldContain "justify-content: center"
    html shouldContain "align-items: stretch"

  test("box model properties"):
    val elem = div(
      style(
        style.width   := "100px",
        style.height  := "50px",
        style.margin  := "10px",
        style.padding := "5px"
      )
    )
    val (node, _) = DomRenderer.createNode(elem)
    val html      = DomRenderer.renderToHtml(node)
    html shouldContain "width: 100px"
    html shouldContain "height: 50px"
    html shouldContain "margin: 10px"
    html shouldContain "padding: 5px"

  test("typography properties"):
    val elem = div(
      style(style.fontSize := "16px", style.fontWeight := "bold", style.textAlign := "center")
    )
    val (node, _) = DomRenderer.createNode(elem)
    val html      = DomRenderer.renderToHtml(node)
    html shouldContain "font-size: 16px"
    html shouldContain "font-weight: bold"
    html shouldContain "text-align: center"

  test("style as <style> tag element"):
    val elem      = head(style("body { margin: 0; }"))
    val (node, _) = DomRenderer.createNode(elem)
    val html      = DomRenderer.renderToHtml(node)
    html shouldContain "<style>"
    html shouldContain "body { margin: 0; }"

end CssStylesTest
