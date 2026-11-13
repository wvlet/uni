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
import wvlet.uni.rx.Rx

/**
  * A WAI-ARIA Authoring Practices (APG) pattern that [[checkPattern]] can verify. Each pattern
  * names the element `checkPattern` looks for in (or at) the root it is given.
  */
enum AriaPattern(val rulePrefix: String):
  /** A `tablist` with `tab`s and the `tabpanel` of the selected tab. */
  case Tabs extends AriaPattern("tabs")

  /** An open `menu` (or `menubar`) of `menuitem`, `menuitemcheckbox`, and `menuitemradio`. */
  case Menu extends AriaPattern("menu")

  /** A button with `aria-haspopup="menu"` that opens a [[Menu]]. */
  case MenuButton extends AriaPattern("menubutton")

  /** A `listbox` of `option`s. */
  case Listbox extends AriaPattern("listbox")

  /** A `combobox` input that controls a popup, usually a `listbox`. */
  case Combobox extends AriaPattern("combobox")

  /** An open `dialog`. */
  case Dialog extends AriaPattern("dialog")

  /** An open `alertdialog`. */
  case AlertDialog extends AriaPattern("alertdialog")

  /** A `tree` of `treeitem`s. */
  case Tree extends AriaPattern("tree")

  /** A `grid` (or `treegrid`) of `row`s and cells. */
  case Grid extends AriaPattern("grid")

  /** A shown `tooltip` and the element it describes. */
  case Tooltip extends AriaPattern("tooltip")

end AriaPattern

/**
  * One way a widget departs from its [[AriaPattern]].
  *
  * @param rule
  *   a stable id such as `tabs/one-selected`, for filtering
  * @param element
  *   a short description of the offending element, such as `button[role=tab]#t-1 "Details"`
  * @param message
  *   what the pattern expects
  */
case class AriaViolation(rule: String, element: String, message: String):
  override def toString: String = s"[${rule}] ${element}: ${message}"

/**
  * Check that the widget at or inside `root` follows the WAI-ARIA APG `pattern`, and emit the
  * violations found; an empty list means it conforms.
  *
  * First come the structure checks: roles and the roles they own, required states such as
  * `aria-selected`, an accessible name, id references (`aria-controls`, `aria-labelledby`,
  * `aria-activedescendant`, ...) that resolve, and one consistent focus model. Two rules are
  * stricter than the APG: every composite widget needs an accessible name, and every id reference
  * must point to an element in the document.
  *
  * Then, unless `keyboard = false`, it presses the keys the APG requires (arrow keys, Home, End,
  * Tab, Escape) with [[fireEvent]] and checks where the focus or the active item moved. These
  * checks change the widget's state (a dialog closes on Escape), so run `checkPattern` last in a
  * test. They stop at the first keyboard violation, since later keys would start from the wrong
  * place.
  *
  * Open overlays and `settle()` before checking them, so their initial focus is in place:
  * {{{
  *   test("the menu follows the APG") {
  *     val open = Rx.variable(true)
  *     mount(Menu(open)(Button("More"))(MenuItem("Copy"), MenuItem("Paste")))
  *     settle().flatMap(_ => checkPattern(dom.document.querySelector("[role=menu]"), AriaPattern.Menu))
  *       .map(_ shouldBe empty)
  *   }
  * }}}
  */
def checkPattern(
    root: dom.Element,
    pattern: AriaPattern,
    keyboard: Boolean = true
): Rx[Seq[AriaViolation]] = AriaChecks.check(root, pattern, keyboard)
