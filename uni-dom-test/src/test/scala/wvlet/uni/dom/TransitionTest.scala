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
import wvlet.uni.rx.Rx
import wvlet.uni.dom.all.*
import wvlet.uni.dom.all.given

class TransitionTest extends UniTest:

  test("TransitionConfig has sensible defaults"):
    val config = TransitionConfig()
    config.name shouldBe "v"
    config.duration shouldBe None
    config.appear shouldBe false

  test("TransitionConfig can be customized"):
    val config = TransitionConfig(name = "fade", duration = Some(300), appear = true)
    config.name shouldBe "fade"
    config.duration shouldBe Some(300)
    config.appear shouldBe true

  test("TransitionConfig withName"):
    val config = TransitionConfig().withName("slide")
    config.name shouldBe "slide"

  test("TransitionConfig withDuration"):
    val config = TransitionConfig().withDuration(500)
    config.duration shouldBe Some(500)

  test("TransitionConfig noDuration"):
    val config = TransitionConfig(duration = Some(300)).noDuration
    config.duration shouldBe None

  test("TransitionConfig withAppear"):
    val config = TransitionConfig().withAppear
    config.appear shouldBe true

  test("Transition.apply with name returns RxElement"):
    val visible    = Rx.variable(false)
    val transition = Transition("fade", visible)(div("content"))
    transition shouldMatch { case _: RxElement =>
    }

  test("Transition.apply with config returns RxElement"):
    val visible    = Rx.variable(false)
    val config     = TransitionConfig(name = "slide", duration = Some(200))
    val transition = Transition(config, visible)(div("content"))
    transition shouldMatch { case _: RxElement =>
    }

  test("Transition.fade returns RxElement"):
    val visible    = Rx.variable(false)
    val transition = Transition.fade(visible)(div("content"))
    transition shouldMatch { case _: RxElement =>
    }

  test("Transition.slide returns RxElement"):
    val visible    = Rx.variable(false)
    val transition = Transition.slide(visible)(div("content"))
    transition shouldMatch { case _: RxElement =>
    }

  test("Transition renders wrapper div with children"):
    val visible        = Rx.variable(true)
    val transition     = Transition("fade", visible)(span("hello"))
    val (node, cancel) = DomRenderer.createNode(transition)

    node match
      case elem: org.scalajs.dom.Element =>
        elem.tagName.toLowerCase shouldBe "div"
        val spans = elem.querySelectorAll("span")
        spans.length shouldBe 1
      case _ =>
        fail("Expected Element")

    cancel.cancel

  test("Transition renders hidden when initially false"):
    val visible        = Rx.variable(false)
    val transition     = Transition("fade", visible)(span("hello"))
    val (node, cancel) = DomRenderer.createNode(transition)

    node match
      case elem: org.scalajs.dom.HTMLElement =>
        elem.style.display shouldBe "none"
      case _ =>
        fail("Expected HTMLElement")

    cancel.cancel

  test("Transition renders visible when initially true"):
    val visible        = Rx.variable(true)
    val transition     = Transition("fade", visible)(span("hello"))
    val (node, cancel) = DomRenderer.createNode(transition)

    node match
      case elem: org.scalajs.dom.HTMLElement =>
        elem.style.display shouldNotBe "none"
      case _ =>
        fail("Expected HTMLElement")

    cancel.cancel

  test("Transition with multiple children"):
    val visible        = Rx.variable(true)
    val transition     = Transition("fade", visible)(span("a"), span("b"), span("c"))
    val (node, cancel) = DomRenderer.createNode(transition)

    node match
      case elem: org.scalajs.dom.Element =>
        val spans = elem.querySelectorAll("span")
        spans.length shouldBe 3
      case _ =>
        fail("Expected Element")

    cancel.cancel

end TransitionTest
