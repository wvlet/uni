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

class CssClassTest extends UniTest:

  test("static class toggle when true") {
    val elem      = div(cls.toggle("active") when true)
    val (node, _) = DomRenderer.createNode(elem)
    val html      = DomRenderer.renderToHtml(node)
    html shouldContain "active"
  }

  test("static class toggle when false") {
    val elem      = div(cls.toggle("active") when false)
    val (node, _) = DomRenderer.createNode(elem)
    val html      = DomRenderer.renderToHtml(node)
    html shouldNotContain "active"
  }

  test("combine base class with toggle") {
    val elem      = div(cls := "base", cls.toggle("active") when true)
    val (node, _) = DomRenderer.createNode(elem)
    val html      = DomRenderer.renderToHtml(node)
    html shouldContain "base"
    html shouldContain "active"
  }

  test("multiple class toggles") {
    val elem = div(
      cls := "base",
      cls.toggle("active") when true,
      cls.toggle("disabled") when false,
      cls.toggle("visible") when true
    )
    val (node, _) = DomRenderer.createNode(elem)
    val html      = DomRenderer.renderToHtml(node)
    html shouldContain "base"
    html shouldContain "active"
    html shouldNotContain "disabled"
    html shouldContain "visible"
  }

  test("reactive class toggle") {
    val isActive           = Rx.variable(false)
    val elem               = div(cls.toggle("active") when isActive)
    val (node, cancelable) = DomRenderer.createNode(elem)

    // Initially false - no class
    val html1 = DomRenderer.renderToHtml(node)
    html1 shouldNotContain "active"

    // Set to true - class appears
    isActive := true
    val html2 = DomRenderer.renderToHtml(node)
    html2 shouldContain "active"

    // Set back to false - class removed
    isActive := false
    val html3 = DomRenderer.renderToHtml(node)
    html3 shouldNotContain "active"

    cancelable.cancel
  }

  test("reactive class toggle with base class") {
    val isActive           = Rx.variable(true)
    val elem               = div(cls := "base", cls.toggle("active") when isActive)
    val (node, cancelable) = DomRenderer.createNode(elem)

    val html = DomRenderer.renderToHtml(node)
    html shouldContain "base"
    html shouldContain "active"

    isActive := false
    val html2 = DomRenderer.renderToHtml(node)
    html2 shouldContain "base"
    html2 shouldNotContain "active"

    cancelable.cancel
  }

  test("cls object works as attribute") {
    val elem      = div(cls := "my-class")
    val (node, _) = DomRenderer.createNode(elem)
    val html      = DomRenderer.renderToHtml(node)
    html shouldContain "my-class"
  }

  test("cls with arrow syntax") {
    val elem      = div(cls -> "my-class")
    val (node, _) = DomRenderer.createNode(elem)
    val html      = DomRenderer.renderToHtml(node)
    html shouldContain "my-class"
  }

end CssClassTest
