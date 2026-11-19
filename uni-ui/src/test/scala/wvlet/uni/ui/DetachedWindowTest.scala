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
package wvlet.uni.ui

import org.scalajs.dom
import wvlet.uni.dom.all.*
import wvlet.uni.dom.all.given
import wvlet.uni.dom.testing.*
import wvlet.uni.rx.{Rx, RxVar}
import wvlet.uni.ui.base.InputHistory

import scala.language.implicitConversions
import scala.scalajs.js

/**
  * Components in a Dock panel detached into a window of its own. That window is a separate JS
  * realm, so the events here are built from its own constructors, as a user's keys and pointer
  * would be.
  */
class DetachedWindowTest extends DomTestSupport:

  private val stylesheet = dom.document.createElement("style")
  stylesheet.textContent = UniUiCss.text

  override protected def beforeAll: Unit = dom.document.head.appendChild(stylesheet)
  override protected def afterAll: Unit  = dom.document.head.removeChild(stylesheet)

  // Detaches a panel holding `content` and returns its window
  private def detached(content: RxElement)(body: dom.Window => Rx[Unit]): Rx[Unit] =
    val layout = Rx.variable(DockLayout.of("a", "p"))
    val d      = Dock(
      layout,
      label = "Dock",
      panels = Seq(DockPanel("a", title = "A", content = div("a")), DockPanel("p", "P", content))
    ).withDetach(true)
    mount(div(styleAttr -> "width: 600px; height: 400px; display: flex", d))
    d.detach("p") shouldBe true
    val w = d.windowOf("p").get
    body(w).map(_ => d.reattach("p"))

  private def event(w: dom.Window, kind: String, tpe: String, init: js.Object): dom.Event =
    val ctor = w.asInstanceOf[js.Dynamic].selectDynamic(kind)
    js.Dynamic.newInstance(ctor)(tpe, init).asInstanceOf[dom.Event]

  private def keyDown(
      w: dom.Window,
      target: dom.EventTarget,
      key: String,
      altKey: Boolean = false
  ): Boolean =
    val e = event(
      w,
      "KeyboardEvent",
      "keydown",
      js.Dynamic.literal(key = key, altKey = altKey, bubbles = true, cancelable = true)
    )
    target.dispatchEvent(e)

  private def find(w: dom.Window, selector: String): dom.HTMLElement = w
    .document
    .querySelector(selector)
    .asInstanceOf[dom.HTMLElement]

  test("events from a detached window come from another realm") {
    detached(div("x")) { w =>
      val e = event(w, "KeyboardEvent", "keydown", js.Dynamic.literal(key = "a"))
      e.isInstanceOf[dom.KeyboardEvent] shouldBe false
      ChangeSource.of(e) shouldBe ChangeSource.Keyboard
      settle()
    }
  }

  test("input history recalls entries in a detached window") {
    val draft   = Rx.variable("")
    val history = InputHistory(initial = Seq("first", "second"))
    detached(textarea(cls -> "h", value.bind(draft), history.keys(draft))) { w =>
      val field = find(w, "textarea.h")
      keyDown(w, field, "ArrowUp")
      draft.get shouldBe "second"
      keyDown(w, field, "ArrowUp")
      draft.get shouldBe "first"
      settle()
    }
  }

  test("Alt+Arrow keys reorder a list in a detached window and announce there") {
    val items: RxVar[Seq[Int]] = Rx.variable(1 to 5)
    detached(
      div(
        styleAttr -> "height: 200px; overflow: hidden",
        VirtualList(items, rowHeight = 20, key = identity, render = i => span(s"Item ${i}"))
          .withReorder(items)(styleAttr += "height: 200px")
      )
    ) { w =>
      val list = find(w, ".uni-virtual-list")
      settle().map { _ =>
        list.focus()
        keyDown(w, list, "End")
        keyDown(w, list, "Home", altKey = true)
        items.get.take(3) shouldBe Seq(5, 1, 2)
        keyDown(w, list, "ArrowDown", altKey = true)
        items.get.take(3) shouldBe Seq(1, 5, 2)
        w.document.querySelector("[data-slot=announcer]").textContent shouldContain "Moved"
      }
    }
  }

  test("a menu opens, moves focus, and closes inside a detached window") {
    val open   = Rx.variable(false)
    var chosen = List.empty[String]
    detached(
      Menu(
        open,
        trigger = Button("Edit"),
        entries = Seq(
          MenuItem("Copy").onSelect(() => chosen ::= "copy"),
          MenuItem("Paste").onSelect(() => chosen ::= "paste")
        )
      )
    ) { w =>
      val trigger = find(w, "button")
      trigger.focus()
      keyDown(w, trigger, "ArrowDown")
      open.get shouldBe true
      settle().flatMap { _ =>
        // In the window the trigger is in, not the page
        Option(dom.document.querySelector(".uni-menu")) shouldBe None
        Option(find(w, ".uni-menu")).isDefined shouldBe true
        def active = w.document.activeElement
        active.textContent shouldContain "Copy"
        keyDown(w, active, "ArrowDown")
        active.textContent shouldContain "Paste"
        // Escape closes it and gives focus back to the trigger
        keyDown(w, active, "Escape")
        open.get shouldBe false
        (active eq trigger) shouldBe true
        keyDown(w, trigger, "ArrowDown")
        settle().map { _ =>
          keyDown(w, w.document.activeElement, "Enter")
          chosen shouldBe List("copy")
        }
      }
    }
  }

  test("a tooltip shows and hides inside a detached window") {
    detached(Tooltip("Theme", trigger = Button("T")).withDelay(0)) { w =>
      val trigger = find(w, "button")
      trigger.dispatchEvent(event(w, "PointerEvent", "pointerenter", js.Dynamic.literal()))
      settle().map { _ =>
        Option(dom.document.querySelector(".uni-tooltip")) shouldBe None
        val tip = find(w, ".uni-tooltip")
        tip.textContent shouldBe "Theme"
        trigger.getAttribute("aria-describedby") shouldBe tip.id
        keyDown(w, trigger, "Escape") shouldBe false
        // Closing: the window has uni-ui.css, so the tooltip plays its exit before it goes
        trigger.hasAttribute("aria-describedby") shouldBe false
        tip.getAttribute("data-state") shouldBe "closed"
      }
    }
  }

end DetachedWindowTest
