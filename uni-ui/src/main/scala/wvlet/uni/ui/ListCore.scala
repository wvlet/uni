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
  * Space, or a click selects a row. Keys from elements inside a row (a button in a cell) are left
  * alone.
  *
  * @param header
  *   content above the rows inside the scroll container, such as a table's sticky header row; the
  *   rows' windowing starts below it
  * @param rowAttrs
  *   extra attributes for a row, given its item and its live index in `items`
  * @param extraKey
  *   handles a key before the default navigation, given the key, the active index (-1 for none),
  *   and a function that moves to an index; true when it handled the key
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
    rowAttrs: (A, Rx[Int]) => DomNode,
    renderRow: A => DomNode,
    selected: RxVar[Option[K]],
    onSelect: A => Unit,
    typeaheadLabel: Option[A => String],
    extraKey: (String, Int, Int => Unit) => Boolean,
    header: DomNode
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
  private var root: Option[dom.HTMLElement]      = None
  private var spacer: Option[dom.Element]        = None
  private var resize: Option[dom.ResizeObserver] = None

  private def rowId(k: K): String = ids.getOrElseUpdate(k, Ids.next(s"${listId}-row"))

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

  private def select(a: A): Unit =
    selected := Some(key(a))
    onSelect(a)

  private def onKeyDown(e: dom.KeyboardEvent): Unit =
    // Keys typed into a control inside a row belong to that control
    val fromContainer = e.target eq e.currentTarget
    if fromContainer && !Keyboard.isComposing(e) && !e.defaultPrevented then
      val i = activeIndex
      e.key match
        case "Enter" | " " =>
          current
            .lift(i)
            .foreach { a =>
              e.preventDefault()
              select(a)
            }
        case k if extraKey(k, i, moveTo) =>
          e.preventDefault()
        case k =>
          ListNavigation
            .nextIndex(k, i, current.size, pageSize)
            .orElse {
              typeaheadLabel.flatMap { label =>
                if k.length == 1 && !e.ctrlKey && !e.metaKey && !e.altKey then
                  RovingFocus.typeahead(current.map(label), i, k)
                else
                  None
              }
            }
            .foreach { n =>
              e.preventDefault()
              moveTo(n)
            }

  end onKeyDown

  // Focusing the list shows where the keys will start: the selected row, else the first
  private def onFocus(): Unit =
    if active.get.isEmpty then
      active := selected.get.filter(index.get.contains).orElse(current.headOption.map(key))

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
      ariaSelected   -> selected.map(s => s.contains(k).toString),
      data("active") ->
        active.map(x =>
          if x.contains(k) then
            Some("true")
          else
            None
        ),
      rowAttrs(a, index.map(_.getOrElse(k, -1))),
      EventListenerBinding(
        "click",
        _ =>
          active := Some(k)
          select(a)
      ),
      renderRow(a)
    )

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
      cls                  -> s"uni-list ${containerClass}",
      tabindex             -> "0",
      styleAttr            -> s"--uni-row-height: ${rowHeight}px; ${containerStyle}",
      ariaActivedescendant -> active.map(_.map(rowId)),
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
