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

/**
  * Which rows of a long list of fixed-height rows to render, and where to scroll to show one. A
  * windowed list renders only the rows in view plus a few on each side (the overscan), inside a
  * spacer as tall as the whole list.
  */
object Windowing:

  /**
    * The rows in view for a scroll position: the first row, and how many rows fit (counting a
    * partly visible row at each end).
    *
    * @param scrollTop
    *   how far the rows are scrolled, in pixels
    * @param viewportHeight
    *   the height available to rows, in pixels
    */
  def inView(scrollTop: Double, viewportHeight: Double, rowHeight: Int): (Int, Int) =
    val first   = math.max(0, math.floor(scrollTop / rowHeight).toInt)
    val visible = math.max(0, math.ceil(viewportHeight / rowHeight).toInt + 1)
    (first, visible)

  /**
    * The rows to render, as `[start, end)`: the rows in view widened by `overscan` on each side and
    * clamped to `count`.
    */
  def range(first: Int, visible: Int, count: Int, overscan: Int): (Int, Int) =
    val start = math.max(0, math.min(first - overscan, count))
    val end   = math.max(start, math.min(first + visible + overscan, count))
    (start, end)

  /**
    * The `scrollTop` that brings row `index` fully into view with the least movement, or None when
    * it is already in view.
    *
    * @param viewportHeight
    *   the height available to rows, in pixels
    */
  def reveal(
      index: Int,
      rowHeight: Int,
      scrollTop: Double,
      viewportHeight: Double
  ): Option[Double] = revealSpan(index.toDouble * rowHeight, rowHeight, scrollTop, viewportHeight)

  /**
    * The scroll offset that brings the span from `start` to `start + size` into view with the least
    * movement, or None when it is already in view. A span larger than the viewport shows its start.
    */
  def revealSpan(start: Double, size: Double, scroll: Double, viewport: Double): Option[Double] =
    val end = start + size
    if start < scroll then
      Some(start)
    else if end > scroll + viewport then
      Some(math.max(0.0, math.min(start, end - viewport)))
    else
      None

end Windowing

/**
  * Arrow-key navigation through a long list: unlike [[RovingFocus]], the ends don't wrap, and
  * PageUp and PageDown move a page at a time.
  */
object ListNavigation:

  /**
    * The index to move to after pressing `key`, or None when the key doesn't navigate.
    *
    * @param current
    *   the active index, or -1 when none is: ArrowDown then goes to the first row, ArrowUp to the
    *   last
    * @param pageSize
    *   how many rows PageUp and PageDown move
    */
  def nextIndex(key: String, current: Int, count: Int, pageSize: Int): Option[Int] =
    if count <= 0 then
      None
    else
      val page = math.max(1, pageSize)
      key match
        case "ArrowDown" if current < 0 =>
          Some(0)
        case "ArrowDown" =>
          Some(math.min(current + 1, count - 1))
        case "ArrowUp" if current < 0 =>
          Some(count - 1)
        case "ArrowUp" =>
          Some(math.max(current - 1, 0))
        case "Home" =>
          Some(0)
        case "End" =>
          Some(count - 1)
        case "PageDown" =>
          Some(math.min(math.max(current, 0) + page, count - 1))
        case "PageUp" =>
          Some(math.max(current - page, 0))
        case _ =>
          None

end ListNavigation
