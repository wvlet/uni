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
package wvlet.uni.dom

import wvlet.uni.rx.{Cancelable, Rx, RxVar}

/**
  * Validation result for a field or form.
  */
enum ValidationState:
  case Valid
  case Invalid(messages: Seq[String])

  def isValid: Boolean =
    this match
      case Valid =>
        true
      case Invalid(_) =>
        false

  def errors: Seq[String] =
    this match
      case Valid =>
        Seq.empty
      case Invalid(messages) =>
        messages

object ValidationState:
  /**
    * Combine multiple validation states into one. If all are valid, returns Valid. Otherwise,
    * returns Invalid with all errors collected.
    */
  def combine(states: Seq[ValidationState]): ValidationState =
    val allErrors = states.flatMap(_.errors)
    if allErrors.isEmpty then
      Valid
    else
      Invalid(allErrors)

/**
  * A single validation rule that checks a value of type A.
  */
trait ValidationRule[A]:
  def validate(value: A): ValidationState

/**
  * Reactive validation for a single field.
  *
  * Validates the source value whenever it changes and exposes the validation state as reactive
  * values.
  *
  * @param source
  *   The reactive value to validate
  * @param rules
  *   The validation rules to apply
  */
class FieldValidation[A](source: Rx[A], rules: Seq[ValidationRule[A]]):
  // Track the last value for imperative validation
  @volatile
  private var lastValue: Option[A] = None

  private def runRules(value: A): ValidationState =
    lastValue = Some(value)
    ValidationState.combine(rules.map(_.validate(value)))

  val state: Rx[ValidationState] = source.map(runRules)
  val isValid: Rx[Boolean]       = state.map(_.isValid)
  val errors: Rx[Seq[String]]    = state.map(_.errors)

  /**
    * Imperatively check the current value against all rules.
    */
  def validateNow(): ValidationState =
    lastValue match
      case Some(v) =>
        runRules(v)
      case None =>
        ValidationState.Invalid(Seq("No value available"))

/**
  * Aggregate validation for multiple fields.
  *
  * Combines the validation state of all fields into a single reactive result.
  *
  * @param fields
  *   The field validations to aggregate
  */
class FormValidation(fields: Seq[FieldValidation[?]]):
  val isValid: Rx[Boolean] =
    fields.size match
      case 0 =>
        Rx.variable(true)
      case 1 =>
        fields(0).isValid
      case n =>
        // Chain pairwise joins for any number of fields
        val first: Rx[Boolean] = fields(0).isValid
        fields
          .tail
          .foldLeft(first) { (acc, field) =>
            acc
              .join(field.isValid)
              .map { case (a, b) =>
                a && b
              }
          }

  val errors: Rx[Seq[String]] =
    fields.size match
      case 0 =>
        Rx.variable(Seq.empty)
      case 1 =>
        fields(0).errors
      case n =>
        val first: Rx[Seq[String]] = fields(0).errors
        fields
          .tail
          .foldLeft(first) { (acc, field) =>
            acc
              .join(field.errors)
              .map { case (a, b) =>
                a ++ b
              }
          }

  /**
    * Imperatively check all fields and return whether the form is valid.
    */
  def validateAll(): Boolean = fields.forall(_.validateNow().isValid)

end FormValidation

/**
  * Entry point for creating field and form validations, plus built-in validators.
  *
  * Usage:
  * {{{
  *   val username = Rx.variable("")
  *   val usernameV = Validate(username)(
  *     Validate.required("Username is required"),
  *     Validate.minLength(3, "At least 3 characters")
  *   )
  *
  *   val emailV = Validate(email)(
  *     Validate.required(),
  *     Validate.email()
  *   )
  *
  *   val formV = Validate.form(usernameV, emailV)
  * }}}
  */
object Validate:

  /**
    * Create a field validation for a reactive source with the given rules.
    */
  def apply[A](source: Rx[A])(rules: ValidationRule[A]*): FieldValidation[A] = FieldValidation(
    source,
    rules.toSeq
  )

  /**
    * Create a form-level validation that aggregates multiple field validations.
    */
  def form(fields: FieldValidation[?]*): FormValidation = FormValidation(fields.toSeq)

  /**
    * Validates that a string is non-empty.
    */
  def required(message: String = "Required"): ValidationRule[String] = rule(_.nonEmpty, message)

  /**
    * Validates that a string has at least `n` characters.
    */
  def minLength(n: Int, message: String = ""): ValidationRule[String] =
    val msg =
      if message.nonEmpty then
        message
      else
        s"Must be at least ${n} characters"
    rule(_.length >= n, msg)

  /**
    * Validates that a string has at most `n` characters.
    */
  def maxLength(n: Int, message: String = ""): ValidationRule[String] =
    val msg =
      if message.nonEmpty then
        message
      else
        s"Must be at most ${n} characters"
    rule(_.length <= n, msg)

  /**
    * Validates that a string matches a regex pattern.
    */
  def pattern(regex: String, message: String = ""): ValidationRule[String] =
    val msg =
      if message.nonEmpty then
        message
      else
        s"Must match pattern ${regex}"
    rule(_.matches(regex), msg)

  /**
    * Validates that a string looks like an email address.
    */
  def email(message: String = "Invalid email address"): ValidationRule[String] = pattern(
    "^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$",
    message
  )

  /**
    * Create a validation rule from a predicate.
    */
  def rule[A](predicate: A => Boolean, message: String): ValidationRule[A] =
    (value: A) =>
      if predicate(value) then
        ValidationState.Valid
      else
        ValidationState.Invalid(Seq(message))

  /**
    * Create a validation rule from a function that returns a ValidationState.
    */
  def ruleWith[A](f: A => ValidationState): ValidationRule[A] = (value: A) => f(value)

end Validate
