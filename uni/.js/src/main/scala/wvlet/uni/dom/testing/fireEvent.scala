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
package wvlet.uni.dom.testing

import org.scalajs.dom
import scala.scalajs.js

/**
  * Simulate DOM events for UI testing, modeled on Testing Library's `fireEvent`.
  *
  * Events are dispatched with `bubbles = true` and `cancelable = true` so they propagate to
  * delegated handlers the way real user input does. Each method returns the boolean result of
  * `dispatchEvent` (false if a handler called `preventDefault`).
  *
  * Example:
  * {{{
  *   import wvlet.uni.dom.testing.*
  *   val m = mount(MyCounter())
  *   fireEvent.click(m.getByText("+1"))
  *   m.text shouldContain "count=1"
  * }}}
  */
object fireEvent:

  /** Dispatch an already-constructed event to the target. */
  def dispatch(target: dom.EventTarget, event: dom.Event): Boolean = target.dispatchEvent(event)

  // --- Mouse / pointer ---

  def click(target: dom.EventTarget): Boolean       = dispatch(target, mouseEvent("click"))
  def dblclick(target: dom.EventTarget): Boolean    = dispatch(target, mouseEvent("dblclick"))
  def mouseDown(target: dom.EventTarget): Boolean   = dispatch(target, mouseEvent("mousedown"))
  def mouseUp(target: dom.EventTarget): Boolean     = dispatch(target, mouseEvent("mouseup"))
  def mouseOver(target: dom.EventTarget): Boolean   = dispatch(target, mouseEvent("mouseover"))
  def mouseOut(target: dom.EventTarget): Boolean    = dispatch(target, mouseEvent("mouseout"))
  def mouseMove(target: dom.EventTarget): Boolean   = dispatch(target, mouseEvent("mousemove"))
  def contextMenu(target: dom.EventTarget): Boolean = dispatch(target, mouseEvent("contextmenu"))

  private def mouseEvent(eventType: String): dom.MouseEvent =
    val init = js
      .Dynamic
      .literal(bubbles = true, cancelable = true)
      .asInstanceOf[dom.MouseEventInit]
    new dom.MouseEvent(eventType, init)

  // --- Form input ---

  /**
    * Set the `value` of an input/textarea/select and dispatch an `input` event, mirroring a user
    * typing into the field. Use this for elements bound with `value <-> rxVar` two-way bindings.
    */
  def input(target: dom.Element, value: String): Boolean =
    setValue(target, value)
    dispatch(target, simpleEvent("input"))

  /**
    * Set the `value` and dispatch a `change` event (fired by `<select>` and on blur for text
    * inputs).
    */
  def change(target: dom.Element, value: String): Boolean =
    setValue(target, value)
    dispatch(target, simpleEvent("change"))

  /** Toggle a checkbox/radio `checked` state and dispatch `input`+`change`. */
  def setChecked(target: dom.Element, checked: Boolean): Boolean =
    target match
      case in: dom.html.Input =>
        in.checked = checked
      case _ =>
    dispatch(target, simpleEvent("input"))
    dispatch(target, simpleEvent("change"))

  def submit(target: dom.EventTarget): Boolean = dispatch(target, simpleEvent("submit"))

  private def setValue(target: dom.Element, value: String): Unit =
    target match
      case in: dom.html.Input =>
        in.value = value
      case ta: dom.html.TextArea =>
        ta.value = value
      case se: dom.html.Select =>
        se.value = value
      case _ =>
        target.asInstanceOf[js.Dynamic].value = value

  // --- Focus ---

  def focus(target: dom.EventTarget): Boolean = dispatch(target, focusEvent("focus"))
  def blur(target: dom.EventTarget): Boolean  = dispatch(target, focusEvent("blur"))

  private def focusEvent(eventType: String): dom.FocusEvent =
    // FocusEvent does not bubble per the spec, but focusin/focusout do. Keep bubbles=false here so
    // tests reflect real focus semantics; handlers registered with `onfocus` still fire.
    val init = js
      .Dynamic
      .literal(bubbles = false, cancelable = false)
      .asInstanceOf[dom.FocusEventInit]
    new dom.FocusEvent(eventType, init)

  // --- Keyboard ---

  def keyDown(
      target: dom.EventTarget,
      key: String,
      code: String = "",
      ctrlKey: Boolean = false,
      shiftKey: Boolean = false,
      altKey: Boolean = false,
      metaKey: Boolean = false
  ): Boolean = dispatch(
    target,
    keyboardEvent("keydown", key, code, ctrlKey, shiftKey, altKey, metaKey)
  )

  def keyUp(
      target: dom.EventTarget,
      key: String,
      code: String = "",
      ctrlKey: Boolean = false,
      shiftKey: Boolean = false,
      altKey: Boolean = false,
      metaKey: Boolean = false
  ): Boolean = dispatch(
    target,
    keyboardEvent("keyup", key, code, ctrlKey, shiftKey, altKey, metaKey)
  )

  def keyPress(
      target: dom.EventTarget,
      key: String,
      code: String = "",
      ctrlKey: Boolean = false,
      shiftKey: Boolean = false,
      altKey: Boolean = false,
      metaKey: Boolean = false
  ): Boolean = dispatch(
    target,
    keyboardEvent("keypress", key, code, ctrlKey, shiftKey, altKey, metaKey)
  )

  private def keyboardEvent(
      eventType: String,
      key: String,
      code: String,
      ctrlKey: Boolean,
      shiftKey: Boolean,
      altKey: Boolean,
      metaKey: Boolean
  ): dom.KeyboardEvent =
    val init = js
      .Dynamic
      .literal(
        bubbles = true,
        cancelable = true,
        key = key,
        code =
          if code.isEmpty then
            key
          else
            code
        ,
        ctrlKey = ctrlKey,
        shiftKey = shiftKey,
        altKey = altKey,
        metaKey = metaKey
      )
      .asInstanceOf[dom.KeyboardEventInit]
    new dom.KeyboardEvent(eventType, init)

  // --- Generic / custom ---

  /** Dispatch a bubbling, cancelable `dom.Event` of the given type. */
  def custom(target: dom.EventTarget, eventType: String): Boolean = dispatch(
    target,
    simpleEvent(eventType)
  )

  private def simpleEvent(eventType: String): dom.Event =
    val init = js.Dynamic.literal(bubbles = true, cancelable = true).asInstanceOf[dom.EventInit]
    new dom.Event(eventType, init)

end fireEvent
