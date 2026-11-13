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
package wvlet.uni.dom

import org.scalajs.dom
import wvlet.uni.rx.{Rx, RxVar}

import scala.scalajs.js

/**
  * Pointer drag tracking for a handle element: a splitter, a slider thumb, or a draggable card.
  *
  * Pressing the primary button on the handle starts a drag. Moves are tracked on `window`, so the
  * drag continues when the pointer leaves a thin handle, and it ends on `pointerup` or
  * `pointercancel`. `onMove` receives the distance `(dx, dy)` from the start point, in CSS pixels.
  *
  * {{{
  *   val drag = PointerDrag(onMove = (dx, _) => offset := startOffset + dx)
  *   div(cls -> "handle", drag.bind)
  *   drag.dragging // Rx[Boolean], e.g. to turn off transitions while dragging
  * }}}
  *
  * @param onMove
  *   called on every pointer move during a drag
  * @param onStart
  *   called when a drag starts, with the `pointerdown` event
  * @param onEnd
  *   called when a drag ends, by release or cancel
  * @param cursor
  *   a CSS cursor (e.g. "col-resize") shown on the whole page while dragging, so it does not
  *   flicker as the pointer leaves the handle. The previous cursor is restored when the drag ends.
  *   Empty keeps the page cursor.
  * @param canStart
  *   whether a `pointerdown` may start a drag, such as only one on a grip inside the element. A
  *   refused press keeps its default action (focus, text selection).
  */
class PointerDrag(
    onMove: (Double, Double) => Unit,
    onStart: dom.PointerEvent => Unit = _ => (),
    onEnd: () => Unit = () => (),
    cursor: String = "",
    canStart: dom.PointerEvent => Boolean = _ => true
):
  private val draggingVar: RxVar[Boolean] = Rx.variable(false)

  /** True while a drag is in progress. */
  def dragging: Rx[Boolean] = draggingVar

  /** Whether a drag is in progress, read synchronously. */
  def isDragging: Boolean = draggingVar.get

  private def start(e: dom.PointerEvent): Unit =
    // Only the primary button (left mouse button, touch, or pen contact) drags
    if e.button == 0 && !draggingVar.get && canStart(e) then
      // Keep the browser from selecting text or starting a native drag
      e.preventDefault()
      val startX = e.clientX
      val startY = e.clientY
      // Follow only the pointer that started the drag, so a second finger does not move it
      val pointerId  = e.pointerId
      val prevCursor = dom.document.body.style.cursor
      draggingVar := true
      if cursor.nonEmpty then
        dom.document.body.style.cursor = cursor
      onStart(e)
      lazy val move: js.Function1[dom.PointerEvent, Unit] =
        (ev: dom.PointerEvent) =>
          if ev.pointerId == pointerId then
            onMove(ev.clientX - startX, ev.clientY - startY)
      lazy val end: js.Function1[dom.PointerEvent, Unit] =
        (ev: dom.PointerEvent) =>
          if ev.pointerId == pointerId then
            dom.window.removeEventListener("pointermove", move)
            dom.window.removeEventListener("pointerup", end)
            dom.window.removeEventListener("pointercancel", end)
            if cursor.nonEmpty then
              dom.document.body.style.cursor = prevCursor
            draggingVar := false
            onEnd()
      dom.window.addEventListener("pointermove", move)
      dom.window.addEventListener("pointerup", end)
      dom.window.addEventListener("pointercancel", end)

  /**
    * The modifier to put on the handle element. Add `touch-action: none` to the handle (e.g.
    * Tailwind's `touch-none`) so touch drags are not taken over by scrolling.
    */
  def bind: DomNode = EventListenerBinding(
    "pointerdown",
    {
      case e: dom.PointerEvent =>
        start(e)
      case _ =>
    }
  )

end PointerDrag

/**
  * Allowed sizes of a resizable element, in CSS pixels.
  *
  * @param default
  *   the initial size, restored by double-clicking a [[PaneResize]] handle
  */
case class SizeRange(min: Int, default: Int, max: Int):
  require(min <= default && default <= max, s"Expected min <= default <= max: ${this}")

  /** The nearest size within this range. */
  def clamp(size: Int): Int = math.max(min, math.min(max, size))

/**
  * The direction a [[PaneResize]] handle moves to make its pane larger. A handle on a sidebar's
  * right edge grows the pane as it moves `Right`; a handle on the left edge of a right-hand dock
  * grows it as it moves `Left`.
  */
enum ResizeDirection:
  case Right,
    Left,
    Down,
    Up

  def isHorizontal: Boolean = this == Right || this == Left

/**
  * Drag-to-resize binding for a pane handle: dragging changes `size` within `range`, and
  * double-clicking restores `range.default`. Only the behaviour is provided; the handle's look and
  * placement are up to the app.
  *
  * {{{
  *   val width  = Rx.variable(272)
  *   val resize = PaneResize(width, SizeRange(min = 208, default = 272, max = 480), ResizeDirection.Right)
  *   aside(
  *     styleAttr -> width.map(w => s"width: ${w}px"),
  *     div(cls -> "absolute right-0 inset-y-0 w-2 cursor-col-resize touch-none",
  *       ariaLabel -> "Resize sidebar", resize.bind),
  *     content
  *   )
  * }}}
  *
  * The handle gets `role="separator"` and `aria-orientation`; give it an `aria-label` too.
  *
  * Construction clamps `size` into `range`, so a stale value restored from storage (e.g. saved
  * under a larger max) is corrected before the pane renders.
  *
  * @param size
  *   the pane's width (horizontal) or height (vertical) in CSS pixels
  * @param grow
  *   the direction a drag makes the pane larger
  */
class PaneResize(size: RxVar[Int], range: SizeRange, grow: ResizeDirection):
  private var startSize = 0

  // A restored size may predate the current range; write only when it is out of range
  private val clamped = range.clamp(size.get)
  if clamped != size.get then
    size := clamped

  private val drag = PointerDrag(
    onStart = _ => startSize = size.get,
    onMove =
      (dx, dy) =>
        val delta =
          grow match
            case ResizeDirection.Right =>
              dx
            case ResizeDirection.Left =>
              -dx
            case ResizeDirection.Down =>
              dy
            case ResizeDirection.Up =>
              -dy
        size := range.clamp((startSize + delta).round.toInt)
    ,
    cursor =
      if grow.isHorizontal then
        "col-resize"
      else
        "row-resize"
  )

  /** True while the handle is being dragged, e.g. to turn off a width transition. */
  def dragging: Rx[Boolean] = drag.dragging

  /** Restore the default size. */
  def reset(): Unit = size := range.default

  /** The modifier to put on the handle element. */
  def bind: DomNode = DomNode.group(
    HtmlTags.attr("role")("separator"),
    // The separator line runs across the drag direction
    HtmlTags.attr("aria-orientation")(
      if grow.isHorizontal then
        "vertical"
      else
        "horizontal"
    ),
    drag.bind,
    EventListenerBinding("dblclick", _ => reset())
  )

end PaneResize
