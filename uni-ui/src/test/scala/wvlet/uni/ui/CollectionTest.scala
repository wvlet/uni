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
import wvlet.uni.dom.all.{button as htmlButton, div, span, styleAttr}
import wvlet.uni.dom.all.given
import wvlet.uni.dom.testing.*
import wvlet.uni.rx.{Rx, RxVar}

import scala.language.implicitConversions

class CollectionTest extends DomTestSupport:

  // Windowing needs the list to scroll inside its own box, which uni-ui.css sets up
  private val stylesheet = dom.document.createElement("style")
  stylesheet.textContent = UniUiCss.text

  override protected def beforeAll: Unit = dom.document.head.appendChild(stylesheet)
  override protected def afterAll: Unit  = dom.document.head.removeChild(stylesheet)

  private def active: dom.Element = dom.document.activeElement

  private def find(selector: String): Option[dom.HTMLElement] = Option(
    dom.document.querySelector(selector)
  ).map(_.asInstanceOf[dom.HTMLElement])

  private def rowTexts(m: Mounted): Seq[String] = m.queryAll(".uni-list__row").map(_.textContent)

  private def activeRow(list: dom.Element): Option[dom.Element] = Option(
    list.getAttribute("aria-activedescendant")
  ).flatMap(id => Option(dom.document.getElementById(id)))

  private def scrollTo(list: dom.HTMLElement, top: Double): Unit =
    list.scrollTop = top
    fireEvent.dispatch(list, dom.Event("scroll"))

  test("a virtual list renders only the rows in view and follows scrolling") {
    val items = Rx.variable((1 to 1000).toSeq)
    val m     = mount(
      VirtualList(items, rowHeight = 20)(identity)(i => span(s"Item ${i}")).withLabel("Numbers")(
        styleAttr += "height: 200px"
      )
    )
    val list = m.get(".uni-virtual-list").asInstanceOf[dom.HTMLElement]
    settle().map { _ =>
      list.getAttribute("role") shouldBe "listbox"
      list.getAttribute("aria-label") shouldBe "Numbers"
      m.get(".uni-list__spacer").asInstanceOf[dom.HTMLElement].style.height shouldBe "20000px"
      val first = rowTexts(m)
      first.head shouldBe "Item 1"
      // 11 rows fit in 200px, plus 8 rows of overscan below
      first.size shouldBe 19
      m.get(".uni-list__row").getAttribute("aria-posinset") shouldBe "1"
      m.get(".uni-list__row").getAttribute("aria-setsize") shouldBe "1000"

      scrollTo(list, 2000)
      val later = rowTexts(m)
      later.contains("Item 1") shouldBe false
      later.contains("Item 101") shouldBe true
      (later.size <= 27) shouldBe true
    }
  }

  test("a virtual list keeps a row's node while the list changes around it") {
    val items = Rx.variable(Seq(1, 2, 3))
    val m     = mount(
      VirtualList(items, rowHeight = 20)(identity)(i => span(s"Item ${i}"))(
        styleAttr += "height: 200px"
      )
    )
    settle().map { _ =>
      val two = m.getByText("Item 2")
      items := Seq(0, 1, 2, 3)
      rowTexts(m) shouldBe Seq("Item 0", "Item 1", "Item 2", "Item 3")
      (m.getByText("Item 2") eq two) shouldBe true
    }
  }

  test("keys move the highlight in a virtual list, scroll it into view, and select") {
    val selected = Rx.variable(Option.empty[Int])
    var chosen   = Seq.empty[Int]
    val m        = mount(
      VirtualList(Rx.const((1 to 100).toSeq), rowHeight = 20)(identity)(i => span(s"Item ${i}"))
        .withSelected(selected)
        .onSelect(i => chosen :+= i)(styleAttr += "height: 200px")
    )
    val list = m.get(".uni-virtual-list").asInstanceOf[dom.HTMLElement]
    settle().map { _ =>
      list.focus()
      activeRow(list).map(_.textContent) shouldBe Some("Item 1")
      fireEvent.keyDown(list, "ArrowDown")
      activeRow(list).map(_.textContent) shouldBe Some("Item 2")
      fireEvent.keyDown(list, "End")
      activeRow(list).map(_.textContent) shouldBe Some("Item 100")
      activeRow(list).map(_.getAttribute("data-active")) shouldBe Some("true")
      (list.scrollTop >= 1800) shouldBe true
      fireEvent.keyDown(list, "Enter")
      selected.get shouldBe Some(100)
      chosen shouldBe Seq(100)
      activeRow(list).map(_.getAttribute("aria-selected")) shouldBe Some("true")
      fireEvent.click(m.getByText("Item 99"))
      selected.get shouldBe Some(99)
    }
  }

  test("a virtual list leaves keys from controls inside its rows alone") {
    var chosen = 0
    val m      = mount(
      VirtualList(Rx.const(Seq(1, 2)), rowHeight = 24)(identity)(i => htmlButton(s"Edit ${i}"))
        .onSelect(_ => chosen += 1)
    )
    settle().map { _ =>
      fireEvent.keyDown(m.getByText("Edit 1"), "Enter")
      chosen shouldBe 0
    }
  }

  private case class FileNode(name: String, kids: Seq[FileNode] = Seq.empty)

  private val files = Seq(
    FileNode("src", Seq(FileNode("Main.scala"), FileNode("Util.scala"))),
    FileNode("build.sbt")
  )

  test("a tree opens and closes rows with the keyboard and keeps ARIA in step") {
    val expanded = Rx.variable(Set.empty[String])
    val m        = mount(
      Tree(Rx.const(files), (f: FileNode) => f.kids)(_.name)(f => span(f.name))
        .withLabel("Files")
        .withExpanded(expanded)
    )
    val tree = m.get(".uni-tree").asInstanceOf[dom.HTMLElement]
    settle().map { _ =>
      tree.getAttribute("role") shouldBe "tree"
      rowTexts(m) shouldBe Seq("src", "build.sbt")
      val src = m.get(".uni-list__row")
      src.getAttribute("role") shouldBe "treeitem"
      src.getAttribute("aria-level") shouldBe "1"
      src.getAttribute("aria-expanded") shouldBe "false"
      m.queryAll(".uni-list__row")(1).hasAttribute("aria-expanded") shouldBe false

      tree.focus()
      fireEvent.keyDown(tree, "ArrowRight")
      expanded.get shouldBe Set("src")
      rowTexts(m) shouldBe Seq("src", "Main.scala", "Util.scala", "build.sbt")
      m.get(".uni-list__row").getAttribute("aria-expanded") shouldBe "true"
      m.queryAll(".uni-list__row")(1).getAttribute("aria-level") shouldBe "2"

      fireEvent.keyDown(tree, "ArrowRight")
      activeRow(tree).map(_.textContent) shouldBe Some("Main.scala")
      fireEvent.keyDown(tree, "ArrowLeft")
      activeRow(tree).map(_.textContent) shouldBe Some("src")
      fireEvent.keyDown(tree, "ArrowLeft")
      expanded.get shouldBe Set.empty
      rowTexts(m) shouldBe Seq("src", "build.sbt")
    }
  }

  test("a click on a tree's chevron toggles the row without selecting it") {
    val selected = Rx.variable(Option.empty[String])
    val m        = mount(
      Tree(Rx.const(files), (f: FileNode) => f.kids)(_.name)(f => span(f.name))
        .withSelected(selected)
        .withTypeahead(_.name)
    )
    val tree = m.get(".uni-tree").asInstanceOf[dom.HTMLElement]
    settle().map { _ =>
      fireEvent.click(m.get(".uni-tree__chevron"))
      rowTexts(m).size shouldBe 4
      selected.get shouldBe None
      fireEvent.click(m.getByText("Util.scala"))
      selected.get shouldBe Some("Util.scala")
      fireEvent.dblclick(m.get(".uni-list__row"))
      rowTexts(m) shouldBe Seq("src", "build.sbt")
      tree.focus()
      fireEvent.keyDown(tree, "b")
      activeRow(tree).map(_.textContent) shouldBe Some("build.sbt")
    }
  }

  private case class Call(id: Int, method: String, millis: Double)

  private val calls = Seq(Call(1, "list", 4.0), Call(2, "send", 12.5), Call(3, "get", 1.5))

  test("a data table sorts by its headers and labels rows and columns") {
    val sort = Rx.variable(Option.empty[SortBy])
    val m    = mount(
      DataTable(Rx.const(calls))(_.id)(
        Column[Call]("method", "Method")(c => span(c.method)).sortBy(_.method),
        Column[Call]("millis", "Time")(c => span(f"${c.millis}%.1f")).sortBy(_.millis).alignEnd,
        Column[Call]("id", "Id")(c => span(c.id.toString))
      ).withLabel("Calls").withSort(sort)
    )
    def methods = m.queryAll(".uni-list__row").map(_.querySelector(".uni-table__cell").textContent)
    settle().map { _ =>
      val table = m.get(".uni-table")
      table.getAttribute("role") shouldBe "grid"
      table.getAttribute("aria-rowcount") shouldBe "4"
      table.getAttribute("aria-colcount") shouldBe "3"
      methods shouldBe Seq("list", "send", "get")
      m.get(".uni-list__row").getAttribute("aria-rowindex") shouldBe "2"

      val headers = m.queryAll("[role=columnheader]")
      headers.map(_.getAttribute("aria-sort")) shouldBe Seq("none", "none", null)
      // The unsortable column has no button
      headers(2).querySelector("button") shouldBe null

      fireEvent.click(m.getByText("Time"))
      sort.get shouldBe Some(SortBy("millis", SortDirection.Ascending))
      methods shouldBe Seq("get", "list", "send")
      headers(1).getAttribute("aria-sort") shouldBe "ascending"
      fireEvent.click(m.getByText("Time"))
      methods shouldBe Seq("send", "list", "get")
      headers(1).getAttribute("aria-sort") shouldBe "descending"
      fireEvent.click(m.getByText("Time"))
      sort.get shouldBe None
      methods shouldBe Seq("list", "send", "get")
      m.queryAll(".uni-table__cell--end").size shouldBe 4
    }
  }

  test("a data table selects rows with the keyboard") {
    var chosen = Option.empty[Call]
    val m      = mount(
      DataTable(Rx.const(calls))(_.id)(Column[Call]("method", "Method")(c => span(c.method)))
        .onSelect(c => chosen = Some(c))
    )
    val table = m.get(".uni-table").asInstanceOf[dom.HTMLElement]
    settle().map { _ =>
      table.focus()
      fireEvent.keyDown(table, "ArrowDown")
      fireEvent.keyDown(table, "Enter")
      chosen.map(_.method) shouldBe Some("send")
    }
  }

  test("a data table moves by cell and names the active cell") {
    val m = mount(
      DataTable(Rx.const(calls))(_.id)(
        Column[Call]("method", "Method")(c => span(c.method)),
        Column[Call]("millis", "Time")(c => span(f"${c.millis}%.1f")),
        Column[Call]("id", "Id")(c => span(c.id.toString))
      )
    )
    val table  = m.get(".uni-table").asInstanceOf[dom.HTMLElement]
    def cell   = activeRow(table).map(_.textContent)
    def marked = m.queryAll(".uni-table__cell[data-active='true']").map(_.textContent)
    settle().map { _ =>
      table.focus()
      cell shouldBe Some("list")
      activeRow(table).map(_.getAttribute("role")) shouldBe Some("gridcell")
      marked shouldBe Seq("list")

      fireEvent.keyDown(table, "ArrowRight")
      cell shouldBe Some("4.0")
      // Up and Down keep the column
      fireEvent.keyDown(table, "ArrowDown")
      cell shouldBe Some("12.5")
      fireEvent.keyDown(table, "End")
      cell shouldBe Some("2")
      // Right stops at the end of the row
      fireEvent.keyDown(table, "ArrowRight")
      cell shouldBe Some("2")
      fireEvent.keyDown(table, "Home")
      cell shouldBe Some("send")
      fireEvent.keyDown(table, "ArrowLeft")
      cell shouldBe Some("send")
      // Ctrl+End goes to the last cell of the last row, Meta+Home to the first of the first
      fireEvent.keyDown(table, "End", ctrlKey = true)
      cell shouldBe Some("3")
      fireEvent.keyDown(table, "Home", metaKey = true)
      cell shouldBe Some("list")
      marked shouldBe Seq("list")

      // A click makes the cell active and selects its row
      fireEvent.click(m.getByText("1.5"))
      cell shouldBe Some("1.5")
      m.queryAll(".uni-list__row[aria-selected='true']").map(_.textContent.take(3)) shouldBe
        Seq("get")
    }
  }

  test("a data table scrolls a wide row to the active cell and leaves modified arrows alone") {
    val m = mount(
      DataTable(Rx.const(calls))(_.id)(
        (1 to 6).map(n =>
          Column[Call](s"c${n}", s"C${n}")(c => span(s"${c.id}-${n}")).withWidth("200px")
        )*
      )(styleAttr += "width: 300px; height: 200px")
    )
    val table = m.get(".uni-table").asInstanceOf[dom.HTMLElement]
    def cell  = activeRow(table).map(_.textContent)
    settle().map { _ =>
      table.focus()
      fireEvent.keyDown(table, "End")
      cell shouldBe Some("1-6")
      (table.scrollLeft > 0) shouldBe true
      fireEvent.keyDown(table, "Home")
      table.scrollLeft shouldBe 0

      // Alt+Arrow and Ctrl or ⌘ with Left or Right belong to the app or the browser
      fireEvent.keyDown(table, "ArrowRight", altKey = true) shouldBe true
      fireEvent.keyDown(table, "ArrowRight", metaKey = true) shouldBe true
      fireEvent.keyDown(table, "ArrowRight", shiftKey = true) shouldBe true
      cell shouldBe Some("1-1")
      // Shift+End still moves along the row, not to the last row
      fireEvent.keyDown(table, "ArrowDown")
      fireEvent.keyDown(table, "End", shiftKey = true)
      cell shouldBe Some("2-6")
    }
  }

  test("nextColumn moves along a row and stops at its ends") {
    DataTable.nextColumn("ArrowRight", 0, 3) shouldBe Some(1)
    DataTable.nextColumn("ArrowRight", 2, 3) shouldBe Some(2)
    DataTable.nextColumn("ArrowLeft", 0, 3) shouldBe Some(0)
    DataTable.nextColumn("End", 0, 3) shouldBe Some(2)
    DataTable.nextColumn("Home", 2, 3) shouldBe Some(0)
    DataTable.nextColumn("ArrowDown", 1, 3) shouldBe None
    DataTable.nextColumn("ArrowRight", 0, 0) shouldBe None
  }

  test("sort helpers cycle the direction and leave unknown columns unsorted") {
    DataTable.nextSort(None, "a") shouldBe Some(SortBy("a"))
    DataTable.nextSort(Some(SortBy("a")), "a") shouldBe Some(SortBy("a", SortDirection.Descending))
    DataTable.nextSort(Some(SortBy("a", SortDirection.Descending)), "a") shouldBe None
    DataTable.nextSort(Some(SortBy("a", SortDirection.Descending)), "b") shouldBe Some(SortBy("b"))
    val cols = Seq(Column[Call]("millis", "Time")(c => span("")).sortBy(_.millis))
    DataTable.sortRows(calls, cols, Some(SortBy("nope"))) shouldBe calls
  }

  private def commands(log: RxVar[Seq[String]]): Seq[Command] = Seq(
    Command("new", "New chat").withGroup("Chat").onRun(() => log.update(_ :+ "new")),
    Command("dock", "Toggle dock")
      .withGroup("View")
      .withShortcut("⌘⇧B")
      .onRun(() => log.update(_ :+ "dock")),
    Command("theme", "Theme: Night")
      .withGroup("View")
      .withKeywords("dark")
      .onRun(() => log.update(_ :+ "theme"))
  )

  private def paletteInput: dom.HTMLInputElement = find(".uni-palette__input")
    .get
    .asInstanceOf[dom.HTMLInputElement]

  private def paletteItems: Seq[String] =
    dom.document.querySelectorAll(".uni-palette__item .uni-palette__label").map(_.textContent).toSeq

  test("a command palette lists commands by group, filters as you type, and runs one") {
    val open   = Rx.variable(false)
    val log    = Rx.variable(Seq.empty[String])
    val m      = mount(div(htmlButton("Opener"), CommandPalette(open, Rx.const(commands(log)))))
    val opener = m.getByText("Opener").asInstanceOf[dom.HTMLElement]
    opener.focus()
    open := true
    settle().map { _ =>
      (active eq paletteInput) shouldBe true
      find(".uni-dialog--palette").map(_.getAttribute("aria-label")) shouldBe
        Some("Command palette")
      paletteInput.getAttribute("role") shouldBe "combobox"
      paletteItems shouldBe Seq("New chat", "Toggle dock", "Theme: Night")
      dom.document.querySelectorAll(".uni-palette__group").map(_.textContent).toSeq shouldBe
        Seq("Chat", "View")
      // The first result is highlighted
      Option(paletteInput.getAttribute("aria-activedescendant"))
        .flatMap(id => Option(dom.document.getElementById(id)))
        .map(_.textContent) shouldBe Some("New chat")

      fireEvent.input(paletteInput, "tgd")
      paletteItems shouldBe Seq("Toggle dock")
      find(".uni-palette__group") shouldBe None
      dom.document.querySelectorAll(".uni-palette__match").map(_.textContent).toSeq shouldBe
        Seq("T", "g", "d")

      fireEvent.keyDown(paletteInput, "Enter")
      log.get shouldBe Seq("dock")
      open.get shouldBe false
      find(".uni-palette") shouldBe None
      (active eq opener) shouldBe true
    }
  }

  test("a command palette matches keywords, shows an empty state, and starts empty again") {
    val open = Rx.variable(true)
    val log  = Rx.variable(Seq.empty[String])
    mount(CommandPalette(open, Rx.const(commands(log))).withEmptyText("Nothing here"))
    settle().flatMap { _ =>
      fireEvent.input(paletteInput, "dark")
      paletteItems shouldBe Seq("Theme: Night")
      fireEvent.input(paletteInput, "zzz")
      paletteItems shouldBe Seq.empty
      find(".uni-palette__empty").map(_.textContent) shouldBe Some("Nothing here")
      fireEvent.keyDown(paletteInput, "Enter")
      log.get shouldBe Seq.empty
      fireEvent.keyDown(paletteInput, "Escape")
      open.get shouldBe false
      open := true
      settle().map { _ =>
        paletteInput.value shouldBe ""
        paletteItems.size shouldBe 3
      }
    }
  }

  test("arrow keys in a command palette wrap, and a click runs a command") {
    val open = Rx.variable(true)
    val log  = Rx.variable(Seq.empty[String])
    mount(CommandPalette(open, Rx.const(commands(log))))
    def highlighted = Option(paletteInput.getAttribute("aria-activedescendant"))
      .flatMap(id => Option(dom.document.getElementById(id)))
      .map(_.querySelector(".uni-palette__label").textContent)
    settle().map { _ =>
      fireEvent.keyDown(paletteInput, "ArrowUp")
      highlighted shouldBe Some("Theme: Night")
      fireEvent.keyDown(paletteInput, "ArrowDown")
      highlighted shouldBe Some("New chat")
      fireEvent.click(dom.document.querySelectorAll(".uni-palette__item")(2))
      log.get shouldBe Seq("theme")
      open.get shouldBe false
      // Reopened, the palette highlights the first command, not the one run last
      open := true
      highlighted shouldBe Some("New chat")
    }
  }

end CollectionTest
