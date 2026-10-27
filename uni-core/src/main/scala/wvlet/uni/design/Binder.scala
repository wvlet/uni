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
import LifeCycleHookType.*
import wvlet.uni.util.LazyF0
import wvlet.uni.util.SourceCode

object Binder:
  sealed trait Binding extends Serializable:
    def forSingleton: Boolean = false
    def from: Surface
    def sourceCode: SourceCode

  case class ClassBinding(from: Surface, to: Surface, sourceCode: SourceCode) extends Binding:
    if from == to then
      throw DesignException.cyclicDependency(List(to), sourceCode)

  case class SingletonBinding(from: Surface, to: Surface, isEager: Boolean, sourceCode: SourceCode)
      extends Binding:
    override def forSingleton: Boolean = true

  case class ProviderBinding(
      factory: DependencyFactory,
      provideSingleton: Boolean,
      eager: Boolean,
      sourceCode: SourceCode
  ) extends Binding:
    assert(!eager || (eager && provideSingleton))
    def from: Surface                  = factory.from
    override def forSingleton: Boolean = provideSingleton

    private val objectId = new Object().hashCode()

    override def hashCode(): Int             = objectId
    override def equals(other: Any): Boolean =
      other match
        case that: ProviderBinding =>
          // Scala 2.12 generates Lambda for Function0, and the class might be generated every time, so
          // comparing functionClasses doesn't work
          (that canEqual this) && this.objectId == that.objectId
        case _ =>
          false

  case class DependencyFactory(from: Surface, dependencyTypes: Seq[Surface], factory: Any):
    override def toString: String =
      val deps =
        if dependencyTypes.isEmpty then
          "()"
        else
          s"(${dependencyTypes.mkString(",")})"
      s"${deps}=>${from} [${factory}]"

    def create(args: Seq[Any]): Any =
      require(args.length == dependencyTypes.length)
      args.length match
        case 0 =>
          // We need to copy the F0 instance in order to make Design immutable
          factory.asInstanceOf[LazyF0[?]].copy.eval
        case 1 =>
          factory.asInstanceOf[Any => Any](args(0))
        case 2 =>
          factory.asInstanceOf[(Any, Any) => Any](args(0), args(1))
        case 3 =>
          factory.asInstanceOf[(Any, Any, Any) => Any](args(0), args(1), args(2))
        case 4 =>
          factory.asInstanceOf[(Any, Any, Any, Any) => Any](args(0), args(1), args(2), args(3))
        case 5 =>
          factory.asInstanceOf[(Any, Any, Any, Any, Any) => Any](
            args(0),
            args(1),
            args(2),
            args(3),
            args(4)
          )
        case other =>
          throw new IllegalStateException("Should never reach")

  end DependencyFactory

end Binder

import Binder.*

/**
  */
class Binder[A](val design: Design, val from: Surface, val sourceCode: SourceCode)
    extends LogSupport:
  /**
    * Bind the type to a given instance. The instance will be instantiated as an eager singleton
    * when creating a session. Note that as you create a new session, new instance will be
    * generated.
    *
    * @param any
    * @return
    */
  def toInstance(any: => A): DesignWithContext[A] =
    trace(s"binder toInstance: ${from}")
    val binding = ProviderBinding(
      DependencyFactory(from, Seq.empty, LazyF0(any).asInstanceOf[Any]),
      true,
      true,
      sourceCode
    )
    design.addBinding[A](binding)

  def toSingleton: DesignWithContext[A] = design.addBinding[A](
    SingletonBinding(from, from, false, sourceCode)
  )

  def toEagerSingleton: DesignWithContext[A] = design.addBinding[A](
    SingletonBinding(from, from, true, sourceCode)
  )

  /**
    * Bind a singleton instance of B to A
    *
    * @tparam B
    */
  inline def to[B <: A]: DesignWithContext[B] =
    val to = Surface.of[B]
    if from == to then
      warn("Binding to the same type is not allowed: " + to.toString)
      throw DesignException.cyclicDependency(List(to), SourceCode())
    design.addBinding[B](SingletonBinding(from, to, false, sourceCode))

  inline def toEagerSingletonOf[B <: A]: DesignWithContext[B] =
    val to = Surface.of[B]
    if from == to then
      warn("Binding to the same type is not allowed: " + to.toString)
      throw DesignException.cyclicDependency(List(to), SourceCode())
    design.addBinding[B](SingletonBinding(from, to, true, sourceCode))

  inline def toProvider[D1](factory: D1 => A): DesignWithContext[A] = design.addBinding[A](
    ProviderBinding(
      DependencyFactory(from, Seq(Surface.of[D1]), factory),
      true,
      false,
      SourceCode()
    )
  )

  inline def toProvider[D1, D2](factory: (D1, D2) => A): DesignWithContext[A] = design.addBinding[
    A
  ](
    ProviderBinding(
      DependencyFactory(from, Seq(Surface.of[D1], Surface.of[D2]), factory),
      true,
      false,
      SourceCode()
    )
  )

  inline def toProvider[D1, D2, D3](factory: (D1, D2, D3) => A): DesignWithContext[A] = design
    .addBinding[A](
      ProviderBinding(
        DependencyFactory(from, Seq(Surface.of[D1], Surface.of[D2], Surface.of[D3]), factory),
        true,
        false,
        SourceCode()
      )
    )

  inline def toProvider[D1, D2, D3, D4](factory: (D1, D2, D3, D4) => A): DesignWithContext[A] =
    design.addBinding[A](
      ProviderBinding(
        DependencyFactory(
          from,
          Seq(Surface.of[D1], Surface.of[D2], Surface.of[D3], Surface.of[D4]),
          factory
        ),
        true,
        false,
        SourceCode()
      )
    )

  inline def toProvider[D1, D2, D3, D4, D5](
      factory: (D1, D2, D3, D4, D5) => A
  ): DesignWithContext[A] = design.addBinding[A](
    ProviderBinding(
      DependencyFactory(
        from,
        Seq(Surface.of[D1], Surface.of[D2], Surface.of[D3], Surface.of[D4], Surface.of[D5]),
        factory
      ),
      true,
      false,
      SourceCode()
    )
  )

  inline def toEagerSingletonProvider[D1](factory: D1 => A): DesignWithContext[A] = design
    .addBinding[A](
      ProviderBinding(
        DependencyFactory(from, Seq(Surface.of[D1]), factory),
        true,
        true,
        SourceCode()
      )
    )

  inline def toEagerSingletonProvider[D1, D2](factory: (D1, D2) => A): DesignWithContext[A] = design
    .addBinding[A](
      ProviderBinding(
        DependencyFactory(from, Seq(Surface.of[D1], Surface.of[D2]), factory),
        true,
        true,
        SourceCode()
      )
    )

  inline def toEagerSingletonProvider[D1, D2, D3](
      factory: (D1, D2, D3) => A
  ): DesignWithContext[A] = design.addBinding[A](
    ProviderBinding(
      DependencyFactory(from, Seq(Surface.of[D1], Surface.of[D2], Surface.of[D3]), factory),
      true,
      true,
      SourceCode()
    )
  )

  inline def toEagerSingletonProvider[D1, D2, D3, D4](
      factory: (D1, D2, D3, D4) => A
  ): DesignWithContext[A] = design.addBinding[A](
    ProviderBinding(
      DependencyFactory(
        from,
        Seq(Surface.of[D1], Surface.of[D2], Surface.of[D3], Surface.of[D4]),
        factory
      ),
      true,
      true,
      SourceCode()
    )
  )

  inline def toEagerSingletonProvider[D1, D2, D3, D4, D5](
      factory: (D1, D2, D3, D4, D5) => A
  ): DesignWithContext[A] = design.addBinding[A](
    ProviderBinding(
      DependencyFactory(
        from,
        Seq(Surface.of[D1], Surface.of[D2], Surface.of[D3], Surface.of[D4], Surface.of[D5]),
        factory
      ),
      true,
      true,
      SourceCode()
    )
  )

  def onInit(body: A => Unit): DesignWithContext[A] = design.withLifeCycleHook[A](
    LifeCycleHookDesign(ON_INIT, from, body.asInstanceOf[Any => Unit])
  )

  def onInject(body: A => Unit): DesignWithContext[A] = design.withLifeCycleHook[A](
    LifeCycleHookDesign(ON_INJECT, from, body.asInstanceOf[Any => Unit])
  )

  def onStart(body: A => Unit): DesignWithContext[A] = design.withLifeCycleHook[A](
    LifeCycleHookDesign(ON_START, from, body.asInstanceOf[Any => Unit])
  )

  def afterStart(body: A => Unit): DesignWithContext[A] = design.withLifeCycleHook[A](
    LifeCycleHookDesign(AFTER_START, from, body.asInstanceOf[Any => Unit])
  )

  def beforeShutdown(body: A => Unit): DesignWithContext[A] = design.withLifeCycleHook[A](
    LifeCycleHookDesign(BEFORE_SHUTDOWN, from, body.asInstanceOf[Any => Unit])
  )

  def onShutdown(body: A => Unit): DesignWithContext[A] = design.withLifeCycleHook[A](
    LifeCycleHookDesign(ON_SHUTDOWN, from, body.asInstanceOf[Any => Unit])
  )

end Binder

/**
  * DesignWithContext[A] is a wrapper of Design class for chaining lifecycle hooks for the same type
  * A. This can be safely cast to just Design
  */
class DesignWithContext[A](design: Design, lastSurface: Surface)
    extends Design(design.designOptions, design.binding, design.hooks):
  def onInit(body: A => Unit): DesignWithContext[A] = design.withLifeCycleHook[A](
    LifeCycleHookDesign(ON_INIT, lastSurface, body.asInstanceOf[Any => Unit])
  )

  def onInject(body: A => Unit): DesignWithContext[A] = design.withLifeCycleHook[A](
    LifeCycleHookDesign(ON_INJECT, lastSurface, body.asInstanceOf[Any => Unit])
  )

  def onStart(body: A => Unit): DesignWithContext[A] = design.withLifeCycleHook[A](
    LifeCycleHookDesign(ON_START, lastSurface, body.asInstanceOf[Any => Unit])
  )

  def afterStart(body: A => Unit): DesignWithContext[A] = design.withLifeCycleHook[A](
    LifeCycleHookDesign(AFTER_START, lastSurface, body.asInstanceOf[Any => Unit])
  )

  def beforeShutdown(body: A => Unit): DesignWithContext[A] = design.withLifeCycleHook[A](
    LifeCycleHookDesign(BEFORE_SHUTDOWN, lastSurface, body.asInstanceOf[Any => Unit])
  )

  def onShutdown(body: A => Unit): DesignWithContext[A] = design.withLifeCycleHook[A](
    LifeCycleHookDesign(ON_SHUTDOWN, lastSurface, body.asInstanceOf[Any => Unit])
  )
