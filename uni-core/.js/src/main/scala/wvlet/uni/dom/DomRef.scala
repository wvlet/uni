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
import wvlet.uni.rx.{Rx, RxVar}

/**
  * Binding that attaches a DomRef to an element during rendering.
  *
  * @param ref
  *   The DomRef to attach to the element
  */
case class RefBinding[E <: dom.Element](ref: DomRef[E]) extends DomNode

/**
  * A reactive reference container for direct DOM element access.
  *
  * Use DomRef when you need imperative access to DOM elements, such as:
  *   - Focus management
  *   - Measuring element dimensions
  *   - Scrolling to elements
  *   - Integrating with third-party libraries
  *
  * Usage:
  * {{{
  *   val inputRef = DomRef[dom.html.Input]()
  *
  *   div(
  *     input(ref -> inputRef, placeholder -> "Enter text"),
  *     button(onClick -> (() => inputRef.focus()), "Focus Input")
  *   )
  *
  *   // Access the element when needed
  *   inputRef.foreach { elem =>
  *     println(s"Input value: \${elem.value}")
  *   }
  * }}}
  *
  * @tparam E
  *   The type of DOM element this ref holds
  */
class DomRef[E <: dom.Element]:
  private val elemVar: RxVar[Option[E]] = Rx.variable(None)

  /**
    * Set the referenced element. Called by DomRenderer when the element is mounted.
    */
  def set(elem: E): Unit = elemVar := Some(elem)

  /**
    * Clear the reference. Called when the element is unmounted.
    */
  def clear(): Unit = elemVar := None

  /**
    * Get the current element, if available.
    */
  def current: Option[E] = elemVar.get

  /**
    * Get the reactive stream of the element reference. Use this to react to element availability
    * changes.
    */
  def rx: Rx[Option[E]] = elemVar

  /**
    * Execute a function with the element if it's available.
    */
  def foreach(f: E => Unit): Unit = current.foreach(f)

  /**
    * Map over the element if it's available.
    */
  def map[A](f: E => A): Option[A] = current.map(f)

  /**
    * Focus the element. Only works for focusable elements (input, textarea, button, etc.). The
    * implicit evidence ensures this method can only be called on DomRef[HTMLElement] subtypes.
    */
  def focus()(implicit ev: E <:< dom.HTMLElement): Unit = foreach(ev(_).focus())

  /**
    * Remove focus from the element. The implicit evidence ensures this method can only be called on
    * DomRef[HTMLElement] subtypes.
    */
  def blur()(implicit ev: E <:< dom.HTMLElement): Unit = foreach(ev(_).blur())

  /**
    * Scroll the element into view.
    */
  def scrollIntoView(): Unit = foreach(_.scrollIntoView())

  /**
    * Scroll the element into view with smooth scrolling.
    */
  def scrollIntoViewSmooth(): Unit =
    import scala.scalajs.js
    foreach { elem =>
      val options = js.Dynamic.literal(behavior = "smooth")
      elem.asInstanceOf[js.Dynamic].scrollIntoView(options)
    }

  /**
    * Get the bounding client rect of the element.
    */
  def getBoundingClientRect(): Option[dom.DOMRect] = current.map(_.getBoundingClientRect())

  /**
    * Create a binding that attaches this ref to an element.
    */
  def binding: RefBinding[E] = RefBinding(this)

end DomRef

object DomRef:
  /**
    * Create a new DomRef for the specified element type.
    */
  def apply[E <: dom.Element](): DomRef[E] = new DomRef[E]

  /**
    * Alias for apply.
    */
  def of[E <: dom.Element]: DomRef[E] = new DomRef[E]

/**
  * Attribute factory for ref bindings.
  *
  * Usage:
  * {{{
  *   val myRef = DomRef[dom.html.Input]()
  *   input(ref -> myRef)
  * }}}
  */
object ref:
  /**
    * Create a ref binding using the arrow syntax: ref -> myRef
    */
  def ->[E <: dom.Element](domRef: DomRef[E]): RefBinding[E] = RefBinding(domRef)
