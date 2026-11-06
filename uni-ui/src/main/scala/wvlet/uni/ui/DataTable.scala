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
import wvlet.uni.rx.{Rx, RxVar}

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
  * stop: the arrow keys, Home, End, PageUp, and PageDown move the highlighted row, and Enter or a
  * click selects it. Navigation moves by row, not by cell. The container is
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

  override def render: RxElement = ListCore[A, K](
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
    rowAttrs = (_, i) => ariaRowindex -> i.map(n => (n + 2).toString),
    renderRow =
      a =>
        columns
          .zipWithIndex
          .map { (c, i) =>
            div(
              role         -> "gridcell",
              cls          -> cellClass(c),
              ariaColindex -> (i + 1).toString,
              c.cell(a)
            )
          },
    selected = selected,
    onSelect = onSelectFn,
    typeaheadLabel = None,
    extraKey = (_, _, _) => false,
    header = div(
      role -> "rowgroup",
      cls  -> "uni-table__header",
      div(
        role         -> "row",
        cls          -> "uni-table__header-row",
        ariaRowindex -> "1",
        columns.zipWithIndex.map(headerCell)
      )
    )
  )

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
