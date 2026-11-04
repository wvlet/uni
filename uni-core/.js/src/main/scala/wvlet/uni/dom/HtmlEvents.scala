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
  * HTML event handler attributes.
  */
trait HtmlEvents:
  import HtmlTags.handler

  // Mouse events
  lazy val onclick: DomEventHandlerOf[dom.MouseEvent]      = handler[dom.MouseEvent]("onclick")
  lazy val ondblclick: DomEventHandlerOf[dom.MouseEvent]   = handler[dom.MouseEvent]("ondblclick")
  lazy val onmousedown: DomEventHandlerOf[dom.MouseEvent]  = handler[dom.MouseEvent]("onmousedown")
  lazy val onmouseup: DomEventHandlerOf[dom.MouseEvent]    = handler[dom.MouseEvent]("onmouseup")
  lazy val onmouseover: DomEventHandlerOf[dom.MouseEvent]  = handler[dom.MouseEvent]("onmouseover")
  lazy val onmouseout: DomEventHandlerOf[dom.MouseEvent]   = handler[dom.MouseEvent]("onmouseout")
  lazy val onmousemove: DomEventHandlerOf[dom.MouseEvent]  = handler[dom.MouseEvent]("onmousemove")
  lazy val onmouseenter: DomEventHandlerOf[dom.MouseEvent] = handler[dom.MouseEvent]("onmouseenter")
  lazy val onmouseleave: DomEventHandlerOf[dom.MouseEvent] = handler[dom.MouseEvent]("onmouseleave")
  lazy val oncontextmenu: DomEventHandlerOf[dom.MouseEvent] = handler[dom.MouseEvent](
    "oncontextmenu"
  )

  // Keyboard events
  lazy val onkeydown: DomEventHandlerOf[dom.KeyboardEvent] = handler[dom.KeyboardEvent]("onkeydown")
  lazy val onkeyup: DomEventHandlerOf[dom.KeyboardEvent]   = handler[dom.KeyboardEvent]("onkeyup")
  lazy val onkeypress: DomEventHandlerOf[dom.KeyboardEvent] = handler[dom.KeyboardEvent](
    "onkeypress"
  )

  // Form events
  lazy val onsubmit: DomEventHandlerOf[dom.Event]  = handler[dom.Event]("onsubmit")
  lazy val onreset: DomEventHandlerOf[dom.Event]   = handler[dom.Event]("onreset")
  lazy val onchange: DomEventHandlerOf[dom.Event]  = handler[dom.Event]("onchange")
  lazy val oninput: DomEventHandlerOf[dom.Event]   = handler[dom.Event]("oninput")
  lazy val oninvalid: DomEventHandlerOf[dom.Event] = handler[dom.Event]("oninvalid")
  lazy val onselect: DomEventHandlerOf[dom.Event]  = handler[dom.Event]("onselect")

  // Focus events
  lazy val onfocus: DomEventHandlerOf[dom.FocusEvent]    = handler[dom.FocusEvent]("onfocus")
  lazy val onblur: DomEventHandlerOf[dom.FocusEvent]     = handler[dom.FocusEvent]("onblur")
  lazy val onfocusin: DomEventHandlerOf[dom.FocusEvent]  = handler[dom.FocusEvent]("onfocusin")
  lazy val onfocusout: DomEventHandlerOf[dom.FocusEvent] = handler[dom.FocusEvent]("onfocusout")

  // Window/Document events
  lazy val onload: DomEventHandlerOf[dom.Event]         = handler[dom.Event]("onload")
  lazy val onunload: DomEventHandlerOf[dom.Event]       = handler[dom.Event]("onunload")
  lazy val onbeforeunload: DomEventHandlerOf[dom.Event] = handler[dom.Event]("onbeforeunload")
  lazy val onresize: DomEventHandlerOf[dom.UIEvent]     = handler[dom.UIEvent]("onresize")
  lazy val onscroll: DomEventHandlerOf[dom.UIEvent]     = handler[dom.UIEvent]("onscroll")
  lazy val onerror: DomEventHandlerOf[dom.ErrorEvent]   = handler[dom.ErrorEvent]("onerror")

  // Clipboard events
  lazy val oncopy: DomEventHandlerOf[dom.ClipboardEvent]  = handler[dom.ClipboardEvent]("oncopy")
  lazy val oncut: DomEventHandlerOf[dom.ClipboardEvent]   = handler[dom.ClipboardEvent]("oncut")
  lazy val onpaste: DomEventHandlerOf[dom.ClipboardEvent] = handler[dom.ClipboardEvent]("onpaste")

  // Drag events
  lazy val ondrag: DomEventHandlerOf[dom.DragEvent]      = handler[dom.DragEvent]("ondrag")
  lazy val ondragstart: DomEventHandlerOf[dom.DragEvent] = handler[dom.DragEvent]("ondragstart")
  lazy val ondragend: DomEventHandlerOf[dom.DragEvent]   = handler[dom.DragEvent]("ondragend")
  lazy val ondragover: DomEventHandlerOf[dom.DragEvent]  = handler[dom.DragEvent]("ondragover")
  lazy val ondragenter: DomEventHandlerOf[dom.DragEvent] = handler[dom.DragEvent]("ondragenter")
  lazy val ondragleave: DomEventHandlerOf[dom.DragEvent] = handler[dom.DragEvent]("ondragleave")
  lazy val ondrop: DomEventHandlerOf[dom.DragEvent]      = handler[dom.DragEvent]("ondrop")

  // Touch events
  lazy val ontouchstart: DomEventHandlerOf[dom.TouchEvent] = handler[dom.TouchEvent]("ontouchstart")
  lazy val ontouchend: DomEventHandlerOf[dom.TouchEvent]   = handler[dom.TouchEvent]("ontouchend")
  lazy val ontouchmove: DomEventHandlerOf[dom.TouchEvent]  = handler[dom.TouchEvent]("ontouchmove")
  lazy val ontouchcancel: DomEventHandlerOf[dom.TouchEvent] = handler[dom.TouchEvent](
    "ontouchcancel"
  )

  // Pointer events
  lazy val onpointerdown: DomEventHandlerOf[dom.PointerEvent] = handler[dom.PointerEvent](
    "onpointerdown"
  )

  lazy val onpointerup: DomEventHandlerOf[dom.PointerEvent] = handler[dom.PointerEvent](
    "onpointerup"
  )

  lazy val onpointermove: DomEventHandlerOf[dom.PointerEvent] = handler[dom.PointerEvent](
    "onpointermove"
  )

  lazy val onpointerenter: DomEventHandlerOf[dom.PointerEvent] = handler[dom.PointerEvent](
    "onpointerenter"
  )

  lazy val onpointerleave: DomEventHandlerOf[dom.PointerEvent] = handler[dom.PointerEvent](
    "onpointerleave"
  )

  lazy val onpointercancel: DomEventHandlerOf[dom.PointerEvent] = handler[dom.PointerEvent](
    "onpointercancel"
  )

  // Wheel events
  lazy val onwheel: DomEventHandlerOf[dom.WheelEvent] = handler[dom.WheelEvent]("onwheel")

  // Animation events
  lazy val onanimationstart: DomEventHandlerOf[dom.AnimationEvent] = handler[dom.AnimationEvent](
    "onanimationstart"
  )

  lazy val onanimationend: DomEventHandlerOf[dom.AnimationEvent] = handler[dom.AnimationEvent](
    "onanimationend"
  )

  lazy val onanimationiteration: DomEventHandlerOf[dom.AnimationEvent] =
    handler[dom.AnimationEvent]("onanimationiteration")

  // Transition events
  lazy val ontransitionend: DomEventHandlerOf[dom.TransitionEvent] = handler[dom.TransitionEvent](
    "ontransitionend"
  )

  // Media events
  lazy val onplay: DomEventHandlerOf[dom.Event]           = handler[dom.Event]("onplay")
  lazy val onpause: DomEventHandlerOf[dom.Event]          = handler[dom.Event]("onpause")
  lazy val onended: DomEventHandlerOf[dom.Event]          = handler[dom.Event]("onended")
  lazy val onvolumechange: DomEventHandlerOf[dom.Event]   = handler[dom.Event]("onvolumechange")
  lazy val onseeking: DomEventHandlerOf[dom.Event]        = handler[dom.Event]("onseeking")
  lazy val onseeked: DomEventHandlerOf[dom.Event]         = handler[dom.Event]("onseeked")
  lazy val ontimeupdate: DomEventHandlerOf[dom.Event]     = handler[dom.Event]("ontimeupdate")
  lazy val ondurationchange: DomEventHandlerOf[dom.Event] = handler[dom.Event]("ondurationchange")
  lazy val onloadeddata: DomEventHandlerOf[dom.Event]     = handler[dom.Event]("onloadeddata")
  lazy val onloadedmetadata: DomEventHandlerOf[dom.Event] = handler[dom.Event]("onloadedmetadata")

  // Toggle events (for details/dialog)
  lazy val ontoggle: DomEventHandlerOf[dom.Event] = handler[dom.Event]("ontoggle")

end HtmlEvents
