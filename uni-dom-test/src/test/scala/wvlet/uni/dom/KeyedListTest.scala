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
import wvlet.uni.rx.{OnCompletion, Rx, RxRunner}
import wvlet.uni.test.UniTest

import scala.language.implicitConversions

class KeyedListTest extends UniTest:

  case class Item(id: Int, text: String)

  private def render(node: DomNode): (dom.Element, wvlet.uni.rx.Cancelable) =
    val (n, c) = DomRenderer.createNode(div(node))
    (n.asInstanceOf[dom.Element], c)

  private def lis(root: dom.Element): Seq[dom.Element] =
    val all = root.querySelectorAll("li")
    (0 until all.length).map(all(_))

  test("an append adds one node and keeps the existing ones") {
    val items     = Rx.variable(Seq(Item(1, "a"), Item(2, "b")))
    var rendered  = 0
    val (root, c) = render(
      ul(
        each(items)(_.id) { it =>
          rendered += 1
          li(it.text)
        }
      )
    )
    val before = lis(root)
    before.map(_.textContent) shouldBe Seq("a", "b")
    items.update(_ :+ Item(3, "c"))
    val after = lis(root)
    after.map(_.textContent) shouldBe Seq("a", "b", "c")
    rendered shouldBe 3
    // Same DOM nodes, not copies
    (after(0) eq before(0)) shouldBe true
    (after(1) eq before(1)) shouldBe true
    c.cancel
  }

  test("removing and reordering moves nodes instead of rebuilding them") {
    val items            = Rx.variable(Seq(Item(1, "a"), Item(2, "b"), Item(3, "c")))
    val (root, c)        = render(ul(items.each(_.id)(it => li(it.text))))
    val Seq(a, _, cNode) = lis(root): @unchecked
    items := Seq(Item(3, "c"), Item(1, "a"))
    val after = lis(root)
    after.map(_.textContent) shouldBe Seq("c", "a")
    (after(0) eq cNode) shouldBe true
    (after(1) eq a) shouldBe true
    c.cancel
  }

  test("an item whose value changed is rendered again") {
    val items     = Rx.variable(Seq(Item(1, "a"), Item(2, "b")))
    val (root, c) = render(ul(each(items)(_.id)(it => li(it.text))))
    val first     = lis(root).head
    items := Seq(Item(1, "a"), Item(2, "B"))
    lis(root).map(_.textContent) shouldBe Seq("a", "B")
    (lis(root).head eq first) shouldBe true
    c.cancel
  }

  test("a repeated key keeps only its first item") {
    val items     = Rx.variable(Seq(Item(1, "a"), Item(1, "dup"), Item(2, "b")))
    val (root, c) = render(ul(each(items)(_.id)(it => li(it.text))))
    lis(root).map(_.textContent) shouldBe Seq("a", "b")
    c.cancel
  }

  test("a keyed list stays between its siblings") {
    val items     = Rx.variable(Seq(Item(1, "a")))
    val (root, c) = render(ul(li("head"), each(items)(_.id)(it => li(it.text)), li("tail")))
    items := Seq(Item(2, "b"), Item(1, "a"))
    lis(root).map(_.textContent) shouldBe Seq("head", "b", "a", "tail")
    items := Seq.empty
    lis(root).map(_.textContent) shouldBe Seq("head", "tail")
    c.cancel
  }

  test("an item's own Rx keeps updating, and stops once the item is removed") {
    val items     = Rx.variable(Seq(Item(1, "a")))
    val count     = Rx.variable(0)
    var runs      = 0
    val (root, c) = render(
      ul(
        each(items)(_.id) { it =>
          li(
            count.map { n =>
              runs += 1
              s"${it.text}${n}"
            }
          )
        }
      )
    )
    lis(root).map(_.textContent) shouldBe Seq("a0")
    count := 1
    lis(root).map(_.textContent) shouldBe Seq("a1")
    items := Seq.empty
    val before = runs
    count := 2
    runs shouldBe before
    c.cancel
  }

  test("an Rx nested directly in an Rx re-renders in place, not at the parent's end") {
    val outer     = Rx.variable(0)
    val inner     = Rx.variable("x")
    val (root, c) = render(span(outer.map(n => inner.map(s => s"${s}${n}")), span("after")))
    root.textContent shouldBe "x0after"
    outer := 1
    root.textContent shouldBe "x1after"
    inner := "y"
    root.textContent shouldBe "y1after"
    c.cancel
  }

  test("eachRx updates an item in place when its value changes under the same key"):
    val items     = Rx.variable(Seq(Item(1, "a"), Item(2, "b")))
    var rendered  = 0
    var emitted   = Seq.empty[String]
    val (root, c) = render(
      ul(
        items.eachRx(_.id) { (first, current) =>
          rendered += 1
          li(
            cls -> first.text,
            current.map { it =>
              emitted :+= it.text
              it.text
            }
          )
        }
      )
    )
    val before = lis(root)
    items := Seq(Item(1, "a"), Item(2, "B"))
    val after = lis(root)
    after.map(_.textContent) shouldBe Seq("a", "B")
    rendered shouldBe 2
    (after(0) eq before(0)) shouldBe true
    (after(1) eq before(1)) shouldBe true
    // The first value stays what the item was rendered with
    after(1).getAttribute("class") shouldBe "b"
    // An equal value under the same key doesn't emit again
    items := Seq(Item(1, "a"), Item(2, "B"))
    emitted shouldBe Seq("a", "b", "B")
    c.cancel

  test("eachRx moves items on reorder and renders new keys"):
    val items     = Rx.variable(Seq(Item(1, "a"), Item(2, "b")))
    val (root, c) = render(ul(items.eachRx(_.id)((_, current) => li(current.map(_.text)))))
    val Seq(a, b) = lis(root): @unchecked
    items := Seq(Item(3, "c"), Item(2, "b2"), Item(1, "a"))
    val after = lis(root)
    after.map(_.textContent) shouldBe Seq("c", "b2", "a")
    (after(1) eq b) shouldBe true
    (after(2) eq a) shouldBe true
    c.cancel

  test("eachRx completes an item's Rx once its key is removed, and a returning key starts fresh"):
    val items     = Rx.variable(Seq(Item(1, "a")))
    var rendered  = 0
    var completed = 0
    val (root, c) = render(
      ul(
        items.eachRx(_.id) { (_, current) =>
          rendered += 1
          RxRunner.runContinuously(current) {
            case OnCompletion =>
              completed += 1
            case _ =>
          }
          li(current.map(_.text))
        }
      )
    )
    items := Seq.empty
    completed shouldBe 1
    lis(root) shouldBe Seq.empty
    items := Seq(Item(1, "again"))
    rendered shouldBe 2
    lis(root).map(_.textContent) shouldBe Seq("again")
    c.cancel
    completed shouldBe 2

  test("split + each updates a row in place through its own Rx") {
    val items     = Rx.variable(Seq(Item(1, "a"), Item(2, "b")))
    var rendered  = 0
    val (root, c) = render(
      ul(
        items.split(_.id).each(_._1) { (_, current) =>
          rendered += 1
          li(current.map(_.text))
        }
      )
    )
    val first = lis(root).head
    items := Seq(Item(1, "a2"), Item(2, "b"))
    lis(root).map(_.textContent) shouldBe Seq("a2", "b")
    (lis(root).head eq first) shouldBe true
    rendered shouldBe 2
    items := Seq(Item(2, "b"))
    lis(root).map(_.textContent) shouldBe Seq("b")
    c.cancel
  }

end KeyedListTest
