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
import wvlet.uni.dom.all.{button as htmlButton, cls, div, styleAttr}
import wvlet.uni.dom.all.given
import wvlet.uni.dom.testing.*
import wvlet.uni.rx.Rx
import wvlet.uni.ui.agent.UiAgent

import scala.language.implicitConversions

class InlineOverlayTest extends DomTestSupport:

  private def find(selector: String): Option[dom.HTMLElement] = Option(
    dom.document.querySelector(selector)
  ).map(_.asInstanceOf[dom.HTMLElement])

  private def active: dom.Element = dom.document.activeElement

  private def inert(el: dom.Element): Boolean = el.closest("[inert]") != null

  // No element uni-ui marked inert is left in the document
  private def noInert: Boolean = dom.document.querySelectorAll("[inert]").length == 0

  test("an inline dialog renders in its panel and blocks only that panel") {
    val open = Rx.variable(false)
    val seen = scala.collection.mutable.ArrayBuffer.empty[ChangeReason]
    var keep = false
    val m    = mount(
      div(
        htmlButton(cls -> "outside", "Outside"),
        div(
          cls       -> "panel",
          styleAttr -> "position: relative",
          htmlButton(cls -> "opener", "Open"),
          Dialog(open, content = Seq(htmlButton(cls -> "first", "First")))
            .withTitle("Rename")
            .withActions(Button("Save")(cls -> "last"))
            .withInline(true)
            .onChange { d =>
              seen += d.reason
              if keep then
                d.cancel()
            }
        )
      )
    )
    val panel  = m.get(".panel")
    val opener = m.get(".opener").asInstanceOf[dom.HTMLElement]
    opener.focus()
    open := true
    val dialog = find(".uni-dialog").get
    panel.contains(dialog) shouldBe true
    dialog.hasAttribute("aria-modal") shouldBe false
    find(".uni-dialog-backdrop").get.classList.contains("uni-dialog-backdrop--inline") shouldBe true
    settle()
      .flatMap { _ =>
        // The panel content under the dialog is inert; the page around it is not
        inert(opener) shouldBe true
        inert(m.get(".outside")) shouldBe false
        dom.document.body.children.exists(_.hasAttribute("inert")) shouldBe false
        // Tab stays inside
        val first = find(".first").get
        val last  = find(".last").get
        (active eq first) shouldBe true
        fireEvent.keyDown(first, "Tab", shiftKey = true)
        (active eq last) shouldBe true
        // Escape from outside the dialog leaves it open
        val outside = m.get(".outside").asInstanceOf[dom.HTMLElement]
        outside.focus()
        fireEvent.keyDown(outside, "Escape")
        open.get shouldBe true
        // Escape inside goes through onChange, which can keep it open
        first.focus()
        keep = true
        fireEvent.keyDown(first, "Escape")
        open.get shouldBe true
        keep = false
        fireEvent.keyDown(first, "Escape")
        open.get shouldBe false
        seen.toSeq shouldBe Seq(ChangeReason.EscapeKey, ChangeReason.EscapeKey)
        settle()
      }
      .map { _ =>
        find(".uni-dialog") shouldBe None
        noInert shouldBe true
        (active eq opener) shouldBe true
      }
  }

  test("a press outside an inline dialog closes it, unless noDismissOnBackdrop") {
    val open   = Rx.variable(true)
    val strict = Rx.variable(true)
    val m      = mount(
      div(
        htmlButton(cls -> "outside", "Outside"),
        div(
          styleAttr -> "position: relative",
          Dialog(open, content = Seq("Body")).withInline(true),
          AlertDialog(strict, title = "Delete?").withInline(true)
        )
      )
    )
    settle().map { _ =>
      find(".uni-dialog[role='alertdialog']").isDefined shouldBe true
      fireEvent.pointerDown(find(".outside").get)
      open.get shouldBe false
      // An alert dialog stays open on an outside press
      strict.get shouldBe true
    }
  }

  test("an agent rooted at the panel sees the inline dialog, not what it covers") {
    val open = Rx.variable(true)
    val m    = mount(
      div(
        cls       -> "panel",
        styleAttr -> "position: relative",
        Button("Covered"),
        Dialog(open, content = Seq(Button("Inside"))).withTitle("Rename").withInline(true)
      )
    )
    val agent = UiAgent.install(root = m.get(".panel"))
    settle().map { _ =>
      val all = agent.snapshot().flatMap(_.flatten).map(_.label)
      all.contains("Rename") shouldBe true
      all.contains("Inside") shouldBe true
      all.contains("Covered") shouldBe false
      open := false
      agent.snapshot().map(_.label) shouldBe Seq("Covered")
    }
  }

  test("a page dialog opened from an inline dialog makes the page inert, and releases it") {
    val inPanel = Rx.variable(true)
    val page    = Rx.variable(false)
    val m       = mount(
      div(
        htmlButton(cls -> "outside", "Outside"),
        div(
          styleAttr -> "position: relative",
          htmlButton(cls -> "covered", "Covered"),
          Dialog(inPanel, content = Seq(Dialog(page, content = Seq("Sure?")))).withInline(true)
        )
      )
    )
    settle()
      .flatMap { _ =>
        page := true
        settle()
      }
      .map { _ =>
        inert(m.get(".outside")) shouldBe true
        page := false
        inert(m.get(".outside")) shouldBe false
        // The inline dialog still blocks its panel
        inert(m.get(".covered")) shouldBe true
        inPanel := false
        noInert shouldBe true
      }
  }

  test("an inline popover renders next to its trigger and is placed by CSS") {
    val open = Rx.variable(false)
    val m    = mount(
      div(
        Popover(
          open,
          trigger = Button("Filter"),
          content = Seq(htmlButton(cls -> "apply", "Apply"))
        ).withInline(true)
      )
    )
    val anchor  = m.get(".uni-popover-anchor")
    val trigger = m.get("button").asInstanceOf[dom.HTMLElement]
    trigger.focus()
    fireEvent.click(trigger)
    open.get shouldBe true
    val panel = find(".uni-popover").get
    anchor.contains(panel) shouldBe true
    trigger.contains(panel) shouldBe false
    panel.classList.contains("uni-popover--inline") shouldBe true
    settle().map { _ =>
      panel.hasAttribute("data-side") shouldBe false
      panel.style.position shouldBe ""
      active.classList.contains("apply") shouldBe true
      // A click inside the panel doesn't reach the trigger's toggle
      fireEvent.click(find(".apply").get)
      open.get shouldBe true
      fireEvent.keyDown(active, "Escape")
      open.get shouldBe false
      (active eq trigger) shouldBe true
    }
  }

end InlineOverlayTest
