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

import scala.scalajs.js

/**
  * DOM type checks and document lookups that also work for values from another window, such as a
  * Dock panel detached into a popup.
  *
  * Each window is a JS realm with its own `KeyboardEvent`, `HTMLElement`, and other classes, and
  * Scala.js compiles `case e: dom.KeyboardEvent` to `instanceof` against this window's class. An
  * event from a popup, or the popup's own `<body>`, fails that check. Match with
  * `case AnyRealm.KeyboardEvent(e) =>` instead.
  */
private[uni] object AnyRealm:

  /**
    * True when `x` is an instance of the DOM class `name` (or a subclass) from any window. Tries this
    * window's class first, then the constructor names along the prototype chain.
    */
  def is(x: Any, name: String): Boolean =
    if js.typeOf(x) != "object" || x == null then
      false
    else
      val c = js.Dynamic.global.globalThis.selectDynamic(name)
      if !js.isUndefined(c) && js.special.instanceof(x, c) then
        true
      else
        var p     = js.Object.getPrototypeOf(x.asInstanceOf[js.Object])
        var found = false
        while !found && p != null do
          val ctor = p.asInstanceOf[js.Dynamic].constructor
          found = js.typeOf(ctor) == "function" && ctor.name.asInstanceOf[Any] == name
          p = js.Object.getPrototypeOf(p)
        found

  /** A DOM type to match with, as in `case AnyRealm.HTMLElement(el) =>`. */
  sealed class Kind[A](name: String):
    def unapply(x: Any): Option[A] =
      if is(x, name) then
        Some(x.asInstanceOf[A])
      else
        None

    def test(x: Any): Boolean = is(x, name)

  object Node                extends Kind[dom.Node]("Node")
  object Element             extends Kind[dom.Element]("Element")
  object HTMLElement         extends Kind[dom.HTMLElement]("HTMLElement")
  object HTMLInputElement    extends Kind[dom.HTMLInputElement]("HTMLInputElement")
  object HTMLTextAreaElement extends Kind[dom.HTMLTextAreaElement]("HTMLTextAreaElement")
  object HTMLButtonElement   extends Kind[dom.HTMLButtonElement]("HTMLButtonElement")
  object KeyboardEvent       extends Kind[dom.KeyboardEvent]("KeyboardEvent")
  object MouseEvent          extends Kind[dom.MouseEvent]("MouseEvent")
  object PointerEvent        extends Kind[dom.PointerEvent]("PointerEvent")

  /** The document `node` belongs to; this window's when it has none. */
  def documentOf(node: dom.Node): dom.HTMLDocument =
    if node == null then
      dom.document
    else
      node.ownerDocument match
        case null =>
          // A document is its own owner
          if node.nodeType == dom.Node.DOCUMENT_NODE then
            node.asInstanceOf[dom.HTMLDocument]
          else
            dom.document
        case d =>
          d.asInstanceOf[dom.HTMLDocument]

  /** The window `node` is shown in; this window when it has none. */
  def windowOf(node: dom.Node): dom.Window = Option(documentOf(node).defaultView).getOrElse(
    dom.window
  )

  /**
    * A new `name` (such as `ResizeObserver`) from the window `node` is in. A `ResizeObserver` from
    * another window doesn't follow that window's layout: it reports a loop error each frame.
    */
  def construct(node: dom.Node, name: String, args: js.Any*): js.Dynamic = js
    .Dynamic
    .newInstance(windowOf(node).asInstanceOf[js.Dynamic].selectDynamic(name))(args*)

  /** A `ResizeObserver` from the window `node` is in; see [[construct]]. */
  def resizeObserver(
      node: dom.Node,
      callback: (js.Array[dom.ResizeObserverEntry], dom.ResizeObserver) => Unit
  ): dom.ResizeObserver = construct(node, "ResizeObserver", js.Any.fromFunction2(callback))
    .asInstanceOf[dom.ResizeObserver]

  /** The focused element of the document `node` belongs to. */
  def activeElementOf(node: dom.Node): dom.Element = documentOf(node).activeElement

end AnyRealm
