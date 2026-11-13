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
package wvlet.uni.ui.base

import org.scalajs.dom

/**
  * Reading direction for horizontal keys. In a right-to-left layout, ArrowLeft moves toward the end
  * and ArrowRight toward the start, as the WAI-ARIA Authoring Practices require.
  */
object TextDirection:

  /** Whether `el` is laid out right to left, by `dir` on it or an ancestor, or by CSS. */
  def isRtl(el: dom.Element): Boolean = dom.window.getComputedStyle(el).direction == "rtl"

  /** Whether the target of `e` is laid out right to left; false when it is not an element. */
  def isRtl(e: dom.Event): Boolean =
    e.target match
      case el: dom.Element =>
        isRtl(el)
      case _ =>
        false

  /**
    * The key as a left-to-right layout reads it: with `rtl`, ArrowLeft and ArrowRight swap, so
    * "ArrowRight" always means toward the end. Other keys are returned as is.
    */
  def logicalKey(key: String, rtl: Boolean): String =
    if !rtl then
      key
    else
      key match
        case "ArrowLeft" =>
          "ArrowRight"
        case "ArrowRight" =>
          "ArrowLeft"
        case other =>
          other

end TextDirection
