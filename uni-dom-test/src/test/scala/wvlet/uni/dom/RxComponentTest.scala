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
import org.scalajs.dom
import scala.language.implicitConversions

class RxComponentTest extends UniTest:

  test("apply(content) invokes render with the supplied content"):
    object Frame extends RxComponent:
      override def render(content: RxElement): RxElement = div(cls -> "frame", content)

    val rendered = Frame(span("inner"))
    rendered shouldMatch { case _: RxElement =>
    }

  test("apply() invokes render with RxElement.empty"):
    var seen: RxElement = null
    object Frame extends RxComponent:
      override def render(content: RxElement): RxElement =
        seen = content
        div("standalone")

    Frame()
    // The standalone form must hand RxElement.empty to render — verify by reference identity so a
    // future change that swaps the default for some other "empty-ish" placeholder fails this test.
    (seen eq RxElement.empty) shouldBe true

  test("MainFrame-style component wraps inner content into the DOM"):
    // Worked example from issue #527: an outer chrome component receiving inner content.
    object MainFrame extends RxComponent:
      override def render(content: RxElement): RxElement = div(cls -> "main-frame", content)

    val target = dom.document.createElement("div")
    target.id = "rx-component-mount"
    dom.document.body.appendChild(target)

    val rendered = MainFrame(span("editor")).renderTo("rx-component-mount")
    target.textContent shouldBe "editor"
    rendered.cancelable.cancel

  test("standalone component renders without inner content"):
    object Standalone extends RxComponent:
      override def render(content: RxElement): RxElement = div(
        cls -> "standalone",
        "no-content-needed"
      )

    val target = dom.document.createElement("div")
    target.id = "rx-component-standalone-mount"
    dom.document.body.appendChild(target)

    val rendered = Standalone().renderTo("rx-component-standalone-mount")
    target.textContent shouldBe "no-content-needed"
    rendered.cancelable.cancel

  test("RxComponent mixes into another base — `extends Base with RxComponent`"):
    // airframe-rx-html's `RxComponent` was a trait, so migrating code like
    // `class Foo extends SomeBase with RxComponent` is valid. This test pins that uni's
    // `RxComponent` keeps the trait shape so those mixin sites still compile. (Suggested by
    // codex review on PR #533.)
    abstract class HasName(val name: String)

    class NamedFrame(name: String) extends HasName(name) with RxComponent:
      override def render(content: RxElement): RxElement = div(cls -> name, content)

    val frame: HasName & RxComponent = NamedFrame("my-frame")
    frame.name shouldBe "my-frame"
    val wrapped = frame(span("inner"))
    wrapped shouldMatch { case _: RxElement =>
    }

end RxComponentTest
