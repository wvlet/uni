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

class DomRefTest extends UniTest:

  test("DomRef starts with None") {
    val myRef = DomRef[dom.html.Input]()
    myRef.current shouldBe None
  }

  test("DomRef.set updates current") {
    val myRef = DomRef[dom.html.Div]()
    val elem  = dom.document.createElement("div").asInstanceOf[dom.html.Div]
    myRef.set(elem)
    myRef.current shouldBe Some(elem)
  }

  test("DomRef.clear removes reference") {
    val myRef = DomRef[dom.html.Div]()
    val elem  = dom.document.createElement("div").asInstanceOf[dom.html.Div]
    myRef.set(elem)
    myRef.clear()
    myRef.current shouldBe None
  }

  test("DomRef.foreach executes when element exists") {
    val myRef  = DomRef[dom.html.Div]()
    val elem   = dom.document.createElement("div").asInstanceOf[dom.html.Div]
    var called = false
    myRef.set(elem)
    myRef.foreach(_ => called = true)
    called shouldBe true
  }

  test("DomRef.foreach does nothing when element is None") {
    val myRef  = DomRef[dom.html.Div]()
    var called = false
    myRef.foreach(_ => called = true)
    called shouldBe false
  }

  test("DomRef.map transforms element when present") {
    val myRef = DomRef[dom.html.Div]()
    val elem  = dom.document.createElement("div").asInstanceOf[dom.html.Div]
    elem.className = "test-class"
    myRef.set(elem)
    myRef.map(_.className) shouldBe Some("test-class")
  }

  test("DomRef.map returns None when element is absent") {
    val myRef = DomRef[dom.html.Div]()
    myRef.map(_.className) shouldBe None
  }

  test("RefBinding is a DomNode") {
    val myRef   = DomRef[dom.html.Input]()
    val binding = RefBinding(myRef)
    binding shouldMatch { case _: DomNode =>
    }
  }

  test("ref -> syntax creates RefBinding") {
    val myRef   = DomRef[dom.html.Input]()
    val binding = ref -> myRef
    binding shouldMatch { case rb: RefBinding[?] =>
      rb.ref shouldBe myRef
    }
  }

  test("DomRef.binding creates RefBinding") {
    val myRef   = DomRef[dom.html.Input]()
    val binding = myRef.binding
    binding shouldMatch { case rb: RefBinding[?] =>
      rb.ref shouldBe myRef
    }
  }

  test("DomRef is set during rendering") {
    val inputRef  = DomRef[dom.html.Input]()
    val elem      = input(tpe -> "text", ref -> inputRef)
    val (node, _) = DomRenderer.createNode(elem)

    inputRef.current shouldNotBe None
    inputRef.current.map(_.tagName.toLowerCase) shouldBe Some("input")
  }

  test("DomRef rx emits element when set") {
    val myRef = DomRef[dom.html.Div]()
    val elem  = dom.document.createElement("div").asInstanceOf[dom.html.Div]

    var received: Option[dom.html.Div] = None
    myRef
      .rx
      .run { opt =>
        received = opt
      }

    myRef.set(elem)
    received shouldBe Some(elem)
  }

  test("DomRef.getBoundingClientRect returns rect when element exists") {
    val myRef     = DomRef[dom.html.Div]()
    val elem      = div(ref -> myRef)
    val (node, _) = DomRenderer.createNode(elem)

    // In jsdom, getBoundingClientRect returns a rect (may be zero dimensions)
    val rect = myRef.getBoundingClientRect()
    rect shouldNotBe None
  }

  test("DomRef.getBoundingClientRect returns None when element is absent") {
    val myRef = DomRef[dom.html.Div]()
    myRef.getBoundingClientRect() shouldBe None
  }

end DomRefTest
