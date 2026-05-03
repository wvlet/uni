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
import wvlet.uni.rx.{Cancelable, Rx}
import wvlet.uni.dom.all.*
import wvlet.uni.dom.all.given
import org.scalajs.dom
import scala.language.implicitConversions

class RxElementTest extends UniTest:

  test("create custom RxElement"):
    var beforeRenderCalled  = false
    var onMountCalled       = false
    var beforeUnmountCalled = false

    class TestElement extends RxElement:
      override def render: RxElement        = div("test")
      override def beforeRender: Unit       = beforeRenderCalled = true
      override def onMount(node: Any): Unit = onMountCalled = true
      override def beforeUnmount: Unit      = beforeUnmountCalled = true

    val elem = TestElement()
    elem shouldMatch { case _: RxElement =>
    }

  test("RxElement.apply with element"):
    val wrapped = RxElement(div("test"))
    wrapped shouldMatch { case _: RxElement =>
    }
    wrapped.render shouldMatch { case _: DomElement =>
    }

  test("LazyRxElement defers evaluation"):
    var evaluated = false
    val lazyElem  = LazyRxElement { () =>
      evaluated = true
      div("lazy")
    }
    evaluated shouldBe false
    lazyElem.value
    evaluated shouldBe true

  test("Embedded wraps values"):
    val embedded = Embedded("test")
    embedded.v shouldBe "test"

  test("traverseModifiers processes all modifiers"):
    val elem  = div(cls -> "a", id -> "b", span("text"))
    var count = 0
    elem.traverseModifiers { node =>
      count += 1
      Cancelable.empty
    }
    count shouldBe 3

  test("nested RxElement"):
    class Inner extends RxElement:
      override def render: RxElement = span("inner")

    class Outer extends RxElement:
      override def render: RxElement = div(Inner())

    val outer = Outer()
    outer shouldMatch { case _: RxElement =>
    }

  test("RxElement with reactive content"):
    val counter = Rx.variable(0)

    class Counter extends RxElement:
      override def render: RxElement = div(cls -> "counter", counter.map(c => span(s"Count: ${c}")))

    val elem = Counter()
    elem shouldMatch { case _: RxElement =>
    }

  test("primitive literals convert to RxElement"):
    // Mirrors the airframe-rx-html pattern of helpers taking `RxElement` so they
    // accept primitive literals at the call site. The explicit `RxElement` type
    // ascription is what forces the compiler to apply the new conversion (without
    // it, the existing `String -> DomNode` conversion would satisfy the expression).
    val s: RxElement = "Editor"
    val i: RxElement = 7
    val l: RxElement = 7L
    val f: RxElement = 1.5f
    val d: RxElement = 1.5
    val b: RxElement = true
    val c: RxElement = 'A'

    s shouldMatch { case Embedded("Editor") =>
    }
    i shouldMatch { case Embedded(7) =>
    }
    l shouldMatch { case Embedded(7L) =>
    }
    f shouldMatch { case Embedded(1.5f) =>
    }
    d shouldMatch { case Embedded(1.5) =>
    }
    b shouldMatch { case Embedded(true) =>
    }
    c shouldMatch { case Embedded('A') =>
    }

  test("reactive and collection values convert to RxElement"):
    val rxVar         = Rx.variable(1)
    val rx: RxElement = rxVar.map(_ + 1)
    rx shouldMatch { case Embedded(_) =>
    }

    val opt: RxElement = Option("hello")
    opt shouldMatch { case Embedded(Some("hello")) =>
    }

    val seq: RxElement = Seq("a", "b")
    seq shouldMatch { case Embedded(Seq("a", "b")) =>
    }

    val lst: RxElement = List(1, 2, 3)
    lst shouldMatch { case Embedded(List(1, 2, 3)) =>
    }

  // The new conversions widen what compiles as an `RxElement`. Verify the existing
  // `renderTo` extension still mounts these widened roots correctly — i.e. that the
  // type-system widening matches the runtime behavior for every conversion target.
  // (Regression guard suggested by codex review on PR #529.)
  private def freshTarget(id: String): dom.Element =
    Option(dom.document.getElementById(id)).foreach(prev => prev.parentNode.removeChild(prev))
    val n = dom.document.createElement("div")
    n.setAttribute("id", id)
    dom.document.body.appendChild(n)
    n

  test("string-typed-as-RxElement mounts as text via renderTo"):
    val target       = freshTarget("rx-elem-mount-string")
    val s: RxElement = "hello"
    val rendered     = s.renderTo("rx-elem-mount-string")
    target.textContent shouldBe "hello"
    rendered.cancelable.cancel

  test("int-typed-as-RxElement mounts as text via renderTo"):
    val target       = freshTarget("rx-elem-mount-int")
    val i: RxElement = 42
    val rendered     = i.renderTo("rx-elem-mount-int")
    target.textContent shouldBe "42"
    rendered.cancelable.cancel

  test("Rx-typed-as-RxElement mounts and updates via renderTo"):
    val target       = freshTarget("rx-elem-mount-rx")
    val counter      = Rx.variable(0)
    val r: RxElement = counter.map(n => s"count=${n}")
    val rendered     = r.renderTo("rx-elem-mount-rx")
    target.textContent shouldBe "count=0"
    counter := 7
    target.textContent shouldBe "count=7"
    rendered.cancelable.cancel

  test("Seq-typed-as-RxElement mounts each element via renderTo"):
    val target         = freshTarget("rx-elem-mount-seq")
    val seq: RxElement = Seq("a", "b", "c")
    val rendered       = seq.renderTo("rx-elem-mount-seq")
    target.textContent shouldBe "abc"
    rendered.cancelable.cancel

  test("Option-typed-as-RxElement mounts the inner value via renderTo"):
    val target         = freshTarget("rx-elem-mount-opt")
    val opt: RxElement = Option("present")
    val rendered       = opt.renderTo("rx-elem-mount-opt")
    target.textContent shouldBe "present"
    rendered.cancelable.cancel

end RxElementTest
