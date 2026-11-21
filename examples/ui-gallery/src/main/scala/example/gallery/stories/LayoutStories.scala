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
        )(dock)
      ),
      ApiDoc.of[Dock] ++ ApiDoc.of[DockPanel],
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
        )(foldablePane)
      ),
      ApiDoc.of[FoldablePane] ++ ApiDoc.of[Splitter],
      a11yNote =
        "The splitter is a focusable separator with aria-valuenow; Escape mid-drag puts the size back."
    )
  )

end LayoutStories
