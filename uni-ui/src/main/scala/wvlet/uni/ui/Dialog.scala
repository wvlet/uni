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
import wvlet.uni.rx.RxVar
import wvlet.uni.ui.base.*

import scala.language.implicitConversions

/**
  * A modal dialog over a backdrop, shown while `open` is true. Tab and Shift+Tab stay inside it;
  * Escape, a click on the backdrop, or `open := false` closes it, and focus returns to where it was
  * before it opened.
  *
  * {{{
  *   val open = Rx.variable(false)
  *   Dialog(open)(
  *     Input(name).withLabel("Name")
  *   ).withTitle("Rename session")
  *     .withActions(Button("Cancel").onClick(() => open := false), Button("Save").primary.onClick(save))
  * }}}
  *
  * On open, focus goes to the first element with the `autofocus` attribute, else the first
  * focusable element, else the dialog itself. The panel is `div.uni-dialog` with `role="dialog"`
  * and `aria-modal="true"`, inside `div.uni-dialog-backdrop`, both rendered at the end of `<body>`.
  * The title and description label the dialog through `aria-labelledby` and `aria-describedby`.
  */
class Dialog private (
    open: RxVar[Boolean],
    content: Seq[DomNode],
    title: Option[String],
    description: Option[String],
    actions: Seq[DomNode],
    dismissOnBackdrop: Boolean,
    dialogRole: String,
    onClose: () => Unit,
    variant: Option[String] = None,
    accessibleName: Option[String] = None
) extends RxElement:

  private val baseId = Ids.next("uni-dialog")

  private def copy(
      title: Option[String] = title,
      description: Option[String] = description,
      actions: Seq[DomNode] = actions,
      dismissOnBackdrop: Boolean = dismissOnBackdrop,
      dialogRole: String = dialogRole,
      onClose: () => Unit = onClose,
      variant: Option[String] = variant,
      accessibleName: Option[String] = accessibleName
  ): Dialog =
    new Dialog(
      open,
      content,
      title,
      description,
      actions,
      dismissOnBackdrop,
      dialogRole,
      onClose,
      variant,
      accessibleName
    )

  /** The heading, which is also the dialog's accessible name. */
  def withTitle(t: String): Dialog = copy(title = Some(t))
  def noTitle(): Dialog            = copy(title = None)

  /** A line under the title that screen readers announce with the name. */
  def withDescription(d: String): Dialog = copy(description = Some(d))
  def noDescription(): Dialog            = copy(description = None)

  /** Buttons for the footer row, aligned to the end. */
  def withActions(xs: DomNode*): Dialog = copy(actions = xs)
  def noActions(): Dialog               = copy(actions = Seq.empty)

  /** Whether a click on the backdrop closes the dialog (on by default). */
  def withDismissOnBackdrop(b: Boolean): Dialog = copy(dismissOnBackdrop = b)
  def noDismissOnBackdrop(): Dialog             = withDismissOnBackdrop(false)

  private[ui] def withRole(r: String): Dialog = copy(dialogRole = r)

  // A `--<v>` modifier on the panel and the backdrop, for components built on Dialog
  private[ui] def withVariant(v: String): Dialog = copy(variant = Some(v))

  // An accessible name for a dialog without a visible title
  private[ui] def withAccessibleName(n: String): Dialog = copy(accessibleName = Some(n))

  private def classes(base: String): String = variant.fold(base)(v => s"${base} ${base}--${v}")

  /** Runs when Escape or the backdrop closes the dialog, before `open` turns false. */
  def onDismiss(f: () => Unit): Dialog = copy(onClose = f)

  private def titleId       = s"${baseId}-title"
  private def descriptionId = s"${baseId}-description"

  private def dismiss(): Unit =
    onClose()
    open := false

  private def panel(el: dom.HTMLElement): Option[dom.HTMLElement] =
    el.querySelector(".uni-dialog") match
      case p: dom.HTMLElement =>
        Some(p)
      case _ =>
        None

  private def surface: RxElement = OverlaySurface(
    div(
      cls           -> classes("uni-dialog-backdrop"),
      data("state") -> "open",
      div(
        id   -> baseId,
        cls  -> classes("uni-dialog"),
        role -> dialogRole,
        accessibleName.map(n => ariaLabel -> n).getOrElse(DomNode.empty),
        ariaModal     -> "true",
        tabindex      -> "-1",
        data("state") -> "open",
        title.map(_ => ariaLabelledby -> titleId).getOrElse(DomNode.empty),
        description.map(_ => ariaDescribedby -> descriptionId).getOrElse(DomNode.empty),
        EventListenerBinding(
          "keydown",
          e =>
            e.currentTarget match
              case p: dom.Element =>
                FocusTrap.onKeyDown(p, e.asInstanceOf[dom.KeyboardEvent])
              case _ =>
        ),
        title.map(t => h2(id -> titleId, cls -> "uni-dialog__title", t)).getOrElse(DomNode.empty),
        description
          .map(d => p(id -> descriptionId, cls -> "uni-dialog__description", d))
          .getOrElse(DomNode.empty),
        div(cls -> "uni-dialog__body", content),
        if actions.isEmpty then
          DomNode.empty
        else
          div(cls -> "uni-dialog__actions", actions)
      )
    ),
    anchor = () => None,
    // The backdrop is outside: only the panel counts as inside
    inside = el => panel(el).toSeq,
    side = Side.Bottom,
    align = Align.Center,
    offset = 0,
    modal = true,
    dismissOnOutside = dismissOnBackdrop,
    onDismiss = () => dismiss(),
    initialFocus =
      el =>
        panel(el).flatMap { p =>
          p.querySelector("[autofocus]") match
            case a: dom.HTMLElement =>
              Some(a)
            case _ =>
              FocusTrap.first(p).orElse(Some(p))
        },
    returnFocus = true
  )

  // Renders nothing in place: the dialog lives at the end of <body> while open
  override def render: RxElement = Embedded(
    open.map { o =>
      if o then
        Portal.toBody(surface)
      else
        DomNode.empty
    }
  )

end Dialog

object Dialog:
  // `new` is required in this file: the `apply` below replaces the constructor proxy
  private val noop: () => Unit = () => ()

  def apply(open: RxVar[Boolean])(content: DomNode*): Dialog =
    new Dialog(
      open,
      content,
      title = None,
      description = None,
      actions = Seq.empty,
      dismissOnBackdrop = true,
      dialogRole = "dialog",
      onClose = noop
    )

/**
  * A dialog that asks to confirm or cancel an action, such as deleting something. It uses
  * `role="alertdialog"`, focuses Cancel first so Enter never confirms by accident, and stays open
  * on a backdrop click. Escape cancels.
  *
  * {{{
  *   AlertDialog(confirmDelete)("Delete this session?")
  *     .withDescription("Its messages can't be restored.")
  *     .withConfirmLabel("Delete")
  *     .danger
  *     .onConfirm(() => store.delete(id))
  * }}}
  */
class AlertDialog private (
    open: RxVar[Boolean],
    title: String,
    description: Option[String],
    confirmLabel: String,
    cancelLabel: String,
    destructive: Boolean,
    confirm: () => Unit,
    cancel: () => Unit
) extends RxElement:

  private def copy(
      description: Option[String] = description,
      confirmLabel: String = confirmLabel,
      cancelLabel: String = cancelLabel,
      destructive: Boolean = destructive,
      confirm: () => Unit = confirm,
      cancel: () => Unit = cancel
  ): AlertDialog =
    new AlertDialog(
      open,
      title,
      description,
      confirmLabel,
      cancelLabel,
      destructive,
      confirm,
      cancel
    )

  def withDescription(d: String): AlertDialog = copy(description = Some(d))
  def noDescription(): AlertDialog            = copy(description = None)

  def withConfirmLabel(l: String): AlertDialog = copy(confirmLabel = l)
  def withCancelLabel(l: String): AlertDialog  = copy(cancelLabel = l)

  /** Style the confirm button as destructive. */
  def danger: AlertDialog = copy(destructive = true)

  /** Runs on the confirm button, after which the dialog closes. */
  def onConfirm(f: () => Unit): AlertDialog = copy(confirm = f)

  /** Runs on the cancel button or Escape, after which the dialog closes. */
  def onCancel(f: () => Unit): AlertDialog = copy(cancel = f)

  override def render: RxElement =
    val confirmButton =
      val b = Button(confirmLabel).onClick { () =>
        confirm()
        open := false
      }
      if destructive then
        b.danger
      else
        b.primary
    val dialog = Dialog(open)()
      .withTitle(title)
      .withActions(
        Button(cancelLabel).onClick { () =>
          cancel()
          open := false
        }(autofocus -> true),
        confirmButton
      )
      .noDismissOnBackdrop()
      .withRole("alertdialog")
      .onDismiss(cancel)
    description.fold(dialog)(dialog.withDescription)

end AlertDialog

object AlertDialog:
  // `new` is required in this file: the `apply` below replaces the constructor proxy
  private val noop: () => Unit = () => ()

  def apply(open: RxVar[Boolean])(title: String): AlertDialog =
    new AlertDialog(open, title, None, "Confirm", "Cancel", false, noop, noop)
