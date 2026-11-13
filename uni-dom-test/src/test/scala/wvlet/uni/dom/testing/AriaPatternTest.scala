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
package wvlet.uni.dom.testing

import org.scalajs.dom
import wvlet.uni.test.{UniTest, empty}

/** The rules of [[checkPattern]] on hand-written markup, without any widget behind it. */
class AriaPatternTest extends UniTest:

  private var hosts = List.empty[dom.Element]

  override protected def after: Unit =
    hosts.foreach(h => dom.document.body.removeChild(h))
    hosts = Nil
    super.after

  private def html(markup: String): dom.Element =
    val host = dom.document.createElement("div")
    host.innerHTML = markup
    dom.document.body.appendChild(host)
    hosts = host :: hosts
    host

  private def rules(root: dom.Element, pattern: AriaPattern, keyboard: Boolean = false) =
    checkPattern(root, pattern, keyboard).map(_.map(_.rule))

  private val goodTabs =
    """<div role="tablist" aria-label="Views">
      |  <button role="tab" id="t1" aria-selected="true" aria-controls="p1" tabindex="0">One</button>
      |  <button role="tab" id="t2" aria-selected="false" aria-controls="p1" tabindex="-1">Two</button>
      |</div>
      |<div role="tabpanel" id="p1" aria-labelledby="t1">Content</div>""".stripMargin

  test("well-formed tabs have no structure violations") {
    rules(html(goodTabs), AriaPattern.Tabs).map(_ shouldBe empty)
  }

  test("tabs report a missing name, two selected tabs, two tab stops, and a dangling reference") {
    val root = html("""<div role="tablist">
        |  <button role="tab" aria-selected="true" aria-controls="nowhere">One</button>
        |  <button role="tab" aria-selected="true">Two</button>
        |</div>""".stripMargin)
    rules(root, AriaPattern.Tabs).map { rs =>
      rs shouldContain "tabs/name"
      rs shouldContain "tabs/one-selected"
      rs shouldContain "tabs/controls"
      rs shouldContain "tabs/id-ref"
      rs shouldContain "tabs/focus"
    }
  }

  test("a violation prints its rule, element, and message on one line") {
    val root = html(
      """<div role="tablist"><button role="tab" aria-selected="true">A</button></div>"""
    )
    checkPattern(root, AriaPattern.Tabs, keyboard = false).map { vs =>
      val v = vs.find(_.rule == "tabs/name").get
      v.element shouldBe "div[role=tablist]"
      v.toString shouldBe s"[tabs/name] div[role=tablist]: ${v.message}"
    }
  }

  test("tabs without key handling fail the keyboard contract") {
    val root = html(goodTabs)
    checkPattern(root, AriaPattern.Tabs).map { vs =>
      vs.map(_.rule) shouldBe Seq("tabs/keyboard")
      vs.head.message shouldContain "ArrowRight"
    }
  }

  test("a pattern reports when its role is missing") {
    rules(html("<div>Nothing here</div>"), AriaPattern.Tree).map(_ shouldBe Seq("tree/role"))
  }

  test("a listbox with aria-activedescendant needs a tabindex and an option it owns") {
    val root = html("""<div role="listbox" aria-label="Fruit" aria-activedescendant="elsewhere">
        |  <div role="option" id="o1" aria-selected="true">Apple</div>
        |  <div role="option" id="o2" aria-selected="true">Pear</div>
        |</div>
        |<div id="elsewhere"></div>""".stripMargin)
    rules(root, AriaPattern.Listbox).map { rs =>
      rs.count(_ == "listbox/focus") shouldBe 2
      rs shouldContain "listbox/one-selected"
    }
  }

  test("options of a listbox nested in another widget are not its own") {
    val root = html("""<div role="listbox" aria-label="Outer" tabindex="0">
        |  <div role="option" aria-selected="false">A</div>
        |  <div role="tree" aria-label="Inner"><div role="treeitem" aria-level="1">B</div></div>
        |</div>""".stripMargin)
    rules(root, AriaPattern.Listbox).map(_ shouldBe empty)
  }

  test("menu items need names, checkable items need aria-checked, and a menu holds the focus") {
    val root = html("""<div role="menu" aria-label="Edit">
        |  <div role="menuitem" tabindex="-1"></div>
        |  <div role="menuitemcheckbox" tabindex="-1">Wrap</div>
        |</div>""".stripMargin)
    rules(root, AriaPattern.Menu).map { rs =>
      rs shouldBe Seq("menu/item-name", "menu/checked", "menu/focus")
    }
  }

  test("a menu button needs aria-haspopup, aria-expanded, and a name") {
    rules(html("""<button aria-haspopup="listbox"></button>"""), AriaPattern.MenuButton).map { rs =>
      rs shouldBe Seq("menubutton/haspopup", "menubutton/name", "menubutton/expanded")
    }
  }

  test("an alert dialog needs a name, aria-modal, and the focus") {
    rules(html("""<div role="alertdialog">Delete?</div>"""), AriaPattern.AlertDialog).map { rs =>
      rs shouldBe Seq("alertdialog/name", "alertdialog/modal", "alertdialog/focus")
    }
  }

  test("a flat tree needs aria-level on its items") {
    val root = html("""<div role="tree" aria-label="Files" tabindex="0">
        |  <div role="treeitem">src</div>
        |</div>""".stripMargin)
    rules(root, AriaPattern.Tree).map(_ shouldBe Seq("tree/level"))
  }

  test("a grid row needs cells") {
    val root = html("""<div role="grid" aria-label="Calls" tabindex="0">
        |  <div role="rowgroup"><div role="row"><span>no cells</span></div></div>
        |</div>""".stripMargin)
    rules(root, AriaPattern.Grid).map(_ shouldBe Seq("grid/cells"))
  }

  test("a tooltip must be referenced by aria-describedby and never take the focus") {
    rules(
      html("""<div role="tooltip" id="tip"><button>Inside</button></div>"""),
      AriaPattern.Tooltip
    ).map(_ shouldBe Seq("tooltip/described-by", "tooltip/focusable"))
  }

  test("a combobox that is expanded needs aria-controls pointing to its popup") {
    rules(
      html("""<input role="combobox" aria-label="Search" aria-expanded="true">"""),
      AriaPattern.Combobox
    ).map(_ shouldBe Seq("combobox/controls"))
  }

end AriaPatternTest
