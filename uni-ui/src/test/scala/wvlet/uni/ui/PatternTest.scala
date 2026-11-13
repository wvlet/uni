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
import wvlet.uni.dom.all.{div, input, span}
import wvlet.uni.dom.all.given
import wvlet.uni.dom.testing.*
import wvlet.uni.rx.Rx
import wvlet.uni.test.empty

import scala.language.implicitConversions

/** Every uni-ui component that implements a WAI-ARIA pattern passes [[checkPattern]]. */
class PatternTest extends DomTestSupport:

  // Windowed lists measure their rows, which uni-ui.css sizes
  private val stylesheet = dom.document.createElement("style")
  stylesheet.textContent = UniUiCss.text

  override protected def beforeAll: Unit = dom.document.head.appendChild(stylesheet)
  override protected def afterAll: Unit  = dom.document.head.removeChild(stylesheet)

  private def find(selector: String): dom.Element = dom.document.querySelector(selector)

  private def check(root: => dom.Element, pattern: AriaPattern): Rx[Unit] = settle()
    .flatMap(_ => checkPattern(root, pattern))
    .map(_ shouldBe empty)

  private def openFrom(trigger: dom.Element): Unit =
    trigger.asInstanceOf[dom.HTMLElement].focus()
    fireEvent.click(trigger): Unit

  test("tabs") {
    val tab  = Rx.variable("details")
    val tabs =
      TabList(tab, "Dock")(
        Tab("details", "Details"),
        Tab("activity", "Activity"),
        Tab("logs", "Logs")
      )
    val m = mount(div(tabs, TabPanel(tabs)(t => span(t))))
    check(m.container, AriaPattern.Tabs)
  }

  test("dock tabs") {
    import DockNode.group
    val layout = Rx.variable(DockLayout(group("a", "b", "c")))
    val panels = Seq("a", "b", "c").map(id => DockPanel(id, id.capitalize)(div(s"${id} content")))
    val m      = mount(
      div(
        wvlet.uni.dom.all.styleAttr -> "width: 600px; height: 400px",
        Dock(layout, "Dock")(panels*)
      )
    )
    check(m.container, AriaPattern.Tabs)
  }

  private def menu(open: wvlet.uni.rx.RxVar[Boolean]) =
    Menu(open)(Button("More"))(
      MenuItem("Rename"),
      MenuSeparator,
      MenuItem("Wrap lines").withChecked(Rx.const(true)),
      MenuItem("Archive").withDisabled(true),
      MenuItem("Delete")
    )

  test("menu button") {
    val m = mount(menu(Rx.variable(false)))
    check(m.container, AriaPattern.MenuButton)
  }

  test("menu") {
    val m = mount(menu(Rx.variable(false)))
    openFrom(m.get("button"))
    check(find("[role=menu]"), AriaPattern.Menu)
  }

  test("context menu") {
    val m      = mount(ContextMenu(div("Message"))(MenuItem("Copy"), MenuItem("Quote")))
    val target = m.getByText("Message")
    target.asInstanceOf[dom.HTMLElement].tabIndex = 0
    target.asInstanceOf[dom.HTMLElement].focus()
    fireEvent.contextMenu(target)
    check(find("[role=menu]"), AriaPattern.Menu)
  }

  test("select listbox") {
    val m = mount(Select(Rx.variable("sonnet"), Seq("haiku", "sonnet", "opus"))(identity))
    openFrom(m.get("button"))
    check(find("[role=listbox]"), AriaPattern.Listbox)
  }

  test("virtual list") {
    val m = mount(
      VirtualList(Rx.const((1 to 50).toSeq), rowHeight = 20)(identity)(i => span(s"Item ${i}"))
        .withLabel("Numbers")
    )
    check(m.container, AriaPattern.Listbox)
  }

  test("command palette") {
    val commands = Seq(
      Command("new", "New chat").withGroup("Chat"),
      Command("dock", "Toggle dock").withGroup("View"),
      Command("theme", "Theme: Night").withGroup("View")
    )
    val open = Rx.variable(true)
    mount(CommandPalette(open, Rx.const(commands)))
    // The arrow keys leave the palette open, so the dialog check can follow
    check(find("[role=combobox]"), AriaPattern.Combobox).flatMap { _ =>
      check(find("[role=dialog]"), AriaPattern.Dialog)
    }
  }

  test("dialog") {
    val open = Rx.variable(true)
    mount(
      Dialog(open)(input(wvlet.uni.dom.all.ariaLabel -> "Name"))
        .withTitle("Rename")
        .withDescription("Pick a new name")
        .withActions(Button("Cancel"), Button("Save"))
    )
    check(find("[role=dialog]"), AriaPattern.Dialog)
  }

  test("alert dialog") {
    val open = Rx.variable(true)
    mount(AlertDialog(open)("Delete the project?").withConfirmLabel("Delete"))
    check(find("[role=alertdialog]"), AriaPattern.AlertDialog)
  }

  private case class FileNode(name: String, kids: Seq[FileNode] = Seq.empty)

  test("tree") {
    val files = Seq(
      FileNode("src", Seq(FileNode("Main.scala"), FileNode("Util.scala"))),
      FileNode("build.sbt"),
      FileNode("README.md")
    )
    val m = mount(
      Tree(Rx.const(files), (f: FileNode) => f.kids)(_.name)(f => span(f.name)).withLabel("Files")
    )
    check(m.container, AriaPattern.Tree)
  }

  test("multi-select tree") {
    val files = Seq(
      FileNode("src", Seq(FileNode("Main.scala"), FileNode("Util.scala"))),
      FileNode("build.sbt"),
      FileNode("README.md")
    )
    val m = mount(
      Tree(Rx.const(files), (f: FileNode) => f.kids)(_.name)(f => span(f.name))
        .withLabel("Files")
        .withExpanded(Rx.variable(Set("src")))
        .withMultiSelect(Rx.variable(Set("Main.scala", "build.sbt")))
    )
    check(m.container, AriaPattern.Tree)
  }

  private case class Call(id: Int, method: String, millis: Double)

  test("data table grid") {
    val calls = Seq(Call(1, "list", 4.0), Call(2, "send", 12.5), Call(3, "get", 1.5))
    val m     = mount(
      DataTable(Rx.const(calls))(_.id)(
        Column[Call]("method", "Method")(c => span(c.method)),
        Column[Call]("millis", "Time")(c => span(c.millis.toString))
      ).withLabel("Calls")
    )
    check(m.container, AriaPattern.Grid)
  }

  test("multi-select data table grid") {
    val calls = Seq(Call(1, "list", 4.0), Call(2, "send", 12.5), Call(3, "get", 1.5))
    val m     = mount(
      DataTable(Rx.const(calls))(_.id)(
        Column[Call]("method", "Method")(c => span(c.method)),
        Column[Call]("millis", "Time")(c => span(c.millis.toString))
      ).withLabel("Calls").withMultiSelect(Rx.variable(Set(1, 3)))
    )
    check(m.container, AriaPattern.Grid)
  }

  test("tooltip") {
    val m = mount(Tooltip("Copy the link")(Button("Copy")).withDelay(0))
    fireEvent.pointerEnter(m.get("button"))
    check(find("[role=tooltip]"), AriaPattern.Tooltip)
  }

end PatternTest
