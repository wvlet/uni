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
import wvlet.uni.ui.agent.{AgentAction, AgentBinding, AgentInputError, AgentRegistry, UiItem}
import wvlet.uni.ui.base.*

import scala.collection.mutable
import scala.language.implicitConversions

/**
  * The windowed, keyboard-driven list under [[VirtualList]], [[Tree]], and [[DataTable]]. It
  * renders only the rows in view (plus `overscan` on each side) inside a spacer as tall as the
  * whole list, keyed with `each`, so scrolling adds and removes rows without rebuilding the rest.
  *
  * Focus stays on the scroll container, and `aria-activedescendant` names the active row: the arrow
  * keys, Home, End, PageUp, PageDown, and typing a letter move it, scrolling it into view. Enter,
  * Space, or a click selects a row; during a typeahead search Space is part of the search. Keys
  * from elements inside a row (a button in a cell) are left alone.
  *
  * With [[ListSelection.Multi]] the selection is a set of keys, kept by a [[Selection]]: a click
  * selects one row, Shift or Ctrl (⌘) with a click extends or toggles, Shift with a navigation key
  * extends from the anchor, Space toggles the active row, Ctrl+A selects all, and Escape clears.
  * Enter and a plain click still run `onSelect`.
  *
  * @param header
  *   content above the rows inside the scroll container, such as a table's sticky header row; the
  *   rows' windowing starts below it
  * @param rowAttrs
  *   extra attributes for a row, given its item, its live index in `items`, and the live number of
  *   items
  * @param extraKey
  *   handles a key before the default navigation, given the key event, the active index (-1 for
  *   none), and a function that moves to an index; true when it handled the key
  * @param activeCell
  *   the active cell within the active row, for a grid; `aria-activedescendant` then names that
  *   cell (`cellId`) instead of the row
  * @param reorderable
  *   the variable `items` shows, to let the user reorder the rows: each rendered row becomes a
  *   [[Reorder.item]], and Alt+Arrow, Alt+Home, and Alt+End move the active row (with the
  *   selection, when it is selected) by key, so they work on rows scrolled out of view too
  * @param reorderEnabled
  *   off while the rows can't be moved, as in a sorted table
  * @param firstKey
  *   handles a key before anything else, including keys from controls inside a row; true when it
  *   handled the key. A grid uses it for its header row and to move into and out of cell widgets
  * @param beforeFocus
  *   runs when the list takes the focus, before it picks a start row
  * @param headerActive
  *   true while a header cell is active instead of a row, in the column `activeCell` names;
  *   `aria-activedescendant` then names `headerCellId` of it, and focus picks no row
  */
private[ui] class ListCore[A, K](
    items: Rx[Seq[A]],
    key: A => K,
    rowHeight: Int,
    overscan: Int,
    containerClass: String,
    containerAttrs: Seq[DomNode],
    containerStyle: String,
    spacerAttrs: Seq[DomNode],
    rowRole: String,
    rowAttrs: (A, Rx[Int], Rx[Int]) => DomNode,
    renderRow: A => DomNode,
    selection: ListSelection[K],
    onSelect: A => Unit,
    typeaheadLabel: Option[A => String],
    extraKey: (dom.KeyboardEvent, Int, Int => Unit) => Boolean,
    header: DomNode,
    activeCell: Rx[Option[Int]] = Rx.const(None),
    changeHandler: ChangeHandler = ChangeGate.noHandler,
    slot: String = "list",
    agentKind: String = "list",
    agentActions: () => Seq[AgentAction] = () => Nil,
    reorderable: Option[RxVar[Seq[A]]] = None,
    reorderEnabled: Reorder.Enabled = Reorder.Enabled.always,
    // The text that names an item for agents and reorder announcements; typeahead text, then the
    // key, when absent
    itemLabel: Option[A => String] = None,
    // The question an agent's selection change must ask the human first, for a row it adds or drops
    agentConfirm: Option[A => Option[String]] = None,
    firstKey: dom.KeyboardEvent => Boolean = _ => false,
    beforeFocus: () => Unit = () => (),
    headerActive: RxVar[Boolean] = Rx.variable(false),
    headerCellId: Int => String = i => s"header-${i}"
) extends RxElement:

  private val listId = Ids.next("uni-list")
  private val active = Rx.variable(Option.empty[K])
  // (first row in view, rows that fit); a guess until the container is measured
  private val viewport = Rx.variable((0, 30))
  // (first rendered row, row count), for the spacer
  private val window = Rx.variable((0, 0))
  private val index  = Rx.variable(Map.empty[K, Int])
  private val ids    = mutable.Map.empty[K, String]

  // The latest list, for keyboard moves; kept in step by the rendered rows
  private var current: Seq[A]                    = Seq.empty
  private val typeahead                          = Typeahead()
  private var root: Option[dom.HTMLElement]      = None
  private var spacer: Option[dom.Element]        = None
  private var resize: Option[dom.ResizeObserver] = None

  private val multi: Option[Selection[K]] =
    selection match
      case ListSelection.Multi(keys) =>
        Some(Selection(keys).onChange(changeHandler))
      case _ =>
        None

  // The name of an item for agents and reorder announcements
  private val nameOf: A => String = itemLabel
    .orElse(typeaheadLabel)
    .getOrElse(a => String.valueOf(key(a)))

  private val reorder: Option[Reorder[K]] = reorderable.map { o =>
    val r = Reorder
      .by(o, label = nameOf)(key)
      .onChange(changeHandler)
      .enabledWhen(reorderEnabled)
      // Every displayed row counts, even far outside the window; rows a filter hides don't
      .showing(k => index.get.contains(k))
    selection match
      case ListSelection.Multi(keys) =>
        r.withSelection(keys)
      case _ =>
        r
  }

  // Rows carry no aria-selected while a grid selects cells
  private def isSelected(k: K): Option[Rx[Boolean]] =
    selection match
      case ListSelection.Single(s) =>
        Some(s.map(_.contains(k)))
      case ListSelection.Multi(keys) =>
        Some(keys.map(_.contains(k)))
      case ListSelection.Cell(_, _, _) =>
        None

  private def order: Seq[K] = current.map(key)

  private def rowId(k: K): String = ids.getOrElseUpdate(k, Ids.next(s"${listId}-row"))

  /** The id of cell `i` in row `k`, which a grid's cells use for `aria-activedescendant`. */
  private[ui] def cellId(k: K, i: Int): String = s"${rowId(k)}-cell-${i}"

  /** The key of the highlighted row. */
  private[ui] def activeKey: Rx[Option[K]] = active

  /** The key of the highlighted row now. */
  private[ui] def activeNow: Option[K] = active.get

  /** Highlight row `k` (or none) without scrolling, as focus does. */
  private[ui] def setActive(k: Option[K]): Unit =
    if active.get != k then
      active := k

  /** The index of the highlighted row in the list, or -1. */
  private[ui] def activeRowIndex: Int = activeIndex

  /** The list as last rendered. */
  private[ui] def rows: Seq[A] = current

  /** The index of row `k`, if the list has it. */
  private[ui] def indexOf(k: K): Option[Int] = index.get.get(k)

  /** The scroll container, while mounted. */
  private[ui] def container: Option[dom.HTMLElement] = root

  private def sync(xs: Seq[A]): Unit =
    if xs ne current then
      current = xs
      val idx = xs.iterator.map(key).zipWithIndex.toMap
      ids.filterInPlace((k, _) => idx.contains(k))
      index := idx
      if active.get.exists(k => !idx.contains(k)) then
        active := None

  /** The space rows have: the container's height minus the header above them. */
  private def rowsTop(el: dom.HTMLElement): Double = spacer
    .collect { case s: dom.HTMLElement =>
      s.offsetTop
    }
    .getOrElse(0.0)

  private def viewportHeight(el: dom.HTMLElement): Double = math.max(
    0.0,
    el.clientHeight - rowsTop(el)
  )

  private def measure(): Unit = root.foreach { el =>
    val v = Windowing.inView(el.scrollTop, viewportHeight(el), rowHeight)
    if v != viewport.get then
      viewport := v
  }

  private def pageSize: Int = math.max(1, viewport.get._2 - 1)

  private def activeIndex: Int = active.get.flatMap(index.get.get).getOrElse(-1)

  private[ui] def moveTo(i: Int): Unit = current
    .lift(i)
    .foreach { a =>
      root.foreach { el =>
        Windowing
          .reveal(i, rowHeight, el.scrollTop, viewportHeight(el))
          .foreach { top =>
            el.scrollTop = top
            measure()
          }
      }
      active := Some(key(a))
    }

  // Choosing the selected row again still runs onSelect, as before (to open it, say). A grid that
  // selects cells selects `column` of the row, else its active column.
  private def select(a: A, cause: ChangeCause, column: Option[String] = None): Unit =
    val accepted =
      selection match
        case ListSelection.Single(s) =>
          ChangeGate.write(s, "selected", Some(key(a)), cause, changeHandler)
        case ListSelection.Multi(_) =>
          multi.exists(_.select(key(a), cause))
        case ListSelection.Cell(s, active, _) =>
          column
            .orElse(active())
            .exists(c =>
              ChangeGate.write(s, "selectedCell", Some(CellKey(key(a), c)), cause, changeHandler)
            )
    if accepted then
      ChangeCause.during(cause)(onSelect(a))

  // With no anchor yet, a Shift range starts at the row that was active (index `from`)
  private def seedAnchor(sel: Selection[K], from: Int): Unit =
    if !sel.anchor.exists(index.get.contains) then
      sel.setAnchor(current.lift(from).map(key))

  // A click: Shift and Ctrl (⌘) extend or toggle a multiple selection; a plain click selects
  private def press(a: A, from: Int, e: dom.MouseEvent): Unit =
    multi match
      case Some(sel) if e.shiftKey || e.ctrlKey || e.metaKey =>
        if e.shiftKey then
          seedAnchor(sel, from)
        sel.press(key(a), order, e)
      case _ =>
        select(a, ChangeCause.of(ChangeReason.ItemPress, e))

  // Move to row `n` and, with Shift in a multiple selection, extend the selection to it
  private def navigate(n: Int, from: Int, e: dom.KeyboardEvent): Unit =
    moveTo(n)
    multi
      .filter(_ => e.shiftKey)
      .foreach { sel =>
        seedAnchor(sel, from)
        current
          .lift(n)
          .foreach(a => sel.extendTo(key(a), order, ChangeCause.of(ChangeReason.NavigationKey, e)))
      }

  private def onKeyDown(e: dom.KeyboardEvent): Unit =
    // Keys typed into a control inside a row belong to that control
    val fromContainer = e.target eq e.currentTarget
    val handled       = !Keyboard.isComposing(e) && !e.defaultPrevented && firstKey(e)
    if handled then
      e.preventDefault()
    else if fromContainer && !Keyboard.isComposing(e) && !e.defaultPrevented then
      val i = activeIndex
      e.key match
        case " " if typeaheadLabel.nonEmpty && typeahead.joinsSearch(e) =>
          // Space inside a search is part of it ("new york"), not a selection
          e.preventDefault()
          typeaheadLabel.flatMap(label => typeahead.onKey(e, current.map(label), i)).foreach(moveTo)
        case " " if multi.nonEmpty =>
          // Space toggles the active row, and Shift+Space extends the selection to it
          current
            .lift(i)
            .foreach { a =>
              e.preventDefault()
              val cause = ChangeCause.of(ChangeReason.ItemPress, e)
              multi.foreach { sel =>
                if e.shiftKey then
                  sel.extendTo(key(a), order, cause)
                else
                  sel.toggle(key(a), cause)
              }
            }
        case "Enter" | " " =>
          current
            .lift(i)
            .foreach { a =>
              e.preventDefault()
              select(a, ChangeCause.of(ChangeReason.ItemPress, e))
            }
        case _ if moveByKey(i, e) =>
          ()
        case _ if extraKey(e, i, moveTo) =>
          e.preventDefault()
        case _ if multi.exists(_.onKey(e, order)) =>
          e.preventDefault()
        case k =>
          ListNavigation
            .nextIndex(k, i, current.size, pageSize)
            .map(n => (n, true))
            .orElse(
              typeaheadLabel
                .flatMap(label => typeahead.onKey(e, current.map(label), i))
                .map(n => (n, false))
            )
            .foreach { (n, isNavigation) =>
              e.preventDefault()
              if isNavigation then
                navigate(n, i, e)
              else
                moveTo(n)
            }
      end match
    end if

  end onKeyDown

  // Alt+Arrow, Alt+Home, or Alt+End on the active row: move it, then keep it in view
  private def moveByKey(i: Int, e: dom.KeyboardEvent): Boolean = reorder.exists { r =>
    current
      .lift(i)
      .map(key)
      .exists { k =>
        val handled = r.onMoveKey(k, e)
        if handled then
          index.get.get(k).foreach(moveTo)
        handled
      }
  }

  // Focusing the list shows where the keys will start: the selected row, else the first
  private def onFocus(): Unit =
    beforeFocus()
    if active.get.isEmpty && !headerActive.get then
      val start =
        selection match
          case ListSelection.Single(s) =>
            s.get.filter(index.get.contains)
          case ListSelection.Cell(s, _, _) =>
            s.get.map(_.row).filter(index.get.contains)
          case ListSelection.Multi(keys) =>
            multi
              .flatMap(_.anchor)
              .filter(k => keys.get.contains(k) && index.get.contains(k))
              .orElse(order.find(keys.get.contains))
      active := start.orElse(current.headOption.map(key))

  override def onMount(node: Any): Unit =
    node match
      case el: dom.HTMLElement =>
        root = Some(el)
        spacer = Option(el.querySelector(":scope > .uni-list__spacer"))
        measure()
        val ro = dom.ResizeObserver((_, _) => measure())
        ro.observe(el)
        resize = Some(ro)
      case _ =>

  override def beforeUnmount: Unit =
    resize.foreach(_.disconnect())
    resize = None
    root = None
    spacer = None

  private def row(a: A): RxElement =
    val k = key(a)
    div(
      id           -> rowId(k),
      role         -> rowRole,
      cls          -> "uni-list__row",
      data("slot") -> s"${slot}-row",
      isSelected(k).map(s => ariaSelected -> s.map(_.toString)).getOrElse(DomNode.empty),
      data("active") ->
        active.map(x =>
          if x.contains(k) then
            Some("true")
          else
            None
        ),
      rowAttrs(a, index.map(_.getOrElse(k, -1)), window.map(_._2)),
      reorder.map { r =>
        DomNode.group(
          r.item(k),
          // A drag's pointerdown keeps the browser from focusing the list, as a click would
          EventListenerBinding(
            "pointerdown",
            _ => root.filterNot(_.contains(dom.document.activeElement)).foreach(_.focus())
          )
        )
      },
      EventListenerBinding(
        "click",
        e =>
          val from = activeIndex
          active := Some(k)
          press(a, from, e.asInstanceOf[dom.MouseEvent])
      ),
      renderRow(a)
    )

  end row

  private object agentBinding extends AgentBinding:
    override def kind: String                 = agentKind
    override def changeHandler: ChangeHandler = ListCore.this.changeHandler
    // Unlabeled, a list would take every row's text as its name
    override def namedByText: Boolean = false

    // Labels for the rows in `labelsOf`, rebuilt only when the rows change
    private var labelsOf: Seq[A]    = Seq.empty
    private var cached: Seq[String] = Seq.empty
    private def labels: Seq[String] =
      if labelsOf ne current then
        labelsOf = current
        cached = AgentBinding.itemLabels(current.map(nameOf))
      cached

    private def selectedKeys: Set[K] =
      selection match
        case ListSelection.Single(s) =>
          s.get.toSet
        case ListSelection.Multi(keys) =>
          keys.get
        case ListSelection.Cell(s, _, _) =>
          s.get.map(_.row).toSet

    override def states: Seq[AnyRef] =
      (
        selection match
          case ListSelection.Single(s) =>
            s
          case ListSelection.Multi(keys) =>
            keys
          case ListSelection.Cell(s, _, _) =>
            s
      ) +: reorderable.toSeq

    // A cell is named by its row's label and its column's header: "list · Method"
    private def cellLabel(c: CellKey[?]): String =
      val header =
        selection match
          case ListSelection.Cell(_, _, columns) =>
            columns
              .collectFirst { case (c.column, h) =>
                h
              }
              .getOrElse(c.column)
          case _ =>
            c.column
      s"${format(c.row)} ${ListCore.cellSeparator} ${header}"

    override def format(v: Any): String =
      v match
        case Some(c: CellKey[?]) =>
          cellLabel(c)
        case c: CellKey[?] =>
          cellLabel(c)
        case _ =>
          formatKeys(v)

    private def formatKeys(v: Any): String =
      val keys: Iterable[Any] =
        v match
          case o: Option[?] =>
            o
          // The rows in a new order, from reorderable
          case rows: Seq[?] =>
            rows.map(a => key(a.asInstanceOf[A]))
          case s: Iterable[?] =>
            s
          case other =>
            Seq(other)
      // Only the changed keys are looked up, with the same (deduplicated) labels as `items`
      val idx = index.get.asInstanceOf[Map[Any, Int]]
      keys.map(k => idx.get(k).fold(String.valueOf(k))(labels)).mkString(", ")

    override def value: Option[String] =
      selection match
        case ListSelection.Cell(s, _, _) =>
          s.get.map(cellLabel)
        case _ =>
          val keys = selectedKeys
          Option.when(keys.nonEmpty)(format(keys))

    override def items: Seq[UiItem] =
      val keys = selectedKeys
      labels.zip(current).map((l, a) => UiItem(l, keys.contains(key(a))))

    override def actions: Seq[AgentAction] =
      val choose =
        selection match
          case ListSelection.Cell(_, _, columns) =>
            AgentAction
              .text(
                "selectCell",
                s"Select a cell, as a click does: '<row label> ${ListCore
                    .cellSeparator} <column header>'"
              ) { v =>
                val (i, c) = findCell(v, columns)
                select(current(i), AgentAction.cause(ChangeReason.ItemPress), Some(c))
              }
              .withConfirm(v => agentConfirm.flatMap(guarded(_, v.map(ListCore.cellRow))))
          case _ =>
            AgentAction
              .choice("select", "Select a row, as a click does", labels)(i =>
                select(current(i), AgentAction.cause(ChangeReason.ItemPress))
              )
              .withConfirm(v => agentConfirm.flatMap(guarded(_, v)))
      choose +: (moveAction.toSeq ++ agentActions())

    // The row index and column id an agent's `selectCell` value names
    private def findCell(v: String, columns: Seq[(String, String)]): (Int, String) =
      val i = labels.indexOf(ListCore.cellRow(v))
      if i < 0 then
        throw AgentInputError(
          s"No row '${ListCore.cellRow(v)}'. Rows: ${labels
              .take(AgentAction.maxChoices)
              .mkString(", ")}"
        )
      val header = ListCore.cellColumn(v)
      val c      = columns
        .collectFirst { case (id, `header`) =>
          id
        }
        .getOrElse(
          throw AgentInputError(
            s"No column '${header}'. Columns: ${columns.map(_._2).mkString(", ")}"
          )
        )
      (i, c)

    // Selecting the row labeled `v` replaces the selection, as a plain click does: ask about the
    // first marked row that it adds or drops
    private def guarded(f: A => Option[String], v: Option[String]): Option[String] = v
      .map(labels.indexOf)
      .filter(_ >= 0)
      .flatMap { i =>
        val target = current(i)
        val before = selectedKeys
        val idx    = index.get
        val added  = Option.unless(before.contains(key(target)))(target)
        val gone   = before.filterNot(_ == key(target)).toSeq
        // A dropped row the list no longer shows (filtered out, or under a closed tree row) can't
        // be checked, so ask rather than let it go unseen
        val unseen = gone.count(k => !idx.contains(k))
        val rows   = (added.toSeq ++ gone.flatMap(idx.get).sorted.flatMap(current.lift))
        rows
          .iterator
          .flatMap(f)
          .nextOption()
          .orElse(
            Option.when(unseen > 0)(
              s"Change the selection, dropping ${unseen} selected row(s) not listed now?"
            )
          )
      }

    private def moveAction: Option[AgentAction] = reorder
      .filter(_.isEnabled)
      .map { r =>
        AgentAction.text(
          "move",
          "Move a row: '<row label> to <position>', where position 1 is the first row"
        ) { v =>
          val (target, to) = ListCore.parseMove(v, current.size)
          val i            = labels.indexOf(target)
          if i < 0 then
            throw AgentInputError(
              s"No row '${target}'. Rows: ${labels.take(AgentAction.maxChoices).mkString(", ")}"
            )
          r.moveTo(key(current(i)), to, AgentAction.cause(ChangeReason.Imperative))
        }
      }

  end agentBinding

  override def render: RxElement =
    val rows = Rx
      .join(items, viewport)
      .map { (xs, v) =>
        sync(xs)
        val (start, end) = Windowing.range(v._1, v._2, xs.size, overscan)
        window := (start, xs.size)
        xs.slice(start, end)
      }
    div(
      cls          -> s"uni-list ${containerClass}",
      data("slot") -> slot,
      AgentRegistry.attach(agentBinding),
      tabindex             -> "0",
      styleAttr            -> s"--uni-row-height: ${rowHeight}px; ${containerStyle}",
      ariaActivedescendant ->
        Rx.join(active, activeCell, headerActive)
          .map { (a, c, h) =>
            if h then
              c.map(headerCellId)
            else
              a.map(k => c.fold(rowId(k))(cellId(k, _)))
          },
      if multi.nonEmpty then
        ariaMultiselectable -> "true"
      else
        DomNode.empty
      ,
      containerAttrs,
      EventListenerBinding("scroll", _ => measure()),
      EventListenerBinding("focus", _ => onFocus()),
      EventListenerBinding("keydown", e => onKeyDown(e.asInstanceOf[dom.KeyboardEvent])),
      header,
      div(
        cls -> "uni-list__spacer",
        spacerAttrs,
        styleAttr ->
          window.map { (start, count) =>
            s"height: ${count * rowHeight}px; padding-top: ${start * rowHeight}px"
          },
        each(rows)(key)(row)
      )
    )

  end render

end ListCore

private[ui] object ListCore:
  /** What separates the row label from the column header in a cell's agent label. */
  val cellSeparator = "·"

  /** The row label in an agent's cell label, `"<row> · <column>"`. */
  def cellRow(v: String): String =
    val at = v.lastIndexOf(s" ${cellSeparator} ")
    if at < 0 then
      v
    else
      v.substring(0, at)

  /** The column header in an agent's cell label, or "" without one. */
  def cellColumn(v: String): String =
    val at = v.lastIndexOf(s" ${cellSeparator} ")
    if at < 0 then
      ""
    else
      v.substring(at + cellSeparator.length + 2)

  /**
    * The row label and the position (from 0) in an agent's `move` value, `"<label> to <n>"` with
    * `n` from 1, or `first` or `last`.
    */
  def parseMove(v: String, size: Int): (String, Int) =
    val at = v.lastIndexOf(" to ")
    if at < 0 then
      throw AgentInputError(s"Write '<row label> to <position>', not '${v}'")
    val position = v.substring(at + 4).trim
    val to       =
      position.toLowerCase match
        case "first" =>
          0
        case "last" =>
          size - 1
        case n =>
          n.toIntOption
            .filter(p => p >= 1 && p <= size)
            .map(_ - 1)
            .getOrElse(throw AgentInputError(s"Position must be 1 to ${size}, first, or last"))
    (v.substring(0, at), to)

end ListCore

/** Whether a list selects one row, a set of rows, or (in a grid) one cell. */
private[ui] enum ListSelection[K]:
  case Single(selected: RxVar[Option[K]])
  case Multi(selected: RxVar[Set[K]])

  /**
    * One cell of a grid: `column` gives the id of the active column, and `columns` pairs each
    * column id with its header, which names the cell for agents.
    */
  case Cell(
      selected: RxVar[Option[CellKey[K]]],
      column: () => Option[String],
      columns: Seq[(String, String)]
  )
