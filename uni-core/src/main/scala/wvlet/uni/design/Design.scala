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
package wvlet.uni.design

import wvlet.uni.log.LogSupport
import wvlet.uni.surface.Surface
import Binder.Binding
import DesignOptions.*
import wvlet.uni.util.SourceCode

/**
  * Immutable airframe design.
  *
  * Design instance does not hold any duplicate bindings for the same Surface.
  */
class Design(
    private[design] val designOptions: DesignOptions,
    private[design] val binding: Vector[Binding],
    private[design] val hooks: Vector[LifeCycleHookDesign]
) extends LogSupport:
  private[design] def getDesignConfig: DesignOptions = designOptions

  /**
    * Used for casting itself as Design if returning DesignWithContext type is cumbersome
    */
  def toDesign: Design = this

  infix def canEqual(other: Any): Boolean = other.isInstanceOf[Design]

  override def equals(other: Any): Boolean =
    other match
      case that: Design =>
        (that canEqual this) && designOptions == that.designOptions && binding == that.binding &&
        hooks == that.hooks
      case _ =>
        false

  override def hashCode(): Int =
    val state = Seq(designOptions, binding, hooks)
    state.map(_.hashCode()).foldLeft(0)((a, b) => 31 * a + b)

  private inline def bind[A](using sourceCode: SourceCode): Binder[A] =
    new Binder(this, Surface.of[A], sourceCode).asInstanceOf[Binder[A]]

  inline def remove[A]: Design =
    val target = Surface.of[A]
    new Design(designOptions, binding.filterNot(_.from == target), hooks)

  inline def bindInstance[A](obj: A)(using sourceCode: SourceCode): DesignWithContext[A] = bind[A]
    .toInstance(obj)

  inline def bindSingleton[A](using sourceCode: SourceCode): DesignWithContext[A] =
    bind[A].toSingleton

  inline def bindEagerSingleton[A](using sourceCode: SourceCode): DesignWithContext[A] =
    bind[A].toEagerSingleton

  inline def bindImpl[A, B <: A](using sourceCode: SourceCode): DesignWithContext[B] = bind[A].to[B]

  inline def bindProvider[D1, A](f: D1 => A)(using sourceCode: SourceCode): DesignWithContext[A] =
    bind[A].toProvider[D1](f)

  inline def bindProvider[D1, D2, A](f: (D1, D2) => A)(using
      sourceCode: SourceCode
  ): DesignWithContext[A] = bind[A].toProvider[D1, D2](f)

  inline def bindProvider[D1, D2, D3, A](f: (D1, D2, D3) => A): DesignWithContext[A] = bind[A]
    .toProvider[D1, D2, D3](f)

  inline def bindProvider[D1, D2, D3, D4, A](f: (D1, D2, D3, D4) => A): DesignWithContext[A] =
    bind[A].toProvider[D1, D2, D3, D4](f)

  inline def bindProvider[D1, D2, D3, D4, D5, A](
      f: (D1, D2, D3, D4, D5) => A
  ): DesignWithContext[A] = bind[A].toProvider[D1, D2, D3, D4, D5](f)

  /**
    * A helper method of creating a new session and an instance of A. This method is useful when you
    * only need to use A as an entry point of your program. After executing the body, the sesion
    * will be closed.
    *
    * @param body
    * @tparam A
    * @return
    */
  inline def build[A](body: A => Any): Any = withSession { session =>
    val a = session.build[A]
    body(a)
  }

  /**
    * Execute a given code block by building A using this design, and return B
    */
  inline def run[A, B](body: A => B): B = {
    withSession { session =>
      val a = session.build[A]
      body(a)
    }
  }.asInstanceOf[B]

  /**
    * Generates a minimized design by removing overwritten bindings
    *
    * @return
    */
  def minimize: Design =
    var seenBindingSurrace   = Set.empty[Surface]
    var minimizedBindingList = List.empty[Binding]

    // Later binding has higher precedence, so traverse bindings from the tail
    for b <- binding.reverseIterator do
      val surface = b.from
      if !seenBindingSurrace.contains(surface) then
        minimizedBindingList = b :: minimizedBindingList
        seenBindingSurrace += surface

    var seenHooks      = Set.empty[(LifeCycleHookType, Surface)]
    var minimizedHooks = List.empty[LifeCycleHookDesign]
    // Override hooks for the same surface and event type
    for h <- hooks.reverseIterator do
      val key: (LifeCycleHookType, Surface) = (h.lifeCycleHookType, h.surface)
      if !seenHooks.contains(key) then
        minimizedHooks = h :: minimizedHooks
        seenHooks += key

    new Design(designOptions, minimizedBindingList.reverse.toVector, minimizedHooks.toVector)

  def add(other: Design): Design =
    new Design(designOptions + other.designOptions, binding ++ other.binding, hooks ++ other.hooks)

  def +(other: Design): Design = add(other)

  def bindSurface(t: Surface)(using sourceCode: SourceCode): Binder[Any] =
    trace(s"bind($t) ${t.isAlias}")
    val b = new Binder[Any](this, t, sourceCode)
    b

  def addBinding[A](b: Binding): DesignWithContext[A] =
    debug(s"Add a binding: $b")
    new DesignWithContext[A](new Design(designOptions, binding :+ b, hooks), b.from)

  private[design] def withLifeCycleHook[A](hook: LifeCycleHookDesign): DesignWithContext[A] =
    trace(s"withLifeCycleHook: ${hook}")
    new DesignWithContext[A](
      new Design(designOptions, binding, hooks = hooks :+ hook),
      hook.surface
    )

  def remove(t: Surface): Design = new Design(designOptions, binding.filterNot(_.from == t), hooks)

  def withLifeCycleLogging: Design = new Design(designOptions.withLifeCycleLogging, binding, hooks)

  def noLifeCycleLogging: Design = new Design(designOptions.noLifecycleLogging, binding, hooks)

  def noDefaultInstanceInjection: Design =
    new Design(designOptions.noDefaultInstanceInjection, binding, hooks)

  /**
    * Enable eager initialization of singletons services for production mode
    */
  def withProductionMode: Design = new Design(designOptions.withProductionMode, binding, hooks)

  /**
    * Do not initialize singletons for debugging
    */
  def withLazyMode: Design = new Design(designOptions.withLazyMode, binding, hooks)

  /**
    * Use a custom binding tracer
    */
  def withTracer(t: Tracer): Design = withOption(tracerOptionKey, t)

  def noTracer: Design = noOption(tracerOptionKey)

  def withStats(stats: DesignStats): Design = withOption(statsOptionKey, stats)

  def noStats: Design = noOption(statsOptionKey)

  private[design] def withOption[A](key: String, value: A): Design =
    new Design(designOptions.withOption(key, value), binding, hooks)

  private[design] def noOption[A](key: String): Design =
    new Design(designOptions.noOption(key), binding, hooks)

  private[design] def getTracer: Option[Tracer] = designOptions.getOption[Tracer](tracerOptionKey)

  private[design] def getStats: Option[DesignStats] = designOptions.getOption[DesignStats](
    statsOptionKey
  )

  /**
    * Method for configuring the session in details
    */
  def newSessionBuilder: SessionBuilder = new SessionBuilder(this)

  /**
    * Create a new session.
    *
    * With this method, the session will not start automatically. You need to explicitly call
    * session.start and session.shutdown to start/terminate the lifecycle of objects
    *
    * @return
    */
  def newSession: Session = new SessionBuilder(this).create

  private def runWithSession[U](session: Session)(body: Session => U): U =
    try
      session.start
      body(session)
    finally
      session.shutdown

  /**
    * Run the code block with a new session.
    *
    * This method will create a new session, start it, run the given code block, and finally
    * terminate the session after the code block completion.
    */
  def withSession[U](body: Session => U): U = runWithSession(newSession)(body)

  override def toString: String = s"Design:\n ${binding.mkString("\n ")}"

end Design

object Design:

  /**
    * Empty design. Using Vector as a binding holder for performance and serialization reason
    */
  private[design] val blanc: Design = new Design(new DesignOptions(), Vector.empty, Vector.empty)

  // Empty design
  def empty: Design = blanc

  // Create a new Design
  def newDesign: Design = blanc

  // Create a new Design without lifecycle logging
  def newSilentDesign: Design = blanc.noLifeCycleLogging
