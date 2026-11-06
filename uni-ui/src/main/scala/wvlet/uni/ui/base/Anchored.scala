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

/** The side of the anchor that an overlay sits on. `cssName` is the overlay's `data-side`. */
enum Side(val cssName: String):
  case Top    extends Side("top")
  case Bottom extends Side("bottom")
  case Left   extends Side("left")
  case Right  extends Side("right")

  /** The side across the anchor, where an overlay flips to when this side has no room. */
  def opposite: Side =
    this match
      case Top =>
        Bottom
      case Bottom =>
        Top
      case Left =>
        Right
      case Right =>
        Left

  /** True for Top and Bottom, where alignment runs along the x axis. */
  def isVertical: Boolean = this == Top || this == Bottom

end Side

/** How an overlay lines up with its anchor along the anchor's edge. */
enum Align:
  case Start,
    Center,
    End

/** A rectangle in viewport coordinates (CSS pixels). */
case class Rect(x: Double, y: Double, width: Double, height: Double):
  def right: Double  = x + width
  def bottom: Double = y + height

object Rect:
  /** The rectangle of an element, from `getBoundingClientRect`. */
  def of(e: dom.Element): Rect =
    val r = e.getBoundingClientRect()
    Rect(r.left, r.top, r.width, r.height)

  /** A zero-size rectangle at a point, such as where a context menu was opened. */
  def point(x: Double, y: Double): Rect = Rect(x, y, 0, 0)

  /** The visible part of the window. */
  def viewport: Rect = Rect(
    0,
    0,
    dom.document.documentElement.clientWidth.toDouble,
    dom.document.documentElement.clientHeight.toDouble
  )

/** Where an overlay goes: its top-left corner and the side it ended up on. */
case class Placement(x: Double, y: Double, side: Side)

/**
  * Places an overlay (popover, tooltip, menu) next to an anchor and keeps it inside the viewport.
  * The overlay flips to the opposite side when its side has no room, then shifts along the anchor's
  * edge so it doesn't stick out.
  *
  * {{{
  *   val p = Anchored.place(Rect.of(button), width = 200, height = 120, Rect.viewport, Side.Bottom)
  * }}}
  */
object Anchored:

  /**
    * @param offset
    *   the gap between the anchor and the overlay
    * @param padding
    *   the minimum distance between the overlay and the viewport edges
    */
  def place(
      anchor: Rect,
      width: Double,
      height: Double,
      viewport: Rect,
      side: Side = Side.Bottom,
      align: Align = Align.Start,
      offset: Double = 6,
      padding: Double = 8
  ): Placement =
    // The room between the anchor and the viewport edge on a side, minus the gap
    def room(s: Side): Double =
      s match
        case Side.Top =>
          anchor.y - viewport.y - offset - padding
        case Side.Bottom =>
          viewport.bottom - anchor.bottom - offset - padding
        case Side.Left =>
          anchor.x - viewport.x - offset - padding
        case Side.Right =>
          viewport.right - anchor.right - offset - padding

    def size(s: Side): Double =
      if s.isVertical then
        height
      else
        width

    val finalSide =
      if room(side) >= size(side) then
        side
      else if room(side.opposite) >= size(side) then
        side.opposite
      else if room(side.opposite) > room(side) then
        // Neither side fits: take the roomier one
        side.opposite
      else
        side

    // Position along the edge, before shifting into the viewport
    def aligned(start: Double, length: Double, overlayLength: Double): Double =
      align match
        case Align.Start =>
          start
        case Align.Center =>
          start + (length - overlayLength) / 2
        case Align.End =>
          start + length - overlayLength

    // Keep [v, v + length] inside [lo, hi]; an overlay longer than the room pins to the start
    def shift(v: Double, length: Double, lo: Double, hi: Double): Double = math.max(
      lo,
      math.min(v, hi - length)
    )

    if finalSide.isVertical then
      val y =
        if finalSide == Side.Top then
          anchor.y - offset - height
        else
          anchor.bottom + offset
      val x = shift(
        aligned(anchor.x, anchor.width, width),
        width,
        viewport.x + padding,
        viewport.right - padding
      )
      Placement(x, y, finalSide)
    else
      val x =
        if finalSide == Side.Left then
          anchor.x - offset - width
        else
          anchor.right + offset
      val y = shift(
        aligned(anchor.y, anchor.height, height),
        height,
        viewport.y + padding,
        viewport.bottom - padding
      )
      Placement(x, y, finalSide)

  end place

  /**
    * Measure `overlay`, place it next to `anchor`, and write the result as `position: fixed` with
    * `left`/`top` and `data-side`. Returns the placement.
    */
  def applyTo(
      overlay: dom.HTMLElement,
      anchor: Rect,
      side: Side = Side.Bottom,
      align: Align = Align.Start,
      offset: Double = 6
  ): Placement =
    overlay.style.position = "fixed"
    // Layout size, not the bounding box: an enter animation may be scaling the overlay
    val p = place(
      anchor,
      overlay.offsetWidth.toDouble,
      overlay.offsetHeight.toDouble,
      Rect.viewport,
      side,
      align,
      offset
    )
    overlay.style.left = s"${p.x}px"
    overlay.style.top = s"${p.y}px"
    overlay.setAttribute("data-side", p.side.cssName)
    p

end Anchored
