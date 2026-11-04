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

import wvlet.uni.rx.RxVar

/**
  * Two-way binding for string values (text inputs, textareas, selects).
  *
  * Usage:
  * {{{
  *   val username = Rx.variable("")
  *   input(tpe -> "text", value.bind(username))
  * }}}
  *
  * @param variable
  *   The RxVar to sync with the DOM element's value property
  * @param useChangeEvent
  *   If true, use "change" event instead of "input" event. Useful for select elements.
  */
case class ValueBinding(variable: RxVar[String], useChangeEvent: Boolean = false) extends DomNode

/**
  * Two-way binding for boolean values (checkboxes).
  *
  * Usage:
  * {{{
  *   val isChecked = Rx.variable(false)
  *   input(tpe -> "checkbox", checked.bind(isChecked))
  * }}}
  *
  * @param variable
  *   The RxVar to sync with the DOM element's checked property
  */
case class CheckedBinding(variable: RxVar[Boolean]) extends DomNode
