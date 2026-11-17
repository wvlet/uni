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
import wvlet.uni.rx.Cancelable

import scala.collection.mutable.ArrayBuffer

/**
  * Makes the page behind the topmost modal overlay `inert`, so pointers, keyboard, screen readers,
  * and [[wvlet.uni.ui.agent.UiAgent]] can't reach it. The `<body>` children before the one that
  * holds the modal become inert; overlays opened later (a popover inside the dialog) are appended
  * after it and stay live, as does anything marked with [[ModalInert.keepLive]], such as the toast
  * region.
  *
  * Only elements this object made inert are restored, so an app's own `inert` stays.
  */
private[ui] object ModalInert:
  /**
    * Keeps an element (and the `<body>` child holding it) live under a modal:
    * `data("uni-keep-live")`.
    */
  val keepLive = "data-uni-keep-live"

  private val modals = ArrayBuffer.empty[dom.Element]
  private val marked = ArrayBuffer.empty[dom.Element]

  /** Make the page behind `root` inert until the result is canceled. */
  def push(root: dom.Element): Cancelable =
    modals += root
    refresh()
    Cancelable { () =>
      modals -= root
      refresh()
    }

  private def refresh(): Unit =
    marked.foreach(_.removeAttribute("inert"))
    marked.clear()
    modals
      .lastOption
      .flatMap(bodyChildOf)
      .foreach { holder =>
        val body = dom.document.body
        body
          .children
          .toSeq
          .takeWhile(_ ne holder)
          .foreach { c =>
            val live = c.hasAttribute(keepLive) || c.querySelector(s"[${keepLive}]") != null
            if !live && !c.hasAttribute("inert") then
              c.setAttribute("inert", "")
              marked += c
          }
      }

  // The child of <body> that holds `el`
  private def bodyChildOf(el: dom.Element): Option[dom.Element] =
    val body          = dom.document.body
    var cur: dom.Node = el
    while cur != null && cur.parentNode != null && !(cur.parentNode eq body) do
      cur = cur.parentNode
    cur match
      case e: dom.Element if e.parentNode eq body =>
        Some(e)
      case _ =>
        None

end ModalInert
