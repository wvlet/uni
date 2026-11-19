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
import wvlet.uni.dom.AnyRealm
import wvlet.uni.rx.Cancelable

import scala.collection.mutable.ArrayBuffer
import scala.scalajs.js

/**
  * The other windows uni-ui renders into, such as those a Dock panel is detached into, so code that
  * asks what has focus, with no node to start from, looks in the window focus last moved into.
  */
private[ui] object Windows:
  private val documents = ArrayBuffer.empty[dom.HTMLDocument]
  // The document focus last moved into, among the page's and the added ones
  private var last: dom.HTMLDocument = dom.document

  private val onFocusIn: js.Function1[dom.Event, Unit] =
    e =>
      e.target match
        case AnyRealm.Node(n) =>
          last = AnyRealm.documentOf(n)
        case _ =>

  /** Adds a window's document until the result is canceled. */
  def add(doc: dom.HTMLDocument): Cancelable =
    if documents.isEmpty then
      dom.document.addEventListener("focusin", onFocusIn, useCapture = true)
    documents += doc
    doc.addEventListener("focusin", onFocusIn, useCapture = true)
    Cancelable { () =>
      doc.removeEventListener("focusin", onFocusIn, useCapture = true)
      documents -= doc
      if last eq doc then
        last = dom.document
      if documents.isEmpty then
        dom.document.removeEventListener("focusin", onFocusIn, useCapture = true)
    }

  /** The document of the window focus is in: the page's, or a detached window's. */
  def focusedDocument: dom.HTMLDocument =
    if documents.isEmpty then
      dom.document
    else
      last

  /** The focused element in the window focus is in. */
  def activeElement: dom.Element = focusedDocument.activeElement

end Windows
