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
import wvlet.uni.dom.{DomNode, OnMount}
import wvlet.uni.rx.{Cancelable, Rx}

import scala.scalajs.js

/**
  * Keeps a scrolling log, such as a chat thread or a terminal, scrolled to its newest content while
  * the reader is at the bottom. Scrolling up to read pauses following, so new content no longer
  * moves the page; scrolling back to the bottom (or [[scrollToBottom]]) resumes it.
  *
  * {{{
  *   val stick = StickToBottom()
  *   div(cls -> "overflow-y-auto", stick.binding, messages.map(...))
  *   button(hidden -> stick.atBottom, onclick -> { () => stick.scrollToBottom(smooth = true) }, "Latest")
  * }}}
  *
  * @param threshold
  *   how close to the bottom, in pixels, still counts as at the bottom
  */
class StickToBottom(threshold: Double = 48):
  private val following                 = Rx.variable(true)
  private var host: Option[dom.Element] = None

  /** True while following new content: the reader is at (or near) the bottom. */
  def atBottom: Rx[Boolean] = following

  /** The current value of [[atBottom]]. */
  def isAtBottom: Boolean = following.get

  /** The modifier to put on the scrolling element. */
  def binding: DomNode = OnMount(attach)

  /** Scrolls to the newest content and follows it again. */
  def scrollToBottom(smooth: Boolean = false): Unit =
    following := true
    host.foreach(StickToBottom.scrollDown(_, smooth))

  /** The DOM binding behind [[binding]], for code that already holds the scrolling element. */
  def attach(el: dom.HTMLElement): Cancelable =
    host = Some(el)
    var lastTop = el.scrollTop

    val onScroll: js.Function1[dom.Event, Unit] =
      _ =>
        val top = el.scrollTop
        if StickToBottom.isNear(top, el.scrollHeight, el.clientHeight, threshold) then
          following := true
        else if top < lastTop then
          // The reader scrolled up. A scroll down (as a smooth scroll in progress) keeps following.
          following := false
        lastTop = top

    def pin(): Unit =
      val top = el.scrollTop
      // The reader scrolled up, and its scroll event has not arrived yet: don't pull them back
      if top < lastTop && !StickToBottom.isNear(top, el.scrollHeight, el.clientHeight, threshold)
      then
        following := false
      if following.get then
        StickToBottom.scrollDown(el, smooth = false)
        lastTop = el.scrollTop

    // New content changes the children; a child can also grow by itself (an image loads, a
    // streamed reply re-renders), and the scroller can shrink (a composer grows under it)
    val resize = dom.ResizeObserver((_, _) => pin())
    resize.observe(el)
    el.children.foreach(resize.observe)
    val mutation = dom.MutationObserver { (records, _) =>
      // Watch the sizes of direct children only, and stop watching removed ones
      records
        .filter(_.target eq el)
        .foreach { r =>
          r.removedNodes
            .foreach {
              case c: dom.Element =>
                resize.unobserve(c)
              case _ =>
            }
          r.addedNodes
            .foreach {
              case c: dom.Element =>
                resize.observe(c)
              case _ =>
            }
        }
      pin()
    }
    mutation.observe(
      el,
      new dom.MutationObserverInit:
        childList = true
        subtree = true
        characterData = true
    )
    el.addEventListener("scroll", onScroll)
    Cancelable { () =>
      el.removeEventListener("scroll", onScroll)
      mutation.disconnect()
      resize.disconnect()
      if host.exists(_ eq el) then
        host = None
    }

  end attach

end StickToBottom

object StickToBottom:
  /** True when the bottom edge of the view is within `threshold` pixels of the content's end. */
  def isNear(
      scrollTop: Double,
      scrollHeight: Double,
      clientHeight: Double,
      threshold: Double
  ): Boolean = scrollHeight - scrollTop - clientHeight <= threshold

  private def scrollDown(el: dom.Element, smooth: Boolean): Unit =
    val behavior =
      if smooth then
        "smooth"
      else
        "auto"
    el.asInstanceOf[js.Dynamic]
      .scrollTo(js.Dynamic.literal(top = el.scrollHeight, behavior = behavior))

end StickToBottom
