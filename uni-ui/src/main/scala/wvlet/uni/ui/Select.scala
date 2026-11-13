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
import wvlet.uni.ui.base.*

import scala.collection.mutable
import scala.language.implicitConversions
import scala.scalajs.js

/**
  * A button that opens a list of options and shows the chosen one, bound to `selected`. Click,
  * ArrowDown, or ArrowUp opens the list at the selected option. Inside, the arrow keys, Home, End,
  * and typing a letter move the highlight; Enter, Space, or a click chooses; Escape, Tab, or a
  * click outside closes without changing the value.
  *
  * {{{
  *   val model = Rx.variable("sonnet")
  *   Select(model, Seq("opus", "sonnet", "haiku"))(_.capitalize).withLabel("Model")
  * }}}
  *
  * `options` can be an `Rx[Seq[A]]` whose list changes while open; each option is keyed by its
  * value, so values must be distinct. The trigger is `button.uni-select`; the list is
  * `div.uni-listbox` with `role="listbox"`, whose rows are `div.uni-listbox__option` with
  * `aria-selected` and, for the highlighted row, `data-active="true"`.
  */
class Select[A] private (
    selected: RxVar[A],
    options: Rx[Seq[A]],
    optionLabel: A => String,
    label: Option[String],
    disabledState: Rx[Boolean]
) extends RxElement:

  private val listId                          = Ids.next("uni-select")
  private val open                            = Rx.variable(false)
  private val active                          = Rx.variable(Option.empty[A])
  private val triggerRef: DomRef[dom.Element] = DomRef[dom.Element]()
  private val optionIds                       = mutable.Map.empty[A, String]
  // The latest list, for keyboard moves; kept in step by the rendered list
  private var current: Seq[A] = Seq.empty

  private def copy(
      label: Option[String] = label,
      disabledState: Rx[Boolean] = disabledState
  ): Select[A] = new Select(selected, options, optionLabel, label, disabledState)

  /** The accessible name of the control, such as "Model". */
  def withLabel(l: String): Select[A] = copy(label = Some(l))
  def noLabel(): Select[A]            = copy(label = None)

  def withDisabled(d: Rx[Boolean]): Select[A] = copy(disabledState = d)
  def withDisabled(d: Boolean): Select[A]     = copy(disabledState = Rx.const(d))

  private def optionId(a: A): String = optionIds.getOrElseUpdate(a, Ids.next(s"${listId}-option"))

  private def choose(a: A): Unit =
    // Close first, so focus is back on the trigger when `selected` subscribers run
    open     := false
    selected := a

  private def moveTo(i: Int): Unit = current
    .lift(i)
    .foreach { a =>
      active := Some(a)
      Option(dom.document.getElementById(optionId(a))).foreach(
        _.asInstanceOf[js.Dynamic].scrollIntoView(js.Dynamic.literal(block = "nearest"))
      )
    }

  private def onListKey(e: dom.KeyboardEvent): Unit =
    if !Keyboard.isComposing(e) then
      val i = active.get.map(current.indexOf).getOrElse(-1)
      e.key match
        case "Enter" | " " =>
          e.preventDefault()
          active.get.foreach(choose)
        case "Tab" =>
          open := false
          FocusTrap.continueTab(e)
        case k =>
          RovingFocus
            .nextIndex(k, i, current.size, Orientation.Vertical)
            .orElse {
              if k.length == 1 && !e.ctrlKey && !e.metaKey && !e.altKey then
                RovingFocus.typeahead(current.map(optionLabel), i, k)
              else
                None
            }
            .foreach { n =>
              e.preventDefault()
              moveTo(n)
            }

  private def onTriggerKey(e: dom.KeyboardEvent): Unit =
    e.key match
      case "ArrowDown" | "ArrowUp" =>
        e.preventDefault()
        open := true
      case _ =>

  private def list(exit: Presence.Exit): RxElement =
    active := Some(selected.get)
    // As wide as the trigger at least, so the list lines up under it
    val minWidth = triggerRef.current.map(t => s"min-width: ${Rect.of(t).width}px").getOrElse("")
    OverlaySurface(
      exit,
      div(
        id            -> listId,
        cls           -> "uni-listbox",
        role          -> "listbox",
        tabindex      -> "-1",
        data("state") -> "open",
        styleAttr     -> minWidth,
        label.map(l => ariaLabel -> l).getOrElse(DomNode.empty),
        ariaActivedescendant -> active.map(_.map(optionId)),
        EventListenerBinding("keydown", e => onListKey(e.asInstanceOf[dom.KeyboardEvent])),
        each(
          options.map { xs =>
            current = xs
            // Forget ids of values that left the list, so a changing list doesn't grow the map
            val live = xs.toSet
            optionIds.filterInPlace((a, _) => live.contains(a))
            xs
          }
        )(identity) { a =>
          div(
            id             -> optionId(a),
            role           -> "option",
            cls            -> "uni-listbox__option",
            ariaSelected   -> selected.map(s => (s == a).toString),
            data("active") ->
              active.map(x =>
                if x.contains(a) then
                  Some("true")
                else
                  None
              ),
            EventListenerBinding("pointermove", _ => active := Some(a)),
            EventListenerBinding("click", _ => choose(a)),
            optionLabel(a)
          )
        }
      ),
      anchor = () => triggerRef.current.map(Rect.of),
      inside = el => el +: triggerRef.current.toSeq,
      side = Side.Bottom,
      align = Align.Start,
      offset = 4,
      modal = false,
      dismissOnOutside = true,
      onDismiss = () => open := false,
      // Focus stays on the list; aria-activedescendant names the highlighted option
      initialFocus =
        el =>
          // Show the selected option in a long, scrolled list
          moveTo(current.indexOf(selected.get))
          Some(el)
      ,
      returnFocus = true
    )

  end list

  override def render: RxElement = button(
    triggerRef.binding,
    tpe           -> "button",
    cls           -> "uni-select",
    disabled      -> disabledState,
    ariaHaspopup  -> "listbox",
    ariaExpanded  -> open.map(_.toString),
    ariaControls  -> listId,
    data("state") -> open.map(Popover.stateName),
    label.map(l => ariaLabel -> l).getOrElse(DomNode.empty),
    EventListenerBinding(
      "click",
      e =>
        e.currentTarget match
          case b: dom.HTMLButtonElement if b.disabled =>
          case _                                      =>
            open.update(!_)
    ),
    EventListenerBinding("keydown", e => onTriggerKey(e.asInstanceOf[dom.KeyboardEvent])),
    span(cls -> "uni-select__value", selected.map(optionLabel)),
    span(cls -> "uni-select__chevron", ariaHidden -> "true"),
    Presence(open)(exit => Portal.toBody(list(exit)))
  )

end Select

object Select:
  // `new` is required in this file: the `apply`s below replace the constructor proxy
  def apply[A](selected: RxVar[A], options: Rx[Seq[A]])(optionLabel: A => String): Select[A] =
    new Select(selected, options, optionLabel, None, Rx.const(false))

  def apply[A](selected: RxVar[A], options: Seq[A])(optionLabel: A => String): Select[A] =
    apply(selected, Rx.const(options))(optionLabel)
