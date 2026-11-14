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
import wvlet.uni.dom.PointerDrag
import wvlet.uni.rx.{Rx, RxVar}
import wvlet.uni.ui.base.{
  Announce,
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
export wvlet.uni.ui.base.{DockLayout, DockNode, DockSide, SplitDirection}

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
  * The dock is a `div.uni-dock`; groups are `div.uni-dock__group` with a `role="tablist"` of
  * `role="tab"` tabs and a `role="tabpanel"`, and `data-top="true"` on the groups along the top
  * edge (to align their tab bar with a title bar). Splits are `div.uni-dock__split` with
  * `data-direction="row|column"`.
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
    changeHandler: ChangeHandler
) extends RxElement:

  private def copy(
      minPanelSize: Int = minPanelSize,
      actions: Seq[DomNode] = actions,
      changeHandler: ChangeHandler = changeHandler
  ): Dock = new Dock(layout, label, panels, minPanelSize, actions, changeHandler)

  /**
    * Called before the user (or [[open]] and [[close]]) changes the layout; `cancel()` keeps it.
    * Key: `layout`.
    */
  def onChange(f: ChangeHandler): Dock = copy(changeHandler = f)

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

  private val byId: Map[String, DockPanel] = panels.map(p => p.id -> p).toMap
  private val required: Seq[String]        = panels.filterNot(_.closable).map(_.id)

  // Every subscriber sees the same layout instance per change, so remember the last result and
  // normalize once per change rather than once per subscriber
  private var lastNorm: (DockLayout, DockLayout) = (null, null)
  private def norm(l: DockLayout): DockLayout    =
    if l ne lastNorm._1 then
      lastNorm = (l, DockModel.normalize(l, byId.contains, required))
    lastNorm._2

  private var lastShape: (DockNode, DockNode)   = (null, null)
  private def shapeOf(root: DockNode): DockNode =
    if root ne lastShape._1 then
      lastShape = (root, DockModel.shape(root))
    lastShape._2

  private def now: DockLayout = norm(layout.get)

  /** The layout as the Dock shows it: unknown panels dropped, required ones added. */
  val current: Rx[DockLayout] = layout.map(norm)

  /** The panels the user can see: the active tab of each group. */
  def visible: Rx[Set[String]] = current.map(l => DockModel.visible(l.root))

  /** Registered panels that are not in the layout, which [[open]] can show again. */
  def hidden: Rx[Seq[String]] = current.map { l =>
    val in = DockModel.panels(l.root).toSet
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
    * The number of groups. Groups are numbered from 0 in reading order (depth first), so group `i`
    * has the accessible name "group i+1".
    */
  val groupCount: Rx[Int] = distinct(current.map(l => DockModel.groups(l.root).size))

  /**
    * Move keyboard focus to the active tab of group `index` (from 0, in reading order), as an
    * editor's Ctrl+1, Ctrl+2 shortcuts do. Returns false, and leaves focus alone, when there is no
    * such group or the Dock isn't mounted.
    */
  def focusGroup(index: Int): Boolean =
    val root = now.root
    DockModel
      .groups(root)
      .lift(index)
      .flatMap(DockModel.nodeAt(root, _))
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

  override def onMount(node: Any): Unit =
    // Save the cleaned-up layout, so unknown ids leave the app's storage too
    layout := now

  // --- Drag and drop -----------------------------------------------------------------------------

  /** Where a dragged tab would land: a tab position in a group, or a side of a group. */
  private case class Drop(path: DockPath, side: DockSide, index: Option[Int], preview: String)

  private val drop: RxVar[Option[Drop]]      = Rx.variable(None)
  private val dragged: RxVar[Option[String]] = Rx.variable(None)
  private var dragStart: (Double, Double)    = (0, 0)
  private var dragMoved: Boolean             = false
  private var suppressClick: Boolean         = false
  private var stopDrag: () => Unit           = () => ()

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
          val side    = edges.minByOption(_._2).map(_._1).getOrElse(DockSide.Center)
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
    val cancel: js.Function1[dom.Event, Unit] =
      ev =>
        ev match
          case k: dom.KeyboardEvent if k.key != "Escape" =>
          case _                                         =>
            drop    := None
            dragged := None
    dom.window.addEventListener("keydown", cancel)
    // Capture, so this runs before the drag's own pointercancel handler ends it
    dom.window.addEventListener("pointercancel", cancel, useCapture = true)
    stopDrag =
      () =>
        dom.window.removeEventListener("keydown", cancel)
        dom.window.removeEventListener("pointercancel", cancel, useCapture = true)

  private def moveDrag(panel: String, dx: Double, dy: Double): Unit =
    if !dragMoved && math.hypot(dx, dy) >= Dock.dragThreshold then
      dragMoved = true
      dragged := Some(panel)
    // dragged is cleared by Escape
    if dragMoved && dragged.get.contains(panel) then
      drop := dropAt(dragStart._1 + dx, dragStart._2 + dy)

  private def endDrag(panel: String): Unit =
    stopDrag()
    if dragMoved then
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
    val i    = tabs.indexOf(panel.id)
    val id   = panel.id
    val next =
      () =>
        val gs = DockModel.groups(now.root)
        gs.lift((gs.indexOf(path) + 1) % gs.size).getOrElse(path)
    val items = Seq[MenuEntry](
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
        .withDisabled(tabs.size < 2)
        .onSelect(() => act(id)(DockModel.split(_, id, path, DockSide.Right))),
      MenuItem("Split down")
        .withDisabled(tabs.size < 2)
        .onSelect(() => act(id)(DockModel.split(_, id, path, DockSide.Bottom)))
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
      onEnd = () => endDrag(panel.id)
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
      live: Rx[DockNode],
      groupCount: Int,
      corner: Boolean,
      top: Boolean
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
        s"${label}, group ${DockModel.groups(DockModel.shape(now.root)).indexOf(path) + 1}"
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
        active.map(_.map(contentOf).getOrElse(DomNode.empty))
      )
    )

  end group

  private def separator(
      path: DockPath,
      border: Int,
      dir: SplitDirection,
      live: Rx[DockNode],
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
    val drag                    = PointerDrag(
      onMove =
        (dx, dy) =>
          resizeBy(
            startSizes,
            startPx,
            if dir == SplitDirection.Row then
              dx
            else
              dy
            ,
            ChangeCause(ChangeReason.Drag, ChangeSource.Pointer)
          ),
      onStart =
        _ =>
          startSizes = sizes
          startPx = available
      ,
      cursor =
        if dir == SplitDirection.Row then
          "col-resize"
        else
          "row-resize"
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
      live: Rx[DockNode],
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
    val roots = current.map(_.root)
    div(
      cls              -> "uni-dock",
      data("slot")     -> "dock",
      data("dragging") -> dragged.map(_.isDefined.toString),
      dockRef.binding,
      distinct(roots.map(shapeOf)).map { shape =>
        // Sizes and the active tabs update in place while the structure stays the same; a new
        // structure rebuilds the tree, and the old group paths no longer apply
        focusedGroup = None
        val live = roots.filter(r => shapeOf(r) == shape)
        node(
          shape,
          Nil,
          live,
          DockModel.groups(shape).size,
          DockModel.cornerGroup(shape),
          DockModel.topGroups(shape)
        )
      },
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
      changeHandler = ChangeGate.noHandler
    )

  /** Pixels a tab moves before a press becomes a drag, so a click still just selects. */
  val dragThreshold: Double = 4

  /** The outer share of a group, on each side, where a drop splits it instead of adding to it. */
  val edgeBand: Double = 0.25
