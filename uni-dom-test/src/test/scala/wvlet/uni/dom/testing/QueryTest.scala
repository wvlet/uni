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
import wvlet.uni.dom.RxElement
import wvlet.uni.dom.all.*
import wvlet.uni.dom.all.given
import scala.language.implicitConversions

class QueryTest extends UniTest with DomTestSupport:

  test("getByTestId finds an element by data-testid"):
    val m = mount(RxElement(div(data("testid") -> "save-button", button("Save"))))
    m.getByTestId("save-button").textContent shouldBe "Save"

  test("queryByTestId returns None for a missing id"):
    val m = mount(RxElement(div("x")))
    m.queryByTestId("nope") shouldBe None

  test("queryAllByTestId returns every match"):
    val m = mount(
      RxElement(
        div(
          span(data("testid") -> "row", "a"),
          span(data("testid") -> "row", "b"),
          span(data("testid") -> "row", "c")
        )
      )
    )
    m.queryAllByTestId("row").map(_.textContent) shouldBe Seq("a", "b", "c")

  test("getByText accepts a regex matcher"):
    val m = mount(RxElement(div(span("Order #1234 shipped"))))
    m.getByText("Order #\\d+ shipped".r).textContent shouldBe "Order #1234 shipped"

  test("queryAll wraps querySelectorAll as a Seq"):
    val m = mount(RxElement(ul(li("one"), li("two"))))
    m.queryAll("li").map(_.textContent) shouldBe Seq("one", "two")

  test("get throws ElementNotFoundException for an absent selector"):
    val m = mount(RxElement(div("x")))
    intercept[ElementNotFoundException] {
      m.query.get(".missing")
    }

  test("getByText trims surrounding whitespace before matching"):
    val m = mount(RxElement(div(span("   trimmed   "))))
    m.getByText("trimmed").textContent.trim shouldBe "trimmed"

end QueryTest
