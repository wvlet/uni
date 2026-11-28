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
import wvlet.uni.dom.AnyRealm
import wvlet.uni.dom.all.*
import wvlet.uni.dom.all.given

import scala.language.implicitConversions
import scala.scalajs.js

/**
  * Content that scrolls sideways without a native scrollbar. Where the browser draws scrollbars in
  * a strip of their own, that strip would add room below the content, as under the last row of a
  * [[DataTable]]. Instead, a thin thumb shows over the bottom edge while the pointer is on the
  * area, and dragging it scrolls. Trackpads, Shift with the wheel, and keyboard focus scroll it as
  * usual.
  *
  * {{{
  *   ScrollArea(DataTable(rows, key = _.id, columns = cols)(styleAttr += "width: max-content"))
  * }}}
  *
  * It renders `div.uni-scroll-area`, which is as wide as its content up to its container, holding
  * the scrolled `div.uni-scroll-area__view` and the `div.uni-scroll-area__thumb`. The thumb is
  * hidden from assistive technology, since the view scrolls by the usual means.
  */
class ScrollArea(content: DomNode) extends RxElement:
  private var root: Option[dom.HTMLElement]      = None
  private var resize: Option[dom.ResizeObserver] = None
  private var cleanup: () => Unit                = () => ()

  private def parts: Option[(dom.HTMLElement, dom.HTMLElement)] =
    for
      r      <- root
      view   <- Option(r.querySelector(":scope > .uni-scroll-area__view"))
      thumb  <- Option(r.querySelector(":scope > .uni-scroll-area__bar > .uni-scroll-area__thumb"))
      (v, t) <-
        (view, thumb) match
          case (AnyRealm.HTMLElement(v), AnyRealm.HTMLElement(t)) =>
            Some((v, t))
          case _ =>
            None
    yield (v, t)

  // Size and place the thumb to match the part of the content in view
  private def update(): Unit = parts.foreach { (view, thumb) =>
    ScrollArea.thumb(view.clientWidth, view.scrollWidth, view.scrollLeft) match
      case None =>
        thumb.style.display = "none"
      case Some((width, x)) =>
        thumb.style.display = "block"
        thumb.style.width = s"${width}px"
        thumb.style.transform = s"translateX(${x}px)"
  }

  // Drag the thumb: content pixels per thumb pixel, also while the thumb is at its minimum width
  private def drag(view: dom.HTMLElement, thumb: dom.HTMLElement, e: dom.PointerEvent): Unit =
    e.preventDefault()
    val startX    = e.clientX
    val startLeft = view.scrollLeft
    val ratio     = (view.scrollWidth - view.clientWidth).toDouble /
      math.max(1.0, view.clientWidth - thumb.getBoundingClientRect().width)
    thumb.setPointerCapture(e.pointerId)
    root.foreach(_.setAttribute("data-dragging", "true"))
    lazy val move: js.Function1[dom.PointerEvent, Unit] =
      m => view.scrollLeft = startLeft + (m.clientX - startX) * ratio
    lazy val up: js.Function1[dom.PointerEvent, Unit] =
      _ =>
        root.foreach(_.removeAttribute("data-dragging"))
        thumb.removeEventListener("pointermove", move)
        thumb.removeEventListener("pointerup", up)
        thumb.removeEventListener("pointercancel", up)
    thumb.addEventListener("pointermove", move)
    thumb.addEventListener("pointerup", up)
    thumb.addEventListener("pointercancel", up)

  override def onMount(node: Any): Unit =
    node match
      case AnyRealm.HTMLElement(el) =>
        root = Some(el)
        parts.foreach { (view, thumb) =>
          val onScroll: js.Function1[dom.Event, Unit]      = _ => update()
          val onEnter: js.Function1[dom.Event, Unit]       = _ => update()
          val onDown: js.Function1[dom.PointerEvent, Unit] = e => drag(view, thumb, e)
          view.addEventListener("scroll", onScroll)
          el.addEventListener("pointerenter", onEnter)
          thumb.addEventListener("pointerdown", onDown)
          // The view or its content changing size (columns, a window resize) moves the thumb
          val ro = AnyRealm.resizeObserver(view, (_, _) => update())
          ro.observe(view)
          Option(view.firstElementChild).foreach(ro.observe)
          resize = Some(ro)
          cleanup =
            () =>
              view.removeEventListener("scroll", onScroll)
              el.removeEventListener("pointerenter", onEnter)
              thumb.removeEventListener("pointerdown", onDown)
          update()
        }
      case _ =>

  override def beforeUnmount: Unit =
    cleanup()
    cleanup = () => ()
    resize.foreach(_.disconnect())
    resize = None
    root = None

  override def render: RxElement = div(
    cls          -> "uni-scroll-area",
    data("slot") -> "scroll-area",
    div(cls -> "uni-scroll-area__view", data("slot") -> "scroll-area-view", content),
    div(
      cls        -> "uni-scroll-area__bar",
      ariaHidden -> "true",
      div(cls -> "uni-scroll-area__thumb", data("slot") -> "scroll-area-thumb")
    )
  )

end ScrollArea

object ScrollArea:
  /** The narrowest thumb, in pixels, so a very wide content still has one to grab. */
  val minThumbWidth: Double = 24.0

  /**
    * The thumb's width and offset for a view `client` pixels wide over content `total` pixels wide,
    * scrolled to `left`; None when the content fits.
    */
  def thumb(client: Double, total: Double, left: Double): Option[(Double, Double)] =
    if total <= client + 1 then
      None
    else
      val width = math.max(minThumbWidth, client * client / total)
      val x     = (client - width) * math.min(1.0, math.max(0.0, left / (total - client)))
      Some((width, x))

end ScrollArea
