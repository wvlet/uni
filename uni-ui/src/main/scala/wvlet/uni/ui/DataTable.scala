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
import wvlet.uni.dom.{AnyRealm, Focus, OnMount}
import wvlet.uni.dom.all.*
import wvlet.uni.dom.all.given
import wvlet.uni.rx.{Cancelable, Rx, RxVar}
import wvlet.uni.ui.agent.AgentAction
import wvlet.uni.ui.base.{Ids, Reorder, TextDirection, Windowing}

import scala.language.implicitConversions
import scala.scalajs.js

enum SortDirection:
  case Ascending,
    Descending

/** Which column a [[DataTable]] is sorted by, and in which direction. */
case class SortBy(column: String, direction: SortDirection = SortDirection.Ascending)

/** One cell of a [[DataTable]]: the key of its row and the id of its column. */
case class CellKey[K](row: K, column: String)

/**
  * One column of a [[DataTable]]: an id (used by [[SortBy]]), a header, and how to render a cell.
  *
  * {{{
  *   Column[Call]("method", "Method", cell = c => span(c.method)).sortBy(_.method)
  *   Column[Call]("millis", "Time", cell = c => f"\${c.millis}%.1f ms").sortBy(_.millis).withWidth("96px").alignEnd
  *   Column[Row]("total", "total", cell = r => span(r.total)).withSubtitle("bigint").alignEnd
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
    val end: Boolean,
    val subtitle: Option[String]
):
  private def copy(
      ordering: Option[Ordering[A]] = ordering,
      width: String = width,
      end: Boolean = end,
      subtitle: Option[String] = subtitle
  ): Column[A] = new Column(id, header, cell, ordering, width, end, subtitle)

  /** Let the user sort by this column, comparing rows by `f`. */
  def sortBy[B](f: A => B)(using o: Ordering[B]): Column[A] = copy(ordering = Some(o.on(f)))

  /** Let the user sort by this column with `o`. */
  def withOrdering(o: Ordering[A]): Column[A] = copy(ordering = Some(o))
  def noOrdering(): Column[A]                 = copy(ordering = None)

  def withWidth(w: String): Column[A] = copy(width = w)

  /** Align the header and cells to the end, as for numbers. */
  def alignEnd: Column[A] = copy(end = true)

  /**
    * A second, smaller line under the header, such as the column's type in a grid of query results.
    * It describes the column header (`aria-describedby`) but is not part of its name, which stays
    * `header`.
    */
  def withSubtitle(s: String): Column[A] = copy(subtitle = Some(s))
  def noSubtitle(): Column[A]            = copy(subtitle = None)

end Column

object Column:
  // `new` is required in this file: the `apply` below replaces the constructor proxy
  def apply[A](id: String, header: String, cell: A => DomNode): Column[A] =
    new Column(
      id,
      header,
      cell,
      ordering = None,
      width = "minmax(0, 1fr)",
      end = false,
      subtitle = None
    )

/**
  * A table of rows with a sticky header, sortable columns, and row selection. Only the rows in view
  * are rendered, as in [[VirtualList]], so every row is `rowHeight` pixels (32 by default) and the
  * table scrolls inside its own box.
  *
  * {{{
  *   val sort = Rx.variable(Option(SortBy("millis", SortDirection.Descending)))
  *   DataTable(log.calls, key = _.id, columns = Seq(
  *     Column[Call]("method", "Method", cell = c => span(c.method)).sortBy(_.method),
  *     Column[Call]("millis", "Time", cell = c => f"\${c.millis}%.1f ms").sortBy(_.millis).alignEnd
  *   )).withLabel("RPC calls").withSort(sort).onSelect(c => show(c))
  * }}}
  *
  * A click on a sortable header cycles ascending, descending, and unsorted. The table is one tab
  * stop that moves by cell, as in the WAI-ARIA grid pattern: Up, Down, PageUp, and PageDown move
  * the highlighted row and keep the column; Left and Right move along the row; Home and End go to
  * the row's first and last cell, Ctrl (or ⌘) with Home to the header's first cell, and with End to
  * the last cell of the last row. With [[withMultiSelect]], Shift with Home or End extends the
  * selection to the first or last row. `aria-activedescendant` names the active cell, which has
  * `data-active="true"`. Enter or a click selects the row, and a click also makes its cell active.
  *
  * The header row is part of the grid: Up from the first row moves to it and Down back, and Enter
  * or Space on a sortable header sorts as a click does, so sort buttons are not tab stops of their
  * own. Enter or F2 on a cell that holds a widget (a button, a link, a field) moves the focus into
  * it, Tab and Shift+Tab then cycle through that cell's widgets, and Escape or F2 brings the focus
  * back to the cell; widgets in cells leave the tab order. With [[withSelectedCell]], Enter, Space,
  * or a click selects the active cell instead of the row. The container is `div.uni-list.uni-table`
  * with `role="grid"`; the header row is `div.uni-table__header`, and each row is a
  * `div.uni-list__row` of `div.uni-table__cell`s, laid out by the CSS grid in
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
    rowHeight: Int,
    changeHandler: ChangeHandler,
    multiSelected: Option[RxVar[Set[K]]],
    reorderable: Option[RxVar[Seq[A]]],
    rowLabel: Option[A => String],
    agentConfirm: Option[A => Option[String]],
    selectedCell: Option[RxVar[Option[CellKey[K]]]],
    selectable: Boolean,
    centerHeaders: Boolean
) extends RxElement:

  private def copy(
      label: Option[String] = label,
      sort: RxVar[Option[SortBy]] = sort,
      selected: RxVar[Option[K]] = selected,
      onSelectFn: A => Unit = onSelectFn,
      rowHeight: Int = rowHeight,
      changeHandler: ChangeHandler = changeHandler,
      multiSelected: Option[RxVar[Set[K]]] = multiSelected,
      reorderable: Option[RxVar[Seq[A]]] = reorderable,
      rowLabel: Option[A => String] = rowLabel,
      agentConfirm: Option[A => Option[String]] = agentConfirm,
      selectedCell: Option[RxVar[Option[CellKey[K]]]] = selectedCell,
      selectable: Boolean = selectable,
      centerHeaders: Boolean = centerHeaders
  ): DataTable[A, K] =
    new DataTable(
      rows,
      key,
      columns,
      label,
      sort,
      selected,
      onSelectFn,
      rowHeight,
      changeHandler,
      multiSelected,
      reorderable,
      rowLabel,
      agentConfirm,
      selectedCell,
      selectable,
      centerHeaders
    )

  /** The accessible name of the table. */
  def withLabel(l: String): DataTable[A, K] = copy(label = Some(l))
  def noLabel(): DataTable[A, K]            = copy(label = None)

  /** The sort state, set by header clicks; the app can also set or save it. */
  def withSort(s: RxVar[Option[SortBy]]): DataTable[A, K] = copy(sort = s)

  /** The key of the selected row, set by Enter or a click. */
  def withSelected(s: RxVar[Option[K]]): DataTable[A, K] = copy(selected = s)

  /**
    * Lets the user select several rows, whose keys live in `s`: Shift or Ctrl (⌘) with a click,
    * Shift with Up, Down, Home, End, PageUp, or PageDown, Space, Ctrl+A, and Escape change the set,
    * as in a file list. The table then leaves [[withSelected]]'s variable alone.
    */
  def withMultiSelect(s: RxVar[Set[K]]): DataTable[A, K] = copy(multiSelected = Some(s))

  /** Selects one row at a time, in [[withSelected]]'s variable (the default). */
  def noMultiSelect(): DataTable[A, K] = copy(multiSelected = None)

  /**
    * Lets the user select one cell, whose row key and column id live in `s`: Enter, Space, or a
    * click selects the active cell, which then has `aria-selected="true"`, and [[onSelect]] runs
    * with its row. While set, rows are not selected: the table leaves [[withSelected]]'s and
    * [[withMultiSelect]]'s variables alone and its multiple-selection keys are off.
    */
  def withSelectedCell(s: RxVar[Option[CellKey[K]]]): DataTable[A, K] = copy(selectedCell = Some(s))

  /** Selects rows again, as [[withSelected]] or [[withMultiSelect]] set them (the default). */
  def noSelectedCell(): DataTable[A, K] = copy(selectedCell = None)

  /**
    * Selects nothing, as in a read-only grid of query results: a click, Enter, or Space leaves no
    * row or cell selected (no `aria-selected`, no selected background) and only runs [[onSelect]].
    * Hover, the active cell, the keys, and sorting work as before. It overrides [[withSelected]],
    * [[withMultiSelect]], and [[withSelectedCell]], whose variables the table then leaves alone.
    */
  def noSelection(): DataTable[A, K] = copy(selectable = false)

  /** Selects rows or a cell, as the other options set (the default). */
  def withSelection(): DataTable[A, K] = copy(selectable = true)

  /**
    * Centers each header over its column, as in a grid of query results; cells keep their own
    * alignment.
    */
  def withCenteredHeaders(): DataTable[A, K] = copy(centerHeaders = true)

  /** Aligns each header as its column's cells (the default). */
  def noCenteredHeaders(): DataTable[A, K] = copy(centerHeaders = false)

  /**
    * Lets the user reorder the rows by dragging one, or with Alt+ArrowUp, Alt+ArrowDown, Alt+Home,
    * and Alt+End on the active row, as [[base.Reorder]] does. The new order is written to `order`,
    * the variable the table shows, after [[onChange]] sees it under the key `order`. With
    * [[withMultiSelect]], a selected row moves the whole selection as one block. While a column
    * sort is applied, the rows keep the sorted order and can't be moved.
    */
  def withReorder(order: RxVar[Seq[A]]): DataTable[A, K] = copy(reorderable = Some(order))
  def noReorder(): DataTable[A, K]                       = copy(reorderable = None)

  /**
    * Runs when Enter or a click selects a row, or with [[withSelectedCell]] a cell in it;
    * [[ChangeCause.current]] tells which.
    */
  def onSelect(f: A => Unit): DataTable[A, K] = copy(onSelectFn = f)

  /**
    * Called before the selection, the sort, or the order changes; `cancel()` keeps the current
    * state. Keys: `selected` (rows), `selectedCell`, `sort`, and `order`.
    */
  def onChange(f: ChangeHandler): DataTable[A, K] = copy(changeHandler = f)

  /**
    * The text that names a row for [[wvlet.uni.ui.agent.UiAgent]] (its snapshot items, its `select`
    * action, and its change feed) and in reorder announcements. Without it, a row is named by its
    * key's `toString`. Labels are computed once per `rows` value, so `f` should depend only on the
    * row.
    */
  def withRowLabel(f: A => String): DataTable[A, K] = copy(rowLabel = Some(f))
  def noRowLabel(): DataTable[A, K]                 = copy(rowLabel = None)

  /**
    * Rows an agent may select or drop from the selection only after the human allows it: `f` gives
    * the question for a guarded row, or None. It works for rows scrolled out of view too, since it
    * reads the data rather than the rendered row.
    *
    * {{{
    *   .withAgentConfirm(u => Option.when(u.role == "admin")(s"Change the selection of \${u.name}?"))
    * }}}
    */
  def withAgentConfirm(f: A => Option[String]): DataTable[A, K] = copy(agentConfirm = Some(f))
  def noAgentConfirm(): DataTable[A, K]                         = copy(agentConfirm = None)

  /** The height of every row in pixels (32 by default). */
  def withRowHeight(h: Int): DataTable[A, K] = copy(rowHeight = h)

  // The active column; the row comes from the list's active row, or the header while `onHeader`
  private val activeColumn                 = Rx.variable(0)
  private val onHeader                     = Rx.variable(false)
  private val tableId                      = Ids.next("uni-table")
  private def headerCellId(i: Int): String = s"${tableId}-header-${i}"

  // The list, set when the table renders, for the key and focus handlers
  private var list: Option[ListCore[A, K]] = None
  // A widget in a body cell that has the focus, to bring the focus back if its row unmounts
  private var widget: Option[dom.Element] = None

  private def plain(e: dom.KeyboardEvent): Boolean = !(e.altKey || e.ctrlKey || e.metaKey)

  // Keys the table handles before the list's own: the header row, and moving into and out of
  // widgets in cells
  private def firstKey(e: dom.KeyboardEvent): Boolean = list.exists { core =>
    if e.target ne e.currentTarget then
      widgetKey(core, e)
    else if onHeader.get then
      headerKey(core, e)
    else
      bodyKey(core, e)
  }

  private def headerKey(core: ListCore[A, K], e: dom.KeyboardEvent): Boolean =
    val toRow = e.ctrlKey || e.metaKey
    e.key match
      case "ArrowDown" | "PageDown" if plain(e) =>
        leaveHeader(core, 0)
        true
      case "ArrowUp" | "PageUp" if plain(e) =>
        true
      case "End" if toRow && !e.altKey =>
        moveColumn(core, columns.size - 1)
        leaveHeader(core, core.rows.size - 1)
        true
      case "Home" if toRow && !e.altKey =>
        moveColumn(core, 0)
        true
      case "Enter" | " " if plain(e) && !e.shiftKey =>
        columns
          .lift(activeColumn.get)
          .filter(_.ordering.isDefined)
          .foreach(c => writeSort(c, ChangeCause.of(ChangeReason.Press, e)))
        true
      case _ if plain(e) && !e.shiftKey =>
        DataTable.nextColumn(e.key, activeColumn.get, columns.size, TextDirection.mirrors(e)) match
          case Some(c) =>
            moveColumn(core, c)
            true
          case None =>
            false
      // Other moves (Ctrl+Down, Shift+End) would move or extend rows behind the header
      case "ArrowDown" | "ArrowUp" | "ArrowLeft" | "ArrowRight" | "Home" | "End" | "PageUp" |
          "PageDown" =>
        true
      case _ =>
        false
    end match

  end headerKey

  private def bodyKey(core: ListCore[A, K], e: dom.KeyboardEvent): Boolean =
    e.key match
      case "ArrowUp" if plain(e) && !e.shiftKey && core.activeRowIndex == 0 =>
        enterHeader(core, activeColumn.get)
        true
      // Ctrl+Shift+Home extends a multiple selection over rows, as the list does
      case "Home" if (e.ctrlKey || e.metaKey) && !e.altKey && !e.shiftKey =>
        core.container.foreach(_.scrollTop = 0)
        enterHeader(core, 0)
        true
      case "Enter" | "F2" if plain(e) && !e.shiftKey =>
        activeCellWidgets(core).headOption match
          case Some(w) =>
            DataTable.focusInPlace(w)
            true
          case None =>
            // Enter without a widget selects, as the list does; F2 has nothing to edit
            e.key == "F2"
      case _ =>
        false

  // Keys from a widget inside a body cell: Escape or F2 returns to the grid, and Tab cycles
  // through the cell's widgets
  private def widgetKey(core: ListCore[A, K], e: dom.KeyboardEvent): Boolean =
    val cell =
      e.target match
        case AnyRealm.Element(t) =>
          Option(t.closest(".uni-table__cell")).filter(c => core.container.exists(_.contains(c)))
        case _ =>
          None
    cell.filter(_.closest(".uni-list__spacer") != null) match
      case None =>
        false
      case Some(c) =>
        e.key match
          case "Escape" | "F2" if plain(e) && !e.shiftKey =>
            core.container.foreach(_.focus())
            true
          case "Tab" if plain(e) && DataTable.widgetsIn(c).nonEmpty =>
            val ws = DataTable.widgetsIn(c)
            val i  = ws.indexWhere(_ eq e.target)
            val n  =
              if e.shiftKey then
                (i - 1 + ws.size) % ws.size
              else
                (i + 1) % ws.size
            ws.lift(n).foreach(DataTable.focusInPlace)
            true
          case _ =>
            false

  private def activeCellWidgets(core: ListCore[A, K]): Seq[dom.HTMLElement] =
    for
      root <- core.container.toSeq
      k    <- core.activeNow.toSeq
      cell <- Option(dom.document.getElementById(core.cellId(k, activeColumn.get))).toSeq
      if root.contains(cell)
      w <- DataTable.widgetsIn(cell)
    yield w

  private def moveColumn(core: ListCore[A, K], c: Int): Unit =
    if c != activeColumn.get then
      activeColumn := c
    core.container.foreach(revealColumn(_, c))

  private def enterHeader(core: ListCore[A, K], c: Int): Unit =
    core.setActive(None)
    onHeader := true
    moveColumn(core, c)

  private def leaveHeader(core: ListCore[A, K], row: Int): Unit =
    if row >= 0 && row < core.rows.size then
      onHeader := false
      core.moveTo(row)

  // Focus starts at the selected cell; a table without rows starts on its header
  private def beforeFocus(): Unit = list.foreach { core =>
    if !onHeader.get && core.activeNow.isEmpty then
      if core.rows.isEmpty then
        onHeader := true
      else
        selectedCell
          .filter(_ => selectable)
          .flatMap(_.get)
          .foreach { c =>
            val col = columns.indexWhere(_.id == c.column)
            if col >= 0 && core.indexOf(c.row).nonEmpty then
              activeColumn := col
              core.setActive(Some(c.row))
          }
  }

  // Keep widgets in body cells out of the tab order as rows render, and bring the focus back to
  // the grid when the row of a focused widget scrolls out and unmounts
  private def watchCells(el: dom.HTMLElement): Cancelable =
    val inCell = s".uni-list__spacer .uni-table__cell :is(${Focus.focusableSelector})"
    def demote(root: dom.Element): Unit =
      (Seq(root).filter(_.matches(inCell)) ++ root.querySelectorAll(inCell).toSeq).foreach(
        _.setAttribute("tabindex", "-1")
      )
    demote(el)
    val observer = dom.MutationObserver { (records, _) =>
      records.foreach(
        _.addedNodes
          .toSeq
          .foreach {
            case AnyRealm.Element(n) =>
              demote(n)
            case _ =>
          }
      )
      widget
        .filterNot(el.contains)
        .foreach { _ =>
          widget = None
          val doc     = AnyRealm.documentOf(el)
          val focused = Option(doc.activeElement)
          if focused.forall(f => (f eq doc.body) || !f.isConnected) then
            el.focus()
        }
    }
    observer.observe(
      el,
      js.Dynamic.literal(childList = true, subtree = true).asInstanceOf[dom.MutationObserverInit]
    )
    val onFocusIn: js.Function1[dom.Event, Unit] =
      e =>
        widget =
          e.target match
            case AnyRealm.Element(t)
                if (t ne el) && t.closest(".uni-list__spacer .uni-table__cell") != null =>
              keepAligned(el, t)
              Some(t)
            case _ =>
              None
    el.addEventListener("focusin", onFocusIn)
    Cancelable { () =>
      observer.disconnect()
      el.removeEventListener("focusin", onFocusIn)
      widget = None
    }

  end watchCells

  // A focused widget in a clipped cell makes the browser scroll the row or cell, which have
  // `overflow: hidden`, out of line with the header. Undo that, and scroll the table instead.
  private def keepAligned(el: dom.HTMLElement, widget: dom.Element): Unit =
    var n = widget.parentNode
    while n != null && (n ne el) do
      n match
        case AnyRealm.HTMLElement(h) if h.scrollLeft != 0 =>
          h.scrollLeft = 0
        case _ =>
      n = n.parentNode
    Option(widget.closest(".uni-table__cell"))
      .flatMap(c => Option(c.getAttribute("aria-colindex")).flatMap(_.toIntOption))
      .foreach(i => revealColumn(el, i - 1))

  private def writeSort(c: Column[A], cause: ChangeCause): Unit =
    ChangeGate.write(sort, "sort", DataTable.nextSort(sort.get, c.id), cause, changeHandler): Unit

  private def onKey(e: dom.KeyboardEvent): Boolean =
    val toRow  = e.ctrlKey || e.metaKey
    val rowEnd = e.key == "Home" || e.key == "End"
    // Left or Right with a modifier (Alt+Left is Back in some browsers) belong to the app. Home and
    // End are handled here, so the list never reads them as a row jump without Ctrl or ⌘, except
    // Shift with Home or End in a multiple selection: rows are what it selects, so the list extends
    // the selection to the first or last row and the column stays.
    if !rowEnd && (e.altKey || e.shiftKey || toRow) then
      false
    else if rowEnd && e.shiftKey && !toRow && !e.altKey && selectable && multiSelected.nonEmpty then
      false
    else
      DataTable.nextColumn(e.key, activeColumn.get, columns.size, TextDirection.mirrors(e)) match
        case Some(c) =>
          if c != activeColumn.get then
            activeColumn := c
          e.currentTarget match
            case AnyRealm.HTMLElement(el) =>
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

  private def subtitleId(i: Int): String = s"${tableId}-subtitle-${i}"

  // The header's label, and its subtitle on a second line. The subtitle is left out of the name
  // (`aria-hidden`, and the header's `aria-label`) and describes the header instead.
  private def heading(c: Column[A], i: Int): DomNode =
    c.subtitle match
      case None =>
        span(c.header)
      case Some(sub) =>
        span(
          cls          -> "uni-table__heading",
          data("slot") -> "data-table-heading",
          span(cls -> "uni-table__label", c.header),
          span(
            id           -> subtitleId(i),
            cls          -> "uni-table__subtitle",
            data("slot") -> "data-table-subtitle",
            ariaHidden   -> "true",
            sub
          )
        )

  private def headerCell(core: => ListCore[A, K])(c: Column[A], i: Int): RxElement =
    // With a subtitle, the element the label names (the sort button, or else the header cell) takes
    // the label as its name and the subtitle as its description
    val describedBy = c
      .subtitle
      .map(_ => DomNode.group(ariaLabel -> c.header, ariaDescribedby -> subtitleId(i)))
      .getOrElse(DomNode.empty)
    val state = sort.map {
      case Some(SortBy(c.id, d)) =>
        Some(d)
      case _ =>
        None
    }
    div(
      id             -> headerCellId(i),
      role           -> "columnheader",
      cls            -> cellClass(c),
      data("slot")   -> "data-table-header-cell",
      ariaColindex   -> (i + 1).toString,
      data("active") ->
        Rx.join(onHeader, activeColumn)
          .map { (h, ac) =>
            Option.when(h && ac == i)("true")
          },
      // A click makes the header cell active, and the grid keeps the keys
      EventListenerBinding(
        "click",
        _ =>
          enterHeader(core, i)
          core.container.filterNot(c => c eq AnyRealm.activeElementOf(c)).foreach(_.focus())
      ),
      c.ordering match
        case None =>
          Seq[DomNode](describedBy, heading(c, i))
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
              tpe          -> "button",
              cls          -> "uni-table__sort",
              data("slot") -> "data-table-sort",
              // Enter or Space on the active header cell sorts, so the button is no tab stop
              tabindex -> "-1",
              EventListenerBinding(
                "click",
                e => writeSort(c, ChangeCause.of(ChangeReason.Press, e))
              ),
              describedBy,
              heading(c, i),
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

  private def sortActions: Seq[AgentAction] =
    val sortable = columns.filter(_.ordering.isDefined)
    val choices  = sortable.flatMap(c => Seq(s"${c.id} ascending", s"${c.id} descending"))
    if sortable.isEmpty then
      Nil
    else
      Seq(
        AgentAction.choice("sort", "Sort the rows by a column", choices) { i =>
          val direction =
            if i % 2 == 0 then
              SortDirection.Ascending
            else
              SortDirection.Descending
          ChangeGate.write(
            sort,
            "sort",
            Some(SortBy(sortable(i / 2).id, direction)),
            AgentAction.cause(ChangeReason.Press),
            changeHandler
          ): Unit
        }
      )

  override def render: RxElement =
    // Lazy, so each cell can take its id from the list that renders it
    lazy val core: ListCore[A, K] = ListCore[A, K](
      sorted,
      key,
      rowHeight,
      VirtualList.defaultOverscan,
      containerClass =
        if centerHeaders then
          "uni-table uni-table--center-headers"
        else
          "uni-table"
      ,
      agentKind = "table",
      agentActions = () => sortActions,
      containerAttrs = Seq(
        role -> "grid",
        label.map(ariaLabel -> _).getOrElse(DomNode.empty),
        ariaColcount -> columns.size.toString,
        ariaRowcount -> rows.map(xs => (xs.size + 1).toString),
        OnMount(watchCells)
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
                data("slot")   -> "data-table-cell",
                ariaColindex   -> (i + 1).toString,
                data("active") ->
                  Rx.join(core.activeKey, activeColumn)
                    .map { (ak, ac) =>
                      if ak.contains(k) && ac == i then
                        Some("true")
                      else
                        None
                    },
                selectedCell
                  .filter(_ => selectable)
                  .map(s => ariaSelected -> s.map(_.contains(CellKey(k, c.id)).toString))
                  .getOrElse(DomNode.empty),
                // The row's click handler then selects the row, or this cell
                EventListenerBinding(
                  "click",
                  _ =>
                    onHeader     := false
                    activeColumn := i
                ),
                c.cell(a)
              )
            },
      selection =
        if !selectable then
          ListSelection.Off()
        else
          selectedCell.fold(
            multiSelected.fold(ListSelection.Single(selected))(ListSelection.Multi(_))
          )(s =>
            ListSelection.Cell(
              s,
              column = () => columns.lift(activeColumn.get).map(_.id),
              columns = columns.map(c => (c.id, c.header))
            )
          )
      ,
      onSelect = onSelectFn,
      changeHandler = changeHandler,
      slot = "data-table",
      typeaheadLabel = None,
      itemLabel = rowLabel,
      agentConfirm = agentConfirm,
      extraKey = (e, _, _) => onKey(e),
      header = div(
        role         -> "rowgroup",
        cls          -> "uni-table__header",
        data("slot") -> "data-table-header",
        div(
          role         -> "row",
          cls          -> "uni-table__header-row",
          ariaRowindex -> "1",
          columns.zipWithIndex.map(headerCell(core))
        )
      ),
      activeCell = activeColumn.map(c => Option.when(columns.nonEmpty)(c)),
      reorderable = reorderable,
      reorderEnabled = Reorder.Enabled.when(sort)(_.isEmpty),
      firstKey = firstKey,
      beforeFocus = () => beforeFocus(),
      headerActive = onHeader,
      headerCellId = headerCellId
    )
    list = Some(core)
    core

  end render

end DataTable

object DataTable:
  // `new` is required in this file: the `apply` below replaces the constructor proxy
  def apply[A, K](rows: Rx[Seq[A]], key: A => K, columns: Seq[Column[A]]): DataTable[A, K] =
    new DataTable(
      rows,
      key,
      columns,
      label = None,
      sort = Rx.variable(None),
      selected = Rx.variable(None),
      onSelectFn = _ => (),
      rowHeight = 32,
      changeHandler = ChangeGate.noHandler,
      multiSelected = None,
      reorderable = None,
      rowLabel = None,
      agentConfirm = None,
      selectedCell = None,
      selectable = true,
      centerHeaders = false
    )

  /**
    * The active column after `key` in a row of `count` cells: Left and Right move by one and stop
    * at the ends, and Home and End go to the first and last cell. With `rtl`, the table is laid out
    * right to left, so Left moves toward the last cell.
    */
  def nextColumn(key: String, current: Int, count: Int, rtl: Boolean = false): Option[Int] =
    if count <= 0 then
      None
    else
      TextDirection.logicalKey(key, rtl) match
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

  // Focus without the browser's own scrolling, which would scroll a clipped row; the table reveals
  // the cell's column itself
  private def focusInPlace(w: dom.HTMLElement): Unit =
    w.asInstanceOf[js.Dynamic].focus(js.Dynamic.literal(preventScroll = true)): Unit

  /** The widgets in `cell` that Enter or F2 moves into, in document order. */
  private[ui] def widgetsIn(cell: dom.Element): Seq[dom.HTMLElement] = cell
    .querySelectorAll(DataTable.widgetSelector)
    .toSeq
    .collect { case AnyRealm.HTMLElement(h) =>
      h
    }

  // Focus.focusableSelector, plus the widgets the table took out of the tab order
  private val widgetSelector: String = Focus
    .focusableSelector
    .replace(":not([tabindex=\"-1\"])", "")

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
