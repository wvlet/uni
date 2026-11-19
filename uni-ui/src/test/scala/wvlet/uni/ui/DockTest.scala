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
import wvlet.uni.dom.all.{dir, div, id, styleAttr, RxElement}
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
    title = id.capitalize,
    content =
      created(id) += 1
      div(s"${id} content")
  ).withClosable(closable)

  private def dock(layout: RxVar[DockLayout], extra: DockPanel*): Dock = Dock(
    layout,
    label = "Dock",
    panels = (Seq(panel("a"), panel("b"), panel("c")) ++ extra)
  )

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

  test("Alt+ArrowLeft moves a tab toward the end in a right-to-left layout") {
    val layout = Rx.variable(DockLayout.of("a", "b", "c"))
    val m      = mount(
      div(dir -> "rtl", styleAttr -> "width: 600px; height: 400px; display: flex", dock(layout))
    )
    fireEvent.keyDown(tabOf(m, "a"), "ArrowLeft", altKey = true)
    tabTexts(m) shouldBe Seq(Seq("b", "a", "c"))
    fireEvent.keyDown(tabOf(m, "a"), "ArrowRight", altKey = true)
    tabTexts(m) shouldBe Seq(Seq("a", "b", "c"))
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
    val m      = mountDock(Dock(layout, label = "Dock", panels = Seq(panel("x.y"), panel("x_y"))))
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

  test("ArrowLeft grows the first pane of a right-to-left row") {
    val layout = Rx.variable(DockLayout(row(group("a"), group("b"))))
    val m      = mount(
      div(dir -> "rtl", styleAttr -> "width: 600px; height: 400px; display: flex", dock(layout))
    )
    val sep           = m.get(".uni-dock__separator")
    def first: Double =
      layout.get.root match
        case Split(_, _, ss) =>
          ss.head
        case _ =>
          0
    fireEvent.keyDown(sep, "ArrowLeft")
    (first > 0.5) shouldBe true
    fireEvent.keyDown(sep, "ArrowRight")
    fireEvent.keyDown(sep, "ArrowRight")
    (first < 0.5) shouldBe true
  }

  test("a leftward drag grows the first pane of a right-to-left row, as ArrowLeft does") {
    val layout = Rx.variable(DockLayout(row(group("a"), group("b"))))
    val m      = mount(
      div(dir -> "rtl", styleAttr -> "width: 600px; height: 400px; display: flex", dock(layout))
    )
    val sep           = m.get(".uni-dock__separator")
    def first: Double =
      layout.get.root match
        case Split(_, _, ss) =>
          ss.head
        case _ =>
          0
    fireEvent.pointerDown(sep, clientX = 300, clientY = 200)
    fireEvent.pointerMove(dom.window, clientX = 250, clientY = 200)
    fireEvent.pointerUp(dom.window, clientX = 250, clientY = 200)
    // 600px less the 1px separator
    (math.abs(first * 599 - (599.0 / 2 + 50)) < 0.01) shouldBe true
    fireEvent.pointerDown(sep, clientX = 250, clientY = 200)
    fireEvent.pointerMove(dom.window, clientX = 350, clientY = 200)
    fireEvent.pointerUp(dom.window, clientX = 350, clientY = 200)
    (math.abs(first * 599 - (599.0 / 2 - 50)) < 0.01) shouldBe true
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

  test("Escape mid-drag puts a separator's sizes back") {
    val layout = Rx.variable(DockLayout(column(group("a"), group("b"))))
    val m      = mountDock(dock(layout))
    val before = layout.get
    fireEvent.pointerDown(m.get(".uni-dock__separator"), clientX = 300, clientY = 200)
    fireEvent.pointerMove(dom.window, clientX = 300, clientY = 250)
    (layout.get == before) shouldBe false
    fireEvent.keyDown(dom.window, "Escape")
    layout.get shouldBe before
    fireEvent.pointerMove(dom.window, clientX = 300, clientY = 300)
    layout.get shouldBe before
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

  // --- Floating groups and detached windows ---

  private def near(a: Double, b: Double): Boolean = math.abs(a - b) < 1e-6

  test("Float in the tab menu lifts a panel over the grid, and Dock puts it back") {
    val layout = Rx.variable(DockLayout.of("a", "b"))
    val m      = mountDock(dock(layout))
    fireEvent.contextMenu(tabOf(m, "b"))
    settle().flatMap { _ =>
      fireEvent.click(menuItem("Float"))
      layout.get.root shouldBe group("a")
      layout.get.floating.map(_.panels) shouldBe Seq(Seq("b"))
      val f = m.get(".uni-dock__float")
      f.querySelector("[role=tabpanel]").textContent shouldBe "b content"
      m.queryAll("[role=tablist]").map(_.getAttribute("aria-label")) shouldBe
        Seq("Dock, group 1", "Dock, group 2")
      // A little in from the group's corner, inside the dock
      val b = layout.get.floating.head.box
      (b.x > 0 && b.y > 0 && b.x + b.width <= 1 && b.y + b.height <= 1) shouldBe true
      settle().flatMap { _ =>
        active shouldBe tabOf(m, "b")
        fireEvent.contextMenu(tabOf(m, "b"))
        settle().map { _ =>
          // A float holds one group
          menuItem("Split right").getAttribute("aria-disabled") shouldBe "true"
          fireEvent.click(menuItem("Dock"))
          layout.get shouldBe DockLayout(Group(Seq("a", "b"), Some("b")))
          m.queryAll(".uni-dock__float").size shouldBe 0
        }
      }
    }
  }

  test("a float's grip moves it and its corner resizes it, by pointer and by key") {
    val layout = Rx.variable(
      DockLayout(group("a"), Seq(DockFloat.of(DockBox(0.1, 0.1, 0.5, 0.5), "b")))
    )
    val m    = mountDock(dock(layout))
    def box  = layout.get.floating.head.box
    val grip = m.get(".uni-dock__float-grip").asInstanceOf[dom.HTMLElement]
    m.get(".uni-dock__float").getAttribute("style") shouldContain "left: 10%"

    fireEvent.pointerDown(grip, clientX = 100, clientY = 100)
    fireEvent.pointerMove(dom.window, clientX = 160, clientY = 140)
    fireEvent.pointerUp(dom.window, clientX = 160, clientY = 140)
    (near(box.x, 0.2) && near(box.y, 0.2)) shouldBe true
    m.get(".uni-dock__float").getAttribute("style") shouldContain "left: 20%"
    // It can't leave the dock
    fireEvent.pointerDown(grip, clientX = 100, clientY = 100)
    fireEvent.pointerMove(dom.window, clientX = 2000, clientY = 100)
    fireEvent.pointerUp(dom.window, clientX = 2000, clientY = 100)
    near(box.x, 0.5) shouldBe true

    val corner = m.get(".uni-dock__float-resize")
    fireEvent.pointerDown(corner, clientX = 300, clientY = 300)
    fireEvent.pointerMove(dom.window, clientX = 0, clientY = 340)
    fireEvent.pointerUp(dom.window, clientX = 0, clientY = 340)
    // No smaller than the minimum panel size
    (near(box.width, 120.0 / 600) && near(box.height, 0.6)) shouldBe true

    grip.focus()
    val before = box
    fireEvent.keyDown(grip, "ArrowLeft")
    near(box.x, before.x - 16.0 / 600) shouldBe true
    fireEvent.keyDown(grip, "ArrowUp", shiftKey = true)
    near(box.height, before.height - 16.0 / 400) shouldBe true
    // Moving and resizing doesn't rebuild the float
    (m.get(".uni-dock__float-grip") eq grip) shouldBe true
  }

  test("Escape mid-drag puts a float back where it was") {
    val start  = DockBox(0.1, 0.1, 0.5, 0.5)
    val layout = Rx.variable(DockLayout(group("a"), Seq(DockFloat.of(start, "b"))))
    val m      = mountDock(dock(layout))
    def box    = layout.get.floating.head.box
    fireEvent.pointerDown(m.get(".uni-dock__float-grip"), clientX = 100, clientY = 100)
    fireEvent.pointerMove(dom.window, clientX = 160, clientY = 140)
    near(box.x, 0.2) shouldBe true
    fireEvent.keyDown(dom.window, "Escape")
    box shouldBe start
    fireEvent.pointerDown(m.get(".uni-dock__float-resize"), clientX = 300, clientY = 300)
    fireEvent.pointerMove(dom.window, clientX = 340, clientY = 340)
    fireEvent.keyDown(dom.window, "Escape")
    box shouldBe start
  }

  test("a tab dragged onto a float joins it, and pressing a float raises it") {
    val boxA   = DockBox(0.5, 0.5, 0.4, 0.4)
    val layout = Rx.variable(
      DockLayout(
        group("a", "c"),
        Seq(DockFloat.of(boxA, "b"), DockFloat.of(DockBox(0, 0.5, 0.4, 0.4), "d"))
      )
    )
    val m      = mountDock(dock(layout, panel("d")))
    val floats = m.queryAll(".uni-dock__float")
    floats.map(_.getAttribute("style").contains("z-index: 2")) shouldBe Seq(false, true)
    fireEvent.pointerDown(floats.head, clientX = 400, clientY = 300)
    fireEvent.pointerUp(dom.window, clientX = 400, clientY = 300)
    m.queryAll(".uni-dock__float").map(_.getAttribute("style").contains("z-index: 2")) shouldBe
      Seq(true, false)

    val (x, y)   = center(tabOf(m, "a"))
    val (px, py) = center(floats.head.querySelector("[role=tabpanel]"))
    fireEvent.pointerDown(tabOf(m, "a"), clientX = x, clientY = y)
    fireEvent.pointerMove(dom.window, clientX = px + 30, clientY = py)
    // The whole float, not a split
    m.get(".uni-dock__drop").getAttribute("data-side") shouldBe "center"
    fireEvent.pointerUp(dom.window, clientX = px + 30, clientY = py)
    layout.get.floating.head shouldBe DockFloat(Seq("b", "a"), Some("a"), boxA)
    layout.get.root shouldBe group("c")
  }

  test("a float's tab dragged onto an empty grid moves back there") {
    val layout = Rx.variable(
      DockLayout(DockLayout.empty.root, Seq(DockFloat.of(DockBox(0.5, 0, 0.5, 0.5), "a")))
    )
    val m = mountDock(dock(layout))
    m.queryAll(".uni-dock__empty").size shouldBe 1
    val (x, y) = center(tabOf(m, "a"))
    fireEvent.pointerDown(tabOf(m, "a"), clientX = x, clientY = y)
    fireEvent.pointerMove(dom.window, clientX = 150, clientY = 300)
    fireEvent.pointerUp(dom.window, clientX = 150, clientY = 300)
    layout.get shouldBe DockLayout(group("a"))
  }

  test("detach moves a panel into a window of its own, and it comes back when that closes") {
    created.clear()
    val layout = Rx.variable(DockLayout.of("a", "b"))
    dock(layout).detach("a") shouldBe false
    val d     = dock(layout).withDetach(true)
    val m     = mountDock(d)
    var shown = Set.empty[String]
    val c     = d.detached.run(shown = _)

    d.detach("a") shouldBe true
    shown shouldBe Set("a")
    tabOf(m, "a").getAttribute("data-detached") shouldBe "true"
    m.get("[role=tabpanel]").textContent shouldContain "A is open in another window."
    val w = d.windowOf("a").get
    w.document.title shouldBe "A"
    w.document.querySelector(".uni-dock__window").textContent shouldBe "a content"
    // The page's styles come along
    (w.document.querySelectorAll("style").length > 0) shouldBe true
    created("a") shouldBe 1

    // The window closing brings the panel back, with the same content
    fireEvent.dispatch(w, dom.Event("pagehide"))
    shown shouldBe Set.empty
    d.windowOf("a") shouldBe None
    tabOf(m, "a").getAttribute("data-detached") shouldBe "false"
    m.get("[role=tabpanel]").textContent shouldBe "a content"
    created("a") shouldBe 1

    // From the tab menu, and back
    fireEvent.contextMenu(tabOf(m, "b"))
    settle().flatMap { _ =>
      fireEvent.click(menuItem("Open in new window"))
      shown shouldBe Set("b")
      fireEvent.contextMenu(tabOf(m, "b"))
      settle().flatMap { _ =>
        fireEvent.click(menuItem("Bring back from window"))
        shown shouldBe Set.empty
        // Closing a detached panel closes its window
        d.detach("b") shouldBe true
        val wb = d.windowOf("b").get
        d.close("b")
        shown shouldBe Set.empty
        wb.closed shouldBe true
        // A hidden panel is shown in the layout first
        d.detach("c") shouldBe true
        DockModel.panels(layout.get) shouldContain "c"
        settle().map { _ =>
          d.reattach("c")
          c.cancel
        }
      }
    }
  }

  test("a detached window follows the theme, mounts content once, and closes with the layout") {
    val root = dom.document.documentElement
    root.setAttribute("data-theme", "dark")
    var mounts = 0
    class Probe extends RxElement:
      override def onMount(node: Any): Unit = mounts += 1
      override def render: RxElement        = div(id -> "dock-probe", "probe content")
    val layout = Rx.variable(DockLayout.of("a"))
    val d      = Dock(
      layout,
      label = "Dock",
      panels = Seq(panel("a"), DockPanel("probe", title = "Probe", content = Probe()))
    ).withDetach(true)
    val m = mountDock(d)
    // Hidden: shown in the layout, but mounted only in the window
    d.detach("probe") shouldBe true
    val w = d.windowOf("probe").get
    w.document.documentElement.getAttribute("data-theme") shouldBe "dark"
    root.removeAttribute("data-theme")
    settle().flatMap { _ =>
      w.document.documentElement.hasAttribute("data-theme") shouldBe false
      mounts shouldBe 1

      // Bring back keeps keyboard focus in the dock
      fireEvent.click(m.get(".uni-dock__detached button:last-child"))
      d.windowOf("probe") shouldBe None
      settle().flatMap { _ =>
        active shouldBe tabOf(m, "probe")
        // The app dropping a detached panel from the layout closes its window
        d.detach("a") shouldBe true
        val wa = d.windowOf("a").get
        layout := DockLayout.of("probe")
        d.windowOf("a") shouldBe None
        wa.closed shouldBe true
        settle()
      }
    }
  }

  private def toggles(m: Mounted): Seq[dom.HTMLElement] = m
    .queryAll(".uni-dock__maximize")
    .map(_.asInstanceOf[dom.HTMLElement])

  private def shown(m: Mounted): Seq[String] = m
    .queryAll(".uni-dock__group")
    .filter(_.closest("[hidden]") == null)
    .map(_.getAttribute("data-path"))

  test("the maximize toggle fills the dock with one group and restores it") {
    val layout = Rx.variable(
      DockLayout(row(group("a", "b"), group("c")), Seq(DockFloat.of(DockBox.default, "d")))
    )
    val seen = scala.collection.mutable.ArrayBuffer.empty[(ChangeReason, ChangeSource)]
    val d    = dock(layout, panel("d")).onChange(c => seen += ((c.reason, c.source)))
    val m    = mountDock(d)
    // The grid groups have one; the float doesn't
    toggles(m).size shouldBe 2
    shown(m) shouldBe Seq("0", "1", "-1")

    fireEvent.click(toggles(m)(1))
    layout.get.maximized shouldBe Some("c")
    seen.last shouldBe (ChangeReason.Press, ChangeSource.Pointer)
    shown(m) shouldBe Seq("1")
    m.get(".uni-dock").getAttribute("data-maximized") shouldBe "true"
    m.get(".uni-dock__group[data-path='1']").getAttribute("data-maximized") shouldBe "true"
    toggles(m)(1).getAttribute("aria-pressed") shouldBe "true"
    m.queryAll(".uni-dock__separator").forall(_.hasAttribute("hidden")) shouldBe true
    m.get(".uni-dock__floats").hasAttribute("hidden") shouldBe true
    // The hidden content stays mounted, so it keeps its state
    m.get(".uni-dock__group[data-path='0']").textContent shouldContain "a content"
    d.visible.run(v => v shouldBe Set("c")).cancel

    fireEvent.click(toggles(m)(1))
    layout.get.maximized shouldBe None
    shown(m) shouldBe Seq("0", "1", "-1")
    toggles(m)(1).getAttribute("aria-pressed") shouldBe "false"
  }

  test("a lone group has no toggle, and the tab menu maximizes from the keyboard") {
    val one = Rx.variable(DockLayout.of("a", "b"))
    toggles(mountDock(dock(one))).size shouldBe 0

    val layout = Rx.variable(DockLayout(row(group("a"), group("b"))))
    val m      = mountDock(dock(layout))
    fireEvent.contextMenu(tabOf(m, "a"))
    settle().flatMap { _ =>
      fireEvent.click(menuItem("Maximize group"))
      layout.get.maximized shouldBe Some("a")
      shown(m) shouldBe Seq("0")
      fireEvent.contextMenu(tabOf(m, "a"))
      settle().flatMap { _ =>
        fireEvent.click(menuItem("Restore group"))
        layout.get.maximized shouldBe None
        settle()
      }
    }
  }

  test("maximize and restore go through onChange, and focusGroup restores a hidden group") {
    val layout = Rx.variable(DockLayout(row(group("a"), group("b"))))
    var cancel = true
    val d      = dock(layout).onChange(c =>
      if cancel then
        c.cancel()
    )
    val m = mountDock(d)
    d.maximize("a") shouldBe false
    layout.get.maximized shouldBe None
    cancel = false
    d.maximize("a") shouldBe true
    var now = Option.empty[String]
    val c   = d.maximized.run(now = _)
    now shouldBe Some("a")
    d.maximize("zzz") shouldBe false

    // Focusing a hidden group shows every group again
    d.focusGroup(1) shouldBe true
    active shouldBe tabOf(m, "b")
    now shouldBe None
    d.maximize("b") shouldBe true
    d.restore()
    now shouldBe None
    c.cancel
  }

  test("the dock's actions move to the maximized group") {
    val layout  = Rx.variable(DockLayout(row(group("a"), group("b"))))
    val m       = mountDock(dock(layout).withActions(div(wvlet.uni.dom.all.cls -> "fold", "Fold")))
    def holders = m.queryAll(".uni-dock__group").filter(_.querySelector(".fold") != null)
    holders.map(_.getAttribute("data-path")) shouldBe Seq("1")
    layout := layout.get.maximize("a")
    holders.map(_.getAttribute("data-path")) shouldBe Seq("0")
    layout := layout.get.restore
    holders.map(_.getAttribute("data-path")) shouldBe Seq("1")
  }

end DockTest
