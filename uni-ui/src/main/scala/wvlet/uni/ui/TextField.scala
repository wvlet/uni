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
import wvlet.uni.rx.{Rx, RxVar}

import scala.language.implicitConversions

/** Options shared by [[Input]] and [[Textarea]]. */
private[ui] case class FieldOptions(
    placeholderText: Option[String] = None,
    label: Option[String] = None,
    invalid: Rx[Boolean] = Rx.const(false),
    disabledState: Rx[Boolean] = Rx.const(false)
):
  def modifiers: Seq[DomNode] = Seq(
    placeholderText.map(p => placeholder -> p).getOrElse(DomNode.empty),
    label.map(l => ariaLabel -> l).getOrElse(DomNode.empty),
    ariaInvalid -> invalid.map(_.toString),
    disabled    -> disabledState
  )

/**
  * A one-line text field bound to `text`: typing updates the variable, and setting the variable
  * updates the field.
  *
  * {{{
  *   val query = Rx.variable("")
  *   Input(query).withPlaceholder("Search chats").withLabel("Search")
  * }}}
  */
class Input private (text: RxVar[String], inputType: String, options: FieldOptions)
    extends RxElement:
  private def copy(inputType: String = inputType, options: FieldOptions = options): Input =
    new Input(text, inputType, options)

  /** The `type` attribute, such as `search`, `email`, or `password` (default: `text`). */
  def withType(t: String): Input = copy(inputType = t)

  def withPlaceholder(p: String): Input = copy(options = options.copy(placeholderText = Some(p)))
  def noPlaceholder(): Input            = copy(options = options.copy(placeholderText = None))

  /** The accessible name, for a field without a visible `<label>`. */
  def withLabel(l: String): Input = copy(options = options.copy(label = Some(l)))
  def noLabel(): Input            = copy(options = options.copy(label = None))

  /** Mark the field invalid: sets `aria-invalid`, which `uni-ui.css` shows with a red border. */
  def withInvalid(i: Rx[Boolean]): Input  = copy(options = options.copy(invalid = i))
  def withDisabled(d: Rx[Boolean]): Input = copy(options = options.copy(disabledState = d))
  def withDisabled(d: Boolean): Input     = withDisabled(Rx.const(d))

  override def render: RxElement = input(
    tpe -> inputType,
    cls -> "uni-input",
    value.bind(text),
    options.modifiers
  )

end Input

object Input:
  def apply(text: RxVar[String]): Input = new Input(text, "text", FieldOptions())

/**
  * A multi-line text field bound to `text`.
  *
  * {{{
  *   Textarea(draft).withRows(4).withPlaceholder("Write a message")
  * }}}
  */
class Textarea private (text: RxVar[String], rowCount: Int, options: FieldOptions)
    extends RxElement:
  private def copy(rowCount: Int = rowCount, options: FieldOptions = options): Textarea =
    new Textarea(text, rowCount, options)

  def withRows(n: Int): Textarea = copy(rowCount = n)

  def withPlaceholder(p: String): Textarea = copy(options = options.copy(placeholderText = Some(p)))
  def noPlaceholder(): Textarea            = copy(options = options.copy(placeholderText = None))

  def withLabel(l: String): Textarea = copy(options = options.copy(label = Some(l)))
  def noLabel(): Textarea            = copy(options = options.copy(label = None))

  def withInvalid(i: Rx[Boolean]): Textarea  = copy(options = options.copy(invalid = i))
  def withDisabled(d: Rx[Boolean]): Textarea = copy(options = options.copy(disabledState = d))
  def withDisabled(d: Boolean): Textarea     = withDisabled(Rx.const(d))

  override def render: RxElement = textarea(
    cls -> "uni-textarea",
    wvlet.uni.dom.HtmlTags.attr("rows")(rowCount.toString),
    value.bind(text),
    options.modifiers
  )

end Textarea

object Textarea:
  def apply(text: RxVar[String]): Textarea = new Textarea(text, 3, FieldOptions())
