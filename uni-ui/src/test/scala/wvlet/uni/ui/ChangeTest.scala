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
import wvlet.uni.dom.SizeRange
import wvlet.uni.dom.all.{div, span}
import wvlet.uni.dom.all.given
import wvlet.uni.dom.testing.*
import wvlet.uni.rx.Rx

import scala.collection.mutable.ArrayBuffer
import scala.language.implicitConversions

class ChangeTest extends DomTestSupport:

  private def find(selector: String): dom.HTMLElement = dom
    .document
    .querySelector(selector)
    .asInstanceOf[dom.HTMLElement]

  // Records every change, and refuses them while `refuse` is true
  private class Recorder:
    val seen                   = ArrayBuffer.empty[ChangeDetails[Any]]
    var refuse                 = false
    val handler: ChangeHandler =
      d =>
        seen += d
        if refuse then
          d.cancel()

    def last: ChangeDetails[Any] = seen.last

  test("a popover reports the press that opens it and the Escape that closes it") {
    val open = Rx.variable(false)
    val rec  = Recorder()
    val m    = mount(Popover(open)(Button("Filter"))("Details").onChange(rec.handler))
    fireEvent.click(m.get("button"))
    open.get shouldBe true
    rec.last.key shouldBe "open"
    rec.last.value shouldBe true
    rec.last.previous shouldBe false
    rec.last.reason shouldBe ChangeReason.Press
    rec.last.source shouldBe ChangeSource.Pointer
    // Escape reaches the popover once it has mounted
    settle().map { _ =>
      fireEvent.keyDown(dom.document.body, "Escape")
      open.get shouldBe false
      rec.last.reason shouldBe ChangeReason.EscapeKey
      rec.last.source shouldBe ChangeSource.Keyboard
    }
  }

  test("cancel() keeps a popover open") {
    val open = Rx.variable(true)
    val rec  = Recorder()
    mount(Popover(open)(Button("Filter"))("Details").onChange(rec.handler))
    rec.refuse = true
    settle().map { _ =>
      fireEvent.keyDown(dom.document.body, "Escape")
      open.get shouldBe true
      rec.last.canceled shouldBe true
    }
  }

  test("a select reports the chosen option, and a refused one keeps the selection") {
    val region = Rx.variable("tokyo")
    val rec    = Recorder()
    val m      = mount(Select(region, Seq("tokyo", "oregon"))(identity).onChange(rec.handler))
    fireEvent.click(m.get("button"))
    fireEvent.click(find("[data-slot=select-option]:nth-child(2)"))
    region.get shouldBe "oregon"
    rec.last.key shouldBe "selected"
    rec.last.reason shouldBe ChangeReason.ItemPress
    rec.refuse = true
    fireEvent.click(m.get("button"))
    fireEvent.click(find("[data-slot=select-option]:nth-child(1)"))
    region.get shouldBe "oregon"
  }

  test("arrow keys on tabs report navigation-key, and a refused move keeps focus") {
    val tab   = Rx.variable("a")
    val rec   = Recorder()
    val tabs  = TabList(tab, "Views")(Tab("a", "A"), Tab("b", "B")).onChange(rec.handler)
    val m     = mount(tabs)
    val list  = m.get("[role=tablist]")
    val first = m.get("[role=tab]").asInstanceOf[dom.HTMLElement]
    first.focus()
    rec.refuse = true
    fireEvent.keyDown(list, "ArrowRight")
    tab.get shouldBe "a"
    (dom.document.activeElement eq first) shouldBe true
    rec.refuse = false
    fireEvent.keyDown(list, "ArrowRight")
    tab.get shouldBe "b"
    rec.last.reason shouldBe ChangeReason.NavigationKey
    rec.last.source shouldBe ChangeSource.Keyboard
  }

  test("a refused edit puts a text field back") {
    val text = Rx.variable("abc")
    val rec  = Recorder()
    val m    = mount(Input(text).onChange(rec.handler))
    val el   = m.get("input").asInstanceOf[dom.HTMLInputElement]
    fireEvent.input(el, "abcd")
    text.get shouldBe "abcd"
    rec.last.reason shouldBe ChangeReason.Input
    rec.refuse = true
    fireEvent.input(el, "x")
    text.get shouldBe "abcd"
    el.value shouldBe "abcd"
    // The app can still set the text
    text := "set by app"
    el.value shouldBe "set by app"
  }

  test("an IME composition goes through onChange once, when it ends") {
    val text = Rx.variable("")
    val rec  = Recorder()
    val m    = mount(Input(text).onChange(rec.handler))
    val el   = m.get("input").asInstanceOf[dom.HTMLInputElement]
    fireEvent.custom(el, "compositionstart")
    fireEvent.input(el, "に")
    fireEvent.input(el, "日本")
    rec.seen.size shouldBe 0
    text.get shouldBe ""
    fireEvent.custom(el, "compositionend")
    // The commit waits a tick, for browsers that send the last input after compositionend
    settle().map { _ =>
      text.get shouldBe "日本"
      rec.seen.size shouldBe 1
    }
  }

  test("an input event after compositionend is gated once") {
    val text = Rx.variable("")
    val rec  = Recorder()
    val m    = mount(Input(text).onChange(rec.handler))
    val el   = m.get("input").asInstanceOf[dom.HTMLInputElement]
    fireEvent.custom(el, "compositionstart")
    fireEvent.input(el, "に")
    fireEvent.custom(el, "compositionend")
    fireEvent.input(el, "日本")
    settle().map { _ =>
      text.get shouldBe "日本"
      rec.seen.map(_.value).toSeq shouldBe Seq("日本")
    }
  }

  test("a menu item's action is skipped when onChange keeps the menu open") {
    val open = Rx.variable(true)
    var ran  = false
    val rec  = Recorder()
    mount(
      Menu(open)(Button("More"))(MenuItem("Delete").onSelect(() => ran = true)).onChange(
        rec.handler
      )
    )
    rec.refuse = true
    fireEvent.click(find("[data-slot=menu-item]"))
    open.get shouldBe true
    ran shouldBe false
  }

  test("ChangeSource.withSource attributes changes to an agent") {
    val open = Rx.variable(false)
    val rec  = Recorder()
    val m    = mount(Popover(open)(Button("Filter"))("Details").onChange(rec.handler))
    ChangeSource.withSource(ChangeSource.Agent)(fireEvent.click(m.get("button")))
    rec.last.source shouldBe ChangeSource.Agent
  }

  test("ChangeCause.current tells a click from Enter inside onClick") {
    val causes = ArrayBuffer.empty[ChangeCause]
    val m      = mount(Button("Save").onClick(() => causes += ChangeCause.current))
    val b      = m.get("button").asInstanceOf[dom.HTMLElement]
    fireEvent.click(b)
    // HTMLElement.click(), as Enter or Space on a button, sends a click with detail 0
    b.click()
    causes.map(_.source).toSeq shouldBe Seq(ChangeSource.Pointer, ChangeSource.Keyboard)
    causes.head.reason shouldBe ChangeReason.Press
    ChangeCause.current.source shouldBe ChangeSource.Programmatic
  }

  test("a refused close keeps an alert dialog open and skips onConfirm") {
    val open      = Rx.variable(true)
    var confirmed = false
    val rec       = Recorder()
    mount(AlertDialog(open)("Delete?").onConfirm(() => confirmed = true).onChange(rec.handler))
    rec.refuse = true
    fireEvent.click(find("[data-slot=alert-dialog-actions] button:last-child"))
    open.get shouldBe true
    confirmed shouldBe false
    rec.last.reason shouldBe ChangeReason.ActionPress
    rec.refuse = false
    fireEvent.click(find("[data-slot=alert-dialog-actions] button:last-child"))
    open.get shouldBe false
    confirmed shouldBe true
  }

  test("a table reports sort changes, and a tree reports rows opening") {
    case class Row(id: Int, name: String)
    case class Node(name: String, children: Seq[Node] = Seq.empty)
    val rec   = Recorder()
    val sort  = Rx.variable(Option.empty[SortBy])
    val table = DataTable(Rx.const(Seq(Row(1, "b"), Row(2, "a"))))(_.id)(
      Column[Row]("name", "Name")(r => span(r.name)).sortBy(_.name)
    ).withSort(sort).onChange(rec.handler)
    val open = Rx.variable(Set.empty[String])
    val tree = Tree(Rx.const(Seq(Node("src", Seq(Node("main"))))), (n: Node) => n.children)(_.name)(
      n => span(n.name)
    ).withExpanded(open).onChange(rec.handler)
    val m = mount(div(table, tree))
    fireEvent.click(m.get("[data-slot=data-table-sort]"))
    sort.get shouldBe Some(SortBy("name", SortDirection.Ascending))
    rec.last.key shouldBe "sort"
    val treeEl = m.get("[data-slot=tree]")
    fireEvent.focus(treeEl)
    fireEvent.keyDown(treeEl, "ArrowRight")
    open.get shouldBe Set("src")
    rec.last.key shouldBe "expanded"
    rec.last.reason shouldBe ChangeReason.NavigationKey
  }

  test("a splitter keeps its size when onChange refuses an arrow key") {
    val width = Rx.variable(272)
    val rec   = Recorder()
    val m     = mount(
      Splitter(width, SizeRange(min = 200, default = 272, max = 480), Edge.Right, "Resize")
        .onChange(rec.handler)
    )
    val grip = m.get("[data-slot=splitter]")
    fireEvent.keyDown(grip, "ArrowRight")
    width.get shouldBe 272 + Splitter.step
    rec.last.key shouldBe "size"
    rec.refuse = true
    fireEvent.keyDown(grip, "ArrowRight")
    width.get shouldBe 272 + Splitter.step
    grip.getAttribute("aria-valuenow") shouldBe (272 + Splitter.step).toString
  }

  test("components name their parts with data-slot") {
    val open = Rx.variable(true)
    val m    = mount(
      div(
        Button("Save"),
        Input(Rx.variable("")),
        TabList(Rx.variable(1), "Tabs")(Tab(1, "One")),
        Dialog(open)("Body").withTitle("Title"),
        Badge("3")
      )
    )
    m.querySelector("[data-slot=button]").isDefined shouldBe true
    m.querySelector("[data-slot=input]").isDefined shouldBe true
    m.querySelector("[data-slot=tab-list] [data-slot=tab]").isDefined shouldBe true
    m.querySelector("[data-slot=badge]").isDefined shouldBe true
    find("[data-slot=dialog] [data-slot=dialog-title]").textContent shouldBe "Title"
    find("[data-slot=dialog-body]").textContent shouldBe "Body"
  }

end ChangeTest
