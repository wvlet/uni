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

/**
  * Represents an HTML/SVG attribute.
  *
  * @param name
  *   The attribute name (e.g., "class", "id", "onclick")
  * @param v
  *   The attribute value (can be a primitive, Rx, or function for event handlers)
  * @param ns
  *   The namespace for this attribute
  * @param append
  *   If true, the value will be appended to the existing attribute value (useful for class/style)
  */
case class DomAttribute(
    name: String,
    v: Any,
    ns: DomNamespace = DomNamespace.xhtml,
    append: Boolean = false
) extends DomNode

/**
  * A boolean attribute (e.g., disabled, hidden). Used as-is, it sets the attribute; it also takes a
  * Boolean or an Rx[Boolean], where false removes the attribute.
  *
  * Usage:
  * {{{
  *   button(disabled)
  *   button(disabled -> isBusy) // isBusy: Rx[Boolean]
  * }}}
  */
class DomBooleanAttribute(attrName: String, attrNs: DomNamespace = DomNamespace.xhtml)
    extends DomAttribute(attrName, true, attrNs):
  /**
    * Set the attribute from a Boolean, Option, or Rx value; false or None removes it.
    */
  def apply[V: EmbeddableAttribute](v: V): DomNode = DomAttribute(name, v, ns)

  /**
    * Alias for apply. Allows `disabled -> isBusy` syntax.
    */
  def ->[V: EmbeddableAttribute](v: V): DomNode = apply(v)

/**
  * Builder for creating DOM attributes with type-safe value assignment.
  *
  * Usage:
  * {{{
  *   val id = DomAttributeOf("id")
  *   div(id -> "myId")
  *   // or
  *   div(id("myId"))
  * }}}
  */
class DomAttributeOf(name: String, namespace: DomNamespace = DomNamespace.xhtml):
  /**
    * Create an attribute with the given value.
    */
  def apply[V: EmbeddableAttribute](v: V): DomNode = DomAttribute(name, v, namespace)

  /**
    * Alias for apply. Allows `id -> "value"` syntax.
    */
  def ->[V: EmbeddableAttribute](v: V): DomNode = apply(v)

  /**
    * Create an attribute that appends to existing value (useful for class/style).
    */
  def add[V: EmbeddableAttribute](v: V): DomNode = DomAttribute(name, v, namespace, append = true)

  /**
    * Alias for add. Allows `cls += "additional-class"` syntax.
    */
  def +=[V: EmbeddableAttribute](v: V): DomNode = add(v)

  /**
    * Create a boolean attribute with no value (e.g., disabled, checked).
    */
  def noValue: DomNode = DomAttribute(name, true, namespace)

  /**
    * A boolean attribute that also accepts a Boolean or Rx[Boolean] value.
    */
  def boolean: DomBooleanAttribute = DomBooleanAttribute(name, namespace)

end DomAttributeOf

/**
  * Builder for creating DOM event handler attributes.
  *
  * Usage:
  * {{{
  *   button(onclick -> { () => println("clicked") })
  *   input(oninput -> { (e: dom.Event) => handleInput(e) })
  * }}}
  */
class DomEventHandlerOf[E](name: String, namespace: DomNamespace = DomNamespace.xhtml):
  /**
    * Create an event handler with an event parameter.
    */
  def apply[U](v: E => U): DomNode = DomAttribute(name, v, namespace)

  /**
    * Alias for apply with event parameter.
    */
  def ->[U](v: E => U): DomNode = apply(v)

  /**
    * Create an event handler without an event parameter.
    */
  def apply[U](v: () => U): DomNode = DomAttribute(name, v, namespace)

  /**
    * Alias for apply without event parameter.
    */
  def ->[U](v: () => U): DomNode = apply(v)

  /**
    * Create a no-op event handler that prevents default behavior.
    */
  def noValue: DomNode = DomAttribute(name, (e: dom.Event) => e.preventDefault(), namespace)
