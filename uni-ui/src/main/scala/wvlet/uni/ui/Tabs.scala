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
import wvlet.uni.dom.all.*
import wvlet.uni.dom.all.given
import wvlet.uni.rx.{Rx, RxVar}
import wvlet.uni.ui.base.RovingFocus

import scala.language.implicitConversions

/** One tab of a [[TabList]]: selecting it sets the list's variable to `value`. */
class Tab[A] private (val value: A, val content: Seq[DomNode])

object Tab:
  def apply[A](value: A, content: DomNode*): Tab[A] = new Tab(value, content)

/**
  * A row of tabs bound to `selected`. Clicking a tab selects it; with focus in the list, the arrow
  * keys, Home, and End move to and select another tab. Only the selected tab is a tab stop.
  *
  * Render the content with [[TabPanel]]:
  * {{{
  *   val tab = Rx.variable(DockTab.Details)
  *   div(
  *     TabList(tab, "Dock")(Tab(DockTab.Details, "Details"), Tab(DockTab.Activity, "Activity")),
  *     TabPanel(tab) {
  *       case DockTab.Details  => DetailsView()
  *       case DockTab.Activity => ActivityLog()
  *     }
  *   )
  * }}}
  *
  * @param label
  *   the accessible name of the list
  */
class TabList[A](selected: RxVar[A], label: String)(tabs: Tab[A]*) extends RxElement:

  private def onKey(e: dom.KeyboardEvent): Unit =
    val current = tabs.indexWhere(_.value == selected.get)
    RovingFocus
      .nextIndex(e.key, math.max(current, 0), tabs.size)
      .foreach { i =>
        e.preventDefault()
        selected := tabs(i).value
        // Move focus along with the selection
        e.currentTarget match
          case list: dom.Element =>
            list.querySelectorAll("[role=tab]")(i) match
              case el: dom.HTMLElement =>
                el.focus()
              case _ =>
          case _ =>
      }

  override def render: RxElement = div(
    role      -> "tablist",
    cls       -> "uni-tabs",
    ariaLabel -> label,
    onkeydown -> { (e: dom.KeyboardEvent) =>
      onKey(e)
    },
    tabs.map { tab =>
      val isSelected: Rx[Boolean] = selected.map(_ == tab.value)
      button(
        tpe          -> "button",
        role         -> "tab",
        cls          -> "uni-tab",
        ariaSelected -> isSelected.map(_.toString),
        tabindex     ->
          isSelected.map(on =>
            if on then
              "0"
            else
              "-1"
          ),
        onclick -> { () =>
          selected := tab.value
        },
        tab.content
      )
    }
  )

end TabList

/**
  * The content of the selected tab: `render` runs again whenever `selected` changes. The panel is a
  * flex column that fills the space it is given.
  */
class TabPanel[A](selected: Rx[A])(renderTab: A => RxElement) extends RxElement:
  override def render: RxElement = div(
    role -> "tabpanel",
    cls  -> "uni-tab-panel",
    selected.map(renderTab)
  )
