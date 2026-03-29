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

end RxElementTest
