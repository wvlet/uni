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
package wvlet.uni.ui.base

import org.scalajs.dom
import wvlet.uni.dom.{DomAttribute, DomNode, EventListenerBinding, OnMount, PointerDrag}
import wvlet.uni.rx.{Cancelable, Rx, RxVar}
import wvlet.uni.ui.{ChangeCause, ChangeGate, ChangeHandler, ChangeReason, ChangeSource}

import scala.scalajs.js

/**
  * Lets the user reorder a list by dragging an item, or by pressing Alt+ArrowUp/Down (Left/Right in
  * a horizontal list, mirrored in a right-to-left layout) and Alt+Home/End on a focused item. A
  * drag shows where the item would land and moves it once, on release; Escape cancels it. Dragging
  * near the edge of the list's scroll container scrolls it. Each move is announced to screen
  * readers, and focus stays on the moved item.
  *
  * The order lives in the app's `order` variable. Every move goes through [[onChange]], which can
  * cancel it, with the reason (`Drag` or `NavigationKey`) and source:
  *
  * {{{
  *   val tasks   = Rx.variable(Seq("Write", "Review", "Ship"))
  *   val reorder = Reorder(tasks).onChange(d => println(d))
  *   ul(tasks.each(identity)(t => li(tabindex -> "0", reorder.item(t), t)))
  * }}}
  *
  * With [[withSelection]], dragging or moving a selected item moves every selected item together,
  * as one block in their current order.
  *
  * When the app's variable holds the items themselves rather than their keys, use [[Reorder.by]].
  * [[wvlet.uni.ui.VirtualList.withReorder]] and [[wvlet.uni.ui.DataTable.withReorder]] wire one
  * into their rows.
  *
  * Put [[Reorder.handle]] on an element inside an item to start drags only from that element.
  * Space, Enter, and letter keys are left alone, so [[Selection]] and [[Typeahead]] work on the
  * same items.
  *
  * @param label
  *   the name of an item in announcements, such as "Moved Review to position 1 of 3"
  */
class Reorder[K] private[ui] (
    store: Reorder.Store[K],
    label: K => String,
    orientation: Orientation
):
  def this(
      order: RxVar[Seq[K]],
      label: K => String = (k: K) => k.toString,
      orientation: Orientation = Orientation.Vertical
  ) = this(Reorder.Store.ofKeys(order), label, orientation)

  private var handler: ChangeHandler          = ChangeGate.noHandler
  private var selected: Option[RxVar[Set[K]]] = None
  private val draggedVar: RxVar[Option[K]]    = Rx.variable(None)
  private val draggedGroupVar: RxVar[Set[K]]  = Rx.variable(Set.empty)
  private val dropVar: RxVar[Option[Int]]     = Rx.variable(None)
  private val elements                      = scala.collection.mutable.Map.empty[K, dom.HTMLElement]
  private var dragStart: (Double, Double)   = (0, 0)
  private var dragPointer: (Double, Double) = (0, 0)
  private var dragActive: Boolean           = false
  private var dragMoved: Boolean            = false
  private var suppressClick: Boolean        = false
  private var stopDrag: () => Unit          = () => ()
  private var scroller: Option[dom.HTMLElement] = None
  private var dragRtl: Boolean                  = false
  private var scrollFrame: Option[Int]          = None
  private var enabled: () => Boolean            = () => true

  /** Sees every move before it lands; `cancel()` keeps the previous order. */
  def onChange(f: ChangeHandler): this.type =
    handler = f
    this

  /**
    * Moves the selected items together: a drag or Alt+Arrow key on a selected item moves every item
    * in `s` as one block. Pass the same variable as [[Selection]] or a list's `withMultiSelect`. An
    * item outside the selection still moves alone.
    */
  def withSelection(s: RxVar[Set[K]]): this.type =
    selected = Some(s)
    this

  /** The keys in their current order. */
  def order: Rx[Seq[K]] = store.keys

  /**
    * Turns reordering off while `f` is false: drags don't start and the move keys are left alone,
    * as in a table sorted by a column.
    */
  private[ui] def enabledWhen(f: () => Boolean): this.type =
    enabled = f
    this

  private[ui] def isEnabled: Boolean = enabled()

  /** The item being dragged, once the pointer moved past the drag threshold. */
  def dragging: Rx[Option[K]] = draggedVar

  /** Every item the drag moves: the selection when a selected item is dragged. Empty otherwise. */
  def draggingItems: Rx[Set[K]] = draggedGroupVar

  /**
    * Where the dragged items would land, as an insertion point in the current order (0 to size).
    * None while no drag is in progress or when the drop would not change the order.
    */
  def dropIndex: Rx[Option[Int]] = dropVar

  /** The items that move with `k`: the selection when `k` is selected, or `k` alone. */
  private def groupOf(k: K): Set[K] =
    selected.map(_.get) match
      case Some(s) if s.contains(k) =>
        s
      case _ =>
        Set(k)

  /**
    * Moves `k` to position `to` (from 0) and announces it. Returns false when `k` isn't in the
    * order or [[onChange]] canceled the move. Only `k` moves, even when it is selected.
    */
  def moveTo(k: K, to: Int, cause: ChangeCause = ChangeCause.current): Boolean = moveAll(
    Set(k),
    to,
    cause
  )

  /**
    * Moves the `keys` that are in the order as one block, in their current order, so the first
    * starts at position `to` (from 0), and announces it. Returns false when none of them is in the
    * order or [[onChange]] canceled the move. Focus stays on the moved item that had it.
    */
  def moveAll(keys: Set[K], to: Int, cause: ChangeCause = ChangeCause.current): Boolean =
    val current = store.current
    val moving  = current.filter(keys.contains)
    if moving.isEmpty then
      false
    else
      val next    = Reorder.movedAll(current, keys, to)
      val focused = moving.find(m => elements.get(m).exists(_.contains(dom.document.activeElement)))
      val accepted = store.write(next, cause, handler)
      if accepted && next != current then
        val at   = next.indexOf(moving.head) + 1
        val what =
          if moving.size == 1 then
            label(moving.head)
          else
            s"${moving.size} items"
        Announce.polite(s"Moved ${what} to position ${at} of ${next.size}")
        // Moving or re-creating the item's node can drop its focus
        focused.foreach { f =>
          elements.get(f).filterNot(_.contains(dom.document.activeElement)).foreach(_.focus())
        }
      accepted

  /**
    * The modifier to put on each item: it starts drags, handles Alt+Arrow keys, and marks the item
    * with `data-reorder-item`, `data-dragging` while dragged, and `data-drop="before"` or `"after"`
    * where the dragged items would land.
    */
  def item(k: K): DomNode = DomNode.group(
    DomAttribute("data-reorder-item", "true"),
    DomAttribute("data-reorder-axis", orientation.toString.toLowerCase),
    DomAttribute("aria-description", Reorder.keyHint(orientation)),
    DomAttribute("data-dragging", draggedGroupVar.map(g => g.contains(k).toString)),
    DomAttribute(
      "data-drop",
      Rx.join(dropVar, store.keys).map((d, o) => Reorder.dropSide(d, o, k))
    ),
    OnMount(el => attach(k, el)),
    EventListenerBinding(
      "keydown",
      {
        case e: dom.KeyboardEvent if !Reorder.inField(e.target) && onMoveKey(k, e) =>
          e.stopPropagation()
        case _ =>
      }
    ),
    PointerDrag(
      onMove = (dx, dy) => moveDrag(k, dx, dy),
      onStart = startDrag,
      onEnd = () => endDrag(k),
      // One drag at a time: a second finger on another item doesn't start another
      canStart = e => !dragActive && enabled() && Reorder.canStart(e)
    ).bind
  )

  /**
    * Handles Alt+`e.key` as a move of `k` (and the selection with it): true when it is a move key,
    * whether or not `k` could move. A list that keeps focus on its container calls this for its
    * active row.
    */
  private[ui] def onMoveKey(k: K, e: dom.KeyboardEvent): Boolean =
    val isMove =
      e.altKey && !e.ctrlKey && !e.metaKey && !e.shiftKey && enabled() &&
        Reorder.isMoveKey(e.key, orientation)
    if isMove then
      // Even when the item can't move further, so Alt+ArrowLeft doesn't go Back in the browser
      e.preventDefault()
      moveByKey(k, e)
    isMove

  private def moveByKey(k: K, e: dom.KeyboardEvent): Unit =
    val o     = store.current
    val group = groupOf(k)
    // The block's position among the other items, taken where the pressed item is: a scattered
    // selection gathers there
    val rest = o.count(!group.contains(_))
    val at   = o.takeWhile(_ != k).count(!group.contains(_))
    val rtl  = orientation == Orientation.Horizontal && elements.get(k).exists(TextDirection.isRtl)
    if o.contains(k) then
      // At an end, a scattered selection still gathers in place
      val to = Reorder.keyTarget(e.key, at, rest + 1, orientation, rtl).getOrElse(at)
      if Reorder.movedAll(o, group, to) != o then
        moveAll(group, to, ChangeCause.of(ChangeReason.NavigationKey, e))

  private def attach(k: K, el: dom.HTMLElement): Cancelable =
    elements(k) = el
    // Capture, so the click that follows a drag's release doesn't reach the item's own handlers
    val onClick: js.Function1[dom.Event, Unit] =
      e =>
        if suppressClick then
          e.preventDefault()
          // Immediate: a list's row may listen for clicks on the item element itself
          e.stopImmediatePropagation()
    el.addEventListener("click", onClick, useCapture = true)
    Cancelable { () =>
      el.removeEventListener("click", onClick, useCapture = true)
      if elements.get(k).exists(_ eq el) then
        elements -= k
    }

  // --- Pointer drag ------------------------------------------------------------------------------

  private def startDrag(e: dom.PointerEvent): Unit =
    dragActive = true
    dragStart = (e.clientX, e.clientY)
    dragPointer = dragStart
    dragMoved = false
    val cancel: js.Function1[dom.Event, Unit] =
      ev =>
        ev match
          case k: dom.KeyboardEvent if k.key != "Escape" =>
          case _                                         =>
            stopScroll()
            dropVar         := None
            draggedVar      := None
            draggedGroupVar := Set.empty
    dom.window.addEventListener("keydown", cancel)
    // Capture, so this runs before the drag's own pointercancel handler ends it
    dom.window.addEventListener("pointercancel", cancel, useCapture = true)
    stopDrag =
      () =>
        stopScroll()
        dom.window.removeEventListener("keydown", cancel)
        dom.window.removeEventListener("pointercancel", cancel, useCapture = true)

  private def moveDrag(k: K, dx: Double, dy: Double): Unit =
    dragPointer = (dragStart._1 + dx, dragStart._2 + dy)
    if !dragMoved && math.hypot(dx, dy) >= Reorder.dragThreshold then
      dragMoved = true
      // Read once per drag, not on every click or move: the item may unmount as the list scrolls
      elements
        .get(k)
        .foreach { el =>
          scroller = Reorder.scrollContainer(el, orientation)
          dragRtl = orientation == Orientation.Horizontal && TextDirection.isRtl(el)
        }
      draggedVar      := Some(k)
      draggedGroupVar := groupOf(k)
    // draggedVar is cleared by Escape
    if dragMoved && draggedVar.get.contains(k) then
      updateDrop()
      if scrollFrame.isEmpty && autoscrollSpeed != 0 then
        scrollFrame = Some(dom.window.requestAnimationFrame(_ => autoscroll()))

  private def updateDrop(): Unit =
    val group = draggedGroupVar.get
    dropVar := dropAt(group, dragPointer._1, dragPointer._2)

  // --- Autoscroll ---------------------------------------------------------------------------------

  private def autoscrollSpeed: Double =
    scroller.fold(0.0) { s =>
      val r = s.getBoundingClientRect()
      orientation match
        case Orientation.Vertical =>
          Reorder.autoscrollStep(dragPointer._2, r.top, r.bottom)
        case Orientation.Horizontal =>
          Reorder.autoscrollStep(dragPointer._1, r.left, r.right)
    }

  // Scrolls one step per frame while the pointer stays near an edge, then moves the drop line to
  // the items now under the pointer
  private def autoscroll(): Unit =
    scrollFrame = None
    if dragMoved && draggedVar.get.nonEmpty then
      val speed = autoscrollSpeed
      scroller.foreach { s =>
        if speed != 0 then
          val before = (s.scrollLeft, s.scrollTop)
          orientation match
            case Orientation.Vertical =>
              s.scrollTop = s.scrollTop + speed
            case Orientation.Horizontal =>
              s.scrollLeft = s.scrollLeft + speed
          // Keep going only while the container still scrolls that way
          if (s.scrollLeft, s.scrollTop) != before then
            updateDrop()
            scrollFrame = Some(dom.window.requestAnimationFrame(_ => autoscroll()))
      }

  private def stopScroll(): Unit =
    scrollFrame.foreach(dom.window.cancelAnimationFrame)
    scrollFrame = None

  private def dropAt(group: Set[K], x: Double, y: Double): Option[Int] =
    val o    = store.current
    val sign =
      if dragRtl then
        -1.0
      else
        1.0
    // Positions and midpoints of the items that are rendered, in order. A windowed list renders
    // only some of them; the pointer can then land only between those. In a right-to-left row
    // the positions are negated, so "before" is to the right.
    val mids = o
      .zipWithIndex
      .flatMap { (key, i) =>
        elements
          .get(key)
          .filter(_.isConnected)
          .map { el =>
            val r = el.getBoundingClientRect()
            orientation match
              case Orientation.Vertical =>
                (i, r.top + r.height / 2)
              case Orientation.Horizontal =>
                (i, sign * (r.left + r.width / 2))
          }
      }
    if !o.exists(group.contains) || mids.isEmpty then
      None
    else
      val pos =
        orientation match
          case Orientation.Vertical =>
            y
          case Orientation.Horizontal =>
            sign * x
      val at =
        Reorder.dropIndex(mids.map(_._2), pos) match
          case j if j < mids.size =>
            mids(j)._1
          case _ =>
            mids.last._1 + 1
      Some(at).filter(at => Reorder.movedTo(o, group, at) != o)

  end dropAt

  private def endDrag(k: K): Unit =
    stopDrag()
    dragActive = false
    if dragMoved then
      suppressClick = true
      dom.window.setTimeout(() => suppressClick = false, 0)
      val group = draggedGroupVar.get
      dropVar
        .get
        .foreach { at =>
          val o = store.current
          moveAll(
            group,
            Reorder.landingOf(o, group, at),
            ChangeCause(ChangeReason.Drag, ChangeSource.Pointer)
          )
        }
    dropVar         := None
    draggedVar      := None
    draggedGroupVar := Set.empty
    dragMoved = false
    scroller = None
    dragRtl = false

end Reorder

object Reorder:
  /**
    * Reorders the items in `items`, matched by `key`, and writes them back in the new order: for an
    * app whose variable holds the items rather than their keys.
    *
    * {{{
    *   val tasks   = Rx.variable(Seq(Task(1, "Write"), Task(2, "Review")))
    *   val reorder = Reorder.by(tasks, label = _.title)(_.id)
    *   ul(tasks.each(_.id)(t => li(tabindex -> "0", reorder.item(t.id), t.title)))
    * }}}
    *
    * [[onChange]] then sees the new `Seq[A]` as its value.
    */
  def by[A, K](
      items: RxVar[Seq[A]],
      label: A => String = (a: A) => a.toString,
      orientation: Orientation = Orientation.Vertical
  )(key: A => K): Reorder[K] =
    def labelOf(k: K): String = items.get.find(key(_) == k).fold(k.toString)(label)
    new Reorder(Store.ofItems(items, key), labelOf, orientation)

  /** Where a [[Reorder]] reads and writes the order. */
  private[ui] trait Store[K]:
    def keys: Rx[Seq[K]]
    def current: Seq[K]
    def write(next: Seq[K], cause: ChangeCause, handler: ChangeHandler): Boolean

  private[ui] object Store:
    def ofKeys[K](order: RxVar[Seq[K]]): Store[K] =
      new Store[K]:
        def keys: Rx[Seq[K]]                                                         = order
        def current: Seq[K]                                                          = order.get
        def write(next: Seq[K], cause: ChangeCause, handler: ChangeHandler): Boolean = ChangeGate
          .write(order, "order", next, cause, handler)

    def ofItems[A, K](items: RxVar[Seq[A]], key: A => K): Store[K] =
      new Store[K]:
        def keys: Rx[Seq[K]] = items.map(_.map(key))
        def current: Seq[K]  = items.get.map(key)
        def write(next: Seq[K], cause: ChangeCause, handler: ChangeHandler): Boolean =
          val byKey = items.get.map(a => key(a) -> a).toMap
          ChangeGate.write(items, "order", next.map(byKey), cause, handler)

  end Store

  /** How far, in CSS pixels, the pointer moves before a press becomes a drag. */
  val dragThreshold: Double = 4

  /**
    * Put this on an element inside a [[Reorder.item]], such as a grip icon, so drags start only
    * from it. Presses elsewhere in the item then click as usual.
    */
  val handle: DomNode = DomAttribute("data-reorder-handle", "true")

  // With a handle in the item, only presses on it drag; presses on a field never do
  private def canStart(e: dom.PointerEvent): Boolean =
    (e.target, e.currentTarget) match
      case (t: dom.Element, item: dom.Element) =>
        val fromHandle = Option(t.closest("[data-reorder-handle]")).exists(item.contains)
        val hasHandle  = item.querySelector("[data-reorder-handle]") != null
        fromHandle || (!hasHandle && !inField(t))
      case _ =>
        true

  // Keys and presses in a text field belong to the field
  private def inField(t: dom.EventTarget): Boolean =
    t match
      case el: dom.Element =>
        el.closest("input, textarea, select, [contenteditable]") != null
      case _ =>
        false

  /** Whether Alt+`key` is a [[keyTarget]] key along `orientation`, whether or not it moves. */
  def isMoveKey(key: String, orientation: Orientation = Orientation.Vertical): Boolean =
    key == "Home" || key == "End" || (
      orientation match
        case Orientation.Vertical =>
          key == "ArrowUp" || key == "ArrowDown"
        case Orientation.Horizontal =>
          key == "ArrowLeft" || key == "ArrowRight"
    )

  /** `order` with the item at `from` moved to position `to`, clamped to the list. */
  def moved[K](order: Seq[K], from: Int, to: Int): Seq[K] =
    if from < 0 || from >= order.size then
      order
    else
      val k    = order(from)
      val rest = order.patch(from, Nil, 1)
      val at   = math.max(0, math.min(rest.size, to))
      rest.patch(at, Seq(k), 0)

  /**
    * The position an item at `from` ends up in when inserted at `at`, an insertion point in the
    * list before the item is removed (0 to size). Removing it first shifts later items left by one.
    */
  def landing(from: Int, at: Int): Int =
    if at > from then
      at - 1
    else
      at

  /**
    * `order` with the `keys` in it moved as one block, in their current order, so the first starts
    * at position `to` (clamped to the list).
    */
  def movedAll[K](order: Seq[K], keys: Set[K], to: Int): Seq[K] =
    val (block, rest) = order.partition(keys.contains)
    rest.patch(math.max(0, math.min(rest.size, to)), block, 0)

  /**
    * The position the block of `keys` starts at when inserted at `at`, an insertion point in
    * `order` before they are removed (0 to size): the number of other items before `at`.
    */
  def landingOf[K](order: Seq[K], keys: Set[K], at: Int): Int = order
    .take(at)
    .count(!keys.contains(_))

  /** `order` with the block of `keys` dropped at insertion point `at`. */
  def movedTo[K](order: Seq[K], keys: Set[K], at: Int): Seq[K] = movedAll(
    order,
    keys,
    landingOf(order, keys, at)
  )

  /**
    * How far to scroll per frame while dragging at `pos`, given the scroll container's `start` and
    * `end` along the list's axis: negative near the start, positive near the end, faster closer to
    * the edge, and 0 in between. The edge zone is 40 pixels, or a fifth of a small container.
    */
  def autoscrollStep(pos: Double, start: Double, end: Double, maxStep: Double = 16): Double =
    val zone = math.min(40.0, (end - start) / 5)
    if zone <= 0 then
      0
    else if pos < start + zone then
      -math.ceil(maxStep * math.min(1.0, (start + zone - pos) / zone))
    else if pos > end - zone then
      math.ceil(maxStep * math.min(1.0, (pos - (end - zone)) / zone))
    else
      0

  /**
    * The nearest ancestor of `el` that scrolls along `orientation`, below the page itself. Drags
    * scroll only this container, not the page.
    */
  private def scrollContainer(el: dom.Element, orientation: Orientation): Option[dom.HTMLElement] =
    def scrolls(e: dom.HTMLElement): Boolean =
      val style = dom.window.getComputedStyle(e)
      orientation match
        case Orientation.Vertical =>
          Set("auto", "scroll").contains(style.overflowY) && e.scrollHeight > e.clientHeight
        case Orientation.Horizontal =>
          Set("auto", "scroll").contains(style.overflowX) && e.scrollWidth > e.clientWidth
    Iterator
      .iterate(el.parentNode)(_.parentNode)
      .takeWhile(n =>
        n != null && !(n eq dom.document.body) && !(n eq dom.document.documentElement)
      )
      .collectFirst {
        case e: dom.HTMLElement if scrolls(e) =>
          e
      }

  /**
    * The insertion point (0 to size) for a pointer at `pos`, given the midpoints of the items along
    * the list's axis: before the first item whose middle is past the pointer.
    */
  def dropIndex(midpoints: Seq[Double], pos: Double): Int =
    midpoints.indexWhere(pos < _) match
      case -1 =>
        midpoints.size
      case i =>
        i

  /**
    * The position Alt+`key` moves the item at `from` to, in a list of `size` items: one step for
    * the arrow keys along `orientation`, the start for Home, and the end for End. With `rtl`, a
    * horizontal list runs right to left, so ArrowLeft moves toward the end. None for other keys and
    * when the item can't move that way.
    */
  def keyTarget(
      key: String,
      from: Int,
      size: Int,
      orientation: Orientation = Orientation.Vertical,
      rtl: Boolean = false
  ): Option[Int] =
    val (prevKey, nextKey) =
      orientation match
        case Orientation.Horizontal =>
          ("ArrowLeft", "ArrowRight")
        case Orientation.Vertical =>
          ("ArrowUp", "ArrowDown")
    val to =
      TextDirection.logicalKey(key, rtl && orientation == Orientation.Horizontal) match
        case `prevKey` =>
          Some(from - 1)
        case `nextKey` =>
          Some(from + 1)
        case "Home" =>
          Some(0)
        case "End" =>
          Some(size - 1)
        case _ =>
          None
    to.filter(t => from >= 0 && from < size && t >= 0 && t < size && t != from)

  /** The keys that move an item, for its `aria-description`. */
  def keyHint(orientation: Orientation): String =
    orientation match
      case Orientation.Vertical =>
        "Alt+ArrowUp or Alt+ArrowDown to move"
      case Orientation.Horizontal =>
        "Alt+ArrowLeft or Alt+ArrowRight to move"

  // "before" on the item at the insertion point, or "after" on the last item for the end
  private def dropSide[K](drop: Option[Int], order: Seq[K], k: K): Option[String] = drop.flatMap {
    at =>
      val i = order.indexOf(k)
      if i == at then
        Some("before")
      else if at == order.size && i == order.size - 1 then
        Some("after")
      else
        None
  }

end Reorder
