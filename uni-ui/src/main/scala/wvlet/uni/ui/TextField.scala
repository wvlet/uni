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

import scala.language.implicitConversions

/** Attributes shared by [[Input]] and [[Textarea]]. */
private[ui] def fieldModifiers(
    placeholderText: Option[String],
    label: Option[String],
    invalid: Rx[Boolean],
    disabledState: Rx[Boolean]
): Seq[DomNode] = Seq(
  placeholderText.map(p => placeholder -> p).getOrElse(DomNode.empty),
  label.map(l => ariaLabel -> l).getOrElse(DomNode.empty),
  ariaInvalid -> invalid.map(_.toString),
  disabled    -> disabledState
)

/**
  * Binds a text field to `text` through the change gate. Typing writes `text` once `onChange`
  * accepts it; a refused edit puts the field back. During an IME composition the text stays a draft
  * in the field and goes through `onChange` once the composition ends.
  */
private class GatedText(text: RxVar[String], handler: () => ChangeHandler):
  private var composing = false
  private val gated     = GatedVar(
    text,
    "value",
    handler,
    () => ChangeCause.ambientOr(ChangeCause(ChangeReason.Input, ChangeSource.Keyboard)),
    () => composing
  )

  private def restore(e: dom.Event): Unit =
    e.currentTarget match
      case f: dom.HTMLInputElement if f.value != gated.get =>
        f.value = gated.get
      case f: dom.HTMLTextAreaElement if f.value != gated.get =>
        f.value = gated.get
      case _ =>

  def modifiers: Seq[DomNode] = Seq(
    value.bind(gated),
    gated.mirror,
    EventListenerBinding("compositionstart", _ => composing = true),
    EventListenerBinding(
      "compositionend",
      e =>
        composing = false
        if !gated.commit() then
          restore(e)
    ),
    // Runs after the binding's own listener: put back an edit that onChange refused
    EventListenerBinding(
      "input",
      e =>
        if !composing then
          restore(e)
    )
  )

end GatedText

/**
  * A one-line text field bound to `text`: typing updates the variable, and setting the variable
  * updates the field.
  *
  * {{{
  *   val query = Rx.variable("")
  *   Input(query).withPlaceholder("Search chats").withLabel("Search")
  * }}}
  *
  * @param inputType
  *   the `type` attribute, such as `search`, `email`, or `password`
  */
class Input(
    text: RxVar[String],
    inputType: String = "text",
    placeholderText: Option[String] = None,
    label: Option[String] = None,
    invalid: Rx[Boolean] = Rx.const(false),
    disabledState: Rx[Boolean] = Rx.const(false),
    changeHandler: ChangeHandler = ChangeGate.noHandler
) extends RxElement:
  private def copy(
      inputType: String = inputType,
      placeholderText: Option[String] = placeholderText,
      label: Option[String] = label,
      invalid: Rx[Boolean] = invalid,
      disabledState: Rx[Boolean] = disabledState,
      changeHandler: ChangeHandler = changeHandler
  ): Input = Input(text, inputType, placeholderText, label, invalid, disabledState, changeHandler)

  /** Called before typed text reaches `text`; `cancel()` puts the field back. Key: `value`. */
  def onChange(f: ChangeHandler): Input = copy(changeHandler = f)

  def withType(t: String): Input = copy(inputType = t)

  def withPlaceholder(p: String): Input = copy(placeholderText = Some(p))
  def noPlaceholder(): Input            = copy(placeholderText = None)

  /** The accessible name, for a field without a visible `<label>`. */
  def withLabel(l: String): Input = copy(label = Some(l))
  def noLabel(): Input            = copy(label = None)

  /** Mark the field invalid: sets `aria-invalid`, which `uni-ui.css` shows with a red border. */
  def withInvalid(i: Rx[Boolean]): Input  = copy(invalid = i)
  def withDisabled(d: Rx[Boolean]): Input = copy(disabledState = d)
  def withDisabled(d: Boolean): Input     = withDisabled(Rx.const(d))

  override def render: RxElement = input(
    tpe          -> inputType,
    cls          -> "uni-input",
    data("slot") -> "input",
    GatedText(text, () => changeHandler).modifiers,
    fieldModifiers(placeholderText, label, invalid, disabledState)
  )

end Input

/**
  * A multi-line text field bound to `text`.
  *
  * {{{
  *   Textarea(draft).withRows(4).withPlaceholder("Write a message")
  * }}}
  */
class Textarea(
    text: RxVar[String],
    rowCount: Int = 3,
    placeholderText: Option[String] = None,
    label: Option[String] = None,
    invalid: Rx[Boolean] = Rx.const(false),
    disabledState: Rx[Boolean] = Rx.const(false),
    changeHandler: ChangeHandler = ChangeGate.noHandler
) extends RxElement:
  private def copy(
      rowCount: Int = rowCount,
      placeholderText: Option[String] = placeholderText,
      label: Option[String] = label,
      invalid: Rx[Boolean] = invalid,
      disabledState: Rx[Boolean] = disabledState,
      changeHandler: ChangeHandler = changeHandler
  ): Textarea = Textarea(
    text,
    rowCount,
    placeholderText,
    label,
    invalid,
    disabledState,
    changeHandler
  )

  /** Called before typed text reaches `text`; `cancel()` puts the field back. Key: `value`. */
  def onChange(f: ChangeHandler): Textarea = copy(changeHandler = f)

  def withRows(n: Int): Textarea = copy(rowCount = n)

  def withPlaceholder(p: String): Textarea = copy(placeholderText = Some(p))
  def noPlaceholder(): Textarea            = copy(placeholderText = None)

  def withLabel(l: String): Textarea = copy(label = Some(l))
  def noLabel(): Textarea            = copy(label = None)

  def withInvalid(i: Rx[Boolean]): Textarea  = copy(invalid = i)
  def withDisabled(d: Rx[Boolean]): Textarea = copy(disabledState = d)
  def withDisabled(d: Boolean): Textarea     = withDisabled(Rx.const(d))

  override def render: RxElement = textarea(
    cls          -> "uni-textarea",
    data("slot") -> "textarea",
    rows         -> rowCount.toString,
    GatedText(text, () => changeHandler).modifiers,
    fieldModifiers(placeholderText, label, invalid, disabledState)
  )

end Textarea
