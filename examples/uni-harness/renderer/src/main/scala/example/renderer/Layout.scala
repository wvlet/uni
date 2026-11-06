package example.renderer

import wvlet.uni.dom.{SizeRange, Storage}
import wvlet.uni.rx.{Rx, RxVar}
import wvlet.uni.ui.ColorScheme

/**
  * Layout state: which panes are open, the active rail view, the theme, and the pane widths.
  * Components read and write these variables directly. The app persists them in localStorage
  * ([[Layout.persistent]]); tests use plain in-memory variables ([[Layout.inMemory]]).
  */
class Layout(
    val sidebarOpen: RxVar[Boolean],
    val dockOpen: RxVar[Boolean],
    val view: RxVar[View],
    val theme: RxVar[ColorScheme],
    val dockTab: RxVar[DockTab],
    val sidebarWidth: RxVar[Int],
    val dockWidth: RxVar[Int]
):
  def toggleSidebar(): Unit = sidebarOpen.update(!_)
  def toggleDock(): Unit    = dockOpen.update(!_)

  /** Clicking the active rail icon folds the sidebar; clicking another one switches to it. */
  def openView(v: View): Unit =
    if view.get == v && sidebarOpen.get then
      sidebarOpen := false
    else
      view        := v
      sidebarOpen := true

  def cycleTheme(): Unit =
    val all = ColorScheme.values
    theme := all((all.indexOf(theme.get) + 1) % all.length)

end Layout

object Layout:
  val sidebarWidth = SizeRange(min = 208, default = 272, max = 480)
  // One width for every dock tab; ~600px fits an 80-column terminal. The max leaves the chat
  // some room in the default 1200px window with the sidebar open.
  val dockWidth = SizeRange(min = 260, default = 320, max = 720)

  def inMemory(): Layout = Layout(
    Rx.variable(true),
    Rx.variable(true),
    Rx.variable(View.Chats),
    Rx.variable(ColorScheme.System),
    Rx.variable(DockTab.Details),
    Rx.variable(sidebarWidth.default),
    Rx.variable(dockWidth.default)
  )

  def persistent(prefix: String = "uni-harness."): Layout = Layout(
    Storage.local(s"${prefix}sidebarOpen", true),
    Storage.local(s"${prefix}dockOpen", true),
    Storage.local(s"${prefix}view", View.Chats),
    Storage.local(s"${prefix}theme", ColorScheme.System),
    Storage.local(s"${prefix}dockTab", DockTab.Details),
    Storage.local(s"${prefix}sidebarWidth", sidebarWidth.default),
    Storage.local(s"${prefix}dockWidth", dockWidth.default)
  )
