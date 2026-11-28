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

  // #region data-table/results
  case class Sale(
      region: String,
      product: String,
      channel: String,
      units: Long,
      revenue: Double,
      discount: Double,
      day: String,
      store: Int
  )

  inline def dataTableResults: RxElement =
    // A query result of 1,248 rows or more: the table shows 3 of them and a footer for the rest.
    // "Run again" gives a new result, which starts folded.
    val products         = Seq(("Notebook", 15.0), ("Pen", 0.5), ("Backpack", 50.0), ("Lamp", 32.0))
    def result(run: Int) = (0 until 1248 + run * 7).map { i =>
      val (product, price) = products(i % products.size)
      val units            = 100L + (i * 7919L) % 9000
      val discount         = (i                 % 4) * 0.05
      Sale(
        Seq("APAC", "EMEA", "AMER")(i % 3),
        product,
        Seq("online", "retail", "partner")(i % 3 / 2 + i % 2),
        units,
        units * price * (1 - discount),
        discount,
        f"2026-11-${i % 28 + 1}%02d",
        i % 16 + 1
      )
    }
    val run    = Rx.variable(0)
    val sales  = run.map(result)
    val opened = Rx.variable("")

    // A text column and a number column, each as wide as its values
    def text(id: String, width: String, f: Sale => String) = Column[Sale](
      id,
      id,
      cell = s => span(f(s))
    ).withSubtitle("varchar").sortBy(f).withWidth(width)

    def number[N: Ordering](
        id: String,
        tpe: String,
        width: String,
        f: Sale => N,
        show: N => String
    ) =
      Column[Sale](id, id, cell = s => span(show(f(s))))
        .withSubtitle(tpe)
        .sortBy(f)
        .withWidth(width)
        .alignEnd

    div(
      cls -> "demo-stack",
      // The area is as wide as the table, up to the page, and scrolls sideways past that: a wheel
      // or trackpad swipe over the table reaches it, and a thumb shows on hover without taking
      // room below the last row
      ScrollArea(
        DataTable(
          sales,
          key = s => (s.region, s.product, s.day, s.store, s.units),
          columns = Seq(
            text("region", "80px", _.region),
            text("product", "96px", _.product),
            text("channel", "88px", _.channel),
            number("units", "bigint", "72px", _.units, _.toString),
            number("revenue", "double", "96px", _.revenue, r => f"${r}%.2f"),
            number("discount", "double", "88px", _.discount, d => f"${d}%.2f"),
            Column[Sale]("day", "day", cell = s => span(s.day))
              .withSubtitle("date")
              .withWidth("104px"),
            number("store", "int", "64px", _.store, _.toString)
          )
        ).withLabel("Query results")
          .noSelection()
          .withCenteredHeaders()
          // The first 3 rows and "+1,245 more rows"; all of them scroll in a box 4 rows tall
          .withPreviewRows(3)
          .withAutoHeight(maxRows = 4)
          .onSelect(s => opened := s"${s.region} ${s.product} on ${s.day}")(
            // As wide as its columns
            styleAttr += "width: max-content"
          )
      )(styleAttr += "border: 1px solid var(--uni-line); border-radius: 6px"),
      div(
        cls -> "demo-row",
        Button("Run again").small.onClick(() => run.update(_ + 1)),
        p(
          cls -> "demo-note",
          opened.map(o =>
            if o.isEmpty then
              "Click a row: it opens without staying selected"
            else
              s"Opened ${o}"
          )
        )
      )
    )
  end dataTableResults
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

  // #region tree/controlled
  case class Topic(id: String, title: String, children: Seq[Topic] = Seq.empty)

  inline def treeControlled: RxElement =
    val topics = Seq(
      Topic("ui", "UI", Seq(Topic("ui/controls", "Controls"), Topic("ui/overlays", "Overlays"))),
      Topic("data", "Data", Seq(Topic("data/json", "JSON"), Topic("data/parquet", "Parquet"))),
      Topic("net", "Networking", Seq(Topic("net/http", "HTTP"), Topic("net/rpc", "RPC")))
    )
    val expanded = Rx.variable(Set.empty[String])

    div(
      cls -> "demo-stack",
      div(
        cls -> "demo-row",
        Button("Expand all").ghost.small.onClick(() => expanded := topics.map(_.id).toSet),
        Button("Collapse all").ghost.small.onClick(() => expanded := Set.empty),
        span(cls -> "demo-note", expanded.map(e => s"${e.size} open"))
      ),
      Tree(Rx.const(topics), children = _.children, key = _.id, render = t => span(t.title))
        .withLabel("Topics")
        .withExpanded(expanded)(styleAttr += "height: 200px")
    )
  end treeControlled
  // #endregion

  // #region tree/multi-select
  case class Item(id: String, name: String, children: Seq[Item] = Seq.empty)

  inline def treeMultiSelect: RxElement =
    val items = Seq(
      Item(
        "img",
        "images",
        Seq(Item("img/a.png", "a.png"), Item("img/b.png", "b.png"), Item("img/c.png", "c.png"))
      ),
      Item("doc", "docs", Seq(Item("doc/intro.md", "intro.md"), Item("doc/api.md", "api.md")))
    )
    val expanded = Rx.variable(Set("img", "doc"))
    val picked   = Rx.variable(Set.empty[String])

    div(
      cls -> "demo-stack",
      Tree(Rx.const(items), children = _.children, key = _.id, render = i => span(i.name))
        .withLabel("Files")
        .withExpanded(expanded)
        .withMultiSelect(picked)(styleAttr += "height: 220px"),
      p(
        cls -> "demo-note",
        picked.map(p =>
          if p.isEmpty then
            "Nothing selected"
          else
            p.toSeq.sorted.mkString(", ")
        )
      )
    )
  end treeMultiSelect
  // #endregion

  // #region data-table/cell
  case class Person(id: Int, name: String, role: String, team: String)

  inline def dataTableCell: RxElement =
    val people = Rx.const(
      Seq(
        Person(1, "Ada", "Admin", "Core"),
        Person(2, "Bob", "Viewer", "Docs"),
        Person(3, "Cy", "Editor", "Core")
      )
    )
    val cell = Rx.variable(Option.empty[CellKey[Int]])

    div(
      cls -> "demo-stack",
      DataTable(
        people,
        key = _.id,
        columns = Seq(
          Column[Person]("name", "Name", cell = p => span(p.name)),
          Column[Person]("role", "Role", cell = p => span(p.role)),
          Column[Person]("team", "Team", cell = p => span(p.team))
        )
      ).withLabel("People")
        .withSelectedCell(cell)
        .withRowLabel(_.name)(styleAttr += "height: 160px"),
      p(
        cls -> "demo-note",
        cell.map(c => c.map(k => s"Row ${k.row} · ${k.column}").getOrElse("Press Enter on a cell"))
      )
    )
  end dataTableCell
  // #endregion

  // #region data-table/widgets
  case class Job(id: Int, name: String, status: String)

  inline def dataTableWidgets: RxElement =
    val jobs = Rx.variable(
      Seq(Job(1, "build", "failed"), Job(2, "test", "passed"), Job(3, "deploy", "failed"))
    )

    def retry(j: Job): Unit = jobs.update(
      _.map(x =>
        if x.id == j.id then
          x.copy(status = "queued")
        else
          x
      )
    )

    DataTable(
      jobs,
      key = _.id,
      columns = Seq(
        Column[Job]("name", "Job", cell = j => span(j.name)),
        Column[Job]("status", "Status", cell = j => span(j.status)),
        Column[Job](
          "action",
          "",
          cell =
            j =>
              Button("Retry").ghost.small.withDisabled(j.status != "failed").onClick(() => retry(j))
        ).alignEnd
      )
    ).withLabel("Jobs").withRowLabel(_.name)(styleAttr += "height: 170px")
  end dataTableWidgets
  // #endregion

  // #region data-table/reorder
  case class Step(id: Int, name: String, minutes: Int)

  inline def dataTableReorder: RxElement =
    val steps = Rx.variable(
      Seq(Step(1, "Fetch", 2), Step(2, "Compile", 5), Step(3, "Test", 8), Step(4, "Package", 1))
    )

    div(
      cls -> "demo-stack",
      DataTable(
        steps,
        key = _.id,
        columns = Seq(
          Column[Step]("grip", "", cell = _ => Reorder.grip).withWidth("32px"),
          Column[Step]("name", "Step", cell = s => span(s.name)),
          Column[Step]("minutes", "Minutes", cell = s => span(s.minutes.toString)).alignEnd
        )
      ).withLabel("Pipeline").withReorder(steps).withRowLabel(_.name)(styleAttr += "height: 190px"),
      p(cls -> "demo-note", steps.map(ss => ss.map(_.name).mkString(" → ")))
    )
  end dataTableReorder
  // #endregion

  // #region virtual-list/large
  case class Row(id: Int, label: String)

  inline def virtualListLarge: RxElement =
    val rows     = Rx.const((1 to 10000).map(i => Row(i, f"Item ${i}%05d")))
    val selected = Rx.variable(Option.empty[Int])

    div(
      cls -> "demo-stack demo-narrow",
      VirtualList(rows, rowHeight = 28, key = _.id, render = r => span(r.label))
        .withLabel("Ten thousand rows")
        .withSelected(selected)
        .withTypeahead(_.label)(styleAttr += "height: 220px"),
      p(
        cls -> "demo-note",
        selected.map(s =>
          s.map(i => s"Selected item ${i}").getOrElse("Only the rows in view are rendered")
        )
      )
    )
  end virtualListLarge
  // #endregion

  // #region virtual-list/filter
  case class Fruit(id: Int, name: String)

  inline def virtualListFilter: RxElement =
    val all = Seq(
      "Apple",
      "Apricot",
      "Banana",
      "Blueberry",
      "Cherry",
      "Grape",
      "Lemon",
      "Mango",
      "Orange",
      "Peach"
    ).zipWithIndex.map((n, i) => Fruit(i, n))
    val query  = Rx.variable("")
    val shown  = query.map(q => all.filter(_.name.toLowerCase.contains(q.toLowerCase)))
    val picked = Rx.variable(Set.empty[Int])

    div(
      cls -> "demo-stack demo-narrow",
      Input(query).withLabel("Filter").withPlaceholder("Type to filter"),
      VirtualList(shown, rowHeight = 28, key = _.id, render = f => span(f.name))
        .withLabel("Fruit")
        .withMultiSelect(picked)
        .withTypeahead(_.name)(styleAttr += "height: 170px"),
      p(cls -> "demo-note", picked.map(p => s"${p.size} picked (kept when filtered out)"))
    )
  end virtualListFilter
  // #endregion

  // #region command-palette/basic
  inline def commandPaletteBasic: RxElement =
    val open     = Rx.variable(false)
    val lastRun  = Rx.variable("Nothing yet")
    val commands = Seq(
      Command("new", "New file")
        .withGroup("File")
        .withShortcut("⌘N")
        .onRun(() => lastRun := "New file"),
      Command("open", "Open…").withGroup("File").withShortcut("⌘O").onRun(() => lastRun := "Open…"),
      Command("theme", "Toggle theme")
        .withGroup("View")
        .withKeywords("dark", "light")
        .onRun(() => lastRun := "Toggle theme"),
      Command("zoom", "Zoom in")
        .withGroup("View")
        .withHint("Ctrl and +")
        .onRun(() => lastRun := "Zoom in")
    )

    div(
      cls -> "demo-row",
      Button("Open the palette").primary.onClick(() => open := true),
      span(cls -> "demo-note", lastRun.map(r => s"Ran: ${r}")),
      CommandPalette(open, Rx.const(commands)).withPlaceholder("Type a command")
    )
  end commandPaletteBasic
  // #endregion

  // #region command-palette/dynamic
  inline def commandPaletteDynamic: RxElement =
    val open     = Rx.variable(false)
    val docs     = Rx.variable(Seq("Roadmap", "Notes"))
    val opened   = Rx.variable("")
    val commands = docs.map(ds =>
      Command("new-doc", "New document")
        .withGroup("Actions")
        .onRun(() => docs.update(xs => xs :+ s"Untitled ${xs.size + 1}")) +:
        ds.map(d => Command(s"doc-${d}", d).withGroup("Documents").onRun(() => opened := d))
    )

    div(
      cls -> "demo-row",
      Button("Search documents").onClick(() => open := true),
      span(
        cls -> "demo-note",
        Rx.join(docs, opened)
          .map((ds, o) =>
            s"${ds.size} documents${
                if o.nonEmpty then
                  s" · opened ${o}"
                else
                  ""
              }"
          )
      ),
      CommandPalette(open, commands).withPlaceholder("Search documents or run New document")
    )
  end commandPaletteDynamic
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
        )(tree),
        Story(
          "tree/controlled",
          "Open rows from code",
          "Press Expand all and Collapse all: the tree follows the RxVar."
        )(treeControlled),
        Story(
          "tree/multi-select",
          "Multi-select",
          "Shift-click a range, ⌘-click to add, Ctrl+A for all, Escape to clear."
        )(treeMultiSelect)
      ),
      ApiDoc.of[Tree[?, ?]],
      Seq(A11yCheck(AriaPattern.Tree, "[role=tree]"))
    ),
    ComponentPage(
      "data-table",
      "Data table",
      "A sortable grid with a sticky header, cell navigation, widgets in cells, multi-select, reordering, and a read-only mode for results.",
      Seq(
        Story(
          "data-table/basic",
          "Sort and select",
          "Press a header to sort; Shift or ⌘ click to select rows."
        )(dataTable),
        Story(
          "data-table/cell",
          "Cell selection",
          "Move with the arrow keys; Enter selects a cell instead of a row."
        )(dataTableCell),
        Story(
          "data-table/widgets",
          "Buttons in cells",
          "Move to a Retry cell and press Enter to reach its button."
        )(dataTableWidgets),
        Story(
          "data-table/reorder",
          "Reorder rows",
          "Drag a grip, or press Alt+ArrowUp or Alt+ArrowDown on a row."
        )(dataTableReorder),
        Story(
          "data-table/results",
          "Read-only results",
          "Column types under each header, rows that a click doesn't select, the first rows with a \"+N more rows\" footer, a box as tall as its rows, and a sideways scroll over the table that moves its panel."
        )(dataTableResults)
      ),
      ApiDoc.of[DataTable[?, ?]] ++ ApiDoc.of[Column[?]] ++ ApiDoc.of[ScrollArea],
      Seq(A11yCheck(AriaPattern.Grid, "[role=grid]"))
    ),
    ComponentPage(
      "virtual-list",
      "Virtual list",
      "A long list that renders only the rows in view, with selection, typeahead, and reordering.",
      Seq(
        Story(
          "virtual-list/large",
          "Ten thousand rows",
          "Scroll, or press End: only the rows in view render."
        )(virtualListLarge),
        Story(
          "virtual-list/filter",
          "Filter and multi-select",
          "Pick a few, then filter: the selection stays."
        )(virtualListFilter),
        Story(
          "virtual-list/reorder",
          "Reorder",
          "Drag a row, or select one and press Alt+ArrowUp or Alt+ArrowDown."
        )(reorder)
      ),
      ApiDoc.of[VirtualList[?, ?]],
      Seq(A11yCheck(AriaPattern.Listbox, "[role=listbox]"))
    ),
    ComponentPage(
      "command-palette",
      "Command palette",
      "A search box over commands, with groups, fuzzy matching, and commands from an Rx.",
      Seq(
        Story(
          "command-palette/basic",
          "Basic",
          "Open it and type: the best matches come first, with the letters marked."
        )(commandPaletteBasic),
        Story(
          "command-palette/dynamic",
          "Commands from an Rx",
          "Run New document, then open the palette again."
        )(commandPaletteDynamic)
      ),
      ApiDoc.of[CommandPalette] ++ ApiDoc.of[Command],
      Seq(
        A11yCheck(
          AriaPattern.Combobox,
          "[role=combobox]",
          "Open the palette: the check runs while it's open."
        ),
        A11yCheck(
          AriaPattern.Dialog,
          ".uni-dialog--palette",
          "Open the palette: the check runs while it's open."
        )
      ),
      a11yNote =
        "uni-ui doesn't bind ⌘K: bind it with Keyboard.bindAll, and give agents a button that opens the palette."
    )
  )

end CollectionStories
