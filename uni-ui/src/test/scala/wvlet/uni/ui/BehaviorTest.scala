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
import wvlet.uni.rx.Rx
import wvlet.uni.ui.base.*

import scala.collection.mutable.ArrayBuffer
import scala.language.implicitConversions

class BehaviorTest extends DomTestSupport:

  private def find(selector: String): dom.HTMLElement = dom
    .document
    .querySelector(selector)
    .asInstanceOf[dom.HTMLElement]

  test("input history recalls entries with ArrowUp and ArrowDown") {
    val draft   = Rx.variable("")
    val history = InputHistory(initial = Seq("first", "second"))
    val seen    = ArrayBuffer.empty[ChangeDetails[Any]]
    mount(textarea(cls -> "h", value.bind(draft), history.keys(draft, d => seen += d)))
    val field = find("textarea.h").asInstanceOf[dom.HTMLTextAreaElement]
    fireEvent.input(field, "typing")
    fireEvent.keyDown(field, "ArrowUp")
    draft.get shouldBe "second"
    field.value shouldBe "second"
    field.selectionStart shouldBe 6
    seen.last.reason shouldBe ChangeReason.NavigationKey
    seen.last.source shouldBe ChangeSource.Keyboard
    fireEvent.keyDown(field, "ArrowUp")
    draft.get shouldBe "first"
    fireEvent.keyDown(field, "ArrowDown")
    fireEvent.keyDown(field, "ArrowDown")
    draft.get shouldBe "typing"
  }

  test("input history keeps an edit to a recalled entry while moving between entries") {
    val draft   = Rx.variable("")
    val history = InputHistory(initial = Seq("first", "second"))
    mount(textarea(cls -> "e", value.bind(draft), history.keys(draft)))
    val field = find("textarea.e").asInstanceOf[dom.HTMLTextAreaElement]
    fireEvent.keyDown(field, "ArrowUp")
    fireEvent.input(field, "second, edited")
    fireEvent.keyDown(field, "ArrowUp")
    draft.get shouldBe "first"
    fireEvent.keyDown(field, "ArrowDown")
    draft.get shouldBe "second, edited"
    history.entries shouldBe Seq("first", "second")
  }

  test("input history leaves the arrows to the caret inside multi-line text") {
    val draft   = Rx.variable("")
    val history = InputHistory(initial = Seq("old"))
    mount(textarea(cls -> "m", value.bind(draft), history.keys(draft)))
    val field = find("textarea.m").asInstanceOf[dom.HTMLTextAreaElement]
    fireEvent.input(field, "line 1\nline 2")
    field.setSelectionRange(10, 10)
    fireEvent.keyDown(field, "ArrowUp")
    draft.get shouldBe "line 1\nline 2"
    field.setSelectionRange(2, 2)
    fireEvent.keyDown(field, "ArrowUp")
    draft.get shouldBe "old"
  }

  test("a refused recall keeps the text and the place in the history") {
    val draft   = Rx.variable("")
    val history = InputHistory(initial = Seq("a", "b"))
    var refuse  = true
    mount(
      input(
        cls -> "r",
        value.bind(draft),
        history.keys(
          draft,
          d =>
            if refuse then
              d.cancel()
        )
      )
    )
    val field = find("input.r")
    fireEvent.keyDown(field, "ArrowUp")
    draft.get shouldBe ""
    history.recalling shouldBe false
    refuse = false
    fireEvent.keyDown(field, "ArrowUp")
    draft.get shouldBe "b"
  }

  test("stick-to-bottom follows new content until the reader scrolls up") {
    val stick = StickToBottom()
    val lines = Rx.variable(Seq.fill(30)("line"))
    mount(
      div(
        cls       -> "log",
        styleAttr -> "height: 100px; overflow-y: auto",
        stick.binding,
        div(lines.map(ls => ls.map(l => div(styleAttr -> "height: 20px", l))))
      )
    )
    val log   = find(".log")
    def atEnd = StickToBottom.isNear(log.scrollTop, log.scrollHeight, log.clientHeight, 1)
    settle().flatMap { _ =>
      atEnd shouldBe true
      lines := lines.get ++ Seq.fill(10)("more")
      settle().flatMap { _ =>
        atEnd shouldBe true
        log.scrollTop = 0
        fireEvent.dispatch(log, dom.Event("scroll"))
        stick.isAtBottom shouldBe false
        lines := lines.get ++ Seq.fill(10)("more")
        settle().flatMap { _ =>
          log.scrollTop shouldBe 0
          stick.scrollToBottom()
          settle().map { _ =>
            atEnd shouldBe true
            stick.isAtBottom shouldBe true
          }
        }
      }
    }
  }

  test("focus recovery moves focus to the item that took the removed one's place") {
    val items = Rx.variable(Seq("a", "b", "c"))
    mount(
      ul(
        cls      -> "fr",
        tabindex -> "-1",
        FocusRecovery.within("li"),
        items.map(_.map(i => li(tabindex -> "0", data("item") -> i, i)))
      )
    )
    find("li[data-item=b]").focus()
    items := Seq("a", "c")
    settle().flatMap { _ =>
      dom.document.activeElement.getAttribute("data-item") shouldBe "c"
      items := Seq("a")
      settle().flatMap { _ =>
        dom.document.activeElement.getAttribute("data-item") shouldBe "a"
        items := Seq.empty
        settle().map { _ =>
          (dom.document.activeElement eq find("ul.fr")) shouldBe true
        }
      }
    }
  }

  test("focus recovery finds the neighbor in a keyed list after items were added above") {
    val items = Rx.variable(Seq("a", "b", "c"))
    mount(
      ul(
        tabindex -> "-1",
        FocusRecovery.within("li"),
        items.eachRx(identity)((i, _) => li(tabindex -> "0", data("item") -> i, i))
      )
    )
    find("li[data-item=b]").focus()
    items := Seq("x", "y", "a", "b", "c")
    settle().flatMap { _ =>
      items := Seq("x", "y", "a", "c")
      settle().map { _ =>
        dom.document.activeElement.getAttribute("data-item") shouldBe "c"
      }
    }
  }

  test("focus recovery leaves focus alone once the reader left the list") {
    val items = Rx.variable(Seq("a", "b"))
    mount(
      ul(
        FocusRecovery.within("li"),
        items.eachRx(identity)((i, _) => li(tabindex -> "0", data("item") -> i, i))
      )
    )
    find("li[data-item=a]").focus()
    find("li[data-item=a]").blur()
    settle().flatMap { _ =>
      items := Seq("b")
      settle().map { _ =>
        (dom.document.activeElement eq dom.document.body) shouldBe true
      }
    }
  }

  test("announce adds the message to a hidden live region") {
    Announce.prepare()
    // Lines other tests announced may still be in the region
    Announce.clear()
    Announce.polite("Response complete")
    Announce.polite("Response complete")
    val region = find("[data-slot=announcer][aria-live=polite]")
    region.getAttribute("role") shouldBe "status"
    region.children.length shouldBe 2
    region.textContent shouldContain "Response complete"
    Announce.assertive("Connection lost")
    find("[data-slot=announcer][aria-live=assertive]").getAttribute("role") shouldBe "alert"
    Announce.clear()
    region.textContent shouldBe ""
  }

  private def reorderList(reorder: Reorder[String]): Unit = mount(
    ul(
      cls -> "rl",
      reorder
        .order
        .each(identity)(t => li(cls -> s"item-${t}", tabindex -> "0", reorder.item(t), t))
    )
  )

  private def centerY(el: dom.Element): (Double, Double) =
    val r = el.getBoundingClientRect()
    (r.left + r.width / 2, r.top + r.height / 2)

  test("reorder moves the focused item with Alt+Arrow keys and keeps focus on it") {
    val order   = Rx.variable(Seq("a", "b", "c"))
    val seen    = ArrayBuffer.empty[ChangeDetails[Any]]
    val reorder = Reorder(order).onChange(d => seen += d)
    reorderList(reorder)
    find("li.item-a").focus()
    fireEvent.keyDown(find("li.item-a"), "ArrowDown", altKey = true)
    order.get shouldBe Seq("b", "a", "c")
    seen.last.reason shouldBe ChangeReason.NavigationKey
    seen.last.source shouldBe ChangeSource.Keyboard
    (dom.document.activeElement eq find("li.item-a")) shouldBe true
    find("[data-slot=announcer][aria-live=polite]").textContent shouldContain
      "Moved a to position 2 of 3"
    fireEvent.keyDown(find("li.item-a"), "End", altKey = true)
    order.get shouldBe Seq("b", "c", "a")
    // Without Alt, the keys are left to the list
    fireEvent.keyDown(find("li.item-a"), "Home")
    order.get shouldBe Seq("b", "c", "a")
  }

  test("reorder moves a dragged item where it is dropped, and Escape cancels the drag") {
    val order   = Rx.variable(Seq("a", "b", "c"))
    val seen    = ArrayBuffer.empty[ChangeDetails[Any]]
    val reorder = Reorder(order).onChange(d => seen += d)
    reorderList(reorder)
    val (x, y) = centerY(find("li.item-a"))
    val c      = find("li.item-c").getBoundingClientRect()

    fireEvent.pointerDown(find("li.item-a"), clientX = x, clientY = y)
    fireEvent.pointerMove(dom.window, clientX = x, clientY = c.bottom - 2)
    find("li.item-a").getAttribute("data-dragging") shouldBe "true"
    find("li.item-c").getAttribute("data-drop") shouldBe "after"
    fireEvent.keyDown(dom.window, "Escape")
    find("li.item-c").hasAttribute("data-drop") shouldBe false
    fireEvent.pointerUp(dom.window, clientX = x, clientY = c.bottom - 2)
    order.get shouldBe Seq("a", "b", "c")

    fireEvent.pointerDown(find("li.item-a"), clientX = x, clientY = y)
    fireEvent.pointerMove(dom.window, clientX = x, clientY = c.bottom - 2)
    fireEvent.pointerUp(dom.window, clientX = x, clientY = c.bottom - 2)
    order.get shouldBe Seq("b", "c", "a")
    seen.last.reason shouldBe ChangeReason.Drag
    seen.last.source shouldBe ChangeSource.Pointer
    find("li.item-a").getAttribute("data-dragging") shouldBe "false"
  }

  test("reorder keeps the browser default off at the ends, and leaves keys in a field alone") {
    val order   = Rx.variable(Seq("a", "b"))
    val reorder = Reorder(order, orientation = Orientation.Horizontal)
    mount(
      div(
        order.each(identity)(t =>
          div(cls -> s"item-${t}", tabindex -> "0", reorder.item(t), input(cls -> s"field-${t}"))
        )
      )
    )
    fireEvent.keyDown(find(".item-a"), "ArrowLeft", altKey = true) shouldBe false
    order.get shouldBe Seq("a", "b")
    fireEvent.keyDown(find(".field-a"), "ArrowRight", altKey = true) shouldBe true
    order.get shouldBe Seq("a", "b")
    fireEvent.keyDown(find(".item-a"), "ArrowRight", altKey = true)
    order.get shouldBe Seq("b", "a")
  }

  test("reorder moves the selected items together with Alt+Arrow keys") {
    val order    = Rx.variable(Seq("a", "b", "c", "d", "e"))
    val selected = Rx.variable(Set("b", "d"))
    val seen     = ArrayBuffer.empty[ChangeDetails[Any]]
    val reorder  = Reorder(order).withSelection(selected).onChange(d => seen += d)
    reorderList(reorder)
    find("li.item-d").focus()
    // The scattered selection gathers where the pressed item is, then moves one step
    fireEvent.keyDown(find("li.item-d"), "ArrowDown", altKey = true)
    order.get shouldBe Seq("a", "c", "e", "b", "d")
    seen.size shouldBe 1
    (dom.document.activeElement eq find("li.item-d")) shouldBe true
    find("[data-slot=announcer][aria-live=polite]").textContent shouldContain
      "Moved 2 items to position 4 of 5"
    fireEvent.keyDown(find("li.item-d"), "Home", altKey = true)
    order.get shouldBe Seq("b", "d", "a", "c", "e")
    selected.get shouldBe Set("b", "d")
    // At the start, a scattered selection still gathers
    selected := Set("b", "c")
    fireEvent.keyDown(find("li.item-b"), "Home", altKey = true)
    order.get shouldBe Seq("b", "c", "d", "a", "e")
    selected := Set("b", "d")
    order    := Seq("b", "d", "a", "c", "e")
    // An item outside the selection moves alone
    fireEvent.keyDown(find("li.item-a"), "ArrowUp", altKey = true)
    order.get shouldBe Seq("b", "a", "d", "c", "e")
  }

  test("reorder drags the selected items together, and a cancel keeps the order") {
    val order    = Rx.variable(Seq("a", "b", "c", "d"))
    val selected = Rx.variable(Set("a", "b"))
    var cancel   = true
    val seen     = ArrayBuffer.empty[ChangeDetails[Any]]
    val reorder  = Reorder(order)
      .withSelection(selected)
      .onChange { d =>
        seen += d
        if cancel then
          d.cancel()
      }
    reorderList(reorder)
    val (x, y)       = centerY(find("li.item-a"))
    val d            = find("li.item-d").getBoundingClientRect()
    def drag(): Unit =
      fireEvent.pointerDown(find("li.item-a"), clientX = x, clientY = y)
      fireEvent.pointerMove(dom.window, clientX = x, clientY = d.bottom - 2)
      find("li.item-b").getAttribute("data-dragging") shouldBe "true"
      find("li.item-c").getAttribute("data-dragging") shouldBe "false"
      fireEvent.pointerUp(dom.window, clientX = x, clientY = d.bottom - 2)

    drag()
    order.get shouldBe Seq("a", "b", "c", "d")
    seen.size shouldBe 1
    cancel = false
    drag()
    order.get shouldBe Seq("c", "d", "a", "b")
    seen.size shouldBe 2
    seen.last.reason shouldBe ChangeReason.Drag
    find("[data-slot=announcer][aria-live=polite]").textContent shouldContain
      "Moved 2 items to position 3 of 4"
  }

  test("reorder scrolls its container while dragging near the edge") {
    val order   = Rx.variable[Seq[String]]((1 to 30).map(i => s"i${i}"))
    val reorder = Reorder(order)
    mount(
      ul(
        cls       -> "scroller",
        styleAttr -> "height: 200px; overflow-y: auto; margin: 0; padding: 0",
        order.each(identity)(t =>
          li(cls -> s"item-${t}", styleAttr -> "height: 30px", reorder.item(t), t)
        )
      )
    )
    val scroller = find("ul.scroller")
    val r        = scroller.getBoundingClientRect()
    val (x, y)   = centerY(find("li.item-i1"))
    fireEvent.pointerDown(find("li.item-i1"), clientX = x, clientY = y)
    fireEvent.pointerMove(dom.window, clientX = x, clientY = r.bottom - 1)
    val p = scala.concurrent.Promise[Unit]()
    dom.window.setTimeout(() => p.success(()), 200)
    Rx.future(p.future)(using scala.concurrent.ExecutionContext.parasitic)
      .map { _ =>
        (scroller.scrollTop > 0) shouldBe true
        fireEvent.pointerUp(dom.window, clientX = x, clientY = r.bottom - 1)
        // Dropped on the row now under the pointer, past the first screenful
        (order.get.indexOf("i1") > 6) shouldBe true
      }
  }

  test("reorder mirrors Alt+ArrowLeft/Right in a right-to-left row") {
    val order   = Rx.variable(Seq("a", "b", "c"))
    val reorder = Reorder(order, orientation = Orientation.Horizontal)
    mount(
      div(
        dir -> "rtl",
        order.each(identity)(t => div(cls -> s"item-${t}", tabindex -> "0", reorder.item(t), t))
      )
    )
    fireEvent.keyDown(find(".item-a"), "ArrowLeft", altKey = true)
    order.get shouldBe Seq("b", "a", "c")
    fireEvent.keyDown(find(".item-a"), "ArrowRight", altKey = true)
    order.get shouldBe Seq("a", "b", "c")
  }

  test("a canceled reorder keeps the order") {
    val order   = Rx.variable(Seq("a", "b"))
    val reorder = Reorder(order).onChange(_.cancel())
    reorderList(reorder)
    fireEvent.keyDown(find("li.item-b"), "ArrowUp", altKey = true)
    order.get shouldBe Seq("a", "b")
  }

  test("with a handle, only presses on the handle start a reorder drag") {
    val order   = Rx.variable(Seq("a", "b"))
    val reorder = Reorder(order)
    mount(
      ul(
        order.each(identity)(t =>
          li(
            cls -> s"item-${t}",

            reorder.item(t),
            span(cls -> s"grip-${t}", Reorder.handle, "::"),
            span(cls -> s"text-${t}", t)
          )
        )
      )
    )
    val (x, y) = centerY(find(".text-a"))
    val b      = find("li.item-b").getBoundingClientRect()
    fireEvent.pointerDown(find(".text-a"), clientX = x, clientY = y)
    fireEvent.pointerMove(dom.window, clientX = x, clientY = b.bottom - 2)
    fireEvent.pointerUp(dom.window, clientX = x, clientY = b.bottom - 2)
    order.get shouldBe Seq("a", "b")
    val (gx, gy) = centerY(find(".grip-a"))
    fireEvent.pointerDown(find(".grip-a"), clientX = gx, clientY = gy)
    fireEvent.pointerMove(dom.window, clientX = gx, clientY = b.bottom - 2)
    fireEvent.pointerUp(dom.window, clientX = gx, clientY = b.bottom - 2)
    order.get shouldBe Seq("b", "a")
  }

end BehaviorTest
