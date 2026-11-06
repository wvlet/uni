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

import wvlet.uni.test.UniTest
import wvlet.uni.dom.all.*
import wvlet.uni.dom.all.given
import wvlet.uni.rx.Rx

class ValidateTest extends UniTest:

  // --- Individual validators ---

  test("required rejects empty string") {
    val rule   = Validate.required("Name is required")
    val result = rule.validate("")
    result.isValid shouldBe false
    result.errors shouldBe Seq("Name is required")
  }

  test("required accepts non-empty string") {
    val rule   = Validate.required()
    val result = rule.validate("hello")
    result.isValid shouldBe true
    result.errors shouldBe Seq.empty
  }

  test("minLength rejects short string") {
    val rule   = Validate.minLength(3, "Too short")
    val result = rule.validate("ab")
    result.isValid shouldBe false
    result.errors shouldBe Seq("Too short")
  }

  test("minLength accepts string of exact length") {
    val rule   = Validate.minLength(3)
    val result = rule.validate("abc")
    result.isValid shouldBe true
  }

  test("maxLength rejects long string") {
    val rule   = Validate.maxLength(5, "Too long")
    val result = rule.validate("abcdef")
    result.isValid shouldBe false
    result.errors shouldBe Seq("Too long")
  }

  test("maxLength accepts string within limit") {
    val rule   = Validate.maxLength(5)
    val result = rule.validate("abc")
    result.isValid shouldBe true
  }

  test("pattern validates against regex") {
    val rule = Validate.pattern("[a-z]+", "Only lowercase")
    rule.validate("hello").isValid shouldBe true
    rule.validate("Hello").isValid shouldBe false
    rule.validate("123").isValid shouldBe false
  }

  test("email validates email format") {
    val rule = Validate.email()
    rule.validate("user@example.com").isValid shouldBe true
    rule.validate("user@sub.example.com").isValid shouldBe true
    rule.validate("not-an-email").isValid shouldBe false
    rule.validate("@missing-local.com").isValid shouldBe false
    rule.validate("missing-domain@").isValid shouldBe false
    rule.validate("has spaces@example.com").isValid shouldBe false
  }

  test("custom rule with predicate") {
    val rule = Validate.rule[Int](_ > 0, "Must be positive")
    rule.validate(5).isValid shouldBe true
    rule.validate(0).isValid shouldBe false
    rule.validate(-1).isValid shouldBe false
  }

  test("custom ruleWith returns ValidationState directly") {
    val rule = Validate.ruleWith[Int] { v =>
      if v >= 0 && v <= 100 then
        ValidationState.Valid
      else
        ValidationState.Invalid(Seq("Must be 0-100"))
    }
    rule.validate(50).isValid shouldBe true
    rule.validate(101).isValid shouldBe false
  }

  // --- ValidationState ---

  test("ValidationState.combine merges multiple states") {
    val combined = ValidationState.combine(
      Seq(
        ValidationState.Valid,
        ValidationState.Invalid(Seq("error1")),
        ValidationState.Valid,
        ValidationState.Invalid(Seq("error2", "error3"))
      )
    )
    combined.isValid shouldBe false
    combined.errors shouldBe Seq("error1", "error2", "error3")
  }

  test("ValidationState.combine returns Valid when all valid") {
    val combined = ValidationState.combine(Seq(ValidationState.Valid, ValidationState.Valid))
    combined.isValid shouldBe true
    combined.errors shouldBe Seq.empty
  }

  // --- FieldValidation ---

  test("FieldValidation reacts to RxVar changes") {
    val name  = Rx.variable("")
    val nameV = Validate(name)(Validate.required("Required"))

    var currentState: ValidationState = ValidationState.Valid
    val cancelable                    = nameV
      .state
      .run { s =>
        currentState = s
      }

    // Initial empty value should be invalid
    currentState.isValid shouldBe false

    name := "hello"
    currentState.isValid shouldBe true

    name := ""
    currentState.isValid shouldBe false

    cancelable.cancel
  }

  test("FieldValidation with multiple rules collects all errors") {
    val input  = Rx.variable("")
    val inputV = Validate(input)(Validate.required("Required"), Validate.minLength(3, "Too short"))

    var currentErrors: Seq[String] = Seq.empty
    val cancelable                 = inputV
      .errors
      .run { e =>
        currentErrors = e
      }

    // Empty string fails both rules
    currentErrors shouldBe Seq("Required", "Too short")

    // Short but non-empty string fails minLength only
    input := "ab"
    currentErrors shouldBe Seq("Too short")

    // Valid string
    input := "abc"
    currentErrors shouldBe Seq.empty

    cancelable.cancel
  }

  test("FieldValidation.isValid reflects current state") {
    val value  = Rx.variable(0)
    val valueV = Validate(value)(Validate.rule(_ > 0, "Must be positive"))

    var valid      = true
    val cancelable = valueV
      .isValid
      .run { v =>
        valid = v
      }

    valid shouldBe false

    value := 5
    valid shouldBe true

    value := -1
    valid shouldBe false

    cancelable.cancel
  }

  test("FieldValidation.validateNow returns current validity") {
    val input  = Rx.variable("hello")
    val inputV = Validate(input)(Validate.required())

    // Subscribe to activate the reactive chain
    val cancelable = inputV
      .state
      .run { _ =>
        ()
      }

    inputV.validateNow().isValid shouldBe true

    input := ""
    inputV.validateNow().isValid shouldBe false

    cancelable.cancel
  }

  // --- FormValidation ---

  test("FormValidation.isValid combines all fields") {
    val name  = Rx.variable("Alice")
    val email = Rx.variable("alice@example.com")

    val nameV  = Validate(name)(Validate.required())
    val emailV = Validate(email)(Validate.required(), Validate.email())
    val formV  = Validate.form(nameV, emailV)

    var formValid  = false
    val cancelable = formV
      .isValid
      .run { v =>
        formValid = v
      }

    formValid shouldBe true

    name := ""
    formValid shouldBe false

    name := "Bob"
    formValid shouldBe true

    email := "not-email"
    formValid shouldBe false

    cancelable.cancel
  }

  test("FormValidation.errors aggregates all field errors") {
    val name  = Rx.variable("")
    val email = Rx.variable("")

    val nameV  = Validate(name)(Validate.required("Name required"))
    val emailV = Validate(email)(Validate.required("Email required"))
    val formV  = Validate.form(nameV, emailV)

    var allErrors: Seq[String] = Seq.empty
    val cancelable             = formV
      .errors
      .run { e =>
        allErrors = e
      }

    allErrors shouldContain "Name required"
    allErrors shouldContain "Email required"

    name := "Alice"
    allErrors shouldBe Seq("Email required")

    email := "alice@example.com"
    allErrors shouldBe Seq.empty

    cancelable.cancel
  }

  test("FormValidation.validateAll returns current validity") {
    val name  = Rx.variable("Alice")
    val email = Rx.variable("alice@example.com")

    val nameV  = Validate(name)(Validate.required())
    val emailV = Validate(email)(Validate.required(), Validate.email())
    val formV  = Validate.form(nameV, emailV)

    // Subscribe to activate the reactive chain
    val c1 = nameV
      .state
      .run { _ =>
        ()
      }
    val c2 = emailV
      .state
      .run { _ =>
        ()
      }

    formV.validateAll() shouldBe true

    name := ""
    formV.validateAll() shouldBe false

    c1.cancel
    c2.cancel
  }

  test("FormValidation with empty fields list") {
    val formV = Validate.form()

    var valid      = false
    val cancelable = formV
      .isValid
      .run { v =>
        valid = v
      }

    valid shouldBe true
    formV.validateAll() shouldBe true

    cancelable.cancel
  }

  test("FormValidation with single field") {
    val input  = Rx.variable("test")
    val inputV = Validate(input)(Validate.required())
    val formV  = Validate.form(inputV)

    var valid      = false
    val cancelable = formV
      .isValid
      .run { v =>
        valid = v
      }

    valid shouldBe true

    input := ""
    valid shouldBe false

    cancelable.cancel
  }

end ValidateTest
