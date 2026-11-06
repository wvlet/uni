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

import scala.language.implicitConversions

/**
  * An expandable tree over the app's own data, such as files and folders. `children` gives each
  * item's children (an empty `Seq` for a leaf); which rows are open and which one is selected live
  * in `RxVar`s the app can save or set.
  *
  * {{{
  *   val expanded = Rx.variable(Set("src"))
  *   Tree(Rx.const(roots), (f: FileNode) => f.children)(_.path)(f => span(f.name))
  *     .withLabel("Files")
  *     .withExpanded(expanded)
  *     .onSelect(f => openFile(f))
  * }}}
  *
  * Keyboard (WAI-ARIA treeview): Up and Down move, Right opens a closed row or goes to its first
  * child, Left closes an open row or goes to its parent, Home and End jump, typing a letter moves
  * to the next matching row, Enter or a click selects. A click on the chevron or a double click
  * opens and closes a row.
  *
  * The visible rows render through the same windowing as [[VirtualList]], so every row is
  * `rowHeight` pixels (28 by default) and a tree with thousands of open rows stays fast. The
  * container is `div.uni-list.uni-tree` with `role="tree"`; rows are `div.uni-list__row` with
  * `role="treeitem"`, `aria-level`, `aria-expanded`, and `--uni-tree-level` for the indent.
  */
class Tree[A, K] private (
    roots: Rx[Seq[A]],
    children: A => Seq[A],
    key: A => K,
    renderRow: A => DomNode,
    label: Option[String],
    expanded: RxVar[Set[K]],
    selected: RxVar[Option[K]],
    onSelectFn: A => Unit,
    rowHeight: Int,
    typeahead: Option[A => String]
) extends RxElement:

  private def copy(
      label: Option[String] = label,
      expanded: RxVar[Set[K]] = expanded,
      selected: RxVar[Option[K]] = selected,
      onSelectFn: A => Unit = onSelectFn,
      rowHeight: Int = rowHeight,
      typeahead: Option[A => String] = typeahead
  ): Tree[A, K] =
    new Tree(
      roots,
      children,
      key,
      renderRow,
      label,
      expanded,
      selected,
      onSelectFn,
      rowHeight,
      typeahead
    )

  /** The accessible name of the tree. */
  def withLabel(l: String): Tree[A, K] = copy(label = Some(l))
  def noLabel(): Tree[A, K]            = copy(label = None)

  /** The keys of the open rows. */
  def withExpanded(e: RxVar[Set[K]]): Tree[A, K] = copy(expanded = e)

  /** The key of the selected row, set by Enter or a click. */
  def withSelected(s: RxVar[Option[K]]): Tree[A, K] = copy(selected = s)

  /** Runs when Enter or a click selects a row. */
  def onSelect(f: A => Unit): Tree[A, K] = copy(onSelectFn = f)

  /** The height of every row in pixels (28 by default). */
  def withRowHeight(h: Int): Tree[A, K] = copy(rowHeight = h)

  /** Typing a letter moves to the next row whose label starts with it. */
  def withTypeahead(label: A => String): Tree[A, K] = copy(typeahead = Some(label))
  def noTypeahead(): Tree[A, K]                     = copy(typeahead = None)

  private def toggle(k: K): Unit = expanded.update { e =>
    if e.contains(k) then
      e - k
    else
      e + k
  }

  private val rows: Rx[Seq[TreeRow[A, K]]] = Rx
    .join(roots, expanded)
    .map { (rs, e) =>
      TreeModel.flatten(rs, children, key, e)
    }

  // The latest rows, for Left and Right; the list keeps them in step
  private var current: Seq[TreeRow[A, K]] = Seq.empty

  private def onKey(k: String, i: Int, moveTo: Int => Unit): Boolean =
    k match
      case "ArrowLeft" | "ArrowRight" =>
        TreeModel.onKey(k, current, i, pageSize = 1) match
          case Some(TreeAction.Expand(key)) =>
            expanded.update(_ + key)
            true
          case Some(TreeAction.Collapse(key)) =>
            expanded.update(_ - key)
            true
          case Some(TreeAction.Move(n)) =>
            moveTo(n)
            true
          case None =>
            // Handled anyway, so the container doesn't scroll sideways
            true
      case _ =>
        false

  private def chevron(r: TreeRow[A, K]): RxElement =
    if r.expandable then
      span(
        cls        -> "uni-tree__chevron",
        ariaHidden -> "true",
        // Toggle without selecting the row
        EventListenerBinding(
          "click",
          e =>
            e.stopPropagation()
            toggle(r.key)
        )
      )
    else
      span(cls -> "uni-tree__chevron uni-tree__chevron--leaf", ariaHidden -> "true")

  override def render: RxElement = ListCore[TreeRow[A, K], K](
    rows.map { rs =>
      current = rs
      rs
    },
    _.key,
    rowHeight,
    VirtualList.defaultOverscan,
    containerClass = "uni-tree",
    containerAttrs = Seq(role -> "tree", label.map(ariaLabel -> _).getOrElse(DomNode.empty)),
    containerStyle = "",
    spacerAttrs = Seq(role -> "presentation"),
    rowRole = "treeitem",
    rowAttrs =
      (r, _) =>
        Seq[DomNode](
          ariaLevel    -> r.level.toString,
          ariaPosinset -> r.position.toString,
          ariaSetsize  -> r.siblings.toString,
          styleAttr    -> s"--uni-tree-level: ${r.level - 1}",
          if r.expandable then
            Seq[DomNode](
              ariaExpanded -> r.expanded.toString,
              EventListenerBinding("dblclick", _ => toggle(r.key))
            ): DomNode
          else
            DomNode.empty
        ),
    renderRow = r => Seq[DomNode](chevron(r), span(cls -> "uni-tree__label", renderRow(r.item))),
    selected = selected,
    onSelect = r => onSelectFn(r.item),
    typeaheadLabel = typeahead.map(f => (r: TreeRow[A, K]) => f(r.item)),
    extraKey = onKey,
    header = DomNode.empty
  )

end Tree

object Tree:
  // `new` is required in this file: the `apply` below replaces the constructor proxy
  def apply[A, K](roots: Rx[Seq[A]], children: A => Seq[A])(key: A => K)(
      render: A => DomNode
  ): Tree[A, K] =
    new Tree(
      roots,
      children,
      key,
      render,
      label = None,
      expanded = Rx.variable(Set.empty),
      selected = Rx.variable(None),
      onSelectFn = _ => (),
      rowHeight = 28,
      typeahead = None
    )
