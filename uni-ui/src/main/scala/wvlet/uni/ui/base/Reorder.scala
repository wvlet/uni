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
  * a horizontal list) and Alt+Home/End on a focused item. A drag shows where the item would land
  * and moves it once, on release; Escape cancels it. Each move is announced to screen readers, and
  * focus stays on the moved item.
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
  * Put [[Reorder.handle]] on an element inside an item to start drags only from that element.
  * Space, Enter, and letter keys are left alone, so [[Selection]] and [[Typeahead]] work on the
  * same items.
  *
  * @param label
  *   the name of an item in announcements, such as "Moved Review to position 1 of 3"
  */
class Reorder[K](
    val order: RxVar[Seq[K]],
    label: K => String = (k: K) => k.toString,
    orientation: Orientation = Orientation.Vertical
):
  private var handler: ChangeHandler       = ChangeGate.noHandler
  private val draggedVar: RxVar[Option[K]] = Rx.variable(None)
  private val dropVar: RxVar[Option[Int]]  = Rx.variable(None)
  private val elements                     = scala.collection.mutable.Map.empty[K, dom.HTMLElement]
  private var dragStart: (Double, Double)  = (0, 0)
  private var dragMoved: Boolean           = false
  private var suppressClick: Boolean       = false
  private var stopDrag: () => Unit         = () => ()

  /** Sees every move before it lands; `cancel()` keeps the previous order. */
  def onChange(f: ChangeHandler): this.type =
    handler = f
    this

  /** The item being dragged, once the pointer moved past the drag threshold. */
  def dragging: Rx[Option[K]] = draggedVar

  /**
    * Where the dragged item would land, as an insertion point in the current order (0 to size).
    * None while no drag is in progress or when the drop would not move the item.
    */
  def dropIndex: Rx[Option[Int]] = dropVar

  /**
    * Moves `k` to position `to` (from 0) and announces it. Returns false when `k` isn't in the
    * order or [[onChange]] canceled the move.
    */
  def moveTo(k: K, to: Int, cause: ChangeCause = ChangeCause.current): Boolean =
    val current = order.get
    val from    = current.indexOf(k)
    if from < 0 then
      false
    else
      val next     = Reorder.moved(current, from, to)
      val focused  = elements.get(k).exists(_.contains(dom.document.activeElement))
      val accepted = ChangeGate.write(order, "order", next, cause, handler)
      if accepted && next != current then
        Announce.polite(s"Moved ${label(k)} to position ${next.indexOf(k) + 1} of ${next.size}")
        // Moving or re-creating the item's node can drop its focus
        if focused then
          elements.get(k).filterNot(_.contains(dom.document.activeElement)).foreach(_.focus())
      accepted

  /**
    * The modifier to put on each item: it starts drags, handles Alt+Arrow keys, and marks the item
    * with `data-reorder-item`, `data-dragging` while dragged, and `data-drop="before"` or `"after"`
    * where the dragged item would land.
    */
  def item(k: K): DomNode = DomNode.group(
    DomAttribute("data-reorder-item", "true"),
    DomAttribute("data-reorder-axis", orientation.toString.toLowerCase),
    DomAttribute("aria-description", Reorder.keyHint(orientation)),
    DomAttribute("data-dragging", draggedVar.map(d => d.contains(k).toString)),
    DomAttribute("data-drop", Rx.join(dropVar, order).map((d, o) => Reorder.dropSide(d, o, k))),
    OnMount(el => attach(k, el)),
    EventListenerBinding(
      "keydown",
      {
        case e: dom.KeyboardEvent if e.altKey && !e.ctrlKey && !e.metaKey && !e.shiftKey =>
          val o = order.get
          Reorder
            .keyTarget(e.key, o.indexOf(k), o.size, orientation)
            .foreach { to =>
              // Also keeps Alt+ArrowLeft from going Back in the browser
              e.preventDefault()
              e.stopPropagation()
              moveTo(k, to, ChangeCause.of(ChangeReason.NavigationKey, e))
            }
        case _ =>
      }
    ),
    PointerDrag(
      onMove = (dx, dy) => moveDrag(k, dx, dy),
      onStart = startDrag,
      onEnd = () => endDrag(k),
      canStart = Reorder.canStart
    ).bind
  )

  private def attach(k: K, el: dom.HTMLElement): Cancelable =
    elements(k) = el
    // Capture, so the click that follows a drag's release doesn't reach the item's own handlers
    val onClick: js.Function1[dom.Event, Unit] =
      e =>
        if suppressClick then
          e.preventDefault()
          e.stopPropagation()
    el.addEventListener("click", onClick, useCapture = true)
    Cancelable { () =>
      el.removeEventListener("click", onClick, useCapture = true)
      if elements.get(k).exists(_ eq el) then
        elements -= k
    }

  // --- Pointer drag ------------------------------------------------------------------------------

  private def startDrag(e: dom.PointerEvent): Unit =
    dragStart = (e.clientX, e.clientY)
    dragMoved = false
    val cancel: js.Function1[dom.Event, Unit] =
      ev =>
        ev match
          case k: dom.KeyboardEvent if k.key != "Escape" =>
          case _                                         =>
            dropVar    := None
            draggedVar := None
    dom.window.addEventListener("keydown", cancel)
    // Capture, so this runs before the drag's own pointercancel handler ends it
    dom.window.addEventListener("pointercancel", cancel, useCapture = true)
    stopDrag =
      () =>
        dom.window.removeEventListener("keydown", cancel)
        dom.window.removeEventListener("pointercancel", cancel, useCapture = true)

  private def moveDrag(k: K, dx: Double, dy: Double): Unit =
    if !dragMoved && math.hypot(dx, dy) >= Reorder.dragThreshold then
      dragMoved = true
      draggedVar := Some(k)
    // draggedVar is cleared by Escape
    if dragMoved && draggedVar.get.contains(k) then
      dropVar := dropAt(k, dragStart._1 + dx, dragStart._2 + dy)

  private def dropAt(k: K, x: Double, y: Double): Option[Int] =
    val o    = order.get
    val from = o.indexOf(k)
    // Midpoints of the items that are on screen, in order
    val mids = o.flatMap { key =>
      elements
        .get(key)
        .filter(_.isConnected)
        .map { el =>
          val r = el.getBoundingClientRect()
          orientation match
            case Orientation.Vertical =>
              r.top + r.height / 2
            case Orientation.Horizontal =>
              r.left + r.width / 2
        }
    }
    if from < 0 || mids.size != o.size then
      None
    else
      val pos =
        orientation match
          case Orientation.Vertical =>
            y
          case Orientation.Horizontal =>
            x
      Some(Reorder.dropIndex(mids, pos)).filter(at => Reorder.landing(from, at) != from)

  private def endDrag(k: K): Unit =
    stopDrag()
    if dragMoved then
      suppressClick = true
      dom.window.setTimeout(() => suppressClick = false, 0)
      dropVar
        .get
        .foreach { at =>
          val from = order.get.indexOf(k)
          if from >= 0 then
            moveTo(
              k,
              Reorder.landing(from, at),
              ChangeCause(ChangeReason.Drag, ChangeSource.Pointer)
            )
        }
    dropVar    := None
    draggedVar := None
    dragMoved = false

end Reorder

object Reorder:
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
        fromHandle ||
        (!hasHandle && t.closest("input, textarea, select, [contenteditable]") == null)
      case _ =>
        true

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
    * the arrow keys along `orientation`, the start for Home, and the end for End. None for other
    * keys and when the item can't move that way.
    */
  def keyTarget(
      key: String,
      from: Int,
      size: Int,
      orientation: Orientation = Orientation.Vertical
  ): Option[Int] =
    val (prevKey, nextKey) =
      orientation match
        case Orientation.Horizontal =>
          ("ArrowLeft", "ArrowRight")
        case Orientation.Vertical =>
          ("ArrowUp", "ArrowDown")
    val to =
      key match
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
