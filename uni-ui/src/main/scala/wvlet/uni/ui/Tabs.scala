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
class Tab[A](val value: A, val content: DomNode*)

/**
  * A row of tabs bound to `selected`. Clicking a tab selects it; with focus in the list, the arrow
  * keys, Home, and End move to and select another tab. Only the selected tab is a tab stop.
  *
  * Render the content with a [[TabPanel]] for this list, which links the panel and the tabs for
  * screen readers (`aria-controls` and `aria-labelledby`):
  * {{{
  *   val tab  = Rx.variable(DockTab.Details)
  *   val tabs = TabList(tab, "Dock")(Tab(DockTab.Details, "Details"), Tab(DockTab.Activity, "Activity"))
  *   div(
  *     tabs,
  *     TabPanel(tabs) {
  *       case DockTab.Details  => DetailsView()
  *       case DockTab.Activity => ActivityLog()
  *     }
  *   )
  * }}}
  *
  * @param label
  *   the accessible name of the list
  */
class TabList[A](val selected: RxVar[A], label: String)(tabs: Tab[A]*) extends RxElement:

  // Unique per list, so two lists on one page never share ids
  private val idPrefix = TabList.nextIdPrefix()

  /** The id of the [[TabPanel]] that shows this list's content. */
  def panelId: String = s"${idPrefix}-panel"

  /** The id of the tab for `value`, which labels the panel while it is selected. */
  def tabId(value: A): String = s"${idPrefix}-tab-${math.max(tabs.indexWhere(_.value == value), 0)}"

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
    // Listeners, so an app's own `onkeydown` or `onclick` modifier runs alongside them
    EventListenerBinding("keydown", e => onKey(e.asInstanceOf[dom.KeyboardEvent])),
    tabs.map { tab =>
      val isSelected: Rx[Boolean] = selected.map(_ == tab.value)
      button(
        tpe          -> "button",
        role         -> "tab",
        id           -> tabId(tab.value),
        cls          -> "uni-tab",
        ariaControls -> panelId,
        ariaSelected -> isSelected.map(_.toString),
        tabindex     ->
          isSelected.map(on =>
            if on then
              "0"
            else
              "-1"
          ),
        EventListenerBinding("click", _ => selected := tab.value),
        tab.content
      )
    }
  )

end TabList

object TabList:
  private var count = 0

  private def nextIdPrefix(): String =
    count += 1
    s"uni-tabs-${count}"

/**
  * The content of the selected tab of `tabs`: `render` runs again whenever the selection changes.
  * The panel is a flex column that fills the space it is given.
  */
class TabPanel[A](tabs: TabList[A])(renderTab: A => RxElement) extends RxElement:
  override def render: RxElement = div(
    role           -> "tabpanel",
    id             -> tabs.panelId,
    cls            -> "uni-tab-panel",
    ariaLabelledby -> tabs.selected.map(tabs.tabId),
    tabs.selected.map(renderTab)
  )
