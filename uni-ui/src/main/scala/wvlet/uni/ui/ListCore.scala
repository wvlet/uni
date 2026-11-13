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
  *   false while the rows can't be moved, as in a sorted table
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
    reorderEnabled: () => Boolean = () => true,
    // The text that names an item for agents; typeahead text, then the key, when absent
    itemLabel: Option[A => String] = None
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
      case ListSelection.Single(_) =>
        None

  private val reorder: Option[Reorder[K]] = reorderable.map { o =>
    val r = Reorder
      .by(o, label = typeaheadLabel.getOrElse((a: A) => String.valueOf(key(a))))(key)
      .onChange(changeHandler)
      .enabledWhen(reorderEnabled)
    selection match
      case ListSelection.Multi(keys) =>
        r.withSelection(keys)
      case ListSelection.Single(_) =>
        r
  }

  private def isSelected(k: K): Rx[Boolean] =
    selection match
      case ListSelection.Single(s) =>
        s.map(_.contains(k))
      case ListSelection.Multi(keys) =>
        keys.map(_.contains(k))

  private def order: Seq[K] = current.map(key)

  private def rowId(k: K): String = ids.getOrElseUpdate(k, Ids.next(s"${listId}-row"))

  /** The id of cell `i` in row `k`, which a grid's cells use for `aria-activedescendant`. */
  private[ui] def cellId(k: K, i: Int): String = s"${rowId(k)}-cell-${i}"

  /** The key of the highlighted row. */
  private[ui] def activeKey: Rx[Option[K]] = active

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

  // Choosing the selected row again still runs onSelect, as before (to open it, say)
  private def select(a: A, cause: ChangeCause): Unit =
    val accepted =
      selection match
        case ListSelection.Single(s) =>
          ChangeGate.write(s, "selected", Some(key(a)), cause, changeHandler)
        case ListSelection.Multi(_) =>
          multi.exists(_.select(key(a), cause))
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
    if fromContainer && !Keyboard.isComposing(e) && !e.defaultPrevented then
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
    if active.get.isEmpty then
      val start =
        selection match
          case ListSelection.Single(s) =>
            s.get.filter(index.get.contains)
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
      id             -> rowId(k),
      role           -> rowRole,
      cls            -> "uni-list__row",
      data("slot")   -> s"${slot}-row",
      ariaSelected   -> isSelected(k).map(_.toString),
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
    override def kind: String = agentKind

    private val nameOf: A => String = itemLabel
      .orElse(typeaheadLabel)
      .getOrElse(a => String.valueOf(key(a)))

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

    override def states: Seq[AnyRef] =
      (
        selection match
          case ListSelection.Single(s) =>
            s
          case ListSelection.Multi(keys) =>
            keys
      ) +: reorderable.toSeq

    override def format(v: Any): String =
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
      val keys = selectedKeys
      Option.when(keys.nonEmpty)(format(keys))

    override def items: Seq[UiItem] =
      val keys = selectedKeys
      labels.zip(current).map((l, a) => UiItem(l, keys.contains(key(a))))

    override def actions: Seq[AgentAction] =
      AgentAction.choice("select", "Select a row, as a click does", labels)(i =>
        select(current(i), AgentAction.cause(ChangeReason.ItemPress))
      ) +: (moveAction.toSeq ++ agentActions())

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
        Rx.join(active, activeCell)
          .map { (a, c) =>
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

/** Whether a list selects one row or a set of rows. */
private[ui] enum ListSelection[K]:
  case Single(selected: RxVar[Option[K]])
  case Multi(selected: RxVar[Set[K]])
