package example.gallery.stories

import example.gallery.{A11yCheck, ApiDoc, ComponentPage, Story}
import wvlet.uni.dom.testing.AriaPattern
import wvlet.uni.dom.SizeRange
import wvlet.uni.dom.all.*
import wvlet.uni.dom.all.given
import wvlet.uni.rx.Rx
import wvlet.uni.ui.*

import scala.language.implicitConversions

object LayoutStories:

  // #region dock/basic
  inline def dock: RxElement =
    val layout = Rx.variable(
      DockLayout(
        DockNode.row(DockNode.group("files", "search"), DockNode.group("editor", "preview"))
      )
    )

    div(
      cls -> "demo-dock",
      Dock(
        layout,
        label = "Workspace",
        panels = Seq(
          DockPanel(
            "files",
            title = "Files",
            content = div(cls -> "demo-panel", "Drag my tab onto another group")
          ),
          DockPanel("search", title = "Search", content = div(cls -> "demo-panel", "Search panel")),
          DockPanel(
            "editor",
            title = "Editor",
            content = div(cls -> "demo-panel", "Drop a tab on an edge to split")
          ).withClosable(false),
          DockPanel(
            "preview",
            title = "Preview",
            content = div(cls -> "demo-panel", "Right-click a tab for its menu")
          )
        )
      )
    )
  end dock
  // #endregion

  // #region foldable-pane/basic
  inline def foldablePane: RxElement =
    val open  = Rx.variable(true)
    val width = Rx.variable(200)
    val range = SizeRange(min = 140, default = 200, max = 320)

    div(
      cls -> "demo-stack",
      div(
        cls -> "demo-row",
        Button(
          open.map(o =>
            if o then
              "Fold sidebar"
            else
              "Unfold sidebar"
          )
        ).onClick(() => open.update(!_)),
        span(
          cls -> "demo-note",
          width.map(w => s"Width: ${w}px · drag the edge, or focus it and use ← →")
        )
      ),
      div(
        cls -> "demo-split",
        FoldablePane(
          open,
          width = width,
          edge = Edge.Right,
          content = div(cls -> "demo-panel", "Sidebar")
        ).withSplitter(Splitter(width, range, Edge.Right, "Resize sidebar")),
        div(cls -> "demo-panel demo-grow", "Main content")
      )
    )
  end foldablePane
  // #endregion

  // #region dock/json
  inline def dockJson: RxElement =
    val initial = DockLayout(DockNode.row(DockNode.group("left"), DockNode.group("right")))
    val layout  = Rx.variable(initial)

    div(
      cls -> "demo-stack",
      div(
        cls -> "demo-dock demo-dock--short",
        Dock(
          layout,
          label = "Saved layout",
          panels = Seq(
            DockPanel("left", title = "Left", content = div(cls -> "demo-panel", "Drag this tab")),
            DockPanel(
              "right",
              title = "Right",
              content = div(cls -> "demo-panel", "or the edge between")
            )
          )
        )
      ),
      div(cls -> "demo-row", Button("Reset").ghost.small.onClick(() => layout := initial)),
      // The layout is plain data: Storage.local("app.dock", initial) would save this JSON
      pre(cls -> "demo-json", layout.map(_.toJSON))
    )
  end dockJson
  // #endregion

  // #region dock/open
  inline def dockOpen: RxElement =
    val layout = Rx.variable(
      DockLayout(DockNode.row(DockNode.group("editor"), DockNode.group("problems")))
    )

    div(
      cls -> "demo-stack",
      div(
        cls -> "demo-row",
        Button("Open terminal").ghost.small.onClick(() => layout.update(_.open("terminal"))),
        Button("Close problems").ghost.small.onClick(() => layout.update(_.close("problems")))
      ),
      div(
        cls -> "demo-dock demo-dock--short",
        Dock(
          layout,
          label = "Panels",
          panels = Seq(
            DockPanel("editor", title = "Editor", content = div(cls -> "demo-panel", "Editor"))
              .withClosable(false),
            DockPanel(
              "problems",
              title = "Problems",
              content = div(cls -> "demo-panel", "No problems")
            ),
            DockPanel(
              "terminal",
              title = "Terminal",
              content = div(cls -> "demo-panel", "$ sbt test")
            )
          )
        )
      )
    )
  end dockOpen
  // #endregion

  // #region foldable-pane/two-sides
  inline def foldableTwoSides: RxElement =
    val leftOpen   = Rx.variable(true)
    val rightOpen  = Rx.variable(true)
    val leftWidth  = Rx.variable(160)
    val rightWidth = Rx.variable(160)
    val range      = SizeRange(min = 120, default = 160, max = 260)

    div(
      cls -> "demo-stack",
      div(
        cls -> "demo-row",
        Button("Toggle left").ghost.small.onClick(() => leftOpen.update(!_)),
        Button("Toggle right").ghost.small.onClick(() => rightOpen.update(!_))
      ),
      div(
        cls -> "demo-split",
        FoldablePane(
          leftOpen,
          width = leftWidth,
          edge = Edge.Right,
          content = div(cls -> "demo-panel", "Files")
        ).withSplitter(Splitter(leftWidth, range, Edge.Right, "Resize files")),
        div(cls -> "demo-panel demo-grow", "Editor"),
        FoldablePane(
          rightOpen,
          width = rightWidth,
          edge = Edge.Left,
          content = div(cls -> "demo-panel", "Outline")
        ).withSplitter(Splitter(rightWidth, range, Edge.Left, "Resize outline"))
      )
    )
  end foldableTwoSides
  // #endregion

  // #region status-bar/basic
  inline def statusBarBasic: RxElement =
    val errors = Rx.variable(0)

    div(
      cls -> "demo-stack",
      Button("Add an error").ghost.small.onClick(() => errors.update(_ + 1)),
      div(
        cls -> "demo-statusbar",
        StatusBar(
          span("main"),
          span("UTF-8"),
          StatusBar.spacer,
          Badge(errors.map(n => s"${n} errors")).withTone(BadgeTone.Danger),
          span("Ln 12, Col 4")
        )
      )
    )
  end statusBarBasic
  // #endregion

  // #region stack/toolbar
  inline def stackToolbar: RxElement =
    val justify = Rx.variable(Justify.Between)

    Stack(
      Stack
        .row(
          span(cls -> "demo-strong", "Projects"),
          Badge("12"),
          Button("New project").primary.small
        )
        .withGap(12)
        .withJustify(Justify.Between),
      justify.map(j =>
        Stack
          .row(Button("Cancel").ghost.small, Button("Save").primary.small)
          .withJustify(j)(cls += "demo-outline")
      ),
      Select(justify, options = Justify.values.toSeq, optionLabel = _.toString).withLabel("Justify")
    ).withGap(16)
  end stackToolbar
  // #endregion

  // #region stack/form
  inline def stackForm: RxElement =
    val name  = Rx.variable("")
    val email = Rx.variable("")

    Stack(
      Input(name).withLabel("Name"),
      Input(email).withLabel("Email"),
      Stack.row(Button("Back").ghost, Button("Continue").primary).withJustify(Justify.End)
    ).withGap(12)(cls += "demo-narrow")
  end stackForm
  // #endregion

  // #region grid/responsive
  inline def gridResponsive: RxElement =
    val minWidth = Rx.variable(160)
    val services = Seq("Auth", "Billing", "Search", "Storage", "Email", "Analytics", "Queue", "CDN")

    Stack(
      Stack.row(
        Button("Narrower").ghost.small.onClick(() => minWidth.update(w => math.max(100, w - 40))),
        Button("Wider").ghost.small.onClick(() => minWidth.update(w => math.min(320, w + 40))),
        span(
          cls -> "demo-note",
          minWidth.map(w => s"Columns at least ${w}px wide; resize the window too")
        )
      ),
      minWidth.map(w =>
        Grid(
          services.map(name =>
            div(cls -> "demo-card", span(cls -> "demo-strong", name), span("Healthy"))
          )*
        ).withMinColumnWidth(w).withGap(12)
      )
    ).withGap(14)
  end gridResponsive
  // #endregion

  // #region grid/dashboard
  inline def gridDashboard: RxElement = Grid(
    GridItem(
      div(
        cls -> "demo-card demo-tall",
        span(cls -> "demo-strong", "Traffic"),
        span("A chart spanning two columns")
      )
    ).withColumnSpan(2),
    div(cls -> "demo-card", span(cls -> "demo-strong", "Users"), span("1,284")),
    div(cls -> "demo-card", span(cls -> "demo-strong", "Errors"), span("3")),
    div(cls -> "demo-card", span(cls -> "demo-strong", "Latency"), span("42 ms")),
    div(cls -> "demo-card", span(cls -> "demo-strong", "Uptime"), span("99.98%")),
    GridItem(
      div(cls -> "demo-card", span(cls -> "demo-strong", "Deploys"), span("A full-width row"))
    ).withColumnSpan(0)
  ).withColumns(3).withGap(12)
  // #endregion

  // #region grid/align
  inline def gridAlign: RxElement =
    val align = Rx.variable(Alignment.Stretch)

    Stack(
      Select(align, options = Alignment.values.toSeq, optionLabel = _.toString).withLabel("Align"),
      align.map(a =>
        Grid(
          div(cls -> "demo-card", "Short"),
          div(cls -> "demo-card", "A longer card", br(), "with two lines"),
          div(cls -> "demo-card", "Short")
        ).withColumns(3).withAlign(a)
      )
    ).withGap(12)
  end gridAlign
  // #endregion

  // #region separator/basic
  inline def separatorBasic: RxElement = Stack(
    Stack.row(
      Button("Bold").ghost.small,
      Button("Italic").ghost.small,
      Separator().vertical,
      Button("Link").ghost.small
    ),
    Separator(),
    p(
      cls -> "demo-note",
      "A horizontal separator between sections, and a vertical one between toolbar groups."
    )
  ).withGap(12)
  // #endregion

  val pages: Seq[ComponentPage] = Seq(
    ComponentPage(
      "dock",
      "Dock",
      "Tab groups and splits that the user rearranges; the layout is plain data you can save.",
      Seq(
        Story(
          "dock/basic",
          "Rearrange",
          "Drag a tab onto another group or an edge; right-click a tab for its menu."
        )(dock),
        Story(
          "dock/json",
          "The layout is data",
          "Rearrange the panels: the JSON below follows. Reset puts it back."
        )(dockJson),
        Story("dock/open", "Open and close from code", "Press Open terminal and Close problems.")(
          dockOpen
        )
      ),
      ApiDoc.of[Dock] ++ ApiDoc.of[DockPanel] ++ ApiDoc.of[DockLayout],
      Seq(A11yCheck(AriaPattern.Tabs, "[role=tablist]"))
    ),
    ComponentPage(
      "foldable-pane",
      "Foldable pane",
      "A side pane that folds to zero width without reflowing, resized by a Splitter.",
      Seq(
        Story(
          "foldable-pane/basic",
          "Fold and resize",
          "Press the button, or drag the edge (or focus it and use ← →)."
        )(foldablePane),
        Story(
          "foldable-pane/two-sides",
          "Both sides",
          "Fold either pane; each splitter resizes its own pane."
        )(foldableTwoSides)
      ),
      ApiDoc.of[FoldablePane] ++ ApiDoc.of[Splitter],
      a11yNote =
        "The splitter is a focusable separator with aria-valuenow; Escape mid-drag puts the size back."
    ),
    ComponentPage(
      "stack",
      "Stack",
      "Children in a column or a row, with a gap, alignment, and justification: flexbox without CSS.",
      Seq(
        Story("stack/toolbar", "Rows", "Change Justify: the row of buttons follows.")(stackToolbar),
        Story("stack/form", "A form", "A column of fields with a row of actions at the end.")(
          stackForm
        )
      ),
      ApiDoc.of[Stack]
    ),
    ComponentPage(
      "grid",
      "Grid",
      "Children in equal columns, or in as many columns of a minimum width as fit, with spans.",
      Seq(
        Story(
          "grid/responsive",
          "Responsive columns",
          "Press Narrower or Wider, or resize the window: the cards reflow."
        )(gridResponsive),
        Story(
          "grid/dashboard",
          "Spans",
          "Three columns: Traffic spans two, and Deploys takes the whole row."
        )(gridDashboard),
        Story(
          "grid/align",
          "Alignment",
          "Change Align: the short cards stretch, or line up in their cells."
        )(gridAlign)
      ),
      ApiDoc.of[Grid] ++ ApiDoc.of[GridItem]
    ),
    ComponentPage(
      "separator",
      "Separator",
      "A thin line between groups: horizontal between sections, vertical in a row.",
      Seq(
        Story(
          "separator/basic",
          "Basic",
          "Inspect the lines: each is role=separator with its orientation."
        )(separatorBasic)
      ),
      ApiDoc.of[Separator],
      a11yNote =
        "role=separator with aria-orientation; .decorative sets role=none for a purely visual line."
    ),
    ComponentPage(
      "status-bar",
      "Status bar",
      "A bar along the bottom of a window; StatusBar.spacer pushes the rest to the end.",
      Seq(
        Story("status-bar/basic", "Basic", "Press Add an error: the badge follows the Rx.")(
          statusBarBasic
        )
      ),
      ApiDoc.of[StatusBar]
    )
  )

end LayoutStories
