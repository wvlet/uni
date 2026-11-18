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
import wvlet.uni.dom.all.*
import wvlet.uni.dom.all.given
import wvlet.uni.dom.{DomRenderer, PointerDrag}
import wvlet.uni.rx.{Cancelable, Rx, RxVar}
import wvlet.uni.ui.agent.{AgentRegistry, StateOwner}
import wvlet.uni.ui.base.{
  Announce,
  DockBox,
  DockModel,
  DockPath,
  FocusRecovery,
  Ids,
  Orientation,
  Reorder,
  RovingFocus,
  TextDirection
}

import scala.collection.mutable
import scala.language.implicitConversions
import scala.scalajs.js

// The layout types are plain data in wvlet.uni.ui.base; apps use them from here
export wvlet.uni.ui.base.{DockBox, DockFloat, DockLayout, DockNode, DockSide, SplitDirection}

/**
  * A panel a [[Dock]] can show: its id in the [[DockLayout]], its title, and its content. The
  * content is created the first time the panel is shown and kept, so moving the panel to another
  * group keeps its state (a terminal keeps its shell, a list its scroll position).
  *
  * {{{
  *   DockPanel("terminal", title = "Terminal", content = TerminalView())
  *     .withTab(Icons.terminal, "Terminal", Badge(unread))
  *     .withClosable(false)
  * }}}
  */
class DockPanel private (
    val id: String,
    val title: String,
    make: () => RxElement,
    tabContent: Seq[DomNode],
    val closable: Boolean
):
  private def copy(tabContent: Seq[DomNode] = tabContent, closable: Boolean = closable): DockPanel =
    new DockPanel(id, title, make, tabContent, closable)

  /** What the tab shows, such as an icon, the title, and a badge. The default is the title. */
  def withTab(content: DomNode*): DockPanel = copy(tabContent = content)
  def noTab(): DockPanel                    = copy(tabContent = Seq.empty)

  /**
    * Whether the user can close the panel (true by default). A panel that can't be closed is added
    * back to the layout when a saved layout is missing it.
    */
  def withClosable(c: Boolean): DockPanel = copy(closable = c)

  private[ui] def tab: Seq[DomNode] =
    if tabContent.isEmpty then
      Seq(title)
    else
      tabContent

  private[ui] def content(): RxElement = make()

end DockPanel

object DockPanel:
  // `new` is required in this file: the `apply` below replaces the constructor proxy
  def apply(id: String, title: String, content: => RxElement): DockPanel =
    new DockPanel(id, title, () => content, Seq.empty, true)

/**
  * Panels in tab groups and splits that the user rearranges. The arrangement is a [[DockLayout]] in
  * `layout`, which the app owns and usually saves:
  * {{{
  *   val layout = Storage.local("app.dock", DockLayout(DockNode.column(DockNode.group("details"), DockNode.group("terminal"))))
  *   Dock(layout, label = "Dock", panels = Seq(
  *     DockPanel("details", title = "Details", content = DetailsView()),
  *     DockPanel("terminal", title = "Terminal", content = TerminalView())
  *   ))
  * }}}
  *
  * The layout refers to panels by id. Ids the Dock doesn't know are dropped, so an old save never
  * breaks the app, and [[open]] shows a panel that is not in the layout.
  *
  * The user drags a tab onto another group's tabs to move it there, onto a group's center to add
  * it, or onto one of its edges to split the group. Escape cancels a drag. Without a pointer, a
  * tab's context menu (right-click, Shift+F10, or the context-menu key) moves and splits it. With
  * focus on a tab, the arrow keys, Home, and End switch tabs, the same keys with Alt move the tab
  * within its group (as [[base.Reorder]] does), and Delete closes a closable one. The border
  * between split children is a separator: drag it, or focus it and press the arrow keys (Shift for
  * larger steps), Home and End for the limits, or Enter to share the space equally. No child gets
  * smaller than [[withMinPanelSize]].
  *
  * A tab's menu also has "Float", which lifts the panel into a floating group over the grid (a
  * [[DockFloat]] in the layout). Drag the float's grip to move it and its corner to resize it, or
  * focus the grip and press the arrow keys to move it (Shift+arrow keys resize). Tabs move into and
  * out of a float as between other groups, and "Dock" puts one back into the grid. With
  * [[withDetach]], "Open in new window" moves a panel into a browser window of its own until the
  * window closes; that is not saved in the layout.
  *
  * The dock is a `div.uni-dock`; groups are `div.uni-dock__group` with a `role="tablist"` of
  * `role="tab"` tabs and a `role="tabpanel"`, and `data-top="true"` on the groups along the top
  * edge (to align their tab bar with a title bar). Splits are `div.uni-dock__split` with
  * `data-direction="row|column"`. A float is a `div.uni-dock__float` holding its group, with a
  * `button.uni-dock__float-grip` and a `div.uni-dock__float-resize`; a detached panel's tab has
  * `data-detached="true"`.
  *
  * @param label
  *   the accessible name of the tab lists, such as "Dock"
  */
class Dock private (
    layout: RxVar[DockLayout],
    label: String,
    panels: Seq[DockPanel],
    minPanelSize: Int,
    actions: Seq[DomNode],
    detachable: Boolean,
    changeHandler: ChangeHandler
) extends RxElement:

  private def copy(
      minPanelSize: Int = minPanelSize,
      actions: Seq[DomNode] = actions,
      detachable: Boolean = detachable,
      changeHandler: ChangeHandler = changeHandler
  ): Dock = new Dock(layout, label, panels, minPanelSize, actions, detachable, changeHandler)

  /**
    * Called before the user (or [[open]] and [[close]]) changes the layout; `cancel()` keeps it.
    * Key: `layout`.
    */
  def onChange(f: ChangeHandler): Dock = copy(changeHandler = f)

  // Owns the layout for UiHistory, so an undo asks this dock's handler; agents don't see it
  private object stateOwner extends StateOwner:
    override def kind: String                 = "dock"
    override def states: Seq[AnyRef]          = Seq(layout)
    override def changeHandler: ChangeHandler = Dock.this.changeHandler
    override def label: Option[String]        = Some(Dock.this.label)
    override def format(v: Any): String       =
      v match
        case l: DockLayout =>
          l.toJSON
        case other =>
          String.valueOf(other)

  private def setLayout(l: DockLayout, cause: ChangeCause): Boolean = ChangeGate.write(
    layout,
    "layout",
    l,
    cause,
    changeHandler
  )

  /** The smallest size of a split child in pixels (120 by default). */
  def withMinPanelSize(px: Int): Dock = copy(minPanelSize = px)

  /**
    * Controls at the end of the tabs of the top-right group, such as a button that folds the dock.
    */
  def withActions(nodes: DomNode*): Dock = copy(actions = nodes)
  def noActions(): Dock                  = copy(actions = Seq.empty)

  /**
    * Whether a tab's menu offers "Open in new window" (false by default), which moves the panel
    * into a browser window of its own. The window opens with `window.open` on the same origin, so a
    * host that blocks popups (such as an Electron app without a window-open handler) has to allow
    * `about:blank`. Menus and popovers opened inside the window show in the main window.
    */
  def withDetach(enabled: Boolean): Dock = copy(detachable = enabled)

  private val byId: Map[String, DockPanel] = panels.map(p => p.id -> p).toMap
  private val required: Seq[String]        = panels.filterNot(_.closable).map(_.id)

  // Every subscriber sees the same layout instance per change, so remember the last result and
  // normalize once per change rather than once per subscriber
  private var lastNorm: (DockLayout, DockLayout) = (null, null)
  private def norm(l: DockLayout): DockLayout    =
    if l ne lastNorm._1 then
      lastNorm = (l, DockModel.normalize(l, byId.contains, required))
    lastNorm._2

  private var lastShape: (DockLayout, DockLayout) = (null, null)
  private def shapeOf(l: DockLayout): DockLayout  =
    if l ne lastShape._1 then
      lastShape = (l, DockModel.shape(l))
    lastShape._2

  private def now: DockLayout = norm(layout.get)

  /** The layout as the Dock shows it: unknown panels dropped, required ones added. */
  val current: Rx[DockLayout] = layout.map(norm)

  /** The panels the user can see: the active tab of each group, floating ones included. */
  def visible: Rx[Set[String]] = current.map(DockModel.visible)

  /** Registered panels that are not in the layout, which [[open]] can show again. */
  def hidden: Rx[Seq[String]] = current.map { l =>
    val in = DockModel.panels(l).toSet
    panels.map(_.id).filterNot(in)
  }

  // The group that last had focus, where `open` adds a panel; cleared when the structure changes
  private var focusedGroup: Option[DockPath] = None

  /**
    * Show `panel`: switch to its tab, or add it to the focused group when it is not in the layout.
    */
  def open(panel: String): Unit =
    if byId.contains(panel) then
      setLayout(DockModel.open(now, panel, focusedGroup), ChangeCause.imperative)

  /** Remove `panel` from the layout. */
  def close(panel: String): Unit = setLayout(DockModel.close(now, panel), ChangeCause.imperative)

  /**
    * The number of groups. Groups are numbered from 0 in reading order (depth first), then the
    * floating groups from the bottom one, so group `i` has the accessible name "group i+1".
    */
  val groupCount: Rx[Int] = distinct(current.map(l => DockModel.groups(l).size))

  /**
    * Move keyboard focus to the active tab of group `index` (from 0, in reading order), as an
    * editor's Ctrl+1, Ctrl+2 shortcuts do. Returns false, and leaves focus alone, when there is no
    * such group or the Dock isn't mounted.
    */
  def focusGroup(index: Int): Boolean =
    val l = now
    DockModel
      .groups(l)
      .lift(index)
      .flatMap(DockModel.nodeAt(l, _))
      .collect { case DockNode.Group(_, Some(a)) =>
        a
      }
      .exists(focusTabNow)

  /**
    * Show `panel`, as [[open]] does, and move keyboard focus to its tab. Returns false when the
    * panel isn't registered or its tab isn't on the page.
    */
  def focusPanel(panel: String): Boolean =
    byId.contains(panel) &&
      setLayout(DockModel.open(now, panel, focusedGroup), ChangeCause.imperative) &&
      focusTabNow(panel)

  private def focusTabNow(panel: String): Boolean =
    Option(dom.document.getElementById(tabId(panel))) match
      case Some(el: dom.HTMLElement) =>
        el.focus()
        // A tab in a hidden container can't take focus
        dom.document.activeElement eq el
      case _ =>
        false

  // Panel content, created on first show and kept for moves between groups
  private val contents = mutable.Map.empty[String, RxElement]

  private def contentOf(id: String): RxElement = contents.getOrElseUpdate(id, byId(id).content())

  private val idPrefix = Ids.next("uni-dock")
  // By registration index, so any panel id makes a valid, unique DOM id
  private val index: Map[String, Int]  = panels.map(_.id).zipWithIndex.toMap
  private def tabId(panel: String)     = s"${idPrefix}-tab-${index.getOrElse(panel, -1)}"
  private def panelId(path: DockPath)  = s"${idPrefix}-panel-${path.mkString("-")}"
  private def pathAttr(path: DockPath) = path.mkString(".")
  private def parsePath(s: String): Option[DockPath] =
    if s.isEmpty then
      Some(Nil)
    else
      s.split('.').toList.map(_.toIntOption) match
        case ps if ps.forall(_.isDefined) =>
          Some(ps.flatten)
        case _ =>
          None

  // Emits only when the value changes, so a resize doesn't rebuild what didn't change. Created per
  // render: the comparison state lives in the subscription.
  private def distinct[A](rx: Rx[A]): Rx[A] = rx
    .scan[(Option[A], Boolean)]((None, false)) { (st, a) =>
      (Some(a), !st._1.contains(a))
    }
    .filter(_._2)
    .map(_._1.get)

  private val dockRef: DomRef[dom.HTMLElement] = DomRef[dom.HTMLElement]()

  private var closedWatch: Cancelable = Cancelable.empty

  override def onMount(node: Any): Unit =
    // Save the cleaned-up layout, so unknown ids leave the app's storage too
    layout := now
    dom.window.addEventListener("pagehide", closeWindows)
    // A panel that leaves the layout closes its window, whoever changed the layout
    closedWatch = current.run { l =>
      if popups.nonEmpty then
        val in = DockModel.panels(l).toSet
        popups.keys.filterNot(in).toList.foreach(reattach)
    }

  override def beforeUnmount: Unit =
    closedWatch.cancel
    dom.window.removeEventListener("pagehide", closeWindows)
    closeWindows(())

  // --- Detached windows --------------------------------------------------------------------------

  private class Popup(val window: dom.Window, val stop: () => Unit)

  private val popups                          = mutable.Map.empty[String, Popup]
  private val detachedIds: RxVar[Set[String]] = Rx.variable(Set.empty)
  private var watch: Option[Int]              = None

  // When the page goes away, its windows go too; they can't live without it
  private val closeWindows: js.Function1[Any, Unit] = _ => popups.keys.toList.foreach(reattach)

  /** The panels open in a window of their own ([[detach]]). */
  def detached: Rx[Set[String]] = detachedIds

  /**
    * Move `panel` into a browser window of its own, showing it in the layout first when it is
    * hidden. Its tab stays in the dock and shows where the window is; the panel comes back when the
    * window closes or on [[reattach]]. Returns false when [[withDetach]] is off, the panel isn't
    * registered, or the browser blocked the window.
    */
  def detach(panel: String): Boolean =
    if !detachable || !byId.contains(panel) then
      false
    else
      popups.get(panel) match
        case Some(p) =>
          p.window.focus()
          true
        case None =>
          val (w, h) = windowSize(panel)
          Option(
            dom
              .window
              .open("", s"${idPrefix}-window-${index(panel)}", s"popup,width=${w},height=${h}")
          ) match
            case None =>
              false
            case Some(win) =>
              // Before the panel shows or leaves the dock, so its content never mounts there on
              // the way to the window
              detachedIds := detachedIds.get + panel
              val shown =
                DockModel.groupOf(now, panel).isDefined ||
                  setLayout(DockModel.open(now, panel, focusedGroup), ChangeCause.imperative)
              if shown then
                popups(panel) = mountWindow(panel, win)
                watchWindows()
              else
                // The app kept the panel hidden
                detachedIds := detachedIds.get - panel
                win.close()
              shown

  /** Bring `panel` back from its window into its tab, and close the window. */
  def reattach(panel: String): Unit = popups
    .remove(panel)
    .foreach { p =>
      p.stop()
      if !p.window.closed then
        p.window.close()
      detachedIds := detachedIds.get - panel
    }

  private def focusWindow(panel: String): Unit = popups.get(panel).foreach(_.window.focus())

  // For tests: the window `panel` is open in
  private[ui] def windowOf(panel: String): Option[dom.Window] = popups.get(panel).map(_.window)

  // The size of the panel's place in the dock, so the window opens about as large
  private def windowSize(panel: String): (Int, Int) =
    val r = DockModel.groupOf(now, panel).flatMap(groupElement).map(_.getBoundingClientRect())
    (
      r.map(_.width.toInt).filter(_ >= 320).getOrElse(480),
      r.map(_.height.toInt).filter(_ >= 240).getOrElse(360)
    )

  private def mountWindow(panel: String, win: dom.Window): Popup =
    val doc = win.document
    doc.title = byId(panel).title
    // The page's styles and theme, so the panel looks the same in its window
    val base = doc.createElement("base")
    base.setAttribute("href", dom.document.baseURI)
    doc.head.appendChild(base)
    val sheets = dom.document.querySelectorAll("style, link[rel=stylesheet]")
    (0 until sheets.length).foreach(i => doc.head.appendChild(doc.importNode(sheets(i), true)))
    def copyAttributes(): Unit =
      for (from, to) <- Seq(
          dom.document.documentElement -> doc.documentElement,
          dom.document.body            -> doc.body
        )
      do
        // Removed ones too: ColorScheme.System removes data-theme
        val stale = (0 until to.attributes.length)
          .map(to.attributes(_).name)
          .filterNot(from.hasAttribute)
        stale.foreach(to.removeAttribute)
        val as = from.attributes
        (0 until as.length).foreach { i =>
          to.setAttribute(as(i).name, as(i).value)
        }
    copyAttributes()
    // Keep following the page's theme, such as a switch to dark mode
    val observer = dom.MutationObserver((_, _) => copyAttributes())
    val attrs    = js.Dynamic.literal(attributes = true).asInstanceOf[dom.MutationObserverInit]
    observer.observe(dom.document.documentElement, attrs)
    observer.observe(dom.document.body, attrs)

    val host = doc.createElement("div")
    host.setAttribute("class", "uni-dock__window")
    host.setAttribute("data-slot", "dock-window")
    host.setAttribute("data-panel", panel)
    doc.body.appendChild(host)
    val rendered                              = DomRenderer.renderTo(host, contentOf(panel))
    val onHide: js.Function1[dom.Event, Unit] = _ => reattach(panel)
    win.addEventListener("pagehide", onHide)
    Popup(
      win,
      () =>
        observer.disconnect()
        win.removeEventListener("pagehide", onHide)
        rendered.cancel
    )

  end mountWindow

  // A closed window doesn't always fire pagehide (a host may destroy it), so also check
  private def watchWindows(): Unit =
    if watch.isEmpty then
      watch = Some(
        dom
          .window
          .setInterval(
            () =>
              popups
                .collect {
                  case (id, p) if p.window.closed =>
                    id
                }
                .toList
                .foreach(reattach)
              if popups.isEmpty then
                watch.foreach(dom.window.clearInterval)
                watch = None
            ,
            500
          )
      )

  private def detachedView(panel: String): RxElement = div(
    cls          -> "uni-dock__detached",
    data("slot") -> "dock-detached",
    div(s"${byId(panel).title} is open in another window."),
    div(
      cls -> "uni-dock__detached-actions",
      Button("Show window").onClick(() => focusWindow(panel)),
      Button("Bring back").onClick { () =>
        reattach(panel)
        // This button goes away with the note
        focusTab(panel)
      }
    )
  )

  // --- Floating groups ---------------------------------------------------------------------------

  // The floats from the bottom up. Raising one only restacks them, so a drag on it isn't cut off
  // by a rebuild; the saved order is the order they were made in.
  private val stack: RxVar[Seq[Int]] = Rx.variable(Seq.empty)

  private def raise(i: Int): Unit =
    if stack.get.lastOption != Some(i) then
      stack := stack.get.filterNot(_ == i) :+ i

  private def groupElement(path: DockPath): Option[dom.Element] = dockRef
    .current
    .flatMap(d => Option(d.querySelector(s".uni-dock__group[data-path='${pathAttr(path)}']")))

  private def dockSize: Option[(Double, Double)] = dockRef
    .current
    .map(_.getBoundingClientRect())
    .collect {
      case r if r.width > 0 && r.height > 0 =>
        (r.width, r.height)
    }

  // Where a panel floats when lifted out of the group at `path`: a little in from the group's
  // corner, at most its size
  private def floatBox(path: DockPath): DockBox =
    (dockRef.current, groupElement(path), dockSize) match
      case (Some(d), Some(g), Some((dw, dh))) =>
        val base = d.getBoundingClientRect()
        val r    = g.getBoundingClientRect()
        val w    = math.max(minPanelSize.toDouble, r.width * 0.6)
        val h    = math.max(minPanelSize.toDouble, r.height * 0.6)
        DockBox((r.left - base.left + 24) / dw, (r.top - base.top + 24) / dh, w / dw, h / dh)
      case _ =>
        DockBox.default

  private def boxOf(i: Int): DockBox = now.floating.lift(i).map(_.box).getOrElse(DockBox.default)

  private def moveFloat(i: Int, from: DockBox, dx: Double, dy: Double, cause: ChangeCause): Unit =
    dockSize.foreach { (dw, dh) =>
      setLayout(
        DockModel.withBox(now, i, from.copy(x = from.x + dx / dw, y = from.y + dy / dh)),
        cause
      )
    }

  private def resizeFloat(i: Int, from: DockBox, dx: Double, dy: Double, cause: ChangeCause): Unit =
    dockSize.foreach { (dw, dh) =>
      val w = math.min(1 - from.x, math.max(minPanelSize / dw, from.width + dx / dw))
      val h = math.min(1 - from.y, math.max(minPanelSize / dh, from.height + dy / dh))
      setLayout(DockModel.withBox(now, i, from.copy(width = w, height = h)), cause)
    }

  private def floatGroup(i: Int, tabs: Seq[String], live: Rx[DockLayout], groupCount: Int) =
    val path = DockModel.floatPath(i)
    val box  = distinct(live.map(_.floating.lift(i).map(_.box).getOrElse(DockBox.default)))
    def pct(v: Double) = f"${v * 100}%.4f%%"
    var from: DockBox  = DockBox.default
    // One drag, one UiHistory entry
    var gesture: AnyRef              = Object()
    val drag: Boolean => PointerDrag =
      resize =>
        PointerDrag(
          onMove =
            (dx, dy) =>
              val cause = ChangeCause(ChangeReason.Drag, ChangeSource.Pointer)
              ChangeGate.inGesture(gesture) {
                if resize then
                  resizeFloat(i, from, dx, dy, cause)
                else
                  moveFloat(i, from, dx, dy, cause)
              }
          ,
          onStart =
            _ =>
              from = boxOf(i)
              gesture = Object()
          ,
          cursor =
            if resize then
              "nwse-resize"
            else
              "grabbing"
          ,
          // Escape puts the group back where the drag started
          onCancel = Some(e =>
            ChangeGate.inGesture(gesture) {
              setLayout(DockModel.withBox(now, i, from), ChangeCause.dragCancel(e))
            }
          )
        )
    val moving                            = drag(false)
    val sizing                            = drag(true)
    def onKey(e: dom.KeyboardEvent): Unit =
      val step     = Dock.floatStep.toDouble
      val (dx, dy) =
        e.key match
          case "ArrowLeft" =>
            (-step, 0.0)
          case "ArrowRight" =>
            (step, 0.0)
          case "ArrowUp" =>
            (0.0, -step)
          case "ArrowDown" =>
            (0.0, step)
          case _ =>
            (0.0, 0.0)
      if (dx != 0 || dy != 0) && !e.altKey && !e.ctrlKey && !e.metaKey then
        e.preventDefault()
        val cause = ChangeCause.of(ChangeReason.NavigationKey, e)
        if e.shiftKey then
          resizeFloat(i, boxOf(i), dx, dy, cause)
        else
          moveFloat(i, boxOf(i), dx, dy, cause)

    div(
      cls           -> "uni-dock__float",
      data("slot")  -> "dock-float",
      data("float") -> i.toString,
      styleAttr     ->
        box
          .join(stack)
          .map { (b, order) =>
            s"left: ${pct(b.x)}; top: ${pct(b.y)}; width: ${pct(b.width)}; height: ${pct(
                b.height
              )}; z-index: ${math.max(0, order.indexOf(i)) + 1}"
          },
      EventListenerBinding("pointerdown", _ => raise(i)),
      EventListenerBinding("focusin", _ => raise(i)),
      group(
        tabs,
        path,
        live,
        groupCount,
        corner = false,
        top = false,
        grip = Some(
          button(
            tpe              -> "button",
            cls              -> "uni-dock__float-grip",
            data("slot")     -> "dock-float-grip",
            ariaLabel        -> "Move floating group",
            titleAttr        -> "Drag to move. Arrow keys move, Shift+arrow keys resize",
            data("dragging") -> moving.dragging.map(_.toString),
            EventListenerBinding("keydown", e => onKey(e.asInstanceOf[dom.KeyboardEvent])),
            moving.bind
          )
        )
      ),
      div(
        cls              -> "uni-dock__float-resize",
        data("slot")     -> "dock-float-resize",
        ariaHidden       -> "true",
        data("dragging") -> sizing.dragging.map(_.toString),
        sizing.bind
      )
    )

  end floatGroup

  // --- Drag and drop -----------------------------------------------------------------------------

  /** Where a dragged tab would land: a tab position in a group, or a side of a group. */
  private case class Drop(path: DockPath, side: DockSide, index: Option[Int], preview: String)

  private val drop: RxVar[Option[Drop]]      = Rx.variable(None)
  private val dragged: RxVar[Option[String]] = Rx.variable(None)
  private var dragStart: (Double, Double)    = (0, 0)
  private var dragMoved: Boolean             = false
  private var suppressClick: Boolean         = false

  private def dropAt(x: Double, y: Double): Option[Drop] =
    for
      root  <- dockRef.current
      el    <- Option(dom.document.elementFromPoint(x, y))
      group <- Option(el.closest(".uni-dock__group")).filter(root.contains)
      path  <- parsePath(group.getAttribute("data-path"))
    yield
      val base                                                    = root.getBoundingClientRect()
      def box(l: Double, t: Double, w: Double, h: Double): String =
        f"left: ${l - base.left}%.1fpx; top: ${t -
            base.top}%.1fpx; width: ${w}%.1fpx; height: ${h}%.1fpx"
      Option(el.closest(".uni-dock__tabs")) match
        case Some(strip) =>
          // Before the first tab whose middle is right of the pointer
          val tabs  = strip.querySelectorAll("[role=tab]")
          val rects = (0 until tabs.length).map(i => tabs(i).getBoundingClientRect())
          val index =
            rects.indexWhere(r => x < r.left + r.width / 2) match
              case -1 =>
                rects.size
              case i =>
                i
          val s  = strip.getBoundingClientRect()
          val at =
            if rects.isEmpty then
              s.left
            else if index < rects.size then
              rects(index).left
            else
              rects.last.right
          Drop(path, DockSide.Center, Some(index), box(at - 1, s.top + 4, 2, s.height - 8))
        case None =>
          val r  = group.getBoundingClientRect()
          val fx = (x - r.left) / math.max(1, r.width)
          val fy = (y - r.top) / math.max(1, r.height)
          // The nearest edge within the outer quarter splits; the middle adds to the group
          val edges = Seq(
            DockSide.Left   -> fx,
            DockSide.Right  -> (1 - fx),
            DockSide.Top    -> fy,
            DockSide.Bottom -> (1 - fy)
          ).filter(_._2 < Dock.edgeBand)
          // A float holds one group, so any drop on it adds to the group
          val side =
            if DockModel.floatIndex(path).isDefined then
              DockSide.Center
            else
              edges.minByOption(_._2).map(_._1).getOrElse(DockSide.Center)
          val preview =
            side match
              case DockSide.Center =>
                box(r.left, r.top, r.width, r.height)
              case DockSide.Left =>
                box(r.left, r.top, r.width / 2, r.height)
              case DockSide.Right =>
                box(r.left + r.width / 2, r.top, r.width / 2, r.height)
              case DockSide.Top =>
                box(r.left, r.top, r.width, r.height / 2)
              case DockSide.Bottom =>
                box(r.left, r.top + r.height / 2, r.width, r.height / 2)
          Drop(path, side, None, preview)
      end match

  private def startDrag(e: dom.PointerEvent): Unit =
    dragStart = (e.clientX, e.clientY)
    dragMoved = false

  private def moveDrag(panel: String, dx: Double, dy: Double): Unit =
    if !dragMoved && math.hypot(dx, dy) >= Dock.dragThreshold then
      dragMoved = true
      dragged := Some(panel)
    if dragMoved then
      drop := dropAt(dragStart._1 + dx, dragStart._2 + dy)

  private def endDrag(panel: String, commit: Boolean): Unit =
    // After a cancel, PointerDrag swallows the click itself
    if dragMoved && commit then
      // The click that follows the release shouldn't also select a tab
      suppressClick = true
      dom.window.setTimeout(() => suppressClick = false, 0)
      drop
        .get
        .foreach { d =>
          setLayout(
            d.index match
              case Some(i) =>
                DockModel.move(now, panel, d.path, i)
              case None =>
                DockModel.split(now, panel, d.path, d.side)
            ,
            ChangeCause(ChangeReason.Drag, ChangeSource.Pointer)
          )
        }
    drop    := None
    dragged := None
    dragMoved = false

  // --- Tabs --------------------------------------------------------------------------------------

  private def focusTab(panel: String): Unit =
    // After the menu closes and returns focus, which would otherwise win
    dom
      .window
      .setTimeout(
        () =>
          Option(dom.document.getElementById(tabId(panel))) match
            case Some(el: dom.HTMLElement) =>
              el.focus()
            case _ =>
        ,
        0
      )

  private def act(panel: String)(f: DockLayout => DockLayout): Unit =
    if setLayout(f(now), ChangeCause.current) then
      focusTab(panel)

  private def menu(panel: DockPanel, path: DockPath, tabs: Seq[String], groupCount: Int) =
    val i        = tabs.indexOf(panel.id)
    val id       = panel.id
    val floating = DockModel.floatIndex(path).isDefined
    val next     =
      () =>
        val gs = DockModel.groups(now)
        gs.lift((gs.indexOf(path) + 1) % gs.size).getOrElse(path)
    val items =
      Seq[MenuEntry](
        MenuItem("Move left")
          .withDisabled(i == 0)
          .onSelect(() => act(id)(DockModel.move(_, id, path, i - 1))),
        MenuItem("Move right")
          .withDisabled(i == tabs.size - 1)
          .onSelect(() => act(id)(DockModel.move(_, id, path, i + 2))),
        MenuItem("Move to next group")
          .withDisabled(groupCount < 2)
          .onSelect(() => act(id)(l => DockModel.move(l, id, next(), Int.MaxValue))),
        MenuSeparator,
        MenuItem("Split right")
          .withDisabled(tabs.size < 2 || floating)
          .onSelect(() => act(id)(DockModel.split(_, id, path, DockSide.Right))),
        MenuItem("Split down")
          .withDisabled(tabs.size < 2 || floating)
          .onSelect(() => act(id)(DockModel.split(_, id, path, DockSide.Bottom))),
        MenuSeparator,
        if floating then
          MenuItem("Dock").onSelect(() => act(id)(DockModel.dock(_, id)))
        else
          MenuItem("Float").onSelect(() => act(id)(DockModel.float(_, id, floatBox(path))))
      ) ++
        Option.when(detachable)(
          MenuItem(
            detachedIds.map(d =>
              if d.contains(id) then
                "Bring back from window"
              else
                "Open in new window"
            )
          ).onSelect(() =>
            if popups.contains(id) then
              reattach(id)
              focusTab(id)
            else
              detach(id)
          )
        )
    if panel.closable then
      items ++
        Seq(
          MenuSeparator,
          MenuItem("Close").onSelect(() => closeTab(id, tabs, ChangeCause.current))
        )
    else
      items

  end menu

  private def tab(
      panel: DockPanel,
      path: DockPath,
      tabs: Seq[String],
      groupCount: Int,
      active: Rx[Option[String]]
  ): RxElement =
    val isActive = active.map(_.contains(panel.id))
    val drag     = PointerDrag(
      onMove = (dx, dy) => moveDrag(panel.id, dx, dy),
      onStart = startDrag,
      onEnd = () => endDrag(panel.id, commit = true),
      // Escape drops nothing
      onCancel = Some(_ => endDrag(panel.id, commit = false))
    )
    val face = div(
      role          -> "tab",
      id            -> tabId(panel.id),
      cls           -> "uni-dock__tab",
      data("slot")  -> "dock-tab",
      data("panel") -> panel.id,
      ariaControls  -> panelId(path),
      ariaSelected  -> isActive.map(_.toString),
      tabindex      ->
        isActive.map(on =>
          if on then
            "0"
          else
            "-1"
        ),
      data("dragging") -> dragged.map(_.contains(panel.id).toString),
      data("detached") -> detachedIds.map(_.contains(panel.id).toString),
      EventListenerBinding(
        "click",
        e =>
          if !suppressClick then
            // The pointer drag keeps the press from focusing the tab
            if setLayout(DockModel.activate(now, panel.id), ChangeCause.of(ChangeReason.Press, e))
            then
              e.currentTarget match
                case el: dom.HTMLElement =>
                  el.focus()
                case _ =>
              focusWindow(panel.id)
      ),
      drag.bind,
      span(cls -> "uni-dock__tab-label", data("slot") -> "dock-tab-label", panel.tab),
      when(
        panel.closable,
        button(
          tpe          -> "button",
          cls          -> "uni-dock__tab-close",
          data("slot") -> "dock-tab-close",
          tabindex     -> "-1",
          ariaLabel    -> s"Close ${panel.title}",
          titleAttr    -> "Close",
          // Not the start of a tab drag
          EventListenerBinding("pointerdown", _.stopPropagation()),
          EventListenerBinding(
            "click",
            e =>
              e.stopPropagation()
              setLayout(DockModel.close(now, panel.id), ChangeCause.of(ChangeReason.Press, e))
          )
        )
      )
    )
    ContextMenu(face, entries = menu(panel, path, tabs, groupCount))

  end tab

  // Close a tab from the keyboard and move focus to its neighbor, as an editor does
  private def closeTab(panel: String, tabs: Seq[String], cause: ChangeCause): Unit =
    if setLayout(DockModel.close(now, panel), cause) then
      FocusRecovery.neighbor(tabs, panel).foreach(focusTab)

  private def onTabKey(e: dom.KeyboardEvent, tabs: Seq[String], path: DockPath): Unit =
    val focused = Option(e.target)
      .collect { case el: dom.Element =>
        el.closest("[role=tab]")
      }
      .flatMap(el => Option(el))
      .map(_.getAttribute("data-panel"))
    focused.foreach { panel =>
      val i = tabs.indexOf(panel)
      if e.key == "Delete" && byId.get(panel).exists(_.closable) then
        e.preventDefault()
        closeTab(panel, tabs, ChangeCause.of(ChangeReason.DeleteKey, e))
      else if e.altKey then
        // Alt+ArrowLeft/Right, Home, End: move the tab within its group, as Reorder does in a list
        if !e.ctrlKey && !e.metaKey && !e.shiftKey &&
          Reorder.isMoveKey(e.key, Orientation.Horizontal)
        then
          // Even when the tab can't move further, so Alt+ArrowLeft doesn't go Back in the browser
          e.preventDefault()
          // In a right-to-left layout, ArrowLeft moves the tab toward the end
          val rtl = TextDirection.isRtl(e)
          Reorder
            .keyTarget(e.key, i, tabs.size, Orientation.Horizontal, rtl)
            .foreach { to =>
              // DockModel.move takes an insertion point in the tabs before the move
              val at =
                if to > i then
                  to + 1
                else
                  to
              val cause = ChangeCause.of(ChangeReason.NavigationKey, e)
              if setLayout(DockModel.move(now, panel, path, at), cause) then
                Announce.polite(
                  s"Moved ${byId.get(panel).map(_.title).getOrElse(panel)} to position ${to +
                      1} of ${tabs.size}"
                )
                focusTab(panel)
            }
      else
        RovingFocus
          .nextIndex(e.key, math.max(i, 0), tabs.size, rtl = TextDirection.mirrors(e))
          .foreach { n =>
            e.preventDefault()
            val cause = ChangeCause.of(ChangeReason.NavigationKey, e)
            if setLayout(DockModel.activate(now, tabs(n)), cause) then
              Option(dom.document.getElementById(tabId(tabs(n)))) match
                case Some(el: dom.HTMLElement) =>
                  el.focus()
                case _ =>
          }
      end if
    }

  end onTabKey

  // --- Layout nodes ------------------------------------------------------------------------------

  private def group(
      tabs: Seq[String],
      path: DockPath,
      live: Rx[DockLayout],
      groupCount: Int,
      corner: Boolean,
      top: Boolean,
      grip: Option[RxElement] = None
  ): RxElement =
    val active: Rx[Option[String]] = distinct(
      live.map(r =>
        DockModel.nodeAt(r, path) match
          case Some(DockNode.Group(_, a)) =>
            a
          case _ =>
            None
      )
    )
    val name =
      if groupCount > 1 then
        s"${label}, group ${DockModel.groups(now).indexOf(path) + 1}"
      else
        label
    div(
      cls          -> "uni-dock__group",
      data("slot") -> "dock-group",
      data("path") -> pathAttr(path),
      data("top")  -> top.toString,
      EventListenerBinding("focusin", _ => focusedGroup = Some(path)),
      div(
        cls          -> "uni-dock__bar",
        data("slot") -> "dock-bar",
        grip,
        div(
          role         -> "tablist",
          cls          -> "uni-dock__tabs",
          data("slot") -> "dock-tabs",
          ariaLabel    -> name,
          EventListenerBinding(
            "keydown",
            e => onTabKey(e.asInstanceOf[dom.KeyboardEvent], tabs, path)
          ),
          Rx.const(tabs).each(identity)(id => tab(byId(id), path, tabs, groupCount, active))
        ),
        when(corner && actions.nonEmpty, div(cls -> "uni-dock__actions", actions))
      ),
      div(
        role           -> "tabpanel",
        id             -> panelId(path),
        cls            -> "uni-dock__panel",
        data("slot")   -> "dock-panel",
        ariaLabelledby -> active.map(_.map(tabId).getOrElse("")),
        // A panel in a window of its own leaves a note in its place
        distinct(active.join(detachedIds).map((a, d) => (a, a.exists(d.contains)))).map {
          case (Some(a), true) =>
            detachedView(a)
          case (Some(a), false) =>
            contentOf(a)
          case _ =>
            DomNode.empty
        }
      )
    )

  end group

  private def separator(
      path: DockPath,
      border: Int,
      dir: SplitDirection,
      live: Rx[DockLayout],
      splitRef: DomRef[dom.HTMLElement]
  ): RxElement =
    def sizes: Seq[Double] =
      DockModel.nodeAt(now.root, path) match
        case Some(DockNode.Split(_, _, ss)) =>
          ss
        case _ =>
          Seq.empty
    // The pixels the children share: the split minus its separators
    def available: Double = splitRef
      .current
      .map { el =>
        val r    = el.getBoundingClientRect()
        val seps = el.querySelectorAll(":scope > .uni-dock__separator")
        val used = (0 until seps.length)
          .map { i =>
            val s = seps(i).getBoundingClientRect()
            if dir == SplitDirection.Row then
              s.width
            else
              s.height
          }
          .sum
        (
          if dir == SplitDirection.Row then
            r.width
          else
            r.height
        ) - used
      }
      .getOrElse(0.0)

    def resizeBy(from: Seq[Double], px: Double, delta: Double, cause: ChangeCause): Unit =
      setLayout(
        DockModel.withSizes(now, path, DockModel.resize(from, border, delta, px, minPanelSize)),
        cause
      )

    var startSizes: Seq[Double] = Seq.empty
    var startPx: Double         = 0
    // One drag, one UiHistory entry
    var gesture: AnyRef = Object()
    // In a right-to-left row the first pane is on the right, so a leftward drag grows it, as
    // ArrowLeft does
    var sign: Double = 1
    val drag         = PointerDrag(
      onMove =
        (dx, dy) =>
          ChangeGate.inGesture(gesture) {
            resizeBy(
              startSizes,
              startPx,
              if dir == SplitDirection.Row then
                sign * dx
              else
                dy
              ,
              ChangeCause(ChangeReason.Drag, ChangeSource.Pointer)
            )
          },
      onStart =
        e =>
          gesture = Object()
          startSizes = sizes
          startPx = available
          sign =
            if dir == SplitDirection.Row && TextDirection.isRtl(e) then
              -1
            else
              1
      ,
      cursor =
        if dir == SplitDirection.Row then
          "col-resize"
        else
          "row-resize"
      ,
      // Escape puts the sizes from before the drag back
      onCancel = Some(e =>
        ChangeGate.inGesture(gesture) {
          setLayout(DockModel.withSizes(now, path, startSizes), ChangeCause.dragCancel(e))
        }
      )
    )

    def onKey(e: dom.KeyboardEvent): Unit =
      // In a right-to-left row the first pane is on the right, so ArrowLeft grows it
      val key = TextDirection.logicalKey(
        e.key,
        dir == SplitDirection.Row && TextDirection.mirrors(e)
      )
      val (back, forward) =
        if dir == SplitDirection.Row then
          ("ArrowLeft", "ArrowRight")
        else
          ("ArrowUp", "ArrowDown")
      val step =
        if e.shiftKey then
          Splitter.largeStep
        else
          Splitter.step
      val delta: Option[Double] =
        key match
          case k if k == back =>
            Some(-step)
          case k if k == forward =>
            Some(step)
          case "Home" =>
            Some(Double.NegativeInfinity)
          case "End" =>
            Some(Double.PositiveInfinity)
          case _ =>
            None
      delta match
        case Some(d) =>
          e.preventDefault()
          val px = available
          // Infinity would turn into NaN; the split's size is a bound large enough
          resizeBy(sizes, px, d.max(-px).min(px), ChangeCause.of(ChangeReason.NavigationKey, e))
        case None if e.key == "Enter" =>
          e.preventDefault()
          val n = sizes.size
          setLayout(
            DockModel.withSizes(now, path, Seq.fill(n)(1.0 / n)),
            ChangeCause.of(ChangeReason.Reset, e)
          )
        case None =>

    end onKey

    val share = live.map(r =>
      DockModel.nodeAt(r, path) match
        case Some(DockNode.Split(_, _, ss)) =>
          ss.lift(border).map(s => math.round(s * 100).toString).getOrElse("50")
        case _ =>
          "50"
    )
    div(
      cls             -> "uni-dock__separator",
      data("slot")    -> "dock-separator",
      role            -> "separator",
      tabindex        -> "0",
      ariaLabel       -> "Resize panels",
      titleAttr       -> "Drag to resize, Enter to share equally",
      ariaOrientation -> (
        if dir == SplitDirection.Row then
          "vertical"
        else
          "horizontal"
      ),
      ariaValuemin     -> "0",
      ariaValuemax     -> "100",
      ariaValuenow     -> distinct(share),
      data("dragging") -> drag.dragging.map(_.toString),
      EventListenerBinding("keydown", e => onKey(e.asInstanceOf[dom.KeyboardEvent])),
      drag.bind
    )

  end separator

  private def node(
      n: DockNode,
      path: DockPath,
      live: Rx[DockLayout],
      groupCount: Int,
      corner: DockPath,
      tops: Set[DockPath]
  ): RxElement =
    n match
      case DockNode.Group(tabs, _) if tabs.isEmpty =>
        // Keep the bar for the actions, such as a button that folds the dock
        div(
          cls          -> "uni-dock__group",
          data("slot") -> "dock-group",
          // A drop target too, for a tab dragged from a float
          data("path") -> pathAttr(path),
          data("top")  -> "true",
          when(
            actions.nonEmpty,
            div(
              cls -> "uni-dock__bar",
              div(cls -> "uni-dock__tabs"),
              div(cls -> "uni-dock__actions", actions)
            )
          ),
          div(cls -> "uni-dock__empty", "No open panels")
        )
      case DockNode.Group(tabs, _) =>
        group(tabs, path, live, groupCount, path == corner, tops.contains(path))
      case DockNode.Split(dir, children, _) =>
        val splitRef = DomRef[dom.HTMLElement]()
        val cells    = children
          .zipWithIndex
          .flatMap { (c, i) =>
            val grow = distinct(
              live.map(r =>
                DockModel.nodeAt(r, path) match
                  case Some(DockNode.Split(_, _, ss)) =>
                    ss.lift(i).getOrElse(1.0)
                  case _ =>
                    1.0
              )
            )
            val cell = div(
              cls       -> "uni-dock__cell",
              styleAttr -> grow.map(g => f"flex: ${g}%.6f 1 0px"),
              node(c, path :+ i, live, groupCount, corner, tops)
            )
            if i == 0 then
              Seq(cell)
            else
              Seq(separator(path, i - 1, dir, live, splitRef), cell)
          }
        div(
          cls               -> "uni-dock__split",
          data("slot")      -> "dock-split",
          data("direction") -> dir.toString.toLowerCase,
          splitRef.binding,
          cells
        )

  override def render: RxElement =
    // Sizes, boxes, and the active tabs update in place while the structure stays the same; a new
    // structure rebuilds the tree, and the old group paths no longer apply
    // The grid and the floats rebuild apart, so a change in a float leaves the grid's content
    // alone. Each also needs the number of groups, which names the groups and the menus use.
    def gridKey(l: DockLayout): (DockNode, Int) =
      val s = shapeOf(l)
      (s.root, DockModel.groups(s).size)
    def floatsKey(l: DockLayout): (Seq[DockFloat], Int) =
      val s = shapeOf(l)
      (s.floating, DockModel.groups(s.root).size)
    div(
      cls              -> "uni-dock",
      data("slot")     -> "dock",
      data("dragging") -> dragged.map(_.isDefined.toString),
      dockRef.binding,
      AgentRegistry.own(stateOwner),
      distinct(current.map(gridKey)).map { key =>
        focusedGroup = None
        val (root, n) = key
        node(
          root,
          Nil,
          current.filter(gridKey(_) == key),
          n,
          DockModel.cornerGroup(root),
          DockModel.topGroups(root)
        )
      },
      div(
        cls          -> "uni-dock__floats",
        data("slot") -> "dock-floats",
        distinct(current.map(floatsKey)).map { key =>
          focusedGroup = None
          val (floats, gridGroups) = key
          stack := floats.indices
          val live = current.filter(floatsKey(_) == key)
          floats.zipWithIndex.map((f, i) => floatGroup(i, f.panels, live, gridGroups + floats.size))
        }
      ),
      drop.map {
        case Some(d) =>
          div(
            cls          -> "uni-dock__drop",
            data("slot") -> "dock-drop",
            data("side") -> d.side.toString.toLowerCase,
            data("kind") -> (
              if d.index.isDefined then
                "tab"
              else
                "zone"
            ),
            ariaHidden -> "true",
            styleAttr  -> d.preview
          )
        case None =>
          DomNode.empty
      }
    )

  end render

end Dock

object Dock:
  // `new` is required in this file: the `apply` below replaces the constructor proxy
  def apply(layout: RxVar[DockLayout], label: String, panels: Seq[DockPanel]): Dock =
    new Dock(
      layout,
      label,
      panels,
      minPanelSize = 120,
      actions = Seq.empty,
      detachable = false,
      changeHandler = ChangeGate.noHandler
    )

  /** Pixels a tab moves before a press becomes a drag, so a click still just selects. */
  val dragThreshold: Double = 4

  /** Pixels an arrow key moves or resizes a floating group. */
  val floatStep: Int = 16

  /** The outer share of a group, on each side, where a drop splits it instead of adding to it. */
  val edgeBand: Double = 0.25
