package example.gallery.stories

import example.gallery.{A11yCheck, ApiDoc, ComponentPage, Story}
import wvlet.uni.dom.testing.AriaPattern
import wvlet.uni.dom.all.*
import wvlet.uni.dom.all.given
import wvlet.uni.rx.Rx
import wvlet.uni.ui.*
import wvlet.uni.ui.base.Reorder

import scala.language.implicitConversions

object CollectionStories:

  // #region tree/basic
  case class FileNode(path: String, name: String, children: Seq[FileNode] = Seq.empty)

  inline def tree: RxElement =
    val roots = Seq(
      FileNode(
        "src",
        "src",
        Seq(
          FileNode("src/main", "main", Seq(FileNode("src/main/App.scala", "App.scala"))),
          FileNode("src/test", "test", Seq(FileNode("src/test/AppTest.scala", "AppTest.scala")))
        )
      ),
      FileNode("build.sbt", "build.sbt")
    )
    val expanded = Rx.variable(Set("src", "src/main"))
    val opened   = Rx.variable("")

    div(
      cls -> "demo-stack",
      Tree(Rx.const(roots), children = _.children, key = _.path, render = f => span(f.name))
        .withLabel("Files")
        .withExpanded(expanded)
        .withTypeahead(_.name)
        .onSelect(f => opened := f.path)(styleAttr += "height: 200px"),
      p(
        cls -> "demo-note",
        opened.map(p =>
          if p.isEmpty then
            "Press Enter on a row"
          else
            s"Opened ${p}"
        )
      )
    )
  end tree
  // #endregion

  // #region data-table/basic
  case class Call(id: Long, method: String, status: Int, millis: Double)

  inline def dataTable: RxElement =
    val calls = Rx.variable(
      Seq(
        Call(1, "getUser", 200, 3.2),
        Call(2, "listOrders", 200, 18.4),
        Call(3, "createOrder", 201, 42.0),
        Call(4, "getUser", 404, 1.1),
        Call(5, "deleteOrder", 500, 7.9)
      )
    )
    val sort   = Rx.variable(Option(SortBy("millis", SortDirection.Descending)))
    val picked = Rx.variable(Set.empty[Long])

    div(
      cls -> "demo-stack",
      DataTable(
        calls,
        key = _.id,
        columns = Seq(
          Column[Call]("method", "Method", cell = c => span(c.method)).sortBy(_.method),
          Column[Call]("status", "Status", cell = c => span(c.status.toString)).sortBy(_.status),
          Column[Call]("millis", "Time", cell = c => span(f"${c.millis}%.1f ms"))
            .sortBy(_.millis)
            .alignEnd
        )
      ).withLabel("RPC calls").withSort(sort).withMultiSelect(picked)(styleAttr += "height: 220px"),
      p(cls -> "demo-note", picked.map(s => s"${s.size} selected (Shift or ⌘ click for more)"))
    )
  end dataTable
  // #endregion

  // #region virtual-list/reorder
  case class Task(id: Int, title: String)

  inline def reorder: RxElement =
    val tasks = Rx.variable(
      Seq(Task(1, "Write"), Task(2, "Review"), Task(3, "Test"), Task(4, "Ship"))
    )
    val picked = Rx.variable(Set.empty[Int])

    div(
      cls -> "demo-stack demo-narrow",
      VirtualList(
        tasks,
        rowHeight = 32,
        key = _.id,
        render = t => div(cls -> "demo-task", Reorder.grip, span(t.title))
      ).withLabel("Tasks")
        .withTypeahead(_.title)
        .withMultiSelect(picked)
        .withReorder(tasks)(styleAttr += "height: 140px"),
      p(
        cls -> "demo-note",
        "Drag a row, or press Alt+↑ / Alt+↓",
        tasks.map(ts => s" · ${ts.map(_.title).mkString(" → ")}")
      )
    )
  // #endregion

  val pages: Seq[ComponentPage] = Seq(
    ComponentPage(
      "tree",
      "Tree",
      "An expandable tree over your own data, rendering only the rows in view.",
      Seq(
        Story(
          "tree/basic",
          "Basic",
          "Arrow keys move; Right opens a row, Left closes it; Enter opens a file."
        )(tree)
      ),
      ApiDoc.of[Tree[?, ?]],
      Seq(A11yCheck(AriaPattern.Tree, "[role=tree]"))
    ),
    ComponentPage(
      "data-table",
      "Data table",
      "A sortable grid with a sticky header, cell navigation, and multi-select.",
      Seq(
        Story(
          "data-table/basic",
          "Sort and select",
          "Press a header to sort; Shift or ⌘ click to select rows."
        )(dataTable)
      ),
      ApiDoc.of[DataTable[?, ?]] ++ ApiDoc.of[Column[?]],
      Seq(A11yCheck(AriaPattern.Grid, "[role=grid]"))
    ),
    ComponentPage(
      "virtual-list",
      "Virtual list",
      "A long list that renders only the rows in view, with selection, typeahead, and reordering.",
      Seq(
        Story(
          "virtual-list/reorder",
          "Reorder",
          "Drag a row, or select one and press Alt+ArrowUp or Alt+ArrowDown."
        )(reorder)
      ),
      ApiDoc.of[VirtualList[?, ?]],
      Seq(A11yCheck(AriaPattern.Listbox, "[role=listbox]"))
    )
  )

end CollectionStories
