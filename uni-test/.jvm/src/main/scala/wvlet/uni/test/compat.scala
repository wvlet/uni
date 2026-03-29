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
package wvlet.uni.test

import java.lang.reflect.InvocationTargetException
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*

/**
  * JVM specific compatibility layer for uni-test
  */
private[test] object compat:

  /**
    * Platform-specific matcher for equality checks. On JVM, there's no special handling needed, so
    * we return an empty PartialFunction.
    */
  def platformSpecificMatcher: PartialFunction[(Any, Any), MatchResult] = PartialFunction.empty

  /**
    * Execution context for async operations
    */
  val executionContext: ExecutionContext = ExecutionContext.global

  /**
    * Create a new instance of the test class or get singleton instance if it's an object. Throws
    * exception if instantiation fails.
    */
  def newInstance(className: String, classLoader: ClassLoader): UniTest =
    val testClass = classLoader.loadClass(className)
    getInstanceOf(testClass, className, classLoader)

  /**
    * Get an instance from a class. For Scala objects (modules), retrieves the singleton instance
    * via MODULE$ field. For regular classes, creates a new instance via no-arg constructor.
    */
  def getInstanceOf(
      testClass: Class[?],
      className: String = "",
      classLoader: ClassLoader = null
  ): UniTest =
    def createInstance: UniTest = testClass
      .getDeclaredConstructor()
      .newInstance()
      .asInstanceOf[UniTest]

    def getModule(cls: Class[?]): Option[Any] =
      try
        Some(cls.getField("MODULE$").get(null))
      catch
        case _: NoSuchFieldException =>
          None

    val moduleInstance = getModule(testClass).orElse {
      if classLoader != null && !className.endsWith("$") then
        try
          getModule(classLoader.loadClass(s"${className}$$"))
        catch
          case _: ClassNotFoundException =>
            None
      else
        None
    }

    moduleInstance match
      case Some(instance: UniTest) =>
        instance
      case _ =>
        createInstance

  end getInstanceOf

  /**
    * Unwrap InvocationTargetException and other wrapper exceptions to find the root cause
    */
  def findCause(e: Throwable): Throwable =
    e match
      case ite: InvocationTargetException if ite.getCause != null =>
        findCause(ite.getCause)
      case _ =>
        e

  /**
    * Run an Rx stream for test purposes. On JVM, blocks until result is available. Returns the
    * result value.
    */
  def runRxTest[A](rx: wvlet.uni.rx.RxOps[A]): A = rx.await

  /**
    * Run a Future for test purposes. On JVM, blocks until result is available using Await.result
    * with a 30-second timeout. Returns the result value or throws the underlying exception on
    * failure.
    */
  def runFutureTest[A](future: Future[A]): A = Await.result(future, 30.seconds)

end compat
