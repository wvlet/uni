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

import scala.annotation.implicitNotFound
import wvlet.uni.rx.{Rx, RxOption}
import org.scalajs.dom

/**
  * Type class evidence for types that can be used as attribute values.
  */
@implicitNotFound(msg = "Cannot use ${X} as an attribute value")
trait EmbeddableAttribute[X]

object EmbeddableAttribute:
  type EA[A] = EmbeddableAttribute[A]

  // Primitive types
  inline given embedNone: EA[None.type]  = null
  inline given embedBoolean: EA[Boolean] = null
  inline given embedInt: EA[Int]         = null
  inline given embedLong: EA[Long]       = null
  inline given embedFloat: EA[Float]     = null
  inline given embedDouble: EA[Double]   = null
  inline given embedString: EA[String]   = null
  inline given embedChar: EA[Char]       = null
  inline given embedShort: EA[Short]     = null
  inline given embedByte: EA[Byte]       = null

  // Function types (for event handlers)
  inline given embedF0[U]: EA[() => U]   = null
  inline given embedF1[I, U]: EA[I => U] = null

  // Container types
  inline given embedOption[C[x] <: Option[x], A: EA]: EA[C[A]]     = null
  inline given embedRx[C[x] <: Rx[x], A: EA]: EA[C[A]]             = null
  inline given embedRxOption[C[x] <: RxOption[x], A: EA]: EA[C[A]] = null
  inline given embedSeq[C[x] <: Iterable[x], A: EA]: EA[C[A]]      = null

end EmbeddableAttribute

/**
  * Type class evidence for types that can be embedded as DOM nodes (children).
  */
@implicitNotFound(msg = "Cannot use ${A} as a DOM node")
trait EmbeddableNode[A]

object EmbeddableNode:
  type EN[A] = EmbeddableNode[A]

  // Empty types
  inline given embedNil: EN[Nil.type]   = null
  inline given embedNone: EN[None.type] = null
  inline given embedUnit: EN[Unit]      = null

  // Primitive types
  inline given embedBoolean: EN[Boolean] = null
  inline given embedInt: EN[Int]         = null
  inline given embedLong: EN[Long]       = null
  inline given embedFloat: EN[Float]     = null
  inline given embedDouble: EN[Double]   = null
  inline given embedString: EN[String]   = null
  inline given embedChar: EN[Char]       = null
  inline given embedShort: EN[Short]     = null
  inline given embedByte: EN[Byte]       = null

  // DOM types
  inline given embedDomNode[A <: DomNode]: EN[A]         = null
  inline given embedHtmlElement[A <: dom.Element]: EN[A] = null

  // Container types
  inline given embedOption[C[x] <: Option[x], A: EN]: EN[C[A]]     = null
  inline given embedRx[C[x] <: Rx[x], A: EN]: EN[C[A]]             = null
  inline given embedRxOption[C[x] <: RxOption[x], A: EN]: EN[C[A]] = null
  inline given embedSeq[C[x] <: Iterable[x], A: EN]: EN[C[A]]      = null

end EmbeddableNode

/**
  * Convert a value to an Embedded node for rendering.
  */
def embedAsNode[A: EmbeddableNode](v: A): RxElement = Embedded(v)

/**
  * Implicit conversions for common types to DomNode.
  */
object DomNodeImplicits:
  import scala.language.implicitConversions

  given stringToDomNode: Conversion[String, DomNode]                           = s => Embedded(s)
  given intToDomNode: Conversion[Int, DomNode]                                 = i => Embedded(i)
  given longToDomNode: Conversion[Long, DomNode]                               = l => Embedded(l)
  given floatToDomNode: Conversion[Float, DomNode]                             = f => Embedded(f)
  given doubleToDomNode: Conversion[Double, DomNode]                           = d => Embedded(d)
  given booleanToDomNode: Conversion[Boolean, DomNode]                         = b => Embedded(b)
  given charToDomNode: Conversion[Char, DomNode]                               = c => Embedded(c)
  given rxToDomNode[A: EmbeddableNode]: Conversion[Rx[A], DomNode]             = rx => Embedded(rx)
  given rxOptionToDomNode[A: EmbeddableNode]: Conversion[RxOption[A], DomNode] = rx => Embedded(rx)
  given optionToDomNode[A: EmbeddableNode]: Conversion[Option[A], DomNode] = opt => Embedded(opt)
  given seqToDomNode[A: EmbeddableNode]: Conversion[Seq[A], DomNode]       = seq => Embedded(seq)
  given listToDomNode[A: EmbeddableNode]: Conversion[List[A], DomNode]     = lst => Embedded(lst)

end DomNodeImplicits

/**
  * Conditional rendering helper. Renders the body only when the condition is true.
  *
  * Usage:
  * {{{
  *   div(
  *     when(isLoggedIn, span("Welcome!")),
  *     when(!isLoggedIn, button("Login"))
  *   )
  * }}}
  */
def when(cond: Boolean, body: => DomNode): DomNode =
  if cond then
    body
  else
    DomNode.empty

/**
  * Conditional rendering helper. Renders the body only when the condition is false.
  */
def unless(cond: Boolean, body: => DomNode): DomNode =
  if !cond then
    body
  else
    DomNode.empty
