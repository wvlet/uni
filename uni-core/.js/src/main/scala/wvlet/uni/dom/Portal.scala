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
  * Portal node that renders children into a target element by ID.
  *
  * If the target element doesn't exist, it will be created and appended to document.body.
  */
case class PortalNode(targetId: String, children: Seq[DomNode]) extends DomNode

/**
  * Portal node that renders children into document.body.
  */
case class PortalToBody(children: Seq[DomNode]) extends DomNode

/**
  * Portal node that renders children into a specific DOM element.
  */
case class PortalToElement(target: dom.Element, children: Seq[DomNode]) extends DomNode

/**
  * Portal renders children into a different part of the DOM tree.
  *
  * Useful for modals, tooltips, and dropdowns that need to escape overflow:hidden or z-index
  * stacking contexts.
  *
  * Usage:
  * {{{
  *   // Modal that escapes overflow:hidden
  *   div(
  *     style := "overflow: hidden",
  *     Portal.toBody(
  *       div(cls -> "modal-overlay",
  *         div(cls -> "modal-content", "Modal content here")
  *       )
  *     )
  *   )
  *
  *   // Tooltip rendered to a specific container
  *   Portal.to("tooltip-root")(
  *     div(cls -> "tooltip", "Tooltip text")
  *   )
  * }}}
  */
object Portal:
  /**
    * Render children into an element with the given ID.
    *
    * If the target element doesn't exist, a div with that ID will be created and appended to
    * document.body.
    *
    * @param targetId
    *   The ID of the target element
    */
  def to(targetId: String)(children: DomNode*): DomNode = PortalNode(targetId, children.toSeq)

  /**
    * Render children into document.body.
    */
  def toBody(children: DomNode*): DomNode = PortalToBody(children.toSeq)

  /**
    * Render children into a specific DOM element.
    *
    * @param target
    *   The target DOM element
    */
  def toElement(target: dom.Element)(children: DomNode*): DomNode = PortalToElement(
    target,
    children.toSeq
  )

end Portal
