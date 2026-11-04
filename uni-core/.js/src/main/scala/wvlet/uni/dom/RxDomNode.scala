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
import wvlet.uni.rx.Cancelable

/**
  * Wrapper for a rendered DOM node with its associated Cancelable.
  *
  * @param node
  *   The underlying DOM node
  * @param cancelable
  *   Cancelable to clean up event listeners and Rx subscriptions
  */
case class RxDomNode(node: dom.Node, cancelable: Cancelable):
  /**
    * Cancel all subscriptions and clean up resources.
    */
  def cancel(): Unit = cancelable.cancel

  /**
    * Get the outerHTML of this node (if it's an Element).
    */
  def outerHTML: String =
    node match
      case e: dom.Element =>
        e.outerHTML
      case _ =>
        node.textContent

  /**
    * Get the innerHTML of this node (if it's an Element).
    */
  def innerHTML: String =
    node match
      case e: dom.Element =>
        e.innerHTML
      case _ =>
        node.textContent

  /**
    * Get the text content of this node.
    */
  def textContent: String = node.textContent

end RxDomNode
