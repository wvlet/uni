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
package wvlet.uni.ui

import org.scalajs.dom
import wvlet.uni.dom.all.{button as htmlButton, div}
import wvlet.uni.dom.all.given
import wvlet.uni.dom.testing.*
import wvlet.uni.rx.Rx

import scala.language.implicitConversions

class ExitAnimationTest extends DomTestSupport:

  // The exit animations come from uni-ui.css
  private val stylesheet = dom.document.createElement("style")
  stylesheet.textContent = UniUiCss.text

  override protected def beforeAll: Unit = dom.document.head.appendChild(stylesheet)
  override protected def afterAll: Unit  = dom.document.head.removeChild(stylesheet)

  private def find(selector: String): Option[dom.HTMLElement] = Option(
    dom.document.querySelector(selector)
  ).map(_.asInstanceOf[dom.HTMLElement])

  private def count(selector: String): Int = dom.document.querySelectorAll(selector).length

  // Turns true once `cond` holds, checking every few milliseconds
  private def becomes(cond: => Boolean): Rx[Boolean] =
    val v           = Rx.variable(cond)
    var handle: Int = 0
    handle = dom
      .window
      .setInterval(
        () =>
          if cond then
            dom.window.clearInterval(handle)
            v := true,
        5
      )
    awaitValue(v)(identity)

  test("a closed popover returns focus at once and leaves the document after its exit") {
    val open = Rx.variable(false)
    mount(div(Popover(open)(Button("Filter"))(htmlButton("Apply"))))
    val trigger = find(".uni-button").get
    trigger.focus()
    open := true
    settle().flatMap { _ =>
      find(".uni-popover").get.getAttribute("data-state") shouldBe "open"
      open := false
      // Closed, but still on screen while it fades out
      val panel = find(".uni-popover").get
      panel.getAttribute("data-state") shouldBe "closed"
      panel.hasAttribute("inert") shouldBe true
      (ExitAnimation.durationMs(panel) > 0) shouldBe true
      (dom.document.activeElement eq trigger) shouldBe true
      trigger.getAttribute("aria-expanded") shouldBe "false"
      becomes(find(".uni-popover").isEmpty)
    }
  }

  test("a dialog fades out its backdrop and panel together") {
    val open = Rx.variable(true)
    mount(div(Dialog(open)(htmlButton("OK")).withTitle("Hello")))
    settle().flatMap { _ =>
      open := false
      find(".uni-dialog-backdrop").get.getAttribute("data-state") shouldBe "closed"
      find(".uni-dialog").get.getAttribute("data-state") shouldBe "closed"
      becomes(find(".uni-dialog-backdrop").isEmpty)
    }
  }

  test("reopening during the exit shows one fresh overlay") {
    val open = Rx.variable(true)
    mount(div(Popover(open)(Button("Filter"))(htmlButton("Apply"))))
    settle().map { _ =>
      open := false
      find(".uni-popover").get.getAttribute("data-state") shouldBe "closed"
      open := true
      count(".uni-popover") shouldBe 1
      find(".uni-popover").get.getAttribute("data-state") shouldBe "open"
      find(".uni-popover").get.hasAttribute("inert") shouldBe false
    }
  }

  test("Escape during an exit goes to the overlay underneath") {
    val dialogOpen  = Rx.variable(true)
    val popoverOpen = Rx.variable(false)
    mount(
      div(
        Dialog(dialogOpen)(Popover(popoverOpen)(Button("Filter"))(htmlButton("Apply"))).withTitle(
          "Settings"
        )
      )
    )
    settle()
      .flatMap { _ =>
        popoverOpen := true
        settle()
      }
      .map { _ =>
        popoverOpen := false
        find(".uni-popover").get.getAttribute("data-state") shouldBe "closed"
        // The closing popover left the Dismiss stack, so Escape closes the dialog
        fireEvent.keyDown(dom.document.activeElement, "Escape")
        dialogOpen.get shouldBe false
      }
  }

  test("a dismissed toast leaves the list at once and the screen after its exit") {
    val toasts = Toaster()
    mount(div(toasts))
    var listed = Seq.empty[Toast]
    val watch  = toasts.toasts.subscribe(listed = _)
    val id     = toasts.info("Saved", durationMs = 0)
    listed.size shouldBe 1
    toasts.dismiss(id)
    listed shouldBe Seq.empty
    watch.cancel
    find(".uni-toast").get.getAttribute("data-state") shouldBe "closed"
    becomes(find(".uni-toast").isEmpty)
  }

  test("an exit whose animationend never comes still ends on the timeout") {
    val el = dom.document.createElement("div").asInstanceOf[dom.HTMLElement]
    el.className = "uni-popover"
    // Paused: the animation never ends, so only the timeout can finish the exit
    el.setAttribute("style", "animation-play-state: paused")
    dom.document.body.appendChild(el)
    var done = false
    ExitAnimation.run(el)(() => done = true)
    done shouldBe false
    becomes(done).map { _ =>
      dom.document.body.removeChild(el)
    }
  }

  test("an element without an exit animation ends at once") {
    val el = dom.document.createElement("div").asInstanceOf[dom.HTMLElement]
    dom.document.body.appendChild(el)
    var done = false
    ExitAnimation.run(el)(() => done = true)
    done shouldBe true
    dom.document.body.removeChild(el)
  }

end ExitAnimationTest
