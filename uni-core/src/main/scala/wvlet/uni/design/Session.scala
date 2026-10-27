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

import wvlet.uni.surface.Surface
import wvlet.uni.util.SourceCode

/**
  * Session manages injected objects (e.g., Singleton)
  */
trait Session extends AutoCloseable:

  /**
    * Name of the session (default: object hash code)
    */
  def name: String

  /**
    * Id of the sesion (= object hash id)
    */
  def sessionId: Long

  /**
    * Reference to the design used for creating this session.
    */
  def design: Design

  /**
    * Internal method for building an instance of type A. This method does not inject the session to
    * A at first hand.
    *
    * @tparam A
    * @return
    */
  def get[A](surface: Surface)(using sourceCode: SourceCode): A

  /**
    * Internal method for building an instance of type A, or if no binding is found, use the given
    * trait instance factory
    *
    * @tparam A
    * @return
    */
  def getOrElse[A](surface: Surface, traitInstanceFactory: => A)(using sourceCode: SourceCode): A

  private[design] def createNewInstanceOf[A](surface: Surface)(using sourceCode: SourceCode): A
  private[design] def createNewInstanceOf[A](surface: Surface, traitInstanceFactory: => A)(using
      sourceCode: SourceCode
  ): A

  def getInstanceOf(surface: Surface)(using sourceCode: SourceCode): Any

  /**
    * Create a child session with an additional design. The created session shares the same
    * singleton holder and the lifecycle manager with this session.
    */
  def newSharedChildSession(d: Design): Session

  /**
    * Create a child session with an additional design. The created session has its own singleton
    * holder and a lifecycle manager.
    *
    *   - Child sessions tries to delegate the object binding to the parent (or ancestor) session if
    *     no corresponding binding is defined in the child design.
    *   - If the parent and ancestors ve no binding for a given type, it will creates a new object
    *     in the child session.
    *   - If the parent or an ancestor session already initialized a target binding, lifecycle hooks
    *     for that binding will not be called in the child session.
    *
    * @param d
    *   Additional design for child session
    * @param inheritParentDesignOptions
    *   if true (default) use the same design options (e.g., production mode, life cycle logging)
    *   with the parent design
    * @return
    */
  def newChildSession(d: Design = Design.blanc, inheritParentDesignOptions: Boolean = true): Session

  /**
    * Create a child session and execute the body part. The created session has its own singleton
    * holder and lifecycle manager.
    *
    * @param d
    *   Additional design for child session.
    * @param body
    * @tparam U
    * @return
    */
  def withChildSession[U](d: Design = Design.blanc)(body: Session => U): U =
    val childSession = newChildSession(d)
    try
      childSession.start
      body(childSession)
    finally
      childSession.shutdown

  /**
    * Get the object LifeCycleManager of this session.
    *
    * @return
    */
  def lifeCycleManager: LifeCycleManager

  def start[U](body: => U): U =
    try
      start
      body
    finally
      shutdown

  def start: Unit            = lifeCycleManager.start
  def shutdown: Unit         = lifeCycleManager.shutdown
  override def close(): Unit = shutdown

  /**
    * Build an instance of A. In general this method is necessary only when creating an entry point
    * of your application. When feasible avoid using this method so that Airframe can inject objects
    * where bind[X] is used.
    *
    * @tparam A
    * @return
    *   object
    */
  inline def build[A]: A = get[A](Surface.of[A])

  /**
    * Register an instance to the session to control the life cycle of the object under this
    * session.
    */
  def register[A](instance: A): Unit

end Session
