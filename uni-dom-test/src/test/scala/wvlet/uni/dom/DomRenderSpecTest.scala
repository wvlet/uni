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
import wvlet.uni.dom.testing.{mount, settle, DomTestSupport}
import wvlet.uni.rx.{Rx, RxVar}

import scala.collection.mutable
import scala.language.implicitConversions

/**
  * Replays the cases generated from the Lean model in spec/lean/UniSpec/DomRender.lean (see
  * spec/lean/README.md): builds each view with uni-dom, applies its updates, and checks the leaves'
  * text and which of them are the same DOM node as before.
  */
class DomRenderSpecTest extends DomTestSupport:

  // An RxElement that renders its body as is, like a component returning a group or an Rx
  class Comp(body: DomNode) extends RxElement:
    override def render: RxElement = Embedded(body)

  enum V:
    case Text(s: String)
    case Val
    case Group(vs: List[V])
    case Comp(body: V)
    case Sec(x: Int, body: V)
    case Keyed(x: Int, item: V)

  private class Parser(input: String):
    private var pos = 0

    private def expect(c: Char): Unit =
      if input.charAt(pos) != c then
        throw IllegalArgumentException(s"Expected ${c} at ${pos} in ${input}")
      pos += 1

    private def number(): Int =
      val start = pos
      while pos < input.length && input.charAt(pos).isDigit do
        pos += 1
      input.substring(start, pos).toInt

    private def body(): V =
      expect('(')
      val v = view()
      expect(')')
      v

    def view(): V =
      val c = input.charAt(pos)
      pos += 1
      c match
        case 'v' =>
          V.Val
        case 'g' =>
          expect('(')
          val b = List.newBuilder[V]
          b += view()
          while input.charAt(pos) == ',' do
            pos += 1
            b += view()
          expect(')')
          V.Group(b.result())
        case 'c' =>
          V.Comp(body())
        case 's' =>
          val x = number()
          V.Sec(x, body())
        case 'k' =>
          val x = number()
          V.Keyed(x, body())
        case other =>
          V.Text(other.toString)

    def parse(): V =
      val v = view()
      if pos != input.length then
        throw IllegalArgumentException(s"Unexpected input at ${pos} in ${input}")
      v

  end Parser

  private class Vars:
    val scalars = mutable.Map.empty[Int, RxVar[Int]]
    val lists   = mutable.Map.empty[Int, RxVar[Seq[(Int, Int)]]]

    def scalar(x: Int): RxVar[Int]           = scalars.getOrElseUpdate(x, Rx.variable(0))
    def list(x: Int): RxVar[Seq[(Int, Int)]] = lists.getOrElseUpdate(x, Rx.variable(Seq.empty))

    def build(v: V, cur: Int): DomNode =
      v match
        case V.Text(s) =>
          span(s)
        case V.Val =>
          span(cur.toString)
        case V.Group(vs) =>
          DomNode.group(vs.map(build(_, cur))*)
        case V.Comp(b) =>
          Comp(build(b, cur))
        case V.Sec(x, b) =>
          Embedded(scalar(x).map(i => build(b, i)))
        case V.Keyed(x, it) =>
          list(x).each(_._1)(p => build(it, p._2))

    def apply(step: String): Unit =
      val (target, value) = step.splitAt(step.indexOf('='))
      val x               = target.drop(1).toInt
      target.head match
        case 's' =>
          scalar(x) := value.drop(1).toInt
        case _ =>
          list(x) :=
            value
              .drop(1)
              .split(",")
              .filter(_.nonEmpty)
              .toSeq
              .map { kv =>
                val Array(k, v) = kv.split(":")
                (k.toInt, v.toInt)
              }

  end Vars

  private def leaves(root: dom.Element): Seq[dom.Element] =
    val all = root.querySelectorAll("span")
    (0 until all.length).map(all(_))

  test("the renderer agrees with the Lean model on every generated case") {
    var checked = 0
    DomRenderSpecCases
      .cases
      .foreach { line =>
        val Array(viewSrc, stepsSrc, statesSrc) = line.split(" \\| ")
        val vars                                = Vars()
        val (node, c)                           = DomRenderer.createNode(
          div(vars.build(Parser(viewSrc).parse(), 0), span("tail"))
        )
        val root  = node.asInstanceOf[dom.Element]
        val steps =
          if stepsSrc == "-" then
            Seq.empty
          else
            stepsSrc.split("; ").toSeq
        val states = statesSrc.split(" / ").toSeq
        // A model node id -> the DOM node it was rendered as
        val seen                                      = mutable.Map.empty[Int, dom.Element]
        def check(state: String, label: String): Unit =
          val expected = state
            .split(" ")
            .toSeq
            .map { leaf =>
              val (id, text) = leaf.splitAt(leaf.indexOf(':'))
              (id.toInt, text.drop(1))
            }
          val actual = leaves(root)
          val clue   = s"${viewSrc} after ${label}"
          (clue, actual.map(_.textContent)) shouldBe (clue, expected.map(_._2))
          expected
            .zip(actual)
            .foreach { case ((id, _), elem) =>
              seen.get(id) match
                case Some(prev) =>
                  // The model kept this node, so the renderer must not have rebuilt it
                  (clue, id, prev eq elem) shouldBe (clue, id, true)
                case None =>
                  // A new node in the model must be a new DOM node too
                  (clue, id, seen.values.exists(_ eq elem)) shouldBe (clue, id, false)
                  seen(id) = elem
            }
        end check

        check(states.head, "the initial render")
        steps
          .zip(states.tail)
          .foreach { (step, state) =>
            vars(step)
            check(state, step)
          }
        c.cancel
        checked += 1
      }
    checked shouldBe DomRenderSpecCases.cases.size
  }

  test("RxElement(rx) renders the Rx in place") {
    val v      = Rx.variable(0)
    val (n, c) = DomRenderer.createNode(div(RxElement(v.map(i => span(s"x${i}"))), span("tail")))
    n.textContent shouldBe "x0tail"
    v := 1
    n.textContent shouldBe "x1tail"
    c.cancel
  }

  test("RxElement(rx) renders at the top level") {
    val v      = Rx.variable(0)
    val (n, c) = DomRenderer.createNode(RxElement(v.map(i => span(s"x${i}"))))
    n.textContent shouldBe "x0"
    v := 1
    n.textContent shouldBe "x1"
    c.cancel
  }

  test("onMount fires for a component rendered inside a section") {
    var mounted = 0
    class Widget(i: Int) extends RxElement:
      override def render: RxElement        = span(s"w${i}")
      override def onMount(node: Any): Unit = mounted += 1

    val v = Rx.variable(0)
    mount(div(v.map(i => Widget(i)), span("tail")))
    settle()
      .flatMap { _ =>
        mounted shouldBe 1
        v := 1
        settle()
      }
      .map { _ =>
        mounted shouldBe 2
      }
  }

end DomRenderSpecTest
