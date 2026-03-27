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
import wvlet.uni.rx.Rx

class DomPropertyTest extends UniTest:

  test("value property sets input value"):
    val elem      = input(tpe -> "text", value -> "hello")
    val (node, _) = DomRenderer.createNode(elem)
    val inputNode = node.asInstanceOf[org.scalajs.dom.html.Input]
    inputNode.value shouldBe "hello"

  test("value property with reactive value"):
    val text      = Rx.variable("initial")
    val elem      = input(tpe -> "text", value -> text)
    val (node, _) = DomRenderer.createNode(elem)
    val inputNode = node.asInstanceOf[org.scalajs.dom.html.Input]
    inputNode.value shouldBe "initial"

    // Update the reactive value
    text := "updated"
    inputNode.value shouldBe "updated"

  test("checked property sets checkbox state"):
    val elem      = input(tpe -> "checkbox", checked -> true)
    val (node, _) = DomRenderer.createNode(elem)
    val inputNode = node.asInstanceOf[org.scalajs.dom.html.Input]
    inputNode.checked shouldBe true

  test("checked property with reactive value"):
    val isChecked = Rx.variable(false)
    val elem      = input(tpe -> "checkbox", checked -> isChecked)
    val (node, _) = DomRenderer.createNode(elem)
    val inputNode = node.asInstanceOf[org.scalajs.dom.html.Input]
    inputNode.checked shouldBe false

    // Update the reactive value
    isChecked := true
    inputNode.checked shouldBe true

  test("disabled property sets disabled state"):
    val elem      = input(tpe -> "text", disabled)
    val (node, _) = DomRenderer.createNode(elem)
    val inputNode = node.asInstanceOf[org.scalajs.dom.html.Input]
    inputNode.disabled shouldBe true

  test("value.bind creates two-way binding"):
    val text      = Rx.variable("initial")
    val elem      = input(tpe -> "text", value.bind(text))
    val (node, _) = DomRenderer.createNode(elem)
    val inputNode = node.asInstanceOf[org.scalajs.dom.html.Input]

    // Initial value should be set
    inputNode.value shouldBe "initial"

    // Update Rx -> DOM should update
    text := "from-rx"
    inputNode.value shouldBe "from-rx"

    // Update DOM -> Rx should update
    inputNode.value = "from-dom"
    inputNode.dispatchEvent(new org.scalajs.dom.Event("input"))
    text.get shouldBe "from-dom"

  test("checked.bind creates two-way binding"):
    val isChecked = Rx.variable(false)
    val elem      = input(tpe -> "checkbox", checked.bind(isChecked))
    val (node, _) = DomRenderer.createNode(elem)
    val inputNode = node.asInstanceOf[org.scalajs.dom.html.Input]

    // Initial value should be set
    inputNode.checked shouldBe false

    // Update Rx -> DOM should update
    isChecked := true
    inputNode.checked shouldBe true

    // Update DOM -> Rx should update
    inputNode.checked = false
    inputNode.dispatchEvent(new org.scalajs.dom.Event("change"))
    isChecked.get shouldBe false

  test("reactive checked toggles correctly from true to false"):
    val isChecked = Rx.variable(true)
    val elem      = input(tpe -> "checkbox", checked -> isChecked)
    val (node, _) = DomRenderer.createNode(elem)
    val inputNode = node.asInstanceOf[org.scalajs.dom.html.Input]
    inputNode.checked shouldBe true

    // Toggle to false - property should be updated, not just attribute removed
    isChecked := false
    inputNode.checked shouldBe false

    // Toggle back to true
    isChecked := true
    inputNode.checked shouldBe true

  test("value.bind works with textarea"):
    val text         = Rx.variable("initial text")
    val elem         = textarea(value.bind(text))
    val (node, _)    = DomRenderer.createNode(elem)
    val textareaNode = node.asInstanceOf[org.scalajs.dom.html.TextArea]

    // Initial value should be set
    textareaNode.value shouldBe "initial text"

    // Update Rx -> DOM should update
    text := "updated text"
    textareaNode.value shouldBe "updated text"

  test("value.bind works with select"):
    val selected = Rx.variable("a")
    val elem     = select(
      option(value -> "a", "Option A"),
      option(value -> "b", "Option B"),
      option(value -> "c", "Option C"),
      value.bind(selected)
    )
    val (node, _)  = DomRenderer.createNode(elem)
    val selectNode = node.asInstanceOf[org.scalajs.dom.html.Select]

    // Initial value should be set (default is first option)
    selectNode.value shouldBe "a"

    // Update Rx -> DOM should update
    selected := "c"
    selectNode.value shouldBe "c"

    // Update back
    selected := "b"
    selectNode.value shouldBe "b"

end DomPropertyTest
