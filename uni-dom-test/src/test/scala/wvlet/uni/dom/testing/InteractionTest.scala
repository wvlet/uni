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

import wvlet.uni.test.UniTest
import wvlet.uni.rx.Rx
import wvlet.uni.dom.RxElement
import wvlet.uni.dom.all.*
import wvlet.uni.dom.all.given
import org.scalajs.dom
import scala.language.implicitConversions

/**
  * End-to-end UI interaction tests: render a real `uni-dom` component into a headless Chromium DOM,
  * dispatch real user events with [[fireEvent]], and assert on the reactively re-rendered output.
  *
  * This is the gap the toolkit closes — previously DOM tests could only `renderTo` and read text,
  * never simulate a click or keystroke.
  */
class InteractionTest extends UniTest with DomTestSupport:

  test("clicking a button updates reactive state"):
    val count = Rx.variable(0)

    class Counter extends RxElement:
      override def render: RxElement = div(
        span(cls -> "count", count.map(c => s"count=${c}")),
        button(
          cls     -> "inc",
          onclick -> { () =>
            count := count.get + 1
          },
          "+1"
        ),
        button(
          cls     -> "dec",
          onclick -> { () =>
            count := count.get - 1
          },
          "-1"
        )
      )

    val m = mount(Counter())
    m.text shouldContain "count=0"

    fireEvent.click(m.getByText("+1"))
    m.text shouldContain "count=1"

    fireEvent.click(m.getByText("+1"))
    fireEvent.click(m.getByText("+1"))
    m.text shouldContain "count=3"

    fireEvent.click(m.getByText("-1"))
    m.text shouldContain "count=2"

  test("typing into an input drives a two-way bound RxVar"):
    val name = Rx.variable("")

    class Greeter extends RxElement:
      override def render: RxElement = div(
        input(tpe -> "text", value.bind(name)),
        span(
          cls -> "greeting",
          name.map(n =>
            if n.isEmpty then
              "Hello, stranger"
            else
              s"Hello, ${n}"
          )
        )
      )

    val m = mount(Greeter())
    m.text shouldContain "Hello, stranger"

    val inputEl = m.query.get("input")
    fireEvent.input(inputEl, "Uni")
    name.get shouldBe "Uni"
    m.text shouldContain "Hello, Uni"

  test("keyboard events reach onkeydown handlers"):
    val lastKey = Rx.variable("")

    class KeyCapture extends RxElement:
      override def render: RxElement = div(
        tabindex  -> "0",
        onkeydown -> { (e: dom.KeyboardEvent) =>
          lastKey := e.key
        },
        span(cls -> "last", lastKey.map(k => s"key=${k}"))
      )

    val m    = mount(KeyCapture())
    val root = m.root.get
    fireEvent.keyDown(root, key = "Enter")
    lastKey.get shouldBe "Enter"
    m.text shouldContain "key=Enter"

  test("getByText returns the deepest matching element, not an ancestor"):
    class Nested extends RxElement:
      override def render: RxElement = div(cls -> "outer", span(cls -> "inner", "Save"))

    val m   = mount(Nested())
    val hit = m.getByText("Save")
    hit.getAttribute("class") shouldBe "inner"

  test("queryByText returns None when no element matches"):
    val m = mount(RxElement(div("hello")))
    m.queryByText("goodbye") shouldBe None

  test("unmount detaches the container and runs cleanup"):
    val m         = mount(RxElement(div(cls -> "ephemeral", "bye")))
    val container = m.container
    (container.parentNode != null) shouldBe true
    m.unmount()
    (container.parentNode == null) shouldBe true

end InteractionTest
