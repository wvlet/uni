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

import scala.language.implicitConversions

/**
  * Regression tests for pitfalls found while building the uni-harness example.
  */
class WorkspaceFixesTest extends UniTest:

  private def inputEvent(node: dom.Node): Unit = node.dispatchEvent(dom.Event("input"))

  test("value.bind and a user oninput both run") {
    val text = Rx.variable("")
    var seen = ""
    val elem = input(
      value.bind(text),
      oninput -> { (e: dom.Event) =>
        seen = text.get
      }
    )
    val (node, c) = DomRenderer.createNode(elem)
    val in        = node.asInstanceOf[dom.html.Input]
    in.value = "hello"
    inputEvent(in)
    text.get shouldBe "hello"
    // The binding is attached first, so the user handler sees the updated value
    seen shouldBe "hello"

    c.cancel
    in.value = "after cancel"
    inputEvent(in)
    text.get shouldBe "hello"
  }

  test("value.bind works when the user oninput comes first") {
    val text  = Rx.variable("")
    var calls = 0
    val elem  = textarea(
      oninput -> { () =>
        calls += 1
      },
      value.bind(text)
    )
    val (node, _) = DomRenderer.createNode(elem)
    val ta        = node.asInstanceOf[dom.html.TextArea]
    ta.value = "typed"
    inputEvent(ta)
    text.get shouldBe "typed"
    calls shouldBe 1
  }

  test("checked.bind and a user onchange both run") {
    val on    = Rx.variable(false)
    var calls = 0
    val elem  = input(
      tpe -> "checkbox",
      checked.bind(on),
      onchange -> { () =>
        calls += 1
      }
    )
    val (node, _) = DomRenderer.createNode(elem)
    val in        = node.asInstanceOf[dom.html.Input]
    in.checked = true
    in.dispatchEvent(dom.Event("change"))
    on.get shouldBe true
    calls shouldBe 1
  }

  test("Rx.const child keeps its content") {
    val elem      = div(Rx.const("constant"))
    val (node, _) = DomRenderer.createNode(elem)
    node.textContent shouldBe "constant"
  }

  test("Rx.const of elements keeps its content") {
    val rows      = Rx.const(Seq("a", "b"))
    val elem      = ul(rows.map(xs => xs.map(x => li(x))))
    val (node, _) = DomRenderer.createNode(elem)
    node.textContent shouldBe "ab"
  }

  test("Rx.const attribute keeps its value") {
    val elem      = div(cls += Rx.const("active"))
    val (node, _) = DomRenderer.createNode(elem)
    node.asInstanceOf[dom.Element].getAttribute("class") shouldBe "active"
  }

  test("disabled still works as a bare attribute") {
    val (node, _) = DomRenderer.createNode(button(disabled))
    node.asInstanceOf[dom.html.Button].disabled shouldBe true
  }

  test("disabled accepts a Boolean") {
    val (on, _)  = DomRenderer.createNode(button(disabled -> true))
    val (off, _) = DomRenderer.createNode(button(disabled -> false))
    on.asInstanceOf[dom.html.Button].disabled shouldBe true
    off.asInstanceOf[dom.html.Button].disabled shouldBe false
    off.asInstanceOf[dom.Element].hasAttribute("disabled") shouldBe false
  }

  test("disabled accepts an Rx[Boolean]") {
    val busy      = Rx.variable(true)
    val (node, _) = DomRenderer.createNode(button(disabled -> busy))
    val b         = node.asInstanceOf[dom.html.Button]
    b.disabled shouldBe true
    b.hasAttribute("disabled") shouldBe true
    busy := false
    b.disabled shouldBe false
    b.hasAttribute("disabled") shouldBe false
    busy := true
    b.disabled shouldBe true
  }

  test("hidden accepts an Rx[Boolean]") {
    val folded    = Rx.variable(false)
    val (node, _) = DomRenderer.createNode(div(hidden -> folded))
    val d         = node.asInstanceOf[dom.html.Element]
    d.hasAttribute("hidden") shouldBe false
    folded := true
    d.hasAttribute("hidden") shouldBe true
  }

  test("muted accepts an Rx[Boolean] and drives the property") {
    val isMuted   = Rx.variable(true)
    val (node, _) = DomRenderer.createNode(video(muted -> isMuted))
    val v         = node.asInstanceOf[dom.html.Video]
    v.muted shouldBe true
    isMuted := false
    v.muted shouldBe false
  }

end WorkspaceFixesTest
