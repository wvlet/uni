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

import wvlet.uni.dom.all.*
import wvlet.uni.dom.all.given
import wvlet.uni.rx.Rx
import wvlet.uni.ui.agent.{AgentAction, AgentBinding, AgentRegistry}

import scala.language.implicitConversions

/** The look of a [[Button]]: the CSS modifier is `uni-button--<cssName>`. */
enum ButtonVariant(val cssName: String):
  case Default extends ButtonVariant("default")
  case Primary extends ButtonVariant("primary")
  case Ghost   extends ButtonVariant("ghost")
  case Danger  extends ButtonVariant("danger")

/** The size of a [[Button]]: the CSS modifier is `uni-button--<cssName>`. */
enum ButtonSize(val cssName: String):
  case Small  extends ButtonSize("sm")
  case Medium extends ButtonSize("md")

/**
  * A push button. It renders `<button type="button">`, so it never submits a form by accident.
  *
  * {{{
  *   Button("Send").primary.withDisabled(busy).onClick(() => store.send())
  * }}}
  *
  * Pass more attributes as modifiers, and use `cls +=` so the `uni-button` classes stay:
  * {{{
  *   Button("Save")(cls += "w-full", data("testid") -> "save")
  * }}}
  */
class Button private (
    content: Seq[DomNode],
    val variant: ButtonVariant,
    val size: ButtonSize,
    disabledState: Rx[Boolean],
    action: () => Unit,
    label: Option[String],
    iconOnly: Boolean
) extends RxElement:

  private def copy(
      variant: ButtonVariant = variant,
      size: ButtonSize = size,
      disabledState: Rx[Boolean] = disabledState,
      action: () => Unit = action,
      label: Option[String] = label
  ): Button = new Button(content, variant, size, disabledState, action, label, iconOnly)

  def withVariant(v: ButtonVariant): Button = copy(variant = v)
  def primary: Button                       = withVariant(ButtonVariant.Primary)
  def ghost: Button                         = withVariant(ButtonVariant.Ghost)
  def danger: Button                        = withVariant(ButtonVariant.Danger)

  def withSize(s: ButtonSize): Button = copy(size = s)
  def small: Button                   = withSize(ButtonSize.Small)

  def withDisabled(d: Rx[Boolean]): Button = copy(disabledState = d)
  def withDisabled(d: Boolean): Button     = copy(disabledState = Rx.const(d))

  /** Runs on a click, or Enter or Space; [[ChangeCause.current]] tells which. */
  def onClick(f: () => Unit): Button = copy(action = f)

  /** The accessible name, for a button whose content is not text. */
  def withLabel(l: String): Button = copy(label = Some(l))
  def noLabel(): Button            = copy(label = None)

  override def render: RxElement =
    val shape =
      if iconOnly then
        " uni-button--icon"
      else
        ""
    button(
      tpe      -> "button",
      cls      -> s"uni-button uni-button--${variant.cssName} uni-button--${size.cssName}${shape}",
      disabled -> disabledState,
      data("slot") -> "button",
      AgentRegistry.attach(Button.binding),
      label.map(l => ariaLabel -> l).getOrElse(DomNode.empty),
      // A listener, so an app's own `onclick` modifier runs alongside the action. Browsers drop
      // real clicks on a disabled button, but not ones dispatched from scripts
      EventListenerBinding(
        "click",
        e =>
          e.currentTarget match
            case b: org.scalajs.dom.HTMLButtonElement if b.disabled =>
            case _                                                  =>
              ChangeCause.during(ChangeCause.of(ChangeReason.Press, e))(action())
      ),
      content
    )

end Button

object Button:
  // `new` is required in this file: the `apply(content*)` below replaces the constructor proxy

  private[ui] def binding: AgentBinding =
    new AgentBinding:
      override def kind: String              = "button"
      override def actions: Seq[AgentAction] = Seq(AgentAction.press(this))

  private val noAction: () => Unit = () => ()

  def apply(content: DomNode*): Button =
    new Button(
      content,
      ButtonVariant.Default,
      ButtonSize.Medium,
      Rx.const(false),
      noAction,
      None,
      iconOnly = false
    )

  /** A square, ghost button that shows only an icon; `label` is its accessible name. */
  private[ui] def icon(label: String, icon: DomNode): Button =
    new Button(
      Seq(icon),
      ButtonVariant.Ghost,
      ButtonSize.Medium,
      Rx.const(false),
      noAction,
      Some(label),
      iconOnly = true
    )

end Button

/**
  * A square ghost [[Button]] that shows only an icon. `label` is required: it is the button's
  * accessible name, read by screen readers in place of the icon.
  *
  * {{{
  *   IconButton("Close dock", Icons.close).onClick(() => layout.toggleDock())
  * }}}
  */
object IconButton:
  def apply(label: String, icon: DomNode): Button = Button.icon(label, icon)
