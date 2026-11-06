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

import org.scalajs.dom

/**
  * Attaches a listener with `addEventListener`, so it runs alongside an `on*` handler (e.g.
  * `onkeydown`) on the same element instead of replacing it. Used by helpers such as
  * `Keyboard.onEnter` and `PointerDrag`, and by component libraries such as uni-ui.
  *
  * {{{
  *   div(EventListenerBinding("keydown", e => onKey(e.asInstanceOf[dom.KeyboardEvent])))
  * }}}
  */
case class EventListenerBinding(eventName: String, listener: dom.Event => Unit) extends DomNode
