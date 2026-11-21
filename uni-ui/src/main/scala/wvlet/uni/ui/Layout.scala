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

import wvlet.uni.dom.all.*
import wvlet.uni.dom.all.given

import scala.language.implicitConversions

/** The direction a [[Stack]] lays out its children. */
enum StackDirection(val cssName: String):
  case Row    extends StackDirection("row")
  case Column extends StackDirection("column")

/** How a layout lines up its children across its main direction (CSS `align-items`). */
enum Alignment(val cssName: String):
  case Start    extends Alignment("start")
  case Center   extends Alignment("center")
  case End      extends Alignment("end")
  case Stretch  extends Alignment("stretch")
  case Baseline extends Alignment("baseline")

/** How a [[Stack]] spreads its children along its direction (CSS `justify-content`). */
enum Justify(val cssName: String):
  case Start   extends Justify("start")
  case Center  extends Justify("center")
  case End     extends Justify("end")
  case Between extends Justify("space-between")
  case Around  extends Justify("space-around")

/**
  * Children in a column or a row, with a gap between them: the flexbox layout most panels and
  * toolbars need, without CSS.
  *
  * {{{
  *   Stack(title, summary, actions).withGap(12)
  *   Stack.row(Button("Cancel").ghost, Button("Save").primary).withJustify(Justify.End)
  * }}}
  *
  * It renders `div.uni-stack` with `data-direction`, `data-align`, and `data-justify`, and the gap
  * in `--uni-stack-gap`, so CSS can restyle any of them.
  */
class Stack private (
    content: Seq[DomNode],
    direction: StackDirection,
    gap: Int,
    align: Alignment,
    justify: Justify,
    wrap: Boolean
) extends RxElement:

  private def copy(
      direction: StackDirection = direction,
      gap: Int = gap,
      align: Alignment = align,
      justify: Justify = justify,
      wrap: Boolean = wrap
  ): Stack = new Stack(content, direction, gap, align, justify, wrap)

  def withDirection(d: StackDirection): Stack = copy(direction = d)

  /** Lay the children out side by side. */
  def row: Stack = withDirection(StackDirection.Row)

  /** Lay the children out top to bottom. */
  def column: Stack = withDirection(StackDirection.Column)

  /** The space between children, in CSS pixels. */
  def withGap(px: Int): Stack = copy(gap = math.max(0, px))

  def withAlign(a: Alignment): Stack = copy(align = a)

  def withJustify(j: Justify): Stack = copy(justify = j)

  /** Let children move to a new line when they don't fit. */
  def withWrap(w: Boolean): Stack = copy(wrap = w)
  def noWrap(): Stack             = withWrap(false)

  override def render: RxElement = div(
    cls               -> "uni-stack",
    data("slot")      -> "stack",
    data("direction") -> direction.cssName,
    data("align")     -> align.cssName,
    data("justify")   -> justify.cssName,
    data("wrap")      -> wrap.toString,
    styleAttr         -> s"--uni-stack-gap: ${gap}px",
    content
  )

end Stack

object Stack:
  // `new` is required in this file: the `apply` below replaces the constructor proxy

  /** Children top to bottom, 8px apart. */
  def apply(content: DomNode*): Stack =
    new Stack(content, StackDirection.Column, 8, Alignment.Stretch, Justify.Start, wrap = false)

  /** Children side by side, 8px apart, centered across the row. */
  def row(content: DomNode*): Stack =
    new Stack(content, StackDirection.Row, 8, Alignment.Center, Justify.Start, wrap = false)

/** How a [[Grid]] sizes its columns. */
enum GridColumns:
  /** Exactly `n` equal columns. */
  case Count(n: Int)

  /**
    * As many columns as fit, each at least `px` wide, sharing the rest: the grid reflows to fewer
    * columns on a narrow screen, with no media query.
    */
  case MinWidth(px: Int)

  def template: String =
    this match
      case Count(n) =>
        s"repeat(${math.max(1, n)}, minmax(0, 1fr))"
      case MinWidth(px) =>
        s"repeat(auto-fill, minmax(min(${math.max(1, px)}px, 100%), 1fr))"

/**
  * Children in a CSS grid: a fixed number of equal columns, or as many columns of a minimum width
  * as fit, so cards and tiles reflow on narrow screens. Wrap a child in [[GridItem]] to span
  * columns or rows.
  *
  * {{{
  *   Grid(cards*).withMinColumnWidth(220).withGap(16)
  *   Grid(GridItem(chart).withColumnSpan(2), stats, table).withColumns(3)
  * }}}
  *
  * It renders `div.uni-grid` with the column template in `--uni-grid-columns` and the gaps in
  * `--uni-grid-gap` and `--uni-grid-row-gap`.
  */
class Grid private (
    content: Seq[DomNode],
    columns: GridColumns,
    gap: Int,
    rowGap: Option[Int],
    align: Alignment
) extends RxElement:

  private def copy(
      columns: GridColumns = columns,
      gap: Int = gap,
      rowGap: Option[Int] = rowGap,
      align: Alignment = align
  ): Grid = new Grid(content, columns, gap, rowGap, align)

  def withColumns(c: GridColumns): Grid = copy(columns = c)

  /** Exactly `n` equal columns. */
  def withColumns(n: Int): Grid = withColumns(GridColumns.Count(n))

  /** As many columns as fit, each at least `px` wide. */
  def withMinColumnWidth(px: Int): Grid = withColumns(GridColumns.MinWidth(px))

  /** The space between columns and, unless [[withRowGap]] sets it, between rows, in CSS pixels. */
  def withGap(px: Int): Grid = copy(gap = math.max(0, px))

  /** The space between rows, in CSS pixels. */
  def withRowGap(px: Int): Grid = copy(rowGap = Some(math.max(0, px)))
  def noRowGap(): Grid          = copy(rowGap = None)

  /** How items line up in their cells (CSS `align-items`); they stretch to the row by default. */
  def withAlign(a: Alignment): Grid = copy(align = a)

  override def render: RxElement = div(
    cls           -> "uni-grid",
    data("slot")  -> "grid",
    data("align") -> align.cssName,
    styleAttr     ->
      s"--uni-grid-columns: ${columns
          .template}; --uni-grid-gap: ${gap}px; --uni-grid-row-gap: ${rowGap.getOrElse(gap)}px",
    content
  )

end Grid

object Grid:
  // `new` is required in this file: the `apply` below replaces the constructor proxy

  /** Children in columns at least 200px wide, 12px apart. */
  def apply(content: DomNode*): Grid =
    new Grid(content, GridColumns.MinWidth(200), 12, None, Alignment.Stretch)

/**
  * A child of a [[Grid]] that spans several columns or rows. A span wider than the grid takes the
  * whole row.
  */
class GridItem private (content: Seq[DomNode], columnSpan: Int, rowSpan: Int) extends RxElement:

  /** How many columns it takes; `0` takes the whole row. */
  def withColumnSpan(n: Int): GridItem = new GridItem(content, math.max(0, n), rowSpan)

  /** How many rows it takes. */
  def withRowSpan(n: Int): GridItem = new GridItem(content, columnSpan, math.max(1, n))

  override def render: RxElement =
    val column =
      if columnSpan == 0 then
        "1 / -1"
      else
        s"span ${columnSpan}"
    div(
      cls          -> "uni-grid-item",
      data("slot") -> "grid-item",
      styleAttr    -> s"grid-column: ${column}; grid-row: span ${rowSpan}",
      content
    )

end GridItem

object GridItem:
  // `new` is required in this file: the `apply` below replaces the constructor proxy
  def apply(content: DomNode*): GridItem = new GridItem(content, 1, 1)

/**
  * A thin line between groups of content: horizontal between stacked sections, vertical between
  * items in a row. It is `role="separator"` with `aria-orientation`, so assistive technology reads
  * the break; `.decorative` hides it from them when it is only visual.
  */
class Separator private (isVertical: Boolean, isDecorative: Boolean) extends RxElement:

  /** A vertical line, for a row of items such as a toolbar. */
  def vertical: Separator = new Separator(true, isDecorative)

  /** A purely visual line that assistive technology skips. */
  def decorative: Separator = new Separator(isVertical, true)

  override def render: RxElement =
    val orientation =
      if isVertical then
        "vertical"
      else
        "horizontal"
    div(
      cls                 -> "uni-separator",
      data("slot")        -> "separator",
      data("orientation") -> orientation,
      role                -> (
        if isDecorative then
          "none"
        else
          "separator"
      ),
      when(!isDecorative, ariaOrientation -> orientation)
    )

end Separator

object Separator:
  // `new` is required in this file: the `apply` below replaces the constructor proxy
  def apply(): Separator = new Separator(false, false)
