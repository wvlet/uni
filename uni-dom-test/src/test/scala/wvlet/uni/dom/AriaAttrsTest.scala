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
import wvlet.uni.rx.Rx

class AriaAttrsTest extends UniTest:

  test("ariaLabel sets aria-label attribute") {
    val elem      = button(ariaLabel -> "Close dialog")
    val (node, _) = DomRenderer.createNode(elem)
    val html      = DomRenderer.renderToHtml(node)
    html shouldContain "aria-label=\"Close dialog\""
  }

  test("ariaHidden sets aria-hidden attribute") {
    val elem      = div(ariaHidden -> "true")
    val (node, _) = DomRenderer.createNode(elem)
    val html      = DomRenderer.renderToHtml(node)
    html shouldContain "aria-hidden=\"true\""
  }

  test("ariaExpanded sets aria-expanded attribute") {
    val elem      = button(ariaExpanded -> "false")
    val (node, _) = DomRenderer.createNode(elem)
    val html      = DomRenderer.renderToHtml(node)
    html shouldContain "aria-expanded=\"false\""
  }

  test("ariaDescribedby sets aria-describedby attribute") {
    val elem      = input(ariaDescribedby -> "help-text")
    val (node, _) = DomRenderer.createNode(elem)
    val html      = DomRenderer.renderToHtml(node)
    html shouldContain "aria-describedby=\"help-text\""
  }

  test("ariaLabelledby sets aria-labelledby attribute") {
    val elem      = div(ariaLabelledby -> "title-id")
    val (node, _) = DomRenderer.createNode(elem)
    val html      = DomRenderer.renderToHtml(node)
    html shouldContain "aria-labelledby=\"title-id\""
  }

  test("ariaControls sets aria-controls attribute") {
    val elem      = button(ariaControls -> "menu-id")
    val (node, _) = DomRenderer.createNode(elem)
    val html      = DomRenderer.renderToHtml(node)
    html shouldContain "aria-controls=\"menu-id\""
  }

  test("ariaLive sets aria-live attribute") {
    val elem      = div(ariaLive -> "polite")
    val (node, _) = DomRenderer.createNode(elem)
    val html      = DomRenderer.renderToHtml(node)
    html shouldContain "aria-live=\"polite\""
  }

  test("ariaPressed sets aria-pressed attribute") {
    val elem      = button(ariaPressed -> "true")
    val (node, _) = DomRenderer.createNode(elem)
    val html      = DomRenderer.renderToHtml(node)
    html shouldContain "aria-pressed=\"true\""
  }

  test("ariaHaspopup sets aria-haspopup attribute") {
    val elem      = button(ariaHaspopup -> "menu")
    val (node, _) = DomRenderer.createNode(elem)
    val html      = DomRenderer.renderToHtml(node)
    html shouldContain "aria-haspopup=\"menu\""
  }

  test("ariaCurrent sets aria-current attribute") {
    val elem      = a(ariaCurrent -> "page")
    val (node, _) = DomRenderer.createNode(elem)
    val html      = DomRenderer.renderToHtml(node)
    html shouldContain "aria-current=\"page\""
  }

  test("role attribute works") {
    val elem      = div(role -> "button")
    val (node, _) = DomRenderer.createNode(elem)
    val html      = DomRenderer.renderToHtml(node)
    html shouldContain "role=\"button\""
  }

  test("aria dynamic method for uncommon attributes") {
    val elem      = div(aria("roledescription") -> "custom widget")
    val (node, _) = DomRenderer.createNode(elem)
    val html      = DomRenderer.renderToHtml(node)
    html shouldContain "aria-roledescription=\"custom widget\""
  }

  test("reactive aria attributes") {
    val expanded  = Rx.variable(false)
    val elem      = button(ariaExpanded -> expanded.map(_.toString))
    val (node, _) = DomRenderer.createNode(elem)
    var html      = DomRenderer.renderToHtml(node)
    html shouldContain "aria-expanded=\"false\""

    expanded := true
    html = DomRenderer.renderToHtml(node)
    html shouldContain "aria-expanded=\"true\""
  }

  test("multiple aria attributes on same element") {
    val elem = button(
      ariaLabel    -> "Submit form",
      ariaExpanded -> "false",
      ariaControls -> "form-content"
    )
    val (node, _) = DomRenderer.createNode(elem)
    val html      = DomRenderer.renderToHtml(node)
    html shouldContain "aria-label=\"Submit form\""
    html shouldContain "aria-expanded=\"false\""
    html shouldContain "aria-controls=\"form-content\""
  }

end AriaAttrsTest
