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
import wvlet.uni.dom.Keyboard
import wvlet.uni.rx.Cancelable
import wvlet.uni.ui.{ChangeCause, ChangeReason, ChangeSource}

import scala.collection.mutable.ArrayBuffer
import scala.scalajs.js

/**
  * One open overlay, as [[Dismiss]] sees it.
  *
  * @param inside
  *   the elements that count as inside the overlay: its content, and usually its trigger, so that
  *   clicking the trigger toggles the overlay instead of dismissing and reopening it
  * @param modal
  *   true for a dialog: a click outside it never reaches the overlays below
  * @param dismissOnOutside
  *   whether a pointer press outside the overlay dismisses it
  * @param onDismiss
  *   closes the overlay, for example `() => open := false`
  */
class DismissLayer(
    val inside: () => Seq[dom.Element],
    val modal: Boolean,
    val dismissOnOutside: Boolean,
    val onDismiss: () => Unit
):
  def contains(target: dom.Node): Boolean = inside().exists(_.contains(target))

/**
  * Dismisses overlays on Escape or a pointer press outside them. Open overlays form a stack, so
  * nested ones (a menu inside a dialog) close from the top:
  *
  *   - Escape dismisses only the topmost overlay.
  *   - A press outside dismisses each non-modal overlay from the top until one contains the target.
  *     A modal overlay (a dialog) stops the walk, so a click on its backdrop never closes what lies
  *     beneath it.
  *
  * {{{
  *   val layer = Dismiss.push(DismissLayer(() => Seq(content, trigger), modal = false,
  *     dismissOnOutside = true, () => open := false))
  *   // when the overlay closes:
  *   layer.cancel
  * }}}
  */
object Dismiss:
  private val layers = ArrayBuffer.empty[DismissLayer]

  private var listeners: Option[Cancelable] = None

  /** Add an open overlay on top of the stack. Cancel the result when the overlay closes. */
  def push(layer: DismissLayer): Cancelable =
    layers += layer
    if listeners.isEmpty then
      listeners = Some(install())
    Cancelable { () =>
      layers -= layer
      if layers.isEmpty then
        listeners.foreach(_.cancel)
        listeners = None
    }

  /** The topmost open overlay. */
  def top: Option[DismissLayer] = layers.lastOption

  /** True when `layer` is the topmost open overlay. */
  def isTop(layer: DismissLayer): Boolean = layers.lastOption.contains(layer)

  private def onKeyDown(e: dom.KeyboardEvent): Unit =
    if e.key == "Escape" && !e.defaultPrevented && !Keyboard.isComposing(e) then
      top.foreach { layer =>
        e.preventDefault()
        ChangeCause.during(ChangeCause(ChangeReason.EscapeKey, ChangeSource.Keyboard))(
          layer.onDismiss()
        )
      }

  private def onPointerDown(e: dom.Event): Unit =
    e.target match
      case target: dom.Node =>
        // Walk a snapshot from the top: dismissing changes the stack
        val it    = layers.reverseIterator.toList.iterator
        var going = true
        while going && it.hasNext do
          val layer = it.next()
          if layer.contains(target) then
            going = false
          else
            if layer.dismissOnOutside then
              ChangeCause.during(ChangeCause(ChangeReason.OutsidePress, ChangeSource.Pointer))(
                layer.onDismiss()
              )
            if layer.modal then
              going = false
      case _ =>

  private def install(): Cancelable =
    val key: js.Function1[dom.KeyboardEvent, Unit] = e => onKeyDown(e)
    val press: js.Function1[dom.Event, Unit]       = e => onPointerDown(e)
    dom.document.addEventListener("keydown", key)
    // Capture, so an app's stopPropagation inside the page doesn't hide the press
    dom.document.addEventListener("pointerdown", press, useCapture = true)
    Cancelable { () =>
      dom.document.removeEventListener("keydown", key)
      dom.document.removeEventListener("pointerdown", press, useCapture = true)
    }

end Dismiss
