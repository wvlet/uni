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

/**
  * A long list that renders only the rows in view, so ten thousand rows scroll like ten. Every row
  * is `rowHeight` pixels tall, and the list scrolls inside its own box: give it a height (or a flex
  * parent that bounds it), or it grows to fit every row.
  *
  * {{{
  *   VirtualList(log.lines, rowHeight = 24)(_.id)(line => span(line.text))
  *     .withLabel("Log")
  *     .onSelect(line => open(line))
  * }}}
  *
  * The list takes focus as one tab stop (`role="listbox"`); the arrow keys, Home, End, PageUp,
  * PageDown, and typing a letter (with [[withTypeahead]]) move the highlighted row, Enter or a
  * click selects it. Rows are keyed by `key`, so a row keeps its DOM node while it stays in view.
  * The container is `div.uni-list.uni-virtual-list`; rows are `div.uni-list__row` with
  * `role="option"`, `aria-selected`, and `data-active="true"` on the highlighted one.
  */
class VirtualList[A, K] private (
    items: Rx[Seq[A]],
    rowHeight: Int,
    key: A => K,
    renderRow: A => DomNode,
    label: Option[String],
    selected: RxVar[Option[K]],
    onSelectFn: A => Unit,
    overscan: Int,
    typeahead: Option[A => String],
    changeHandler: ChangeHandler,
    multiSelected: Option[RxVar[Set[K]]]
) extends RxElement:

  private def copy(
      label: Option[String] = label,
      selected: RxVar[Option[K]] = selected,
      onSelectFn: A => Unit = onSelectFn,
      overscan: Int = overscan,
      typeahead: Option[A => String] = typeahead,
      changeHandler: ChangeHandler = changeHandler,
      multiSelected: Option[RxVar[Set[K]]] = multiSelected
  ): VirtualList[A, K] =
    new VirtualList(
      items,
      rowHeight,
      key,
      renderRow,
      label,
      selected,
      onSelectFn,
      overscan,
      typeahead,
      changeHandler,
      multiSelected
    )

  /** The accessible name of the list. */
  def withLabel(l: String): VirtualList[A, K] = copy(label = Some(l))
  def noLabel(): VirtualList[A, K]            = copy(label = None)

  /** The key of the selected row, set by Enter or a click; the app can also set it. */
  def withSelected(s: RxVar[Option[K]]): VirtualList[A, K] = copy(selected = s)

  /**
    * Lets the user select several rows, whose keys live in `s`: Shift or Ctrl (⌘) with a click,
    * Shift with an arrow key, Space, Ctrl+A, and Escape change the set, as in a file list. The list
    * then leaves [[withSelected]]'s variable alone.
    */
  def withMultiSelect(s: RxVar[Set[K]]): VirtualList[A, K] = copy(multiSelected = Some(s))

  /** Selects one row at a time, in [[withSelected]]'s variable (the default). */
  def noMultiSelect(): VirtualList[A, K] = copy(multiSelected = None)

  /** Runs when Enter or a click selects a row; [[ChangeCause.current]] tells which. */
  def onSelect(f: A => Unit): VirtualList[A, K] = copy(onSelectFn = f)

  /** Called before Enter or a click changes the selection; `cancel()` keeps it. Key: `selected`. */
  def onChange(f: ChangeHandler): VirtualList[A, K] = copy(changeHandler = f)

  /** How many rows to render beyond each edge of the view (8 by default). */
  def withOverscan(n: Int): VirtualList[A, K] = copy(overscan = math.max(0, n))

  /** Typing a letter moves to the next row whose label starts with it. */
  def withTypeahead(label: A => String): VirtualList[A, K] = copy(typeahead = Some(label))
  def noTypeahead(): VirtualList[A, K]                     = copy(typeahead = None)

  override def render: RxElement = ListCore[A, K](
    items,
    key,
    rowHeight,
    overscan,
    containerClass = "uni-virtual-list",
    containerAttrs = Seq(role -> "listbox", label.map(ariaLabel -> _).getOrElse(DomNode.empty)),
    containerStyle = "",
    spacerAttrs = Seq(role -> "presentation"),
    rowRole = "option",
    rowAttrs =
      (_, i, count) =>
        Seq(ariaPosinset -> i.map(n => (n + 1).toString), ariaSetsize -> count.map(_.toString)),
    renderRow = renderRow,
    selection = multiSelected.fold(ListSelection.Single(selected))(ListSelection.Multi(_)),
    onSelect = onSelectFn,
    typeaheadLabel = typeahead,
    extraKey = (_, _, _) => false,
    header = DomNode.empty,
    changeHandler = changeHandler,
    slot = "virtual-list"
  )

end VirtualList

object VirtualList:
  // `new` is required in this file: the `apply` below replaces the constructor proxy
  private[ui] val defaultOverscan = 8

  def apply[A, K](items: Rx[Seq[A]], rowHeight: Int)(
      key: A => K
  )(render: A => DomNode): VirtualList[A, K] =
    new VirtualList(
      items,
      rowHeight,
      key,
      render,
      label = None,
      selected = Rx.variable(None),
      onSelectFn = _ => (),
      overscan = defaultOverscan,
      typeahead = None,
      changeHandler = ChangeGate.noHandler,
      multiSelected = None
    )
