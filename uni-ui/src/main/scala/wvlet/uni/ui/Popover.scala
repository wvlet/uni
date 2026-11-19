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

import scala.language.implicitConversions

/**
  * A non-modal panel next to its trigger, for small forms, details, or pickers. Clicking the
  * trigger toggles `open`; Escape, a click outside, or `open := false` closes it, and focus returns
  * to the trigger.
  *
  * {{{
  *   val open = Rx.variable(false)
  *   Popover(open, trigger = Button("Filter"), content = Input(query).withLabel("Filter"))
  *     .withSide(Side.Bottom)
  *     .withAlign(Align.End)
  * }}}
  *
  * The panel is `div.uni-popover` with `role="dialog"`, rendered at the end of `<body>` so pane
  * overflow never clips it. It carries `data-side`, the side it ended up on after flipping.
  * [[withInline]] renders it in place instead.
  */
class Popover private (
    open: RxVar[Boolean],
    trigger: RxElement,
    content: Seq[DomNode],
    side: Side,
    align: Align,
    label: Option[String],
    changeHandler: ChangeHandler,
    inPlace: Boolean = false
) extends RxElement:

  private val contentId                       = Ids.next("uni-popover")
  private val triggerRef: DomRef[dom.Element] = DomRef[dom.Element]()

  private def copy(
      side: Side = side,
      align: Align = align,
      label: Option[String] = label,
      changeHandler: ChangeHandler = changeHandler,
      inPlace: Boolean = inPlace
  ): Popover = new Popover(open, trigger, content, side, align, label, changeHandler, inPlace)

  def withSide(s: Side): Popover   = copy(side = s)
  def withAlign(a: Align): Popover = copy(align = a)

  /** The accessible name of the panel. */
  def withLabel(l: String): Popover = copy(label = Some(l))
  def noLabel(): Popover            = copy(label = None)

  /**
    * Whether to render the panel where the popover is instead of at the end of `<body>` (off by
    * default), for a popover inside a frame or a fixed widget. The trigger and the panel are
    * wrapped in `span.uni-popover-anchor`, and CSS, not script, places the panel under the trigger:
    * `withSide` and `withAlign` don't apply, and `data-side` is not set. Style
    * `.uni-popover--inline` to move it. Pane overflow can clip it.
    */
  def withInline(b: Boolean): Popover = copy(inPlace = b)
  def noInline(): Popover             = withInline(false)

  /** Called before the popover opens or closes; `cancel()` keeps it as it is. Key: `open`. */
  def onChange(f: ChangeHandler): Popover = copy(changeHandler = f)

  private def setOpen(v: Boolean, cause: ChangeCause): Unit = ChangeGate.write(
    open,
    "open",
    v,
    cause,
    changeHandler
  )

  private def panel(exit: Presence.Exit): RxElement = OverlaySurface(
    exit,
    div(
      id  -> contentId,
      cls -> (
        if inPlace then
          "uni-popover uni-popover--inline"
        else
          "uni-popover"
      ),
      data("slot")  -> "popover-content",
      role          -> "dialog",
      tabindex      -> "-1",
      data("state") -> "open",
      label.map(l => ariaLabel -> l).getOrElse(DomNode.empty),
      content
    ),
    // An inline panel is placed by CSS
    anchor =
      () =>
        if inPlace then
          None
        else
          triggerRef.current.map(Rect.of)
    ,
    inside = el => el +: triggerRef.current.toSeq,
    side = side,
    align = align,
    offset = 6,
    modal = false,
    dismissOnOutside = true,
    onDismiss = () => setOpen(false, ChangeCause.current),
    // The first field or button, else the panel itself, so Escape and Tab start from inside it
    initialFocus = el => FocusTrap.first(el).orElse(Some(el)),
    returnFocus = true,
    inPlace = inPlace
  )

  private def wiredTrigger(modifiers: DomNode*): RxElement = trigger(
    triggerRef.binding,
    ariaHaspopup -> "dialog",
    ariaExpanded -> open.map(_.toString),
    // Only while open: a closed popup is not in the document, and the reference would dangle
    ariaControls  -> open.map(o => Option.when(o)(contentId)),
    data("state") -> open.map(Popover.stateName),
    EventListenerBinding("click", e => setOpen(!open.get, ChangeCause.of(ChangeReason.Press, e))),
    modifiers
  )

  override def render: RxElement =
    if inPlace then
      // Next to the trigger, not inside it, where the trigger's click would toggle it
      span(
        cls          -> "uni-popover-anchor",
        data("slot") -> "popover-anchor",
        wiredTrigger(),
        Presence(open)(exit => panel(exit))
      )
    else
      wiredTrigger(Presence(open)(exit => OverlaySurface.portal(triggerRef)(panel(exit))))

end Popover

object Popover:
  // `new` is required in this file: the `apply` below replaces the constructor proxy
  def apply(open: RxVar[Boolean], trigger: RxElement, content: DomNode | Seq[DomNode]): Popover =
    new Popover(
      open,
      trigger,
      Content.nodes(content),
      Side.Bottom,
      Align.Start,
      None,
      ChangeGate.noHandler
    )

  private[ui] def stateName(open: Boolean): String =
    if open then
      "open"
    else
      "closed"
