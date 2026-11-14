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
import wvlet.uni.rx.Rx
import wvlet.uni.ui.base.*

import scala.language.implicitConversions

/**
  * A short text label for its trigger, such as the name of an icon button. It shows when the
  * pointer rests on the trigger (after a delay) and right away when the trigger gets keyboard
  * focus. It hides on pointer leave, blur, a click, or Escape. While shown, the trigger's
  * `aria-describedby` points to it.
  *
  * {{{
  *   Tooltip("Close dock", trigger = IconButton("Close dock", Icons.close).onClick(...))
  *   Tooltip(title.map(t => s"Terminal: ${t}"), trigger = button(...)).withSide(Side.Right)
  * }}}
  *
  * The label is `div.uni-tooltip` with `role="tooltip"` and `data-side`. It holds text only; use a
  * [[Popover]] for content people need to interact with.
  */
class Tooltip private (text: Rx[String], trigger: RxElement, side: Side, delayMs: Int)
    extends RxElement:

  private val tipId                           = Ids.next("uni-tooltip")
  private val shown                           = Rx.variable(false)
  private val triggerRef: DomRef[dom.Element] = DomRef[dom.Element]()
  private var timer: Option[Int]              = None

  private def copy(side: Side = side, delayMs: Int = delayMs): Tooltip =
    new Tooltip(text, trigger, side, delayMs)

  def withSide(s: Side): Tooltip = copy(side = s)

  /** How long the pointer rests on the trigger before the tooltip shows. */
  def withDelay(ms: Int): Tooltip = copy(delayMs = ms)

  private def cancelTimer(): Unit =
    timer.foreach(dom.window.clearTimeout)
    timer = None

  private def show(): Unit =
    cancelTimer()
    shown := true

  private def hide(): Unit =
    cancelTimer()
    shown := false

  private def showLater(): Unit =
    cancelTimer()
    timer = Some(dom.window.setTimeout(() => show(), delayMs.toDouble))

  private def onPointerEnter(e: dom.Event): Unit =
    e match
      // Touch has no hover: a tap is a click, not a request for a label
      case p: dom.PointerEvent if p.pointerType == "touch" =>
      case _                                               =>
        showLater()

  private def onFocus(e: dom.Event): Unit =
    e.currentTarget match
      // Only keyboard focus: a mouse click also focuses a button, and the label would linger
      case el: dom.Element if el.matches(":focus-visible") =>
        show()
      case _ =>

  private def onKeyDown(e: dom.Event): Unit =
    e match
      case k: dom.KeyboardEvent if k.key == "Escape" && shown.get =>
        // Handled: an enclosing overlay stays open
        k.preventDefault()
        hide()
      case _ =>

  private def tip(exit: Presence.Exit): RxElement = OverlaySurface(
    exit,
    div(
      id            -> tipId,
      cls           -> "uni-tooltip",
      data("slot")  -> "tooltip",
      role          -> "tooltip",
      data("state") -> "open",
      text
    ),
    anchor = () => triggerRef.current.map(Rect.of),
    inside = el => Seq(el),
    side = side,
    align = Align.Center,
    offset = 6,
    modal = false,
    dismissOnOutside = false,
    onDismiss = () => hide(),
    initialFocus = _ => None,
    returnFocus = false,
    // Off the Dismiss stack: Escape still closes an open menu while a tooltip shows elsewhere.
    // The trigger's own listeners hide it (pointer leave, blur, press, Escape)
    stacked = false
  )

  override def render: RxElement = trigger(
    triggerRef.binding,
    ariaDescribedby ->
      shown.map(s =>
        if s then
          Some(tipId)
        else
          None
      ),
    EventListenerBinding("pointerenter", onPointerEnter),
    EventListenerBinding("pointerleave", _ => hide()),
    EventListenerBinding("pointerdown", _ => hide()),
    EventListenerBinding("focus", onFocus),
    EventListenerBinding("blur", _ => hide()),
    EventListenerBinding("keydown", onKeyDown),
    Presence(shown)(exit => Portal.toBody(tip(exit)))
  )

  override def beforeUnmount: Unit = cancelTimer()

end Tooltip

object Tooltip:
  // `new` is required in this file: the `apply`s below replace the constructor proxy
  private val defaultDelayMs = 500

  def apply(text: String, trigger: RxElement): Tooltip =
    new Tooltip(Rx.const(text), trigger, Side.Top, defaultDelayMs)

  def apply(text: Rx[String], trigger: RxElement): Tooltip =
    new Tooltip(text, trigger, Side.Top, defaultDelayMs)
