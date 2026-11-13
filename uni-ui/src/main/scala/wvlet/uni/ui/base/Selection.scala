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
package wvlet.uni.ui.base

import org.scalajs.dom
import wvlet.uni.rx.{Rx, RxVar}
import wvlet.uni.ui.{ChangeCause, ChangeGate, ChangeHandler, ChangeReason}

/**
  * Multiple selection over an ordered collection of keys, as in a file list: a press selects one
  * item, Ctrl or Cmd adds or removes one, and Shift selects the range from the last pressed item.
  *
  * The selected keys live in the app's `selected` variable. Every change goes through [[onChange]],
  * which can cancel it, with the reason and source of the press:
  *
  * {{{
  *   val selected  = Rx.variable(Set.empty[String])
  *   val selection = Selection(selected).onChange(d => println(d))
  *   files.map { f =>
  *     li(
  *       aria.selected -> selection.isSelected(f.name).map(_.toString),
  *       onclick       -> { (e: dom.MouseEvent) => selection.press(f.name, files.map(_.name), e) },
  *       f.name
  *     )
  *   }
  * }}}
  */
class Selection[K](val selected: RxVar[Set[K]]):
  private var handler: ChangeHandler = _ => ()
  private var anchorKey: Option[K]   = None

  /** Sees every change before it lands; `cancel()` keeps the previous selection. */
  def onChange(f: ChangeHandler): this.type =
    handler = f
    this

  /** The item a Shift range starts from: the last item selected or toggled on its own. */
  def anchor: Option[K] = anchorKey

  /**
    * Moves the anchor without changing the selection, as a list does when Shift+Arrow starts a
    * range from the row it was on.
    */
  def setAnchor(k: Option[K]): Unit = anchorKey = k

  def isSelected(k: K): Rx[Boolean] = selected.map(_.contains(k))

  /** Selects only `k`. */
  def select(k: K, cause: ChangeCause = ChangeCause.current): Boolean = write(
    Set(k),
    cause,
    Some(k)
  )

  /** Adds `k` to the selection, or removes it when already selected. */
  def toggle(k: K, cause: ChangeCause = ChangeCause.current): Boolean =
    val s = selected.get
    write(
      if s.contains(k) then
        s - k
      else
        s + k
      ,
      cause,
      Some(k)
    )

  /**
    * Selects the items between the anchor and `k` in `order`, both included, replacing the
    * selection. The anchor stays, so the next Shift press extends from the same place. With no
    * anchor it selects `k` alone.
    */
  def extendTo(k: K, order: Seq[K], cause: ChangeCause = ChangeCause.current): Boolean =
    anchorKey.filter(order.contains) match
      case Some(a) =>
        write(Selection.range(order, a, k), cause, Some(a))
      case None =>
        select(k, cause)

  def selectAll(order: Seq[K], cause: ChangeCause = ChangeCause.current): Boolean = write(
    order.toSet,
    cause,
    anchorKey
  )

  def clear(cause: ChangeCause = ChangeCause.current): Boolean = write(Set.empty, cause, None)

  /**
    * A click or a key press (Space or Enter) on item `k`: Shift extends the range, Ctrl or Cmd
    * toggles, and a plain press selects `k` alone.
    */
  def press(k: K, order: Seq[K], e: dom.MouseEvent | dom.KeyboardEvent): Boolean =
    val cause = ChangeCause.of(ChangeReason.ItemPress, e)
    // MouseEvent and KeyboardEvent both carry the modifier keys
    val m = e.asInstanceOf[dom.ModifierKeyEvent]
    if m.shiftKey then
      extendTo(k, order, cause)
    else if m.ctrlKey || m.metaKey then
      toggle(k, cause)
    else
      select(k, cause)

  /**
    * Ctrl+A or Cmd+A selects everything, and Escape clears the selection. Returns true when it
    * handled the key, so the caller can call `preventDefault()`. An Escape that [[onChange]]
    * cancels returns false, so it can still close an enclosing dialog.
    */
  def onKey(e: dom.KeyboardEvent, order: Seq[K]): Boolean =
    if (e.ctrlKey || e.metaKey) && e.key.toLowerCase == "a" then
      selectAll(order, ChangeCause.of(ChangeReason.NavigationKey, e))
      true
    else if e.key == "Escape" && selected.get.nonEmpty then
      clear(ChangeCause.of(ChangeReason.EscapeKey, e))
    else
      false

  private def write(next: Set[K], cause: ChangeCause, newAnchor: Option[K]): Boolean =
    val accepted = ChangeGate.write(selected, "selected", next, cause, handler)
    if accepted then
      anchorKey = newAnchor
    accepted

end Selection

object Selection:
  /** The keys from `from` to `to` in `order`, both included, in either direction. */
  def range[K](order: Seq[K], from: K, to: K): Set[K] =
    val i = order.indexOf(from)
    val j = order.indexOf(to)
    if i < 0 || j < 0 then
      Set(to).filter(order.contains)
    else
      order.slice(math.min(i, j), math.max(i, j) + 1).toSet

end Selection
