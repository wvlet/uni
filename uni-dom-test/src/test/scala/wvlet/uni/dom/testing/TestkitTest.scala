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
import wvlet.uni.dom.RxElement
import wvlet.uni.dom.all.*
import wvlet.uni.dom.all.given

import scala.collection.mutable
import scala.language.implicitConversions

/** `settle`, pointer events, and IME key events of the testing kit. */
class TestkitTest extends DomTestSupport:

  test("settle waits until onMount has run"):
    var mounted = false
    class Widget extends RxElement:
      override def render: RxElement        = div("widget")
      override def onMount(node: Any): Unit = mounted = true

    mount(Widget())
    settle().map { _ =>
      mounted shouldBe true
    }

  private def recordPointers(target: dom.EventTarget): mutable.Buffer[dom.PointerEvent] =
    val seen = mutable.Buffer.empty[dom.PointerEvent]
    Seq(
      "pointerdown",
      "pointermove",
      "pointerup",
      "pointercancel",
      "pointerover",
      "pointerout",
      "pointerenter",
      "pointerleave"
    ).foreach { t =>
      target.addEventListener(t, (e: dom.PointerEvent) => seen += e)
    }
    seen

  test("pointer events carry coordinates, pointer id, type, and button"):
    val m    = mount(RxElement(div(cls -> "pad", "pad")))
    val pad  = m.get(".pad")
    val seen = recordPointers(pad)
    fireEvent.pointerDown(pad, clientX = 10, clientY = 20, pointerId = 7, pointerType = "pen")
    fireEvent.pointerMove(pad, clientX = 15)
    fireEvent.pointerUp(pad, button = 2)
    fireEvent.pointerCancel(pad)
    fireEvent.pointerOver(pad)
    fireEvent.pointerOut(pad)
    fireEvent.pointerEnter(pad)
    fireEvent.pointerLeave(pad)
    seen.map(_.`type`).toSeq shouldBe
      Seq(
        "pointerdown",
        "pointermove",
        "pointerup",
        "pointercancel",
        "pointerover",
        "pointerout",
        "pointerenter",
        "pointerleave"
      )
    val down = seen.head
    down.clientX shouldBe 10
    down.clientY shouldBe 20
    down.pointerId shouldBe 7
    down.pointerType shouldBe "pen"
    seen(1).pointerType shouldBe "mouse"
    seen(2).button shouldBe 2

  test("pointerEnter and pointerLeave do not bubble, like the browser"):
    val m      = mount(RxElement(div(cls -> "outer", span(cls -> "inner", "x"))))
    val parent = recordPointers(m.get(".outer"))
    val inner  = m.get(".inner")
    fireEvent.pointerEnter(inner)
    fireEvent.pointerLeave(inner)
    fireEvent.pointerDown(inner)
    parent.map(_.`type`).toSeq shouldBe Seq("pointerdown")

  test("keyboard events report isComposing"):
    val m     = mount(RxElement(input(tpe -> "text")))
    val field = m.get("input")
    val seen  = mutable.Buffer.empty[Boolean]
    field.addEventListener("keydown", (e: dom.KeyboardEvent) => seen += e.isComposing)
    fireEvent.keyDown(field, "Enter")
    fireEvent.keyDown(field, "Enter", isComposing = true)
    seen.toSeq shouldBe Seq(false, true)

end TestkitTest
