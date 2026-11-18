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
        Option(el.closest("[aria-modal='true'], .uni-dialog--inline")).foreach(onKeyDown(_, e))
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
  *
  * When the element is gone, as when the overlay's action deleted the row that opened it, focus
  * moves onward: to the element now in its place, else the one before it (see [[FocusPlace]]). The
  * same happens when the element focus went back to is removed in the frame after the close, as
  * when an app writes `open := false` and then deletes the row.
  */
class FocusReturn:
  private val saved: Option[dom.HTMLElement] =
    dom.document.activeElement match
      case el: dom.HTMLElement if !(el eq dom.document.body) =>
        Some(el)
      case _ =>
        None

  // Where the element sat, read before the overlay is in the document
  private val place: Option[FocusPlace] = saved.map(FocusPlace.of)

  /** The element that had focus when this was created. */
  def target: Option[dom.HTMLElement] = saved

  def restore(overlay: Option[dom.Element]): Unit =
    if FocusReturn.lost(overlay) then
      val outside = (e: dom.Element) => !overlay.exists(_.contains(e))
      val next    = saved.filter(dom.document.contains(_)).orElse(place.flatMap(_.resolve(outside)))
      next.foreach { el =>
        el.focus()
        FocusReturn.followOnward(el)
      }

end FocusReturn

private[ui] object FocusReturn:
  // Focus is inside the closing overlay, or nowhere
  private def lost(overlay: Option[dom.Element]): Boolean =
    val active = dom.document.activeElement
    active == null ||
    (active eq dom.document.body) || !dom.document.contains(active) ||
    overlay.exists(_.contains(active))

  // Keeps going onward when `el` leaves before the next frame and focus falls to the page
  private def followOnward(el: dom.HTMLElement): Unit =
    val place                          = FocusPlace.of(el)
    var observer: dom.MutationObserver = null
    var timer                          = 0
    var frame                          = 0
    def stop(): Unit                   =
      observer.disconnect()
      dom.window.clearTimeout(timer)
      dom.window.cancelAnimationFrame(frame)
    observer = dom.MutationObserver { (_, _) =>
      if !dom.document.contains(el) then
        stop()
        if lost(None) then
          place.resolve(_ => true).foreach(_.focus())
    }
    observer.observe(
      dom.document.body,
      new dom.MutationObserverInit:
        childList = true
        subtree = true
    )
    frame = dom.window.requestAnimationFrame(_ => stop())
    // Frames pause in a hidden tab
    timer = dom.window.setTimeout(() => stop(), 100)

end FocusReturn

/**
  * Where a focusable element sits: its ancestors, and how many focusable elements precede it in
  * each. Recorded while the element is in the document, so that once it is removed,
  * [[FocusPlace.resolve]] can find what took its place, even when its neighbors were rendered again
  * along with it.
  */
private[ui] class FocusPlace(levels: Seq[(dom.Element, Int)]):

  /**
    * The element now at this place, among those `allowed`: in the nearest ancestor still in the
    * document, the focusable element at the same index, else its last one, else the ancestor itself
    * when it can take focus. None when nothing is left.
    */
  def resolve(allowed: dom.Element => Boolean): Option[dom.HTMLElement] = levels
    .iterator
    .filter((a, _) => dom.document.contains(a))
    .map { (a, index) =>
      val candidates = FocusPlace
        .candidates(a)
        .filter(h => allowed(h) && h.closest("[inert]") == null)
      // The first visible one from the index on, else the last visible one before it
      candidates
        .drop(index)
        .find(FocusPlace.visible)
        .orElse(candidates.take(index).reverseIterator.find(FocusPlace.visible))
        .orElse(
          a match
            case h: dom.HTMLElement if h.hasAttribute("tabindex") && !(h eq dom.document.body) =>
              Some(h)
            case _ =>
              None
        )
    }
    .collectFirst { case Some(h) =>
      h
    }

end FocusPlace

private[ui] object FocusPlace:
  // Focus.getFocusableElements without its visibility check, which reads a computed style per
  // element: a place is recorded over the whole page each time an overlay opens
  private val selector =
    "button:not([disabled]), [href], input:not([disabled]), select:not([disabled]), " +
      "textarea:not([disabled]), [tabindex]:not([tabindex=\"-1\"]):not([disabled])"

  private def candidates(container: dom.Element): Seq[dom.HTMLElement] = container
    .querySelectorAll(selector)
    .toSeq
    .collect { case h: dom.HTMLElement =>
      h
    }

  private def visible(el: dom.HTMLElement): Boolean =
    val style = dom.window.getComputedStyle(el)
    style.display != "none" && style.visibility != "hidden"

  private def parentOf(e: dom.Element): Option[dom.Element] =
    e.parentNode match
      case p: dom.Element =>
        Some(p)
      case _ =>
        None

  def of(el: dom.Element): FocusPlace =
    val ancestors =
      Iterator.iterate(parentOf(el))(_.flatMap(parentOf)).takeWhile(_.isDefined).flatten.toSeq
    // One query for the whole page, then count the candidates before `el` within each ancestor
    val before = candidates(dom.document.body).takeWhile(f =>
      (el.compareDocumentPosition(f) & dom.Node.DOCUMENT_POSITION_PRECEDING) != 0
    )
    FocusPlace(ancestors.map(a => (a, before.count(a.contains))))

end FocusPlace

/** Unique element ids for linking ARIA attributes (`aria-controls`, `aria-labelledby`). */
object Ids:
  private var count = 0

  def next(prefix: String): String =
    count += 1
    s"${prefix}-${count}"
