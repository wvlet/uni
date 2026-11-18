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
import wvlet.uni.dom.{DomRef, RxElement}
import wvlet.uni.rx.Cancelable
import wvlet.uni.ui.base.*

import scala.scalajs.js

/**
  * The open state of one overlay: renders `content`, and once it is in the document places it next
  * to the anchor (following it each frame while it moves), joins the [[Dismiss]] stack, makes the
  * page behind a modal [[ModalInert]], and moves focus into it. When `exit.open` turns false it
  * closes at once (leaves the stack and returns focus), then plays its [[ExitAnimation]] and calls
  * `exit.done` so [[Presence]] unmounts it. Each overlay component renders one inside
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
  * @param inPlace
  *   rendered in place instead of at the end of `<body>`: a modal one blocks only the region its
  *   root covers (its offset parent), takes Escape only while focus is inside it, and lets outside
  *   presses go on to the overlays under it
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
    stacked: Boolean = true,
    inPlace: Boolean = false
) extends RxElement:
  private var root: Option[dom.HTMLElement]    = None
  private var cleanup: Cancelable              = Cancelable.empty
  private var focusReturn: Option[FocusReturn] = None
  private var unmounted                        = false
  private var closing                          = false
  private var watch: Cancelable                = Cancelable.empty
  private var exiting: Cancelable              = Cancelable.empty
  // Set while rendering, so an overlay closed before onMount can still play its exit
  private val rendered = DomRef[dom.HTMLElement]()

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
      // Before onMount (closed in the frame it opened), the rendered element plays the exit
      root.orElse(rendered.current.filter(dom.document.contains)) match
        case Some(el) =>
          exiting = ExitAnimation.run(el)(exit.done)
        case None =>
          // Never reached the document: nothing to animate
          exit.done()

  override def render: RxElement = content.add(rendered.binding)

  private def reposition(): Unit =
    for
      el <- root
      a  <- anchor()
    do
      Anchored.applyTo(el, a, side, align, offset)

  // Places the overlay again whenever the anchor or the viewport moves: on scroll, on resize, or
  // during a CSS transition or layout shift that fires no event. Checked once per frame while open
  private def follow(): Cancelable =
    var last                                  = (anchor(), Rect.viewport)
    var frame                                 = 0
    var stopped                               = false
    lazy val tick: js.Function1[Double, Unit] =
      _ =>
        if !stopped then
          val now = (anchor(), Rect.viewport)
          if now != last then
            last = now
            reposition()
          frame = dom.window.requestAnimationFrame(tick)
    frame = dom.window.requestAnimationFrame(tick)
    Cancelable { () =>
      stopped = true
      dom.window.cancelAnimationFrame(frame)
    }

  override def onMount(node: Any): Unit =
    node match
      // onMount fires a step after rendering: skip it when the overlay already closed
      case el: dom.HTMLElement if !unmounted && !closing && dom.document.contains(el) =>
        root = Some(el)
        reposition()
        val layer =
          if stacked then
            Dismiss.push(
              DismissLayer(
                () => inside(el),
                modal = modal && !inPlace,
                dismissOnOutside = dismissOnOutside,
                onDismiss = onDismiss,
                escapeFromInside = inPlace
              )
            )
          else
            Cancelable.empty
        val background =
          if modal && inPlace then
            // The positioned ancestor the backdrop covers
            val region =
              el.offsetParent match
                case p: dom.Element =>
                  p
                case _ =>
                  el.parentElement
            ModalInert.pushWithin(el, region)
          else if modal then
            ModalInert.push(el)
          else
            Cancelable.empty
        val placement =
          if anchor().isDefined then
            // Content that grows or shrinks (a filtered list) is placed again
            val resize = dom.ResizeObserver((_, _) => reposition())
            resize.observe(el)
            Cancelable.merge(follow(), Cancelable(() => resize.disconnect()))
          else
            Cancelable.empty
        cleanup = Cancelable.merge(Seq(layer, background, placement))
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
