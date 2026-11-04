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

import wvlet.uni.rx.Rx

/**
  * Helper for conditional CSS class application.
  *
  * Usage:
  * {{{
  *   val isActive = Rx.variable(false)
  *
  *   div(
  *     cls := "base",
  *     cls.toggle("active") when isActive,      // reactive
  *     cls.toggle("disabled") when isDisabled   // static
  *   )
  * }}}
  */
case class ClassToggle(className: String):
  /**
    * Apply the class when the condition is true (static).
    */
  infix def when(condition: Boolean): DomNode =
    if condition then
      DomAttribute("class", className, append = true)
    else
      DomNode.empty

  /**
    * Apply the class reactively based on an Rx[Boolean] stream. Uses reactive attribute binding to
    * avoid inserting text node markers.
    */
  infix def when(rx: Rx[Boolean]): DomNode = DomAttribute(
    "class",
    rx.map(cond =>
      if cond then
        className
      else
        ""
    ),
    append = true
  )

end ClassToggle
