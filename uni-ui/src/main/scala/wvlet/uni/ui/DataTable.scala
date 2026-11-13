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
import wvlet.uni.rx.{Rx, RxVar}
import wvlet.uni.ui.base.Windowing

import scala.language.implicitConversions

enum SortDirection:
  case Ascending,
    Descending

/** Which column a [[DataTable]] is sorted by, and in which direction. */
case class SortBy(column: String, direction: SortDirection = SortDirection.Ascending)

/**
  * One column of a [[DataTable]]: an id (used by [[SortBy]]), a header, and how to render a cell.
  *
  * {{{
  *   Column[Call]("method", "Method")(c => span(c.method)).sortBy(_.method)
  *   Column[Call]("millis", "Time")(c => f"${c.millis}%.1f ms").sortBy(_.millis).withWidth("96px").alignEnd
  * }}}
  *
  * @param width
  *   a CSS grid track size, such as `"120px"`, `"2fr"`, or `"minmax(80px, 1fr)"`; by default the
  *   columns share the width equally
  */
class Column[A] private (
    val id: String,
    val header: String,
    val cell: A => DomNode,
    val ordering: Option[Ordering[A]],
    val width: String,
    val end: Boolean
):
  private def copy(
      ordering: Option[Ordering[A]] = ordering,
      width: String = width,
      end: Boolean = end
  ): Column[A] = new Column(id, header, cell, ordering, width, end)

  /** Let the user sort by this column, comparing rows by `f`. */
  def sortBy[B](f: A => B)(using o: Ordering[B]): Column[A] = copy(ordering = Some(o.on(f)))

  /** Let the user sort by this column with `o`. */
  def withOrdering(o: Ordering[A]): Column[A] = copy(ordering = Some(o))
  def noOrdering(): Column[A]                 = copy(ordering = None)

  def withWidth(w: String): Column[A] = copy(width = w)

  /** Align the header and cells to the end, as for numbers. */
  def alignEnd: Column[A] = copy(end = true)

end Column

object Column:
  // `new` is required in this file: the `apply` below replaces the constructor proxy
  def apply[A](id: String, header: String)(cell: A => DomNode): Column[A] =
    new Column(id, header, cell, ordering = None, width = "minmax(0, 1fr)", end = false)

/**
  * A table of rows with a sticky header, sortable columns, and row selection. Only the rows in view
  * are rendered, as in [[VirtualList]], so every row is `rowHeight` pixels (32 by default) and the
  * table scrolls inside its own box.
  *
  * {{{
  *   val sort = Rx.variable(Option(SortBy("millis", SortDirection.Descending)))
  *   DataTable(log.calls)(_.id)(
  *     Column[Call]("method", "Method")(c => span(c.method)).sortBy(_.method),
  *     Column[Call]("millis", "Time")(c => f"${c.millis}%.1f ms").sortBy(_.millis).alignEnd
  *   ).withLabel("RPC calls").withSort(sort).onSelect(c => show(c))
  * }}}
  *
  * A click on a sortable header cycles ascending, descending, and unsorted. The table is one tab
  * stop that moves by cell, as in the WAI-ARIA grid pattern: Up, Down, PageUp, and PageDown move
  * the highlighted row and keep the column; Left and Right move along the row; Home and End go to
  * the row's first and last cell, and Ctrl (or ⌘) with Home or End to the first and last row.
  * `aria-activedescendant` names the active cell, which has `data-active="true"`. Enter or a click
  * selects the row, and a click also makes its cell active. The container is
  * `div.uni-list.uni-table` with `role="grid"`; the header row is `div.uni-table__header`, and each
  * row is a `div.uni-list__row` of `div.uni-table__cell`s, laid out by the CSS grid in
  * `--uni-table-columns`.
  */
class DataTable[A, K] private (
    rows: Rx[Seq[A]],
    key: A => K,
    columns: Seq[Column[A]],
    label: Option[String],
    sort: RxVar[Option[SortBy]],
    selected: RxVar[Option[K]],
    onSelectFn: A => Unit,
    rowHeight: Int
) extends RxElement:

  private def copy(
      label: Option[String] = label,
      sort: RxVar[Option[SortBy]] = sort,
      selected: RxVar[Option[K]] = selected,
      onSelectFn: A => Unit = onSelectFn,
      rowHeight: Int = rowHeight
  ): DataTable[A, K] =
    new DataTable(rows, key, columns, label, sort, selected, onSelectFn, rowHeight)

  /** The accessible name of the table. */
  def withLabel(l: String): DataTable[A, K] = copy(label = Some(l))
  def noLabel(): DataTable[A, K]            = copy(label = None)

  /** The sort state, set by header clicks; the app can also set or save it. */
  def withSort(s: RxVar[Option[SortBy]]): DataTable[A, K] = copy(sort = s)

  /** The key of the selected row, set by Enter or a click. */
  def withSelected(s: RxVar[Option[K]]): DataTable[A, K] = copy(selected = s)

  /** Runs when Enter or a click selects a row. */
  def onSelect(f: A => Unit): DataTable[A, K] = copy(onSelectFn = f)

  /** The height of every row in pixels (32 by default). */
  def withRowHeight(h: Int): DataTable[A, K] = copy(rowHeight = h)

  // The active column; the row comes from the list's active row
  private val activeColumn = Rx.variable(0)

  private def onKey(e: dom.KeyboardEvent): Boolean =
    val toRow  = e.ctrlKey || e.metaKey
    val rowEnd = e.key == "Home" || e.key == "End"
    // Left or Right with a modifier (Alt+Left is Back in some browsers) belong to the app. Home and
    // End are always handled here, so the list never reads them as a row jump without Ctrl or ⌘.
    if !rowEnd && (e.altKey || e.shiftKey || toRow) then
      false
    else
      DataTable.nextColumn(e.key, activeColumn.get, columns.size) match
        case Some(c) =>
          if c != activeColumn.get then
            activeColumn := c
          e.currentTarget match
            case el: dom.HTMLElement =>
              revealColumn(el, c)
            case _ =>
          // Ctrl+Home and Ctrl+End also move the row, through the list's own Home and End
          !(toRow && rowEnd)
        case None =>
          false

  // Scroll a table wider than its box so that column `c` is in view. The header cell has the same
  // track as the column's cells and is always rendered.
  private def revealColumn(el: dom.HTMLElement, c: Int): Unit = Option(
    el.querySelector(s":scope > .uni-table__header > .uni-table__header-row > :nth-child(${c + 1})")
  ).foreach { cell =>
    val left = cell.getBoundingClientRect().left - el.getBoundingClientRect().left + el.scrollLeft
    Windowing
      .revealSpan(left, cell.getBoundingClientRect().width, el.scrollLeft, el.clientWidth)
      .foreach(x => el.scrollLeft = x)
  }

  private def cellClass(c: Column[A]): String =
    if c.end then
      "uni-table__cell uni-table__cell--end"
    else
      "uni-table__cell"

  private val sorted: Rx[Seq[A]] = Rx
    .join(rows, sort)
    .map { (xs, s) =>
      DataTable.sortRows(xs, columns, s)
    }

  private def headerCell(c: Column[A], i: Int): RxElement =
    val state = sort.map {
      case Some(SortBy(c.id, d)) =>
        Some(d)
      case _ =>
        None
    }
    div(
      role         -> "columnheader",
      cls          -> cellClass(c),
      ariaColindex -> (i + 1).toString,
      c.ordering match
        case None =>
          span(c.header): DomNode
        case Some(_) =>
          Seq[DomNode](
            ariaSort ->
              state.map {
                case Some(SortDirection.Ascending) =>
                  "ascending"
                case Some(SortDirection.Descending) =>
                  "descending"
                case None =>
                  "none"
              },
            button(
              tpe -> "button",
              cls -> "uni-table__sort",
              EventListenerBinding("click", _ => sort := DataTable.nextSort(sort.get, c.id)),
              span(c.header),
              span(
                cls               -> "uni-table__sort-icon",
                ariaHidden        -> "true",
                data("direction") ->
                  state.map(
                    _.map {
                      case SortDirection.Ascending =>
                        "asc"
                      case SortDirection.Descending =>
                        "desc"
                    }
                  )
              )
            )
          )
    )

  end headerCell

  override def render: RxElement =
    // Lazy, so each cell can take its id from the list that renders it
    lazy val core: ListCore[A, K] = ListCore[A, K](
      sorted,
      key,
      rowHeight,
      VirtualList.defaultOverscan,
      containerClass = "uni-table",
      containerAttrs = Seq(
        role -> "grid",
        label.map(ariaLabel -> _).getOrElse(DomNode.empty),
        ariaColcount -> columns.size.toString,
        ariaRowcount -> rows.map(xs => (xs.size + 1).toString)
      ),
      containerStyle = s"--uni-table-columns: ${columns.map(_.width).mkString(" ")}",
      spacerAttrs = Seq(role -> "rowgroup"),
      rowRole = "row",
      // The header is row 1
      rowAttrs = (_, i, _) => ariaRowindex -> i.map(n => (n + 2).toString),
      renderRow =
        a =>
          columns
            .zipWithIndex
            .map { (c, i) =>
              val k = key(a)
              div(
                id             -> core.cellId(k, i),
                role           -> "gridcell",
                cls            -> cellClass(c),
                ariaColindex   -> (i + 1).toString,
                data("active") ->
                  Rx.join(core.activeKey, activeColumn)
                    .map { (ak, ac) =>
                      if ak.contains(k) && ac == i then
                        Some("true")
                      else
                        None
                    },
                // The row's click handler then selects the row
                EventListenerBinding("click", _ => activeColumn := i),
                c.cell(a)
              )
            },
      selected = selected,
      onSelect = onSelectFn,
      typeaheadLabel = None,
      extraKey = (e, _, _) => onKey(e),
      header = div(
        role -> "rowgroup",
        cls  -> "uni-table__header",
        div(
          role         -> "row",
          cls          -> "uni-table__header-row",
          ariaRowindex -> "1",
          columns.zipWithIndex.map(headerCell)
        )
      ),
      activeCell = activeColumn.map(c => Option.when(columns.nonEmpty)(c))
    )
    core

  end render

end DataTable

object DataTable:
  // `new` is required in this file: the `apply` below replaces the constructor proxy
  def apply[A, K](rows: Rx[Seq[A]])(key: A => K)(columns: Column[A]*): DataTable[A, K] =
    new DataTable(
      rows,
      key,
      columns,
      label = None,
      sort = Rx.variable(None),
      selected = Rx.variable(None),
      onSelectFn = _ => (),
      rowHeight = 32
    )

  /**
    * The active column after `key` in a row of `count` cells: Left and Right move by one and stop
    * at the ends, and Home and End go to the first and last cell.
    */
  def nextColumn(key: String, current: Int, count: Int): Option[Int] =
    if count <= 0 then
      None
    else
      key match
        case "ArrowLeft" =>
          Some(math.max(current - 1, 0))
        case "ArrowRight" =>
          Some(math.min(current + 1, count - 1))
        case "Home" =>
          Some(0)
        case "End" =>
          Some(count - 1)
        case _ =>
          None

  /** The sort after a click on `column`'s header: ascending, then descending, then none. */
  def nextSort(current: Option[SortBy], column: String): Option[SortBy] =
    current match
      case Some(SortBy(`column`, SortDirection.Ascending)) =>
        Some(SortBy(column, SortDirection.Descending))
      case Some(SortBy(`column`, SortDirection.Descending)) =>
        None
      case _ =>
        Some(SortBy(column, SortDirection.Ascending))

  /** `rows` in the order `sort` asks for; unknown or unsortable columns keep the given order. */
  def sortRows[A](rows: Seq[A], columns: Seq[Column[A]], sort: Option[SortBy]): Seq[A] = sort
    .flatMap(s => columns.find(_.id == s.column).flatMap(_.ordering).map(o => (s, o)))
    .map { (s, o) =>
      s.direction match
        case SortDirection.Ascending =>
          rows.sorted(using o)
        case SortDirection.Descending =>
          rows.sorted(using o.reverse)
    }
    .getOrElse(rows)

end DataTable
