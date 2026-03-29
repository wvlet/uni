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
import wvlet.uni.test.UniTest
import wvlet.uni.dom.all.*
import wvlet.uni.dom.all.given
import scala.language.implicitConversions
import wvlet.uni.rx.Rx

class PortalTest extends UniTest:

  private def cleanupPortalRoots(): Unit =
    val portalRoot = dom.document.getElementById("portal-root")
    if portalRoot != null then
      portalRoot.parentNode.removeChild(portalRoot)
    val tooltipRoot = dom.document.getElementById("tooltip-root")
    if tooltipRoot != null then
      tooltipRoot.parentNode.removeChild(tooltipRoot)

  test("Portal.to renders children to target element by ID"):
    cleanupPortalRoots()
    // Create a target element
    val target = dom.document.createElement("div")
    target.setAttribute("id", "portal-root")
    dom.document.body.appendChild(target)

    val elem = div(Portal.to("portal-root")(span(cls -> "portaled", "Portal content")))
    val (node, cancelable) = DomRenderer.createNode(elem)
    dom.document.body.appendChild(node)

    // Content should be in portal-root, not in the parent div
    val portalRoot = dom.document.getElementById("portal-root")
    portalRoot.innerHTML shouldContain "Portal content"
    portalRoot.innerHTML shouldContain "portaled"

    // Cleanup
    cancelable.cancel
    dom.document.body.removeChild(node)

  test("Portal.to creates target element if it doesn't exist"):
    cleanupPortalRoots()
    val elem               = div(Portal.to("tooltip-root")(span("Tooltip content")))
    val (node, cancelable) = DomRenderer.createNode(elem)
    dom.document.body.appendChild(node)

    // Target should have been created
    val tooltipRoot = dom.document.getElementById("tooltip-root")
    tooltipRoot shouldNotBe null
    tooltipRoot.innerHTML shouldContain "Tooltip content"

    // Cleanup
    cancelable.cancel
    dom.document.body.removeChild(node)

  test("Portal.toBody renders children to document.body"):
    cleanupPortalRoots()
    val elem = div(Portal.toBody(span(id -> "body-portal-content", "Body portal content")))
    val (node, cancelable) = DomRenderer.createNode(elem)
    dom.document.body.appendChild(node)

    // Content should be in document.body
    val portalContent = dom.document.getElementById("body-portal-content")
    portalContent shouldNotBe null
    portalContent.textContent shouldBe "Body portal content"

    // Cleanup removes portal content
    cancelable.cancel
    dom.document.getElementById("body-portal-content") shouldBe null
    dom.document.body.removeChild(node)

  test("Portal.toElement renders children to specific element"):
    cleanupPortalRoots()
    val customTarget = dom.document.createElement("div")
    customTarget.setAttribute("id", "custom-target")
    dom.document.body.appendChild(customTarget)

    val elem               = div(Portal.toElement(customTarget)(span("Custom target content")))
    val (node, cancelable) = DomRenderer.createNode(elem)
    dom.document.body.appendChild(node)

    // Content should be in custom target
    customTarget.innerHTML shouldContain "Custom target content"

    // Cleanup
    cancelable.cancel
    dom.document.body.removeChild(node)
    dom.document.body.removeChild(customTarget)

  test("Portal content is cleaned up on cancel"):
    cleanupPortalRoots()
    val target = dom.document.createElement("div")
    target.setAttribute("id", "portal-root")
    dom.document.body.appendChild(target)

    val elem = div(Portal.to("portal-root")(span(id -> "cleanup-test", "Content to cleanup")))
    val (node, cancelable) = DomRenderer.createNode(elem)
    dom.document.body.appendChild(node)

    // Verify content exists
    val portalRoot = dom.document.getElementById("portal-root")
    portalRoot.innerHTML shouldContain "cleanup-test"

    // Cancel should clean up portal content
    cancelable.cancel
    portalRoot.innerHTML shouldNotContain "cleanup-test"

    dom.document.body.removeChild(node)

  test("Portal renders reactive content"):
    cleanupPortalRoots()
    val target = dom.document.createElement("div")
    target.setAttribute("id", "portal-root")
    dom.document.body.appendChild(target)

    val message            = Rx.variable("Initial")
    val elem               = div(Portal.to("portal-root")(span(message)))
    val (node, cancelable) = DomRenderer.createNode(elem)
    dom.document.body.appendChild(node)

    val portalRoot = dom.document.getElementById("portal-root")
    portalRoot.innerHTML shouldContain "Initial"

    // Update reactive value
    message := "Updated"
    portalRoot.innerHTML shouldContain "Updated"

    // Cleanup
    cancelable.cancel
    dom.document.body.removeChild(node)

  test("Multiple children in Portal"):
    cleanupPortalRoots()
    val target = dom.document.createElement("div")
    target.setAttribute("id", "portal-root")
    dom.document.body.appendChild(target)

    val elem = div(Portal.to("portal-root")(span("Child 1"), span("Child 2"), span("Child 3")))
    val (node, cancelable) = DomRenderer.createNode(elem)
    dom.document.body.appendChild(node)

    val portalRoot = dom.document.getElementById("portal-root")
    portalRoot.innerHTML shouldContain "Child 1"
    portalRoot.innerHTML shouldContain "Child 2"
    portalRoot.innerHTML shouldContain "Child 3"

    // Cleanup
    cancelable.cancel
    dom.document.body.removeChild(node)

end PortalTest
