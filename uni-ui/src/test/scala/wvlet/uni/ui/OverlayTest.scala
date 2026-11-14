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
import wvlet.uni.dom.all.{button as htmlButton, div, input, onkeydown}
import wvlet.uni.dom.all.given
import wvlet.uni.dom.testing.*
import wvlet.uni.rx.Rx
import wvlet.uni.ui.base.Side

import scala.language.implicitConversions

class OverlayTest extends DomTestSupport:

  private def find(selector: String): Option[dom.HTMLElement] = Option(
    dom.document.querySelector(selector)
  ).map(_.asInstanceOf[dom.HTMLElement])

  private def active: dom.Element = dom.document.activeElement

  private def escape(): Unit = fireEvent.keyDown(active, "Escape")

  test("a popover toggles from its trigger, wires ARIA, and moves focus in and back") {
    val open = Rx.variable(false)
    val m    = mount(
      Popover(
        open,
        trigger = Button("Filter"),
        content = Seq(input(wvlet.uni.dom.all.cls -> "field"))
      )
    )
    val trigger = m.get("button").asInstanceOf[dom.HTMLElement]
    trigger.getAttribute("aria-expanded") shouldBe "false"
    trigger.focus()
    fireEvent.click(trigger)
    open.get shouldBe true
    trigger.getAttribute("aria-expanded") shouldBe "true"
    val panel = find(".uni-popover").get
    panel.getAttribute("role") shouldBe "dialog"
    trigger.getAttribute("aria-controls") shouldBe panel.id
    settle().map { _ =>
      active.classList.contains("field") shouldBe true
      panel.style.position shouldBe "fixed"
      panel.getAttribute("data-side") shouldBe "bottom"
      escape()
      open.get shouldBe false
      find(".uni-popover") shouldBe None
      (active eq trigger) shouldBe true
    }
  }

  test("a press outside closes a popover, but a press on its trigger does not") {
    val open = Rx.variable(false)
    val m    = mount(
      div(
        Popover(open, trigger = Button("Open"), content = Seq("Details")),
        htmlButton("elsewhere")
      )
    )
    val trigger = m.get(".uni-button")
    fireEvent.click(trigger)
    settle().flatMap { _ =>
      fireEvent.pointerDown(trigger)
      open.get shouldBe true
      // A click on the trigger toggles it closed
      fireEvent.click(trigger)
      open.get shouldBe false
      open := true
      settle().map { _ =>
        fireEvent.pointerDown(m.getByText("elsewhere"))
        open.get shouldBe false
      }
    }
  }

  test("Escape closes only the topmost overlay") {
    val dialogOpen  = Rx.variable(true)
    val popoverOpen = Rx.variable(false)
    mount(
      Dialog(
        dialogOpen,
        content = Seq(Popover(popoverOpen, trigger = Button("More"), content = Seq("Inner")))
      ).withTitle("Outer")
    )
    settle().flatMap { _ =>
      fireEvent.click(find(".uni-dialog .uni-button").get)
      popoverOpen.get shouldBe true
      settle().map { _ =>
        escape()
        popoverOpen.get shouldBe false
        dialogOpen.get shouldBe true
        escape()
        dialogOpen.get shouldBe false
      }
    }
  }

  test("a dialog is labelled by its title, traps Tab, and closes on its backdrop") {
    val open = Rx.variable(false)
    val m    = mount(
      div(
        htmlButton("opener"),
        Dialog(open, content = Seq("Body"))
          .withTitle("Rename")
          .withDescription("Pick a new name")
          .withActions(Button("Cancel"), Button("Save"))
      )
    )
    val opener = m.getByText("opener").asInstanceOf[dom.HTMLElement]
    opener.focus()
    open := true
    val dialog = find(".uni-dialog").get
    dialog.getAttribute("aria-modal") shouldBe "true"
    dom.document.getElementById(dialog.getAttribute("aria-labelledby")).textContent shouldBe
      "Rename"
    dom.document.getElementById(dialog.getAttribute("aria-describedby")).textContent shouldBe
      "Pick a new name"
    settle().map { _ =>
      val buttons = dialog.querySelectorAll("button")
      val first   = buttons(0).asInstanceOf[dom.HTMLElement]
      val last    = buttons(1).asInstanceOf[dom.HTMLElement]
      (active eq first) shouldBe true
      fireEvent.keyDown(first, "Tab", shiftKey = true)
      (active eq last) shouldBe true
      fireEvent.keyDown(last, "Tab")
      (active eq first) shouldBe true
      // A press on the panel keeps it open; one on the backdrop closes it
      fireEvent.pointerDown(dialog)
      open.get shouldBe true
      fireEvent.pointerDown(find(".uni-dialog-backdrop").get)
      open.get shouldBe false
      (active eq opener) shouldBe true
    }
  }

  test("noDismissOnBackdrop keeps a dialog open on a backdrop press") {
    val open = Rx.variable(true)
    mount(Dialog(open, content = Seq("Body")).noDismissOnBackdrop())
    settle().map { _ =>
      fireEvent.pointerDown(find(".uni-dialog-backdrop").get)
      open.get shouldBe true
    }
  }

  test("an alert dialog focuses Cancel, confirms, and cancels on Escape") {
    val open      = Rx.variable(true)
    var confirmed = 0
    var cancelled = 0
    mount(
      AlertDialog(open, title = "Delete this session?")
        .withConfirmLabel("Delete")
        .danger
        .onConfirm(() => confirmed += 1)
        .onCancel(() => cancelled += 1)
    )
    find(".uni-dialog").get.getAttribute("role") shouldBe "alertdialog"
    settle().map { _ =>
      active.textContent shouldBe "Cancel"
      escape()
      cancelled shouldBe 1
      open.get shouldBe false
      open := true
      val delete = find(".uni-button--danger").get
      delete.textContent shouldBe "Delete"
      fireEvent.click(delete)
      confirmed shouldBe 1
      open.get shouldBe false
    }
  }

  test("a menu opens from the keyboard, skips disabled items, and runs the chosen one") {
    val open   = Rx.variable(false)
    var chosen = List.empty[String]
    val m      = mount(
      Menu(
        open,
        trigger = Button("Edit"),
        entries = Seq(
          MenuItem("Copy").onSelect(() => chosen ::= "copy"),
          MenuItem("Cut").withDisabled(true),
          MenuSeparator,
          MenuItem("Paste").withShortcut("⌘V").onSelect(() => chosen ::= "paste")
        )
      )
    )
    val trigger = m.get("button").asInstanceOf[dom.HTMLElement]
    trigger.getAttribute("aria-haspopup") shouldBe "menu"
    trigger.focus()
    fireEvent.keyDown(trigger, "ArrowDown")
    open.get shouldBe true
    val menu = find(".uni-menu").get
    menu.getAttribute("aria-labelledby") shouldBe trigger.id
    settle().map { _ =>
      active.textContent shouldContain "Copy"
      fireEvent.keyDown(active, "ArrowDown")
      active.textContent shouldContain "Paste"
      fireEvent.keyDown(active, "ArrowDown")
      active.textContent shouldContain "Copy"
      fireEvent.keyDown(active, "p")
      active.textContent shouldContain "Paste"
      fireEvent.keyDown(active, "Enter")
      chosen shouldBe List("paste")
      open.get shouldBe false
      (active eq trigger) shouldBe true
    }
  }

  test("ArrowUp opens a menu at its last item, and a checked item reports aria-checked") {
    val open = Rx.variable(false)
    val dark = Rx.variable(true)
    val m    = mount(
      Menu(
        open,
        trigger = Button("Theme"),
        entries = Seq(MenuItem("Light"), MenuItem("Dark").withChecked(dark))
      )
    )
    fireEvent.keyDown(m.get("button"), "ArrowUp")
    settle().map { _ =>
      active.getAttribute("role") shouldBe "menuitemcheckbox"
      active.getAttribute("aria-checked") shouldBe "true"
      dark := false
      active.getAttribute("aria-checked") shouldBe "false"
    }
  }

  test("a context menu opens at the pointer instead of the browser's menu") {
    var copied = 0
    val m      = mount(
      ContextMenu(div("Message"), entries = Seq(MenuItem("Copy").onSelect(() => copied += 1)))
    )
    val notCancelled = fireEvent.contextMenu(m.getByText("Message"), clientX = 40, clientY = 50)
    notCancelled shouldBe false
    settle().map { _ =>
      val menu = find(".uni-menu").get
      menu.style.left shouldBe "40px"
      menu.style.top shouldBe "50px"
      fireEvent.click(active)
      copied shouldBe 1
      find(".uni-menu") shouldBe None
    }
  }

  test("a select highlights the chosen option and changes it from the keyboard") {
    val model = Rx.variable("sonnet")
    val m     = mount(
      Select(model, options = Seq("opus", "sonnet", "haiku"), optionLabel = _.capitalize).withLabel(
        "Model"
      )
    )
    val trigger = m.get("button").asInstanceOf[dom.HTMLElement]
    trigger.textContent shouldBe "Sonnet"
    trigger.getAttribute("aria-haspopup") shouldBe "listbox"
    trigger.focus()
    fireEvent.click(trigger)
    val list = find(".uni-listbox").get
    list.getAttribute("role") shouldBe "listbox"
    val activeOption = dom.document.getElementById(list.getAttribute("aria-activedescendant"))
    activeOption.textContent shouldBe "Sonnet"
    activeOption.getAttribute("aria-selected") shouldBe "true"
    settle().flatMap { _ =>
      (active eq list) shouldBe true
      fireEvent.keyDown(list, "ArrowDown")
      dom.document.getElementById(list.getAttribute("aria-activedescendant")).textContent shouldBe
        "Haiku"
      // Escape leaves the value as it was
      escape()
      model.get shouldBe "sonnet"
      (active eq trigger) shouldBe true
      fireEvent.keyDown(trigger, "ArrowDown")
      settle().map { _ =>
        fireEvent.keyDown(active, "o")
        fireEvent.keyDown(active, "Enter")
        model.get shouldBe "opus"
        trigger.textContent shouldBe "Opus"
      }
    }
  }

  test("Space inside a typeahead search is part of it in a select") {
    val model = Rx.variable("Newark")
    val m     = mount(
      Select(model, options = Seq("New Delhi", "Newark", "New York"), optionLabel = s => s)
    )
    val trigger = m.get("button").asInstanceOf[dom.HTMLElement]
    trigger.focus()
    fireEvent.click(trigger)
    val list = find(".uni-listbox").get
    settle().map { _ =>
      Seq("n", "e", "w", " ", "y").foreach(k => fireEvent.keyDown(list, k))
      // The Space joined the search instead of choosing
      model.get shouldBe "Newark"
      dom.document.getElementById(list.getAttribute("aria-activedescendant")).textContent shouldBe
        "New York"
      fireEvent.keyDown(list, "Enter")
      model.get shouldBe "New York"
    }
  }

  test("Space inside a typeahead search is part of it in a menu") {
    val open   = Rx.variable(false)
    var opened = List.empty[String]
    val m      = mount(
      Menu(
        open,
        trigger = Button("File"),
        entries = Seq(
          MenuItem("New file").onSelect(() => opened ::= "file"),
          MenuItem("New window").onSelect(() => opened ::= "window")
        )
      )
    )
    val trigger = m.get("button").asInstanceOf[dom.HTMLElement]
    trigger.focus()
    fireEvent.keyDown(trigger, "ArrowDown")
    settle().map { _ =>
      Seq("n", "e", "w", " ", "w").foreach(k => fireEvent.keyDown(active, k))
      opened shouldBe Nil
      active.textContent shouldContain "New window"
      fireEvent.keyDown(active, "Enter")
      opened shouldBe List("window")
    }
  }

  test("a tooltip shows on hover, links the trigger, and hides on Escape") {
    val m       = mount(Tooltip("Theme", trigger = Button("T")).withDelay(0).withSide(Side.Right))
    val trigger = m.get("button")
    trigger.hasAttribute("aria-describedby") shouldBe false
    fireEvent.pointerEnter(trigger)
    settle().map { _ =>
      val tip = find(".uni-tooltip").get
      tip.getAttribute("role") shouldBe "tooltip"
      tip.textContent shouldBe "Theme"
      trigger.getAttribute("aria-describedby") shouldBe tip.id
      fireEvent.keyDown(trigger, "Escape") shouldBe false
      find(".uni-tooltip") shouldBe None
      trigger.hasAttribute("aria-describedby") shouldBe false
    }
  }

  test("toasts stack, announce errors as alerts, and close on their button or timer") {
    val toasts = Toaster()
    mount(div(toasts))
    toasts.info("Saved", durationMs = 0)
    toasts.error("Offline", durationMs = 0)
    val shown = dom.document.querySelectorAll(".uni-toast")
    shown.length shouldBe 2
    find(".uni-toaster").get.getAttribute("aria-live") shouldBe "polite"
    find(".uni-toast--error").get.getAttribute("role") shouldBe "alert"
    fireEvent.click(find(".uni-toast--info .uni-button").get)
    dom.document.querySelectorAll(".uni-toast").length shouldBe 1
    find(".uni-toast").get.textContent shouldContain "Offline"
    toasts.clear()
    toasts.success("Done", durationMs = 1)
    awaitValue(toasts.toasts)(_.isEmpty).map { _ =>
      find(".uni-toast") shouldBe None
    }
  }

  test("Tab out of a menu inside a dialog stays inside the dialog") {
    val dialogOpen = Rx.variable(true)
    val menuOpen   = Rx.variable(false)
    mount(
      Dialog(
        dialogOpen,
        content = Seq(
          Button("First"),
          Menu(menuOpen, trigger = Button("Last"), entries = Seq(MenuItem("Copy")))
        )
      )
    )
    settle().flatMap { _ =>
      val last = find(".uni-dialog [aria-haspopup=menu]").get
      fireEvent.click(last)
      settle().map { _ =>
        active.textContent shouldContain "Copy"
        fireEvent.keyDown(active, "Tab")
        menuOpen.get shouldBe false
        // Focus went back to the trigger (the dialog's last element), then the trap wrapped it
        active.textContent shouldBe "First"
      }
    }
  }

  test("a tooltip showing elsewhere doesn't take Escape from an open menu") {
    val menuOpen = Rx.variable(false)
    val m        = mount(
      div(
        Menu(menuOpen, trigger = Button("Edit"), entries = Seq(MenuItem("Copy"))),
        Tooltip("Hint", trigger = Button("H")).withDelay(0)
      )
    )
    fireEvent.click(m.getByText("Edit"))
    fireEvent.pointerEnter(m.getByText("H"))
    settle().map { _ =>
      find(".uni-tooltip").isDefined shouldBe true
      escape()
      menuOpen.get shouldBe false
    }
  }

end OverlayTest
