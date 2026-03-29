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
import wvlet.uni.test.UniTest
import wvlet.uni.dom.all.*
import wvlet.uni.dom.all.given
import scala.language.implicitConversions
import wvlet.uni.rx.Rx

class FocusTest extends UniTest:

  test("Focus.trap returns RxElement"):
    val trap = Focus.trap(div("content"))
    trap shouldMatch { case _: RxElement =>
    }

  test("Focus.onMount returns DomNode"):
    val node = Focus.onMount
    node shouldMatch { case _: DomNode =>
    }

  test("Focus.onMountDelayed returns DomNode"):
    val node = Focus.onMountDelayed(100)
    node shouldMatch { case _: DomNode =>
    }

  test("Focus.active returns Rx[Option[Element]]"):
    val active = Focus.active
    active shouldMatch { case _: Rx[?] =>
    }

  test("Focus.currentActive returns Option[Element]"):
    val active = Focus.currentActive
    active shouldMatch { case _: Option[?] =>
    }

  test("Focus.saveAndRestore returns restore function"):
    val restore = Focus.saveAndRestore()
    restore shouldMatch { case _: Function0[?] =>
    }

  test("Focus.withRestoration executes function with restore callback"):
    var restoreCalled = false
    val result        = Focus.withRestoration { restore =>
      // Verify restore is a function
      restore shouldMatch { case _: Function0[?] =>
      }
      "result"
    }
    result shouldBe "result"

  test("Focus.focusById focuses element by id"):
    // Create a test element
    val testDiv = dom.document.createElement("div")
    testDiv.id = "focus-test-element"
    testDiv.setAttribute("tabindex", "-1")
    dom.document.body.appendChild(testDiv)

    try
      Focus.focusById("focus-test-element")
      // Check it was focused
      dom.document.activeElement shouldBe testDiv
    finally
      dom.document.body.removeChild(testDiv)

  test("Focus.blur removes focus"):
    val testInput = dom.document.createElement("input").asInstanceOf[dom.HTMLInputElement]
    dom.document.body.appendChild(testInput)

    try
      testInput.focus()
      dom.document.activeElement shouldBe testInput

      Focus.blur()
      // After blur, focus should no longer be on the input
      (dom.document.activeElement == testInput) shouldBe false
      // And activeElement should be body
      dom.document.activeElement shouldBe dom.document.body
    finally
      dom.document.body.removeChild(testInput)

  test("Focus.getFocusableElements finds buttons"):
    val container = dom.document.createElement("div")
    val button    = dom.document.createElement("button")
    container.appendChild(button)
    dom.document.body.appendChild(container)

    try
      val focusables = Focus.getFocusableElements(container)
      focusables.length shouldBe 1
      focusables.head shouldBe button
    finally
      dom.document.body.removeChild(container)

  test("Focus.getFocusableElements finds inputs"):
    val container = dom.document.createElement("div")
    val input     = dom.document.createElement("input")
    container.appendChild(input)
    dom.document.body.appendChild(container)

    try
      val focusables = Focus.getFocusableElements(container)
      focusables.length shouldBe 1
      focusables.head shouldBe input
    finally
      dom.document.body.removeChild(container)

  test("Focus.getFocusableElements excludes disabled elements"):
    val container = dom.document.createElement("div")
    val button    = dom.document.createElement("button")
    button.setAttribute("disabled", "true")
    container.appendChild(button)
    dom.document.body.appendChild(container)

    try
      val focusables = Focus.getFocusableElements(container)
      focusables.length shouldBe 0
    finally
      dom.document.body.removeChild(container)

  test("Focus.getFocusableElements finds elements with tabindex"):
    val container = dom.document.createElement("div")
    val divTab    = dom.document.createElement("div")
    divTab.setAttribute("tabindex", "0")
    container.appendChild(divTab)
    dom.document.body.appendChild(container)

    try
      val focusables = Focus.getFocusableElements(container)
      focusables.length shouldBe 1
      focusables.head shouldBe divTab
    finally
      dom.document.body.removeChild(container)

  test("Focus.getFocusableElements excludes tabindex=-1"):
    val container = dom.document.createElement("div")
    val divTab    = dom.document.createElement("div")
    divTab.setAttribute("tabindex", "-1")
    container.appendChild(divTab)
    dom.document.body.appendChild(container)

    try
      val focusables = Focus.getFocusableElements(container)
      focusables.length shouldBe 0
    finally
      dom.document.body.removeChild(container)

  test("Focus.active emits values reactively"):
    var result: Option[dom.Element] = None
    val cancel                      = Focus
      .active
      .run { v =>
        result = v
      }
    result shouldMatch { case _: Option[?] =>
    }
    cancel.cancel

  test("Focus.trap renders with tabindex"):
    val trap           = Focus.trap(button("Click me"))
    val (node, cancel) = DomRenderer.createNode(trap)

    node match
      case elem: dom.Element =>
        elem.getAttribute("tabindex") shouldBe "-1"
      case _ =>
        fail("Expected Element")

    cancel.cancel

  test("Focus.trap contains children"):
    val trap           = Focus.trap(button("Button 1"), button("Button 2"))
    val (node, cancel) = DomRenderer.createNode(trap)

    node match
      case elem: dom.Element =>
        val buttons = elem.querySelectorAll("button")
        buttons.length shouldBe 2
      case _ =>
        fail("Expected Element")

    cancel.cancel

end FocusTest
