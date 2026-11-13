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
  * opens and closes a row. With [[withMultiSelect]], Shift, Ctrl (⌘), Space, Ctrl+A, and Escape
  * select several rows, as in [[VirtualList]].
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
    multiSelected: Option[RxVar[Set[K]]],
    onSelectFn: A => Unit,
    rowHeight: Int,
    typeahead: Option[A => String],
    changeHandler: ChangeHandler
) extends RxElement:

  private def copy(
      label: Option[String] = label,
      expanded: RxVar[Set[K]] = expanded,
      selected: RxVar[Option[K]] = selected,
      multiSelected: Option[RxVar[Set[K]]] = multiSelected,
      onSelectFn: A => Unit = onSelectFn,
      rowHeight: Int = rowHeight,
      typeahead: Option[A => String] = typeahead,
      changeHandler: ChangeHandler = changeHandler
  ): Tree[A, K] =
    new Tree(
      roots,
      children,
      key,
      renderRow,
      label,
      expanded,
      selected,
      multiSelected,
      onSelectFn,
      rowHeight,
      typeahead,
      changeHandler
    )

  /** The accessible name of the tree. */
  def withLabel(l: String): Tree[A, K] = copy(label = Some(l))
  def noLabel(): Tree[A, K]            = copy(label = None)

  /** The keys of the open rows. */
  def withExpanded(e: RxVar[Set[K]]): Tree[A, K] = copy(expanded = e)

  /** The key of the selected row, set by Enter or a click. */
  def withSelected(s: RxVar[Option[K]]): Tree[A, K] = copy(selected = s)

  /**
    * Lets the user select several rows, whose keys live in `s`: Shift or Ctrl (⌘) with a click,
    * Shift with Up, Down, Home, End, PageUp, or PageDown, Space, Ctrl+A, and Escape change the set,
    * as in a file list. Ranges and Ctrl+A cover the visible rows only, and closing a row deselects
    * the rows it hides. The tree then leaves [[withSelected]]'s variable alone.
    */
  def withMultiSelect(s: RxVar[Set[K]]): Tree[A, K] = copy(multiSelected = Some(s))

  /** Selects one row at a time, in [[withSelected]]'s variable (the default). */
  def noMultiSelect(): Tree[A, K] = copy(multiSelected = None)

  /** Runs when Enter or a click selects a row; [[ChangeCause.current]] tells which. */
  def onSelect(f: A => Unit): Tree[A, K] = copy(onSelectFn = f)

  /**
    * Called before the selection changes or a row opens or closes; `cancel()` keeps the current
    * state. Keys: `selected` and `expanded`. Closing a row in a multiple selection reports the
    * descendants it deselects as a second `selected` change with the same cause.
    */
  def onChange(f: ChangeHandler): Tree[A, K] = copy(changeHandler = f)

  /** The height of every row in pixels (28 by default). */
  def withRowHeight(h: Int): Tree[A, K] = copy(rowHeight = h)

  /** Typing a letter moves to the next row whose label starts with it. */
  def withTypeahead(label: A => String): Tree[A, K] = copy(typeahead = Some(label))
  def noTypeahead(): Tree[A, K]                     = copy(typeahead = None)

  // Closing a row hides its descendants, so they leave a multiple selection: an action on the
  // selection should never reach rows the user can't see. They are found before the write, since
  // the rows follow `expanded` at once.
  private def setExpanded(e: Set[K], cause: ChangeCause): Unit =
    val hidden = hiddenBy(expanded.get -- e)
    if ChangeGate.write(expanded, "expanded", e, cause, changeHandler) then
      multiSelected.foreach { keys =>
        val sel = keys.get
        if sel.exists(hidden.contains) then
          ChangeGate.write(keys, "selected", sel -- hidden, cause, changeHandler)
      }

  // The visible descendants of the `closed` rows
  private def hiddenBy(closed: Set[K]): Set[K] =
    if closed.isEmpty || multiSelected.isEmpty then
      Set.empty
    else
      closed.flatMap { k =>
        val i = current.indexWhere(_.key == k)
        if i < 0 then
          Seq.empty
        else
          val level = current(i).level
          current.iterator.drop(i + 1).takeWhile(_.level > level).map(_.key).toSeq
      }

  private def toggle(k: K, cause: ChangeCause): Unit =
    val e = expanded.get
    setExpanded(
      if e.contains(k) then
        e - k
      else
        e + k
      ,
      cause
    )

  private val rows: Rx[Seq[TreeRow[A, K]]] = Rx
    .join(roots, expanded)
    .map { (rs, e) =>
      TreeModel.flatten(rs, children, key, e)
    }

  // The latest rows, for Left and Right; the list keeps them in step
  private var current: Seq[TreeRow[A, K]] = Seq.empty

  private def onKey(e: dom.KeyboardEvent, i: Int, moveTo: Int => Unit): Boolean =
    val cause = ChangeCause.of(ChangeReason.NavigationKey, e)
    e.key match
      case k @ ("ArrowLeft" | "ArrowRight") =>
        TreeModel.onKey(k, current, i, pageSize = 1, rtl = TextDirection.mirrors(e)) match
          case Some(TreeAction.Expand(key)) =>
            setExpanded(expanded.get + key, cause)
            true
          case Some(TreeAction.Collapse(key)) =>
            setExpanded(expanded.get - key, cause)
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
        cls          -> "uni-tree__chevron",
        data("slot") -> "tree-chevron",
        ariaHidden   -> "true",
        // Toggle without selecting the row
        EventListenerBinding(
          "click",
          e =>
            e.stopPropagation()
            toggle(r.key, ChangeCause.of(ChangeReason.Press, e))
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
      (r, _, _) =>
        Seq[DomNode](
          ariaLevel    -> r.level.toString,
          ariaPosinset -> r.position.toString,
          ariaSetsize  -> r.siblings.toString,
          styleAttr    -> s"--uni-tree-level: ${r.level - 1}",
          if r.expandable then
            Seq[DomNode](
              ariaExpanded -> r.expanded.toString,
              EventListenerBinding(
                "dblclick",
                e => toggle(r.key, ChangeCause.of(ChangeReason.Press, e))
              )
            ): DomNode
          else
            DomNode.empty
        ),
    renderRow =
      r =>
        Seq[DomNode](
          chevron(r),
          span(cls -> "uni-tree__label", data("slot") -> "tree-label", renderRow(r.item))
        ),
    selection = multiSelected.fold(ListSelection.Single(selected))(ListSelection.Multi(_)),
    onSelect = r => onSelectFn(r.item),
    typeaheadLabel = typeahead.map(f => (r: TreeRow[A, K]) => f(r.item)),
    extraKey = (e, i, moveTo) => onKey(e, i, moveTo),
    header = DomNode.empty,
    changeHandler = changeHandler,
    slot = "tree",
    agentKind = "tree"
  )

end Tree

object Tree:
  // `new` is required in this file: the `apply` below replaces the constructor proxy
  def apply[A, K](roots: Rx[Seq[A]], children: A => Seq[A])(
      key: A => K
  )(render: A => DomNode): Tree[A, K] =
    new Tree(
      roots,
      children,
      key,
      render,
      label = None,
      expanded = Rx.variable(Set.empty),
      selected = Rx.variable(None),
      multiSelected = None,
      onSelectFn = _ => (),
      rowHeight = 28,
      typeahead = None,
      changeHandler = ChangeGate.noHandler
    )
