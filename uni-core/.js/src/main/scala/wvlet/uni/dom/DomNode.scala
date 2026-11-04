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
  * Base trait for all DOM nodes in the reactive DOM library.
  */
trait DomNode

object DomNode:
  /**
    * An empty DOM node that renders nothing.
    */
  object empty extends DomNode

  /**
    * Group multiple DomNodes together as a single DomNode.
    */
  def group(nodes: DomNode*): DomNode = DomNodeGroup(nodes)

/**
  * Represents raw HTML content that will be inserted directly into the DOM.
  *
  * @note
  *   Using this with untrusted input can lead to Cross-Site Scripting (XSS) vulnerabilities. Ensure
  *   that the HTML content is properly sanitized or comes from a trusted source.
  */
case class RawHtml(html: String) extends DomNode

/**
  * Represents an HTML entity reference (e.g., &nbsp;, &amp;).
  */
case class EntityRef(entityName: String) extends DomNode

/**
  * Groups multiple DomNodes together as a single DomNode. Useful for returning multiple modifiers
  * from a single function.
  */
case class DomNodeGroup(nodes: Seq[DomNode]) extends DomNode
