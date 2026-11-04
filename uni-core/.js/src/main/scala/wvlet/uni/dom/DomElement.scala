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

/**
  * Represents an HTML or SVG element.
  *
  * @param name
  *   The tag name (e.g., "div", "span", "svg")
  * @param namespace
  *   The namespace for this element (default: XHTML)
  * @param modifiers
  *   Accumulated modifiers (children and attributes)
  */
case class DomElement(
    name: String,
    namespace: DomNamespace = DomNamespace.xhtml,
    override val modifiers: List[Seq[DomNode]] = Nil
) extends RxElement(modifiers):

  /**
    * DomElement renders itself.
    */
  override def render: RxElement = this

  /**
    * Add modifiers and return a new DomElement with the same tag name and namespace.
    */
  override def addModifier(xs: DomNode*): DomElement = DomElement(name, namespace, xs :: modifiers)

  /**
    * Override add to return DomElement for method chaining.
    */
  override def add(xs: DomNode*): DomElement = addModifier(xs*)

  /**
    * Override apply to return DomElement for method chaining.
    */
  override def apply(xs: DomNode*): DomElement =
    if xs.isEmpty then
      this
    else
      addModifier(xs*)
