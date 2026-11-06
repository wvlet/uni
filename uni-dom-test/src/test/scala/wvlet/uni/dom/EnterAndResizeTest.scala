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
import wvlet.uni.dom.testing.*
import wvlet.uni.rx.Rx

import scala.collection.mutable
import scala.scalajs.js
import scala.language.implicitConversions

/** `Keyboard.onEnter`, IME-safe shortcuts, `PointerDrag`, and `PaneResize`. */
class EnterAndResizeTest extends DomTestSupport:

  test("onEnter fires on plain Enter only") {
    var sent = 0
    val m    = mount(textarea(Keyboard.onEnter(() => sent += 1)))
    val box  = m.get("textarea")
    fireEvent.keyDown(box, "Enter") shouldBe false // default prevented
    sent shouldBe 1
    fireEvent.keyDown(box, "Enter", shiftKey = true) shouldBe true
    fireEvent.keyDown(box, "Enter", metaKey = true) shouldBe true
    fireEvent.keyDown(box, "a")
    sent shouldBe 1
  }

  test("onEnter ignores the Enter that confirms an IME conversion") {
    var sent = 0
    val m    = mount(textarea(Keyboard.onEnter(() => sent += 1)))
    fireEvent.keyDown(m.get("textarea"), "Enter", isComposing = true) shouldBe true
    sent shouldBe 0
  }

  test("onEnter runs alongside an onkeydown handler") {
    val seen = mutable.Buffer.empty[String]
    val m    = mount(
      input(
        onkeydown -> { (e: dom.KeyboardEvent) =>
          seen += s"key:${e.key}"
        },
        Keyboard.onEnter(() => seen += "enter")
      )
    )
    fireEvent.keyDown(m.get("input"), "Enter")
    seen.toSet shouldBe Set("key:Enter", "enter")
  }

  private def enterKey(isComposing: Boolean = false, keyCode: Int = 13): dom.KeyboardEvent =
    // keyCode is not settable through KeyboardEventInit, so define it on the event
    val e = dom.KeyboardEvent(
      "keydown",
      js.Dynamic
        .literal(key = "Enter", isComposing = isComposing)
        .asInstanceOf[dom.KeyboardEventInit]
    )
    js.Object
      .defineProperty(
        e,
        "keyCode",
        js.Dynamic.literal(value = keyCode).asInstanceOf[js.PropertyDescriptor]
      )
    e

  test("Keyboard.isComposing detects composition and keyCode 229") {
    Keyboard.isComposing(enterKey()) shouldBe false
    Keyboard.isComposing(enterKey(isComposing = true)) shouldBe true
    // Safari reports the keydown that ends a composition with keyCode 229
    Keyboard.isComposing(enterKey(keyCode = 229)) shouldBe true
  }

  test("scoped shortcuts do not fire during IME composition") {
    var count = 0
    val m     = mount(input(Keyboard.scoped("enter" -> (() => count += 1))))
    val field = m.get("input")
    fireEvent.keyDown(field, "Enter", isComposing = true)
    count shouldBe 0
    fireEvent.keyDown(field, "Enter")
    count shouldBe 1
  }

  test("global shortcuts do not fire during IME composition") {
    var count  = 0
    val cancel = Keyboard.bind("enter", () => count += 1)
    try
      fireEvent.keyDown(dom.window, "Enter", isComposing = true)
      count shouldBe 0
      fireEvent.keyDown(dom.window, "Enter")
      count shouldBe 1
    finally
      cancel.cancel
  }

  test("PointerDrag reports distances from the start until release") {
    val moves = mutable.Buffer.empty[(Double, Double)]
    var ended = 0
    val drag  = PointerDrag(
      onMove = (dx, dy) => moves += ((dx, dy)),
      onEnd = () => ended += 1,
      cursor = "grabbing"
    )
    val m = mount(div(cls -> "handle", drag.bind))
    fireEvent.pointerDown(m.get(".handle"), clientX = 100, clientY = 50)
    drag.isDragging shouldBe true
    dom.document.body.style.cursor shouldBe "grabbing"
    fireEvent.pointerMove(dom.window, clientX = 130, clientY = 40)
    fireEvent.pointerUp(dom.window, clientX = 130, clientY = 40)
    drag.isDragging shouldBe false
    dom.document.body.style.cursor shouldBe ""
    ended shouldBe 1
    // Moves after release are not tracked
    fireEvent.pointerMove(dom.window, clientX = 0, clientY = 0)
    moves.toSeq shouldBe Seq((30.0, -10.0))
  }

  test("PointerDrag ends on pointercancel and ignores non-primary buttons") {
    var moved = 0
    val drag  = PointerDrag(onMove = (_, _) => moved += 1)
    val m     = mount(div(cls -> "handle", drag.bind))
    fireEvent.pointerDown(m.get(".handle"), button = 2)
    drag.isDragging shouldBe false
    fireEvent.pointerDown(m.get(".handle"))
    fireEvent.pointerCancel(dom.window)
    drag.isDragging shouldBe false
    fireEvent.pointerMove(dom.window, clientX = 10)
    moved shouldBe 0
  }

  test("PointerDrag follows only the starting pointer and restores the page cursor") {
    val moves = mutable.Buffer.empty[Double]
    val drag  = PointerDrag(onMove = (dx, _) => moves += dx, cursor = "col-resize")
    val m     = mount(div(cls -> "handle", drag.bind))
    dom.document.body.style.cursor = "wait"
    try
      fireEvent.pointerDown(m.get(".handle"), clientX = 100, pointerId = 1)
      dom.document.body.style.cursor shouldBe "col-resize"
      // A second finger neither moves nor ends the drag
      fireEvent.pointerMove(dom.window, clientX = 500, pointerId = 2)
      fireEvent.pointerUp(dom.window, clientX = 500, pointerId = 2)
      drag.isDragging shouldBe true
      fireEvent.pointerMove(dom.window, clientX = 120, pointerId = 1)
      fireEvent.pointerUp(dom.window, clientX = 120, pointerId = 1)
      drag.isDragging shouldBe false
      moves.toSeq shouldBe Seq(20.0)
      dom.document.body.style.cursor shouldBe "wait"
    finally
      dom.document.body.style.cursor = ""
  }

  test("PaneResize clamps to the range and resets on double-click") {
    val width  = Rx.variable(272)
    val range  = SizeRange(min = 208, default = 272, max = 480)
    val resize = PaneResize(width, range, ResizeDirection.Right)
    val m      = mount(div(cls -> "grip", resize.bind))
    val grip   = m.get(".grip")
    grip.getAttribute("role") shouldBe "separator"
    grip.getAttribute("aria-orientation") shouldBe "vertical"
    fireEvent.pointerDown(grip, clientX = 300)
    fireEvent.pointerMove(dom.window, clientX = 350)
    width.get shouldBe 322
    fireEvent.pointerMove(dom.window, clientX = 1000)
    width.get shouldBe range.max
    fireEvent.pointerMove(dom.window, clientX = -1000)
    width.get shouldBe range.min
    fireEvent.pointerUp(dom.window)
    fireEvent.dblclick(grip)
    width.get shouldBe range.default
  }

  test("PaneResize clamps an out-of-range starting size") {
    val range = SizeRange(min = 260, default = 320, max = 720)
    val wide  = Rx.variable(960)
    PaneResize(wide, range, ResizeDirection.Left)
    wide.get shouldBe range.max
    val narrow = Rx.variable(10)
    PaneResize(narrow, range, ResizeDirection.Left)
    narrow.get shouldBe range.min
    val inRange = Rx.variable(400)
    PaneResize(inRange, range, ResizeDirection.Left)
    inRange.get shouldBe 400
  }

  test("PaneResize grows against the pointer for Left and Up handles") {
    val width = Rx.variable(300)
    val wide  = mount(
      div(cls -> "left", PaneResize(width, SizeRange(200, 300, 400), ResizeDirection.Left).bind)
    )
    fireEvent.pointerDown(wide.get(".left"), clientX = 500)
    fireEvent.pointerMove(dom.window, clientX = 450)
    fireEvent.pointerUp(dom.window)
    width.get shouldBe 350

    val height = Rx.variable(200)
    val tall   = mount(
      div(cls -> "up", PaneResize(height, SizeRange(100, 200, 300), ResizeDirection.Up).bind)
    )
    tall.get(".up").getAttribute("aria-orientation") shouldBe "horizontal"
    fireEvent.pointerDown(tall.get(".up"), clientY = 400)
    fireEvent.pointerMove(dom.window, clientY = 380)
    fireEvent.pointerUp(dom.window)
    height.get shouldBe 220
  }

  test("PaneResize exposes dragging") {
    val resize = PaneResize(Rx.variable(250), SizeRange(200, 250, 300), ResizeDirection.Down)
    val m      = mount(div(cls -> "grip", resize.bind))
    val states = mutable.Buffer.empty[Boolean]
    val sub    = resize.dragging.run(states += _)
    fireEvent.pointerDown(m.get(".grip"))
    fireEvent.pointerUp(dom.window)
    sub.cancel
    states.toSeq shouldBe Seq(false, true, false)
  }

end EnterAndResizeTest
