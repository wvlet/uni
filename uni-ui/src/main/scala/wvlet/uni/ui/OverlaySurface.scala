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
import wvlet.uni.dom.RxElement
import wvlet.uni.rx.Cancelable
import wvlet.uni.ui.base.*

import scala.scalajs.js

/**
  * The open state of one overlay: renders `content`, and once it is in the document places it next
  * to the anchor, joins the [[Dismiss]] stack, and moves focus into it. When `exit.open` turns
  * false it closes at once (leaves the stack and returns focus), then plays its [[ExitAnimation]]
  * and calls `exit.done` so [[Presence]] unmounts it. Each overlay component renders one inside
  * `Portal.toBody` through `Presence`.
  *
  * @param anchor
  *   the rectangle to place the overlay next to; None leaves placement to CSS (dialogs, toasts)
  * @param inside
  *   the elements that count as inside the overlay for dismissal, given its root element: usually
  *   the root and the trigger
  * @param initialFocus
  *   the element to focus once the overlay is in the document; None keeps focus where it is
  * @param stacked
  *   whether the overlay joins the [[Dismiss]] stack; a tooltip doesn't, so Escape and outside
  *   presses keep going to the menu or dialog under it
  */
private[ui] class OverlaySurface(
    exit: Presence.Exit,
    content: RxElement,
    anchor: () => Option[Rect],
    inside: dom.HTMLElement => Seq[dom.Element],
    side: Side,
    align: Align,
    offset: Double,
    modal: Boolean,
    dismissOnOutside: Boolean,
    onDismiss: () => Unit,
    initialFocus: dom.HTMLElement => Option[dom.HTMLElement],
    returnFocus: Boolean,
    stacked: Boolean = true
) extends RxElement:
  private var root: Option[dom.HTMLElement]    = None
  private var cleanup: Cancelable              = Cancelable.empty
  private var focusReturn: Option[FocusReturn] = None
  private var unmounted                        = false
  private var closing                          = false
  private var watch: Cancelable                = Cancelable.empty
  private var exiting: Cancelable              = Cancelable.empty

  // Runs as the overlay opens, while focus is still on the trigger
  override def beforeRender: Unit =
    if returnFocus then
      focusReturn = Some(FocusReturn())
    watch.cancel
    watch = exit
      .open
      .subscribe { o =>
        if !o then
          close()
      }

  // Closes at once, so focus and Escape move on while the exit animation still plays
  private def close(): Unit =
    if !closing && !unmounted then
      closing = true
      cleanup.cancel
      cleanup = Cancelable.empty
      focusReturn.foreach(_.restore(root))
      focusReturn = None
      root match
        case Some(el) =>
          exiting = ExitAnimation.run(el)(exit.done)
        case None =>
          // Closed before it reached the document: nothing to animate
          exit.done()

  override def render: RxElement = content

  private def reposition(): Unit =
    for
      el <- root
      a  <- anchor()
    do
      Anchored.applyTo(el, a, side, align, offset)

  override def onMount(node: Any): Unit =
    node match
      // onMount fires a step after rendering: skip it when the overlay already closed
      case el: dom.HTMLElement if !unmounted && !closing && dom.document.contains(el) =>
        root = Some(el)
        reposition()
        val layer =
          if stacked then
            Dismiss.push(DismissLayer(() => inside(el), modal, dismissOnOutside, onDismiss))
          else
            Cancelable.empty
        val placement =
          if anchor().isDefined then
            val onMove: js.Function1[dom.Event, Unit] = _ => reposition()
            dom.window.addEventListener("resize", onMove)
            // Capture, so scrolling any container (not only the page) moves the overlay along
            dom.document.addEventListener("scroll", onMove, useCapture = true)
            // Content that grows or shrinks (a filtered list) is placed again
            val resize = dom.ResizeObserver((_, _) => reposition())
            resize.observe(el)
            Cancelable { () =>
              dom.window.removeEventListener("resize", onMove)
              dom.document.removeEventListener("scroll", onMove, useCapture = true)
              resize.disconnect()
            }
          else
            Cancelable.empty
        cleanup = Cancelable.merge(layer, placement)
        initialFocus(el).foreach(_.focus())
      case _ =>

  override def beforeUnmount: Unit =
    unmounted = true
    watch.cancel
    exiting.cancel
    cleanup.cancel
    cleanup = Cancelable.empty
    focusReturn.foreach(_.restore(root))
    root = None

end OverlaySurface
