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
import wvlet.uni.rx.Cancelable

import scala.scalajs.js

/**
  * Keeps keyboard focus in a list when the focused item goes away, as when a tab or a row is
  * deleted. Without it the browser drops focus to the page, and a keyboard user must find their way
  * back. Focus moves to the item that took the removed one's place, else to the new last item, else
  * to the container.
  *
  * {{{
  *   ul(tabindex -> "-1", FocusRecovery.within("li"), items.map(i => li(tabindex -> "0", i)))
  * }}}
  */
object FocusRecovery:

  /** The key to focus after `removed` leaves `keys`: the next one, else the previous one. */
  def neighbor[K](keys: Seq[K], removed: K): Option[K] =
    val i = keys.indexOf(removed)
    if i < 0 then
      None
    else
      keys.lift(i + 1).orElse(keys.lift(i - 1))

  /**
    * Watches the element this modifier is on. When the focused element inside an item matching
    * `itemSelector` is removed, focuses the item now at the same position, else the last item, else
    * the container (give it `tabindex="-1"`). Focus that has already moved elsewhere is left alone.
    */
  def within(itemSelector: String): DomNode = OnMount(container => watch(container, itemSelector))

  /** The DOM binding behind [[within]], for code that already holds the container. */
  def watch(container: dom.HTMLElement, itemSelector: String): Cancelable =
    // The focused item and its position, remembered while focus is inside
    var focused: Option[(dom.Element, Int)] = None

    def items: Seq[dom.HTMLElement] = container
      .querySelectorAll(itemSelector)
      .toSeq
      .collect { case h: dom.HTMLElement =>
        h
      }

    val onFocusIn: js.Function1[dom.Event, Unit] =
      e =>
        e.target match
          case t: dom.Element =>
            focused = Option(t.closest(itemSelector))
              .filter(container.contains)
              .map(item => (item, items.indexWhere(_ eq item)))
          case _ =>

    // Forget the item once focus leaves the list while the item stays, e.g. a click elsewhere
    val onFocusOut: js.Function1[dom.Event, Unit] =
      _ =>
        dom
          .window
          .setTimeout(
            () =>
              focused.foreach { case (item, _) =>
                val active = Option(dom.document.activeElement)
                if container.contains(item) && !active.exists(container.contains) then
                  focused = None
              },
            0
          )

    def itemIn(n: dom.Node, last: Boolean): Option[dom.HTMLElement] =
      n match
        case e: dom.HTMLElement if e.matches(itemSelector) =>
          Some(e)
        case e: dom.Element =>
          val inner = e
            .querySelectorAll(itemSelector)
            .toSeq
            .collect { case h: dom.HTMLElement =>
              h
            }
          if last then
            inner.lastOption
          else
            inner.headOption
        case _ =>
          None

    val observer = dom.MutationObserver { (records, _) =>
      focused.foreach { case (item, index) =>
        if container.contains(item) then
          // Keep the position current as items come and go around it
          focused = Some((item, items.indexWhere(_ eq item)))
        else
          focused = None
          // Focus is gone only when it fell back to the page; leave a focus the app moved
          val lost = Option(dom.document.activeElement).forall { a =>
            (a eq dom.document.body) || !dom.document.contains(a)
          }
          if lost then
            // The item now next to where the removed one was, else the same position
            val beside = records
              .find(_.removedNodes.exists(n => (n eq item) || n.contains(item)))
              .flatMap { r =>
                Option(r.nextSibling)
                  .flatMap(itemIn(_, last = false))
                  .orElse(Option(r.previousSibling).flatMap(itemIn(_, last = true)))
              }
              .filter(container.contains)
            val rest = items
            beside.orElse(rest.lift(index)).orElse(rest.lastOption).getOrElse(container).focus()
      }
    }
    container.addEventListener("focusin", onFocusIn)
    container.addEventListener("focusout", onFocusOut)
    observer.observe(
      container,
      new dom.MutationObserverInit:
        childList = true
        subtree = true
    )
    Cancelable { () =>
      observer.disconnect()
      container.removeEventListener("focusin", onFocusIn)
      container.removeEventListener("focusout", onFocusOut)
    }

  end watch

end FocusRecovery
