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
import wvlet.uni.dom.all.{div, styleAttr}
import wvlet.uni.dom.all.given
import wvlet.uni.dom.testing.*
import wvlet.uni.rx.{Rx, RxVar}
import wvlet.uni.ui.base.DockModel

import scala.language.implicitConversions

import DockNode.{group, row, column, Group, Split}

class DockTest extends DomTestSupport:

  // The dock lays out with flexbox, which uni-ui.css sets up
  private val stylesheet = dom.document.createElement("style")
  stylesheet.textContent = UniUiCss.text

  override protected def beforeAll: Unit = dom.document.head.appendChild(stylesheet)
  override protected def afterAll: Unit  = dom.document.head.removeChild(stylesheet)

  private def active: dom.Element = dom.document.activeElement

  private def find(selector: String): Option[dom.HTMLElement] = Option(
    dom.document.querySelector(selector)
  ).map(_.asInstanceOf[dom.HTMLElement])

  // How many times each panel's content was created
  private val created = scala.collection.mutable.Map.empty[String, Int].withDefaultValue(0)

  private def panel(id: String, closable: Boolean = true): DockPanel = DockPanel(
    id,
    id.capitalize
  ) {
    created(id) += 1
    div(s"${id} content")
  }.withClosable(closable)

  private def dock(layout: RxVar[DockLayout], extra: DockPanel*): Dock =
    Dock(layout, "Dock")((Seq(panel("a"), panel("b"), panel("c")) ++ extra)*)

  private def mountDock(d: Dock): Mounted = mount(
    div(styleAttr -> "width: 600px; height: 400px; display: flex", d)
  )

  private def tabOf(m: Mounted, panel: String): dom.HTMLElement = m
    .get(s"[role=tab][data-panel='${panel}']")
    .asInstanceOf[dom.HTMLElement]

  private def tabTexts(m: Mounted): Seq[Seq[String]] = m
    .queryAll(".uni-dock__group")
    .map(g =>
      (0 until g.querySelectorAll("[role=tab]").length).map(i =>
        g.querySelectorAll("[role=tab]")(i).getAttribute("data-panel")
      )
    )

  test("renders groups with tabs, and a click switches the tab") {
    val layout = Rx.variable(DockLayout(row(group("a", "b"), group("c"))))
    val m      = mountDock(dock(layout))
    tabTexts(m) shouldBe Seq(Seq("a", "b"), Seq("c"))
    m.queryAll("[role=tablist]").map(_.getAttribute("aria-label")) shouldBe
      Seq("Dock, group 1", "Dock, group 2")
    val a = tabOf(m, "a")
    a.getAttribute("aria-selected") shouldBe "true"
    a.getAttribute("tabindex") shouldBe "0"
    m.queryAll("[role=tabpanel]").map(_.textContent) shouldBe Seq("a content", "c content")
    val panelEl = m.get("[role=tabpanel]")
    a.getAttribute("aria-controls") shouldBe panelEl.id
    panelEl.getAttribute("aria-labelledby") shouldBe a.id

    fireEvent.click(tabOf(m, "b"))
    layout.get.root shouldBe row(Group(Seq("a", "b"), Some("b")), group("c"))
    m.queryAll("[role=tabpanel]").map(_.textContent) shouldBe Seq("b content", "c content")
    tabOf(m, "b").getAttribute("aria-selected") shouldBe "true"
    active shouldBe tabOf(m, "b")
  }

  test("drops unknown panels from a saved layout and saves the cleaned-up one") {
    val layout = Rx.variable(DockLayout(row(group("a", "gone"), group("old"))))
    val m      = mountDock(dock(layout))
    tabTexts(m) shouldBe Seq(Seq("a"))
    // Saved once the dock is mounted
    settle().map { _ =>
      layout.get shouldBe DockLayout(group("a"))
    }
  }

  test("adds back a panel that can't be closed, and open shows a hidden one") {
    val layout = Rx.variable(DockLayout.of("a"))
    val d      = dock(layout, panel("pinned", closable = false))
    val m      = mountDock(d)
    tabTexts(m) shouldBe Seq(Seq("a", "pinned"))
    m.queryAll(s"[data-panel='pinned'] .uni-dock__tab-close").size shouldBe 0

    var hidden = Seq.empty[String]
    val c      = d.hidden.run(h => hidden = h)
    hidden shouldBe Seq("b", "c")
    d.open("c")
    tabTexts(m) shouldBe Seq(Seq("a", "pinned", "c"))
    tabOf(m, "c").getAttribute("aria-selected") shouldBe "true"
    hidden shouldBe Seq("b")
    c.cancel
  }

  test("the arrow keys switch tabs and Delete closes one") {
    val layout = Rx.variable(DockLayout.of("a", "b", "c"))
    val m      = mountDock(dock(layout))
    tabOf(m, "a").focus()
    fireEvent.keyDown(tabOf(m, "a"), "ArrowRight")
    active shouldBe tabOf(m, "b")
    layout.get.root shouldBe Group(Seq("a", "b", "c"), Some("b"))
    fireEvent.keyDown(tabOf(m, "b"), "End")
    active shouldBe tabOf(m, "c")
    fireEvent.keyDown(tabOf(m, "c"), "Home")
    active shouldBe tabOf(m, "a")

    fireEvent.keyDown(tabOf(m, "a"), "Delete")
    tabTexts(m) shouldBe Seq(Seq("b", "c"))
    settle().map { _ =>
      active shouldBe tabOf(m, "b")
    }
  }

  test("Alt with the arrow keys, Home, or End moves a tab within its group") {
    val layout = Rx.variable(DockLayout.of("a", "b", "c"))
    val m      = mountDock(dock(layout))
    tabOf(m, "a").focus()
    fireEvent.keyDown(tabOf(m, "a"), "ArrowRight", altKey = true)
    tabTexts(m) shouldBe Seq(Seq("b", "a", "c"))
    fireEvent.keyDown(tabOf(m, "a"), "End", altKey = true)
    tabTexts(m) shouldBe Seq(Seq("b", "c", "a"))
    fireEvent.keyDown(tabOf(m, "a"), "ArrowLeft", altKey = true)
    tabTexts(m) shouldBe Seq(Seq("b", "a", "c"))
    // At the start, nothing moves, but the browser still doesn't go Back
    fireEvent.keyDown(tabOf(m, "b"), "ArrowLeft", altKey = true) shouldBe false
    tabTexts(m) shouldBe Seq(Seq("b", "a", "c"))
    settle().map { _ =>
      active shouldBe tabOf(m, "a")
    }
  }

  test("Close in the tab menu moves focus to the neighbor tab") {
    val layout = Rx.variable(DockLayout.of("a", "b", "c"))
    val m      = mountDock(dock(layout))
    fireEvent.contextMenu(tabOf(m, "b"))
    settle().flatMap { _ =>
      fireEvent.click(menuItem("Close"))
      tabTexts(m) shouldBe Seq(Seq("a", "c"))
      settle().map { _ =>
        active shouldBe tabOf(m, "c")
      }
    }
  }

  test("panel ids that differ only in punctuation get distinct tab ids") {
    val layout = Rx.variable(DockLayout.of("x.y", "x_y"))
    val m      = mountDock(Dock(layout, "Dock")(panel("x.y"), panel("x_y")))
    (tabOf(m, "x.y").id == tabOf(m, "x_y").id) shouldBe false
  }

  test("open adds to the focused group, and forgets it when the structure changes") {
    val layout = Rx.variable(DockLayout(row(group("a"), group("b"))))
    val d      = dock(layout)
    val m      = mountDock(d)
    tabOf(m, "b").focus()
    d.open("c")
    layout.get.root shouldBe row(group("a"), Group(Seq("b", "c"), Some("c")))
    // A new structure: the old path [1] would now point at the group of "a"
    d.close("c")
    tabOf(m, "b").focus()
    layout := DockLayout(row(group("b"), column(group("a"), group("x"))))
    d.open("c")
    layout.get.root shouldBe row(Group(Seq("b", "c"), Some("c")), group("a"))
  }

  test("the close button removes a tab") {
    val layout = Rx.variable(DockLayout.of("a", "b"))
    val m      = mountDock(dock(layout))
    fireEvent.click(m.get("[data-panel='a'] .uni-dock__tab-close"))
    tabTexts(m) shouldBe Seq(Seq("b"))
    m.get("[data-panel='b'] .uni-dock__tab-close").getAttribute("aria-label") shouldBe "Close B"
  }

  private def menuItem(text: String): dom.HTMLElement =
    val items = dom.document.querySelectorAll(".uni-menu [role=menuitem]")
    (0 until items.length)
      .map(items(_).asInstanceOf[dom.HTMLElement])
      .find(_.textContent.contains(text))
      .get

  test("the tab menu splits a group and moves a tab, without a pointer") {
    val layout = Rx.variable(DockLayout.of("a", "b"))
    val m      = mountDock(dock(layout))
    fireEvent.contextMenu(tabOf(m, "b"))
    settle().flatMap { _ =>
      fireEvent.click(menuItem("Split down"))
      layout.get.root shouldBe column(group("a"), group("b"))
      m.queryAll(".uni-dock__split[data-direction='column']").size shouldBe 1
      settle().flatMap { _ =>
        // Focus follows the tab to its new group
        active shouldBe tabOf(m, "b")
        fireEvent.contextMenu(tabOf(m, "b"))
        settle().map { _ =>
          fireEvent.click(menuItem("Move to next group"))
          layout.get.root shouldBe Group(Seq("a", "b"), Some("b"))
        }
      }
    }
  }

  test("a panel keeps its content when it moves to another group") {
    created.clear()
    val layout = Rx.variable(DockLayout(row(group("a"), group("b"))))
    val m      = mountDock(dock(layout))
    created("a") shouldBe 1
    layout := DockModel.move(layout.get, "a", List(1), 0)
    m.queryAll("[role=tabpanel]").map(_.textContent) shouldBe Seq("a content")
    created("a") shouldBe 1
  }

  test("a separator resizes with the arrow keys and keeps the minimum size") {
    val layout = Rx.variable(DockLayout(row(group("a"), group("b"))))
    val m      = mountDock(dock(layout).withMinPanelSize(100))
    val sep    = m.get(".uni-dock__separator").asInstanceOf[dom.HTMLElement]
    sep.getAttribute("role") shouldBe "separator"
    sep.getAttribute("aria-orientation") shouldBe "vertical"
    sep.getAttribute("aria-valuenow") shouldBe "50"

    def sizes: Seq[Double] =
      layout.get.root match
        case Split(_, _, ss) =>
          ss
        case _ =>
          Seq.empty
    // 600px less the 1px separator
    val px = 599.0
    fireEvent.keyDown(sep, "ArrowRight")
    (math.abs(sizes.head * px - (px / 2 + Splitter.step)) < 0.01) shouldBe true
    fireEvent.keyDown(sep, "ArrowLeft", shiftKey = true)
    (math.abs(sizes.head * px - (px / 2 + Splitter.step - Splitter.largeStep)) < 0.01) shouldBe true
    fireEvent.keyDown(sep, "End")
    (math.abs(sizes(1) * px - 100) < 0.01) shouldBe true
    sep.getAttribute("aria-valuenow") shouldBe "83"
    fireEvent.keyDown(sep, "Home")
    (math.abs(sizes.head * px - 100) < 0.01) shouldBe true
    fireEvent.keyDown(sep, "Enter")
    sizes shouldBe Seq(0.5, 0.5)
    // Resizing changes sizes in place; the groups are not rebuilt
    m.queryAll(".uni-dock__separator").head shouldBe sep
  }

  test("dragging a separator resizes the split") {
    val layout = Rx.variable(DockLayout(column(group("a"), group("b"))))
    val m      = mountDock(dock(layout))
    val sep    = m.get(".uni-dock__separator")
    sep.getAttribute("aria-orientation") shouldBe "horizontal"
    fireEvent.pointerDown(sep, clientX = 300, clientY = 200)
    fireEvent.pointerMove(dom.window, clientX = 300, clientY = 250)
    fireEvent.pointerUp(dom.window, clientX = 300, clientY = 250)
    layout.get.root match
      case Split(_, _, ss) =>
        (math.abs(ss.head * 399 - (399.0 / 2 + 50)) < 0.01) shouldBe true
      case other =>
        fail(s"Unexpected ${other}")
  }

  private def center(el: dom.Element): (Double, Double) =
    val r = el.getBoundingClientRect()
    (r.left + r.width / 2, r.top + r.height / 2)

  test("dragging a tab to a group's edge splits the group, and Escape cancels a drag") {
    val layout = Rx.variable(DockLayout.of("a", "b"))
    val m      = mountDock(dock(layout))
    val (x, y) = center(tabOf(m, "b"))
    val panelR = m.get(".uni-dock__panel").getBoundingClientRect()

    // Escape first: nothing changes
    fireEvent.pointerDown(tabOf(m, "b"), clientX = x, clientY = y)
    fireEvent.pointerMove(dom.window, clientX = x, clientY = panelR.bottom - 10)
    m.get(".uni-dock__drop").getAttribute("data-side") shouldBe "bottom"
    fireEvent.keyDown(dom.window, "Escape")
    m.queryAll(".uni-dock__drop").size shouldBe 0
    fireEvent.pointerUp(dom.window, clientX = x, clientY = panelR.bottom - 10)
    layout.get shouldBe DockLayout.of("a", "b")

    fireEvent.pointerDown(tabOf(m, "b"), clientX = x, clientY = y)
    fireEvent.pointerMove(dom.window, clientX = x, clientY = panelR.bottom - 10)
    m.get(".uni-dock").getAttribute("data-dragging") shouldBe "true"
    tabOf(m, "b").getAttribute("data-dragging") shouldBe "true"
    fireEvent.pointerUp(dom.window, clientX = x, clientY = panelR.bottom - 10)
    layout.get.root shouldBe column(group("a"), group("b"))
    m.queryAll(".uni-dock__drop").size shouldBe 0
  }

  test("dragging a tab onto another group's tabs moves it there") {
    val layout = Rx.variable(DockLayout(row(group("a", "b"), group("c"))))
    val m      = mountDock(dock(layout))
    val (x, y) = center(tabOf(m, "a"))
    val c      = tabOf(m, "c").getBoundingClientRect()
    fireEvent.pointerDown(tabOf(m, "a"), clientX = x, clientY = y)
    // Left of c's middle: before c
    fireEvent.pointerMove(dom.window, clientX = c.left + 2, clientY = c.top + c.height / 2)
    m.get(".uni-dock__drop").getAttribute("data-kind") shouldBe "tab"
    fireEvent.pointerUp(dom.window, clientX = c.left + 2, clientY = c.top + c.height / 2)
    layout.get.root shouldBe row(Group(Seq("b"), Some("b")), Group(Seq("a", "c"), Some("a")))
  }

  test("a press without movement selects the tab and doesn't drag") {
    val layout = Rx.variable(DockLayout.of("a", "b"))
    val m      = mountDock(dock(layout))
    val (x, y) = center(tabOf(m, "b"))
    fireEvent.pointerDown(tabOf(m, "b"), clientX = x, clientY = y)
    fireEvent.pointerMove(dom.window, clientX = x + 2, clientY = y)
    m.queryAll(".uni-dock__drop").size shouldBe 0
    fireEvent.pointerUp(dom.window, clientX = x + 2, clientY = y)
    fireEvent.click(tabOf(m, "b"))
    layout.get.root shouldBe Group(Seq("a", "b"), Some("b"))
  }

  test("actions go to the top-right group, and an empty dock says so") {
    val layout = Rx.variable(DockLayout(row(group("a"), column(group("b"), group("c")))))
    val m      = mountDock(dock(layout).withActions(div(wvlet.uni.dom.all.cls -> "fold", "Fold")))
    m.queryAll(".uni-dock__group").map(g => g.querySelector(".fold") != null) shouldBe
      Seq(false, true, false)
    layout := DockLayout.empty
    m.get(".uni-dock__empty").textContent shouldBe "No open panels"
    // The actions stay, so the dock can still be folded
    m.queryAll(".uni-dock__actions .fold").size shouldBe 1
  }

  test("focusGroup focuses a group's active tab by its index in reading order") {
    val layout = Rx.variable(
      DockLayout(row(Group(Seq("a", "b"), Some("b")), column(group("c"), group("d"))))
    )
    val d     = dock(layout, panel("d"))
    val m     = mountDock(d)
    var count = 0
    val c     = d.groupCount.run(n => count = n)
    count shouldBe 3
    d.focusGroup(0) shouldBe true
    active shouldBe tabOf(m, "b")
    d.focusGroup(2) shouldBe true
    active shouldBe tabOf(m, "d")
    // The focused group is where open adds a panel
    d.close("a")
    d.focusGroup(1) shouldBe true
    d.open("a")
    layout.get.root shouldBe row(group("b"), column(Group(Seq("c", "a"), Some("a")), group("d")))

    d.focusGroup(3) shouldBe false
    d.focusGroup(-1) shouldBe false
    active shouldBe tabOf(m, "a")
    c.cancel
  }

  test("focusGroup follows the new numbering after a split") {
    val layout = Rx.variable(DockLayout.of("a", "b"))
    val d      = dock(layout)
    val m      = mountDock(d)
    d.focusGroup(1) shouldBe false
    layout := DockModel.split(layout.get, "b", Nil, DockSide.Bottom)
    d.focusGroup(1) shouldBe true
    active shouldBe tabOf(m, "b")
  }

  test("focusPanel shows a hidden panel and focuses its tab") {
    val layout = Rx.variable(DockLayout(row(group("a"), group("b"))))
    val d      = dock(layout)
    val m      = mountDock(d)
    d.focusGroup(1) shouldBe true
    d.focusPanel("c") shouldBe true
    layout.get.root shouldBe row(group("a"), Group(Seq("b", "c"), Some("c")))
    active shouldBe tabOf(m, "c")
    d.focusPanel("a") shouldBe true
    active shouldBe tabOf(m, "a")
    d.focusPanel("unknown") shouldBe false
  }

end DockTest
