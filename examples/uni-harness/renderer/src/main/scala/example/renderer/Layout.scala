package example.renderer

import org.scalajs.dom
import wvlet.uni.dom.{SizeRange, Storage, StorageCodec}
import wvlet.uni.rx.{Rx, RxVar}
import wvlet.uni.ui.{ColorScheme, DockLayout, DockNode}

import scala.util.Try

/**
  * Layout state: which panes are open, the active rail view, the theme, the pane widths, and the
  * arrangement of the dock's panels. Components read and write these variables directly. The app
  * persists them in localStorage ([[Layout.persistent]]); tests use plain in-memory variables
  * ([[Layout.inMemory]]).
  */
class Layout(
    val sidebarOpen: RxVar[Boolean],
    val dockOpen: RxVar[Boolean],
    val view: RxVar[View],
    val theme: RxVar[ColorScheme],
    val dockLayout: RxVar[DockLayout],
    val sidebarWidth: RxVar[Int],
    val dockWidth: RxVar[Int]
):
  def toggleSidebar(): Unit = sidebarOpen.update(!_)
  def toggleDock(): Unit    = dockOpen.update(!_)

  /** Open the dock on `tab`, wherever the user has put it. */
  def showDock(tab: DockTab): Unit =
    dockLayout.update(_.open(tab.id))
    dockOpen := true

  /** Clicking the active rail icon folds the sidebar; clicking another one switches to it. */
  def openView(v: View): Unit =
    if view.get == v && sidebarOpen.get then
      sidebarOpen := false
    else
      view        := v
      sidebarOpen := true

end Layout

object Layout:
  val sidebarWidth = SizeRange(min = 208, default = 272, max = 480)
  // One width for every dock tab; ~600px fits an 80-column terminal. The max leaves the chat
  // some room in the default 1200px window with the sidebar open.
  val dockWidth = SizeRange(min = 260, default = 320, max = 720)

  /** Every dock panel in one group, showing `active`. */
  def defaultDock(active: DockTab = DockTab.Details): DockLayout = DockLayout(
    DockNode.Group(DockTab.values.map(_.id).toSeq, Some(active.id))
  )

  def inMemory(): Layout = Layout(
    Rx.variable(true),
    Rx.variable(true),
    Rx.variable(View.Chats),
    Rx.variable(ColorScheme.System),
    Rx.variable(defaultDock()),
    Rx.variable(sidebarWidth.default),
    Rx.variable(dockWidth.default)
  )

  def persistent(prefix: String = "uni-harness."): Layout =
    // Before the dock had a layout, it saved only its tab. Start from that tab until a layout is
    // saved; the old key is left as it is.
    val oldTab = Try(Option(dom.window.localStorage.getItem(s"${prefix}dockTab")))
      .toOption
      .flatten
      .flatMap(summon[StorageCodec[DockTab]].decode)
      .getOrElse(DockTab.Details)
    Layout(
      Storage.local(s"${prefix}sidebarOpen", true),
      Storage.local(s"${prefix}dockOpen", true),
      Storage.local(s"${prefix}view", View.Chats),
      Storage.local(s"${prefix}theme", ColorScheme.System),
      Storage.local(s"${prefix}dockLayout", defaultDock(oldTab)),
      Storage.local(s"${prefix}sidebarWidth", sidebarWidth.default),
      Storage.local(s"${prefix}dockWidth", dockWidth.default)
    )

end Layout
