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
import wvlet.uni.rx.{Cancelable, RxVar}

import scala.scalajs.js

/**
  * Binding for click outside detection. Handled by DomRenderer to register a document-level
  * mousedown listener that checks if the click target is outside the host element.
  */
case class ClickOutsideBinding(callback: dom.MouseEvent => Unit) extends DomNode

/**
  * Click outside detection for dismissing dropdowns, modals, and other overlays.
  *
  * Usage:
  * {{{
  *   import wvlet.uni.dom.all.*
  *
  *   val isOpen = Rx.variable(true)
  *
  *   // Dismiss dropdown on outside click
  *   isOpen.map { open =>
  *     if open then
  *       div(cls -> "dropdown",
  *         ClickOutside.hide(isOpen),
  *         ul(li("Option 1"), li("Option 2"))
  *       )
  *     else DomNode.empty
  *   }
  *
  *   // Custom callback
  *   div(
  *     ClickOutside.detect { event =>
  *       println(s"Clicked outside at (${event.clientX}, ${event.clientY})")
  *     },
  *     "Click outside me"
  *   )
  *
  *   // Simple no-arg callback
  *   div(
  *     ClickOutside.onClickOutside(() => println("Outside!")),
  *     "Content"
  *   )
  * }}}
  */
object ClickOutside:

  /**
    * Detect clicks outside the host element and invoke a callback with the mouse event.
    *
    * @param callback
    *   Function called with the MouseEvent when a click occurs outside
    */
  def detect(callback: dom.MouseEvent => Unit): DomNode = ClickOutsideBinding(callback)

  /**
    * Set an RxVar to false when a click occurs outside the host element. Convenient for hiding
    * dropdowns and modals.
    *
    * @param visible
    *   The RxVar to set to false on outside click
    */
  def hide(visible: RxVar[Boolean]): DomNode = ClickOutsideBinding(_ => visible := false)

  /**
    * Invoke a no-arg callback when a click occurs outside the host element.
    *
    * @param callback
    *   Function called on outside click
    */
  def onClickOutside(callback: () => Unit): DomNode = ClickOutsideBinding(_ => callback())

end ClickOutside
