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
package wvlet.uni.dom

import org.scalajs.dom
import wvlet.uni.rx.{Cancelable, Rx, RxVar}

import scala.scalajs.js

/**
  * Focus management utilities for accessibility.
  *
  * Usage:
  * {{{
  *   import wvlet.uni.dom.all.*
  *
  *   // Focus trap for modals (tab cycles within children)
  *   val isOpen = Rx.variable(false)
  *   isOpen.map { open =>
  *     if open then
  *       Focus.trap(
  *         div(cls -> "modal",
  *           input(Focus.onMount, placeholder -> "Email"),
  *           button("Submit"),
  *           button(onclick -> (() => isOpen := false), "Close")
  *         )
  *       )
  *     else DomNode.empty
  *   }
  *
  *   // Auto-focus on mount
  *   input(Focus.onMount, placeholder -> "Auto-focused")
  *
  *   // Focus with delay (useful for animations)
  *   input(Focus.onMountDelayed(100), placeholder -> "Focused after 100ms")
  *
  *   // Track active element
  *   Focus.active.map { elem =>
  *     elem.map(_.tagName).getOrElse("none")
  *   }
  * }}}
  */
object Focus:
  private class FocusState extends Cancelable:
    private val activeElementVar: RxVar[Option[dom.Element]] = Rx.variable(
      Option(dom.document.activeElement)
    )

    private val focusHandler: js.Function1[dom.FocusEvent, Unit] =
      _ => activeElementVar := Option(dom.document.activeElement)

    private val blurHandler: js.Function1[dom.FocusEvent, Unit] =
      _ =>
        // Delay to allow activeElement to update
        dom.window.setTimeout(() => activeElementVar := Option(dom.document.activeElement), 0)

    dom.document.addEventListener("focusin", focusHandler)
    dom.document.addEventListener("focusout", blurHandler)

    def active: Rx[Option[dom.Element]]    = activeElementVar
    def currentActive: Option[dom.Element] = activeElementVar.get

    override def cancel: Unit =
      dom.document.removeEventListener("focusin", focusHandler)
      dom.document.removeEventListener("focusout", blurHandler)

  end FocusState

  private lazy val instance: FocusState = FocusState()

  /**
    * Create a focus trap that keeps focus within the children. Useful for modals and dialogs.
    *
    * Tab cycles through focusable elements, wrapping from last to first. Shift+Tab wraps from first
    * to last. The first focusable element is automatically focused on mount.
    *
    * @param children
    *   Content to trap focus within
    * @return
    *   RxElement that manages focus trapping
    */
  def trap(children: DomNode*): RxElement = FocusTrap(children)

  /**
    * Auto-focus this element when it mounts.
    *
    * @return
    *   DomNode modifier
    */
  def onMount: DomNode = FocusOnMount(delay = 0)

  /**
    * Auto-focus this element after a delay when it mounts. Useful for elements that appear with
    * animations.
    *
    * @param delayMs
    *   Delay in milliseconds before focusing
    * @return
    *   DomNode modifier
    */
  def onMountDelayed(delayMs: Int): DomNode = FocusOnMount(delay = delayMs)

  /**
    * Execute a function with focus restoration. When the returned restore function is called, focus
    * returns to the element that was focused before.
    *
    * @param f
    *   Function that receives a restore callback
    * @return
    *   Result of the function
    */
  def withRestoration[A](f: (() => Unit) => A): A =
    val previousElement     = dom.document.activeElement
    val restore: () => Unit =
      () =>
        previousElement match
          case elem: dom.HTMLElement =>
            elem.focus()
          case _ =>
            ()
    f(restore)

  /**
    * Save the currently focused element and return a function to restore focus to it.
    *
    * @return
    *   Function to restore focus
    */
  def saveAndRestore(): () => Unit =
    val previousElement = dom.document.activeElement
    () =>
      previousElement match
        case elem: dom.HTMLElement =>
          elem.focus()
        case _ =>
          ()

  /**
    * Reactive stream of the currently focused element.
    */
  def active: Rx[Option[dom.Element]] = instance.active

  /**
    * Get the currently focused element synchronously.
    */
  def currentActive: Option[dom.Element] = instance.currentActive

  /**
    * Focus an element by its ID.
    *
    * @param id
    *   Element ID
    */
  def focusById(id: String): Unit = Option(dom.document.getElementById(id)).foreach {
    case elem: dom.HTMLElement =>
      elem.focus()
    case _ =>
      ()
  }

  /**
    * Remove focus from the currently focused element.
    */
  def blur(): Unit =
    dom.document.activeElement match
      case elem: dom.HTMLElement =>
        elem.blur()
      case _ =>
        ()

  /**
    * Get all focusable elements within a container.
    *
    * @param container
    *   Container element
    * @return
    *   Sequence of focusable elements
    */
  def getFocusableElements(container: dom.Element): Seq[dom.Element] =
    val selector =
      "button:not([disabled]), [href], input:not([disabled]), select:not([disabled]), " +
        "textarea:not([disabled]), [tabindex]:not([tabindex=\"-1\"]):not([disabled])"
    val nodeList = container.querySelectorAll(selector)
    (0 until nodeList.length)
      .map(nodeList(_))
      .filter { elem =>
        // Filter out invisible elements (display: none or visibility: hidden)
        elem match
          case htmlElem: dom.HTMLElement =>
            val style = dom.window.getComputedStyle(htmlElem)
            style.display != "none" && style.visibility != "hidden"
          case _ =>
            // For non-HTMLElements (like SVG), assume they are visible if selected
            true
      }

  /**
    * Stop focus tracking. Call this when the application is shutting down.
    */
  def stop(): Unit = instance.cancel

end Focus

/**
  * Internal: Focus trap element that keeps tab navigation within its children.
  */
private case class FocusTrap(children: Seq[DomNode]) extends RxElement:
  private var containerRef: Option[dom.Element]   = None
  private var firstFocusable: Option[dom.Element] = None
  private var lastFocusable: Option[dom.Element]  = None

  override def onMount(node: Any): Unit =
    node match
      case container: dom.Element =>
        containerRef = Some(container)
        updateFocusables()
        // Focus the first focusable element, or the container itself if none
        firstFocusable match
          case Some(elem: dom.HTMLElement) =>
            elem.focus()
          case _ =>
            // No focusable children, focus the container to activate the trap
            container match
              case c: dom.HTMLElement =>
                c.focus()
              case _ =>
                ()
      case _ =>
        ()

  private def updateFocusables(): Unit = containerRef.foreach { container =>
    val focusables = Focus.getFocusableElements(container)
    firstFocusable = focusables.headOption
    lastFocusable = focusables.lastOption
  }

  override def render: RxElement =
    import HtmlTags.{tag, attr, handler}
    val onkeydown  = handler[dom.KeyboardEvent]("onkeydown")
    val keyHandler = onkeydown { (e: dom.KeyboardEvent) =>
      if e.key == "Tab" then
        // Refresh focusables in case content changed
        updateFocusables()

        val activeElement = dom.document.activeElement
        if e.shiftKey then
          // Shift+Tab: wrap to last if at first
          if firstFocusable.contains(activeElement) then
            e.preventDefault()
            lastFocusable.foreach {
              case elem: dom.HTMLElement =>
                elem.focus()
              case _ =>
                ()
            }
        else
          // Tab: wrap to first if at last
          if lastFocusable.contains(activeElement) then
            e.preventDefault()
            firstFocusable.foreach {
              case elem: dom.HTMLElement =>
                elem.focus()
              case _ =>
                ()
            }
    }
    tag("div")((Seq(attr("tabindex")("-1"), keyHandler) ++ children)*)

  end render

end FocusTrap

/**
  * Internal: Auto-focus element on mount.
  */
private case class FocusOnMount(delay: Int) extends RxElement:
  override def onMount(node: Any): Unit =
    node match
      case elem: dom.HTMLElement =>
        if delay > 0 then
          dom.window.setTimeout(() => elem.focus(), delay)
        else
          elem.focus()
      case _ =>
        ()

  override def render: RxElement = RxElement.empty

end FocusOnMount
