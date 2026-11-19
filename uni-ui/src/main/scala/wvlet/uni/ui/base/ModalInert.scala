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

import wvlet.uni.dom.AnyRealm
import org.scalajs.dom
import wvlet.uni.rx.Cancelable

import scala.collection.mutable.ArrayBuffer

/**
  * Makes the page behind the topmost modal overlay `inert`, so pointers, keyboard, screen readers,
  * and [[wvlet.uni.ui.agent.UiAgent]] can't reach it. The `<body>` children before the one that
  * holds the modal become inert; overlays opened later (a popover inside the dialog) are appended
  * after it and stay live, as does anything marked with [[ModalInert.keepLive]], such as the toast
  * region.
  *
  * A modal rendered in place (an inline dialog) blocks only a region, the element its backdrop
  * covers: everything in the region outside the modal becomes inert, and the rest of the page stays
  * live.
  *
  * Only elements this object made inert are restored, so an app's own `inert` stays.
  */
private[ui] object ModalInert:
  /**
    * Keeps an element (and the `<body>` child holding it) live under a modal:
    * `data("uni-keep-live")`.
    */
  val keepLive = "data-uni-keep-live"

  // An open modal, and the region it blocks (None for the page)
  private class Modal(val root: dom.Element, val region: Option[dom.Element])

  private val modals = ArrayBuffer.empty[Modal]
  private val marked = ArrayBuffer.empty[dom.Element]

  /** Make the page behind `root` inert until the result is canceled. */
  def push(root: dom.Element): Cancelable = add(Modal(root, None))

  /** Make the content of `region` outside `root` inert until the result is canceled. */
  def pushWithin(root: dom.Element, region: dom.Element): Cancelable = add(
    Modal(root, Some(region))
  )

  private def add(m: Modal): Cancelable =
    modals += m
    refresh()
    Cancelable { () =>
      modals -= m
      refresh()
    }

  private def mark(c: dom.Element): Unit =
    val live = c.hasAttribute(keepLive) || c.querySelector(s"[${keepLive}]") != null
    if !live && !c.hasAttribute("inert") then
      c.setAttribute("inert", "")
      marked += c

  private def refresh(): Unit =
    marked.foreach(_.removeAttribute("inert"))
    marked.clear()
    // The topmost page modal blocks the page; each in-place modal blocks its own region
    modals
      .filter(_.region.isEmpty)
      .lastOption
      .flatMap(m => bodyChildOf(m.root))
      .foreach { holder =>
        dom.document.body.children.toSeq.takeWhile(_ ne holder).foreach(mark)
      }
    modals.foreach { m =>
      m.region
        .foreach { region =>
          // Every sibling along the path from the modal up to the region
          var cur: dom.Element = m.root
          while cur != null && !(cur eq region) && region.contains(cur) do
            cur =
              cur.parentNode match
                case AnyRealm.Element(parent) =>
                  parent.children.toSeq.filter(_ ne cur).foreach(mark)
                  parent
                case _ =>
                  null
        }
    }

  // The child of <body> that holds `el`
  private def bodyChildOf(el: dom.Element): Option[dom.Element] =
    val body          = dom.document.body
    var cur: dom.Node = el
    while cur != null && cur.parentNode != null && !(cur.parentNode eq body) do
      cur = cur.parentNode
    cur match
      case AnyRealm.Element(e) if e.parentNode eq body =>
        Some(e)
      case _ =>
        None

end ModalInert
