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
import wvlet.uni.dom.Focus

/**
  * Keeps Tab and Shift+Tab inside a container, for modal dialogs. Call [[FocusTrap.onKeyDown]] from
  * the container's `keydown` listener:
  *
  * {{{
  *   div(role -> "dialog", EventListenerBinding("keydown", e =>
  *     FocusTrap.onKeyDown(container, e.asInstanceOf[dom.KeyboardEvent])))
  * }}}
  */
object FocusTrap:

  /** Wrap Tab from the last focusable element to the first, and Shift+Tab the other way. */
  def onKeyDown(container: dom.Element, e: dom.KeyboardEvent): Unit =
    if e.key == "Tab" then
      val focusables = Focus.getFocusableElements(container)
      val active     = dom.document.activeElement
      if focusables.isEmpty then
        // Nothing to move to: keep focus where it is
        e.preventDefault()
      else
        val i    = focusables.indexWhere(_ eq active)
        val next =
          if e.shiftKey && i <= 0 then
            focusables.lastOption
          else if !e.shiftKey && (i < 0 || i == focusables.size - 1) then
            focusables.headOption
          else
            None
        next.foreach {
          case el: dom.HTMLElement =>
            e.preventDefault()
            el.focus()
          case _ =>
        }

  /**
    * Keep a Tab inside an enclosing modal dialog after an overlay inside it (a menu or a select's
    * list) closed on that Tab and returned focus to its trigger. The overlay lives at the end of
    * `<body>`, outside the dialog, so the dialog's own trap never saw the key.
    */
  def continueTab(e: dom.KeyboardEvent): Unit =
    dom.document.activeElement match
      case el: dom.Element =>
        Option(el.closest("[aria-modal='true']")).foreach(onKeyDown(_, e))
      case _ =>

  /** The first focusable element in `container`, if any. */
  def first(container: dom.Element): Option[dom.HTMLElement] = Focus
    .getFocusableElements(container)
    .collectFirst { case el: dom.HTMLElement =>
      el
    }

end FocusTrap

/**
  * Returns focus to where it was before an overlay opened. Create it when the overlay opens and
  * call [[restore]] as it closes, while its content is still in the document.
  *
  * Focus goes back only when it is inside the overlay or nowhere (on `<body>`), so closing an
  * overlay after the user clicked into another field doesn't steal focus from that field.
  */
class FocusReturn:
  private val saved: Option[dom.HTMLElement] =
    dom.document.activeElement match
      case el: dom.HTMLElement if !(el eq dom.document.body) =>
        Some(el)
      case _ =>
        None

  /** The element that had focus when this was created. */
  def target: Option[dom.HTMLElement] = saved

  def restore(overlay: Option[dom.Element]): Unit =
    val active = dom.document.activeElement
    val lost = active == null || (active eq dom.document.body) || overlay.exists(_.contains(active))
    if lost then
      saved.filter(dom.document.contains(_)).foreach(_.focus())

end FocusReturn

/** Unique element ids for linking ARIA attributes (`aria-controls`, `aria-labelledby`). */
object Ids:
  private var count = 0

  def next(prefix: String): String =
    count += 1
    s"${prefix}-${count}"
