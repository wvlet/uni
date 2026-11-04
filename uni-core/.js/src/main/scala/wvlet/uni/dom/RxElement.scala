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

import wvlet.uni.rx.{Cancelable, Rx}

/**
  * Base class for reactive DOM elements with lifecycle hooks.
  *
  * RxElement provides:
  *   - Modifier accumulation via apply() and add()
  *   - Lifecycle hooks: beforeRender, onMount, beforeUnmount
  *   - Abstract render method for element rendering
  *
  * Usage:
  * {{{
  *   class Counter extends RxElement:
  *     private val count = Rx.variable(0)
  *
  *     override def render: RxElement =
  *       div(
  *         p(count.map(c => s"Count: \${c}")),
  *         button(onclick -> { () => count.update(_ + 1) }, "Increment")
  *       )
  *
  *     override def onMount(node: Any): Unit =
  *       println("Counter mounted")
  * }}}
  */
abstract class RxElement(val modifiers: List[Seq[DomNode]] = Nil) extends DomNode:
  self =>

  /**
    * Render this element. Subclasses must implement this method.
    */
  def render: RxElement

  /**
    * Called before the element is rendered.
    */
  def beforeRender: Unit = {}

  /**
    * Called after the element is mounted to the DOM.
    *
    * @param node
    *   The underlying DOM node
    */
  def onMount(node: Any): Unit = {}

  /**
    * Called before the element is unmounted from the DOM.
    */
  def beforeUnmount: Unit = {}

  /**
    * Add modifiers (children, attributes) to this element.
    */
  def apply(xs: DomNode*): RxElement =
    if xs.isEmpty then
      this
    else
      addModifier(xs*)

  /**
    * Add modifiers to this element. Override in subclasses to return the correct type.
    */
  def addModifier(xs: DomNode*): RxElement = add(xs*)

  /**
    * Add modifiers and return a new RxElement wrapper.
    */
  def add(xs: DomNode*): RxElement =
    new RxElement(xs :: modifiers):
      override def render: RxElement = self.render

  /**
    * Traverse all modifiers and apply a function to each.
    *
    * @return
    *   A Cancelable that cancels all subscriptions
    */
  private[dom] def traverseModifiers(f: DomNode => Cancelable): Cancelable =
    val cancelables =
      for
        g <- modifiers.reverse;
        m <- g
      yield f(m)
    Cancelable.merge(cancelables)

end RxElement

object RxElement:
  /**
    * No-op function for lifecycle hooks.
    */
  private[dom] val NoOp: Any => Unit = (_: Any) => ()

  /**
    * An empty RxElement that renders nothing.
    */
  val empty: RxElement =
    new RxElement():
      // Return Embedded(DomNode.empty) to terminate recursion in DomRenderer
      override def render: RxElement = Embedded(DomNode.empty)

  /**
    * Create an RxElement from an existing element.
    */
  def apply(a: RxElement): RxElement =
    new RxElement():
      override def render: RxElement = a

  /**
    * Create an RxElement from a reactive stream of elements.
    */
  def apply[A <: RxElement](a: Rx[A]): RxElement =
    new RxElement():
      override def render: RxElement = LazyRxElement(() => a)

/**
  * A lazy wrapper for reactive elements that defers evaluation.
  */
class LazyRxElement[A](lazyValue: () => A) extends RxElement():
  lazy val value: A = lazyValue()

  override def render: RxElement = this

object LazyRxElement:
  def apply[A](lazyValue: () => A): LazyRxElement[A] = new LazyRxElement(lazyValue)

/**
  * A placeholder for embedded values that will be rendered based on their type.
  */
case class Embedded(v: Any) extends RxElement():
  override def render: RxElement = this
