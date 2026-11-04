package example.renderer

import wvlet.uni.dom.{SizeRange, Storage}
import wvlet.uni.rx.{Rx, RxVar}

/**
  * Layout state: which panes are open, the active rail view, the theme, and the sidebar width.
  * Components read and write these variables directly. The app persists them in localStorage
  * ([[Layout.persistent]]); tests use plain in-memory variables ([[Layout.inMemory]]).
  */
class Layout(
    val sidebarOpen: RxVar[Boolean],
    val dockOpen: RxVar[Boolean],
    val view: RxVar[View],
    val theme: RxVar[Theme],
    val dockTab: RxVar[DockTab],
    val sidebarWidth: RxVar[Int]
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
    val all = Theme.values
    theme := all((all.indexOf(theme.get) + 1) % all.length)

end Layout

object Layout:
  val sidebarWidth = SizeRange(min = 208, default = 272, max = 480)
  val dockWidth    = 320

  def inMemory(): Layout = Layout(
    Rx.variable(true),
    Rx.variable(true),
    Rx.variable(View.Chats),
    Rx.variable(Theme.System),
    Rx.variable(DockTab.Details),
    Rx.variable(sidebarWidth.default)
  )

  def persistent(prefix: String = "uni-harness."): Layout = Layout(
    Storage.local(s"${prefix}sidebarOpen", true),
    Storage.local(s"${prefix}dockOpen", true),
    Storage.local(s"${prefix}view", View.Chats),
    Storage.local(s"${prefix}theme", Theme.System),
    Storage.local(s"${prefix}dockTab", DockTab.Details),
    Storage.local(s"${prefix}sidebarWidth", sidebarWidth.default)
  )
