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
import java.util.concurrent.LinkedBlockingQueue
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

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
    * Run a test suite on the calling thread, which sbt dedicates to the suite. Running it on
    * ExecutionContext.global instead would let test bodies that block on Futures in that pool
    * starve it.
    */
  def runSuite(suite: ExecutionContext => Future[Unit]): Unit =
    val loop = RunLoop()
    try loop.runUntilComplete(suite(loop))
    finally loop.close()

  /**
    * An ExecutionContext whose tasks run on the thread that calls runUntilComplete
    */
  private class RunLoop extends ExecutionContext:
    private val queue  = LinkedBlockingQueue[Runnable]()
    private var closed = false

    override def execute(task: Runnable): Unit =
      val queued = synchronized {
        if !closed then
          queue.put(task)
        !closed
      }
      // A callback that arrives after the suite finished has no thread to run it here
      if !queued then
        ExecutionContext.global.execute(task)

    override def reportFailure(cause: Throwable): Unit = ExecutionContext
      .global
      .reportFailure(cause)

    def runUntilComplete(f: Future[Unit]): Unit =
      // Wake up the loop when the suite completes from another thread
      f.onComplete(_ => queue.put(() => ()))(using ExecutionContext.parasitic)
      while !f.isCompleted do
        val task = queue.take()
        try
          task.run()
        catch
          case NonFatal(e) =>
            reportFailure(e)
      f.value.get.get

    def close(): Unit =
      val pending = synchronized {
        closed = true
        val tasks = java.util.ArrayList[Runnable]()
        queue.drainTo(tasks)
        tasks
      }
      pending.forEach(ExecutionContext.global.execute(_))

  end RunLoop

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

end compat
