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
package wvlet.uni.test.spi

import sbt.testing.*
import wvlet.uni.cli.Tint
import wvlet.uni.test.AssertionFailure
import wvlet.uni.test.TestException
import wvlet.uni.test.TestResult
import wvlet.uni.test.TestSkipped
import wvlet.uni.test.TestPending
import wvlet.uni.test.TestCancelled
import wvlet.uni.test.TestIgnored
import wvlet.uni.test.UniTest
import wvlet.uni.test.compat

import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.concurrent.duration.Duration

/**
  * sbt test task that executes tests for a single test class
  */
class UniTestTask(_taskDef: TaskDef, testClassLoader: ClassLoader, config: TestConfig) extends Task:

  def taskDef(): TaskDef = _taskDef

  override def tags(): Array[String] = Array.empty

  /**
    * Synchronous execute method for JVM. Delegates to the async version using Promise/Await.
    */
  override def execute(
      eventHandler: EventHandler,
      loggers: Array[sbt.testing.Logger]
  ): Array[Task] =
    val p = Promise[Unit]()
    execute(eventHandler, loggers, _ => p.success(()))
    Await.result(p.future, Duration.Inf)
    Array.empty

  /**
    * Asynchronous execute method for Scala.js. This is the main implementation that both sync and
    * async paths use. Continuation is called after all async test work completes.
    */
  def execute(
      eventHandler: EventHandler,
      loggers: Array[sbt.testing.Logger],
      continuation: Array[Task] => Unit
  ): Unit =
    given ExecutionContext = compat.executionContext
    val className          = taskDef().fullyQualifiedName()

    Future(compat.newInstance(className, testClassLoader))
      .flatMap(testInstance => runTests(testInstance, className, eventHandler, loggers))
      .recover { case e: Throwable =>
        handleSpecLevelError(className, e, eventHandler, loggers)
      }
      .foreach { _ =>
        continuation(Array.empty)
      }

  end execute

  private def handleSpecLevelError(
      className: String,
      e: Throwable,
      eventHandler: EventHandler,
      loggers: Array[sbt.testing.Logger]
  ): Unit =
    val cause           = compat.findCause(e)
    val (event, logMsg) = classifySpecLevelException(className, cause)
    eventHandler.handle(event)
    loggers.foreach(_.info(logMsg))
    cause match
      case _: TestSkipped | _: TestPending | _: TestCancelled | _: TestIgnored =>
      case _                                                                   =>
        loggers.foreach(_.trace(cause))

  private def runTests(
      testInstance: UniTest,
      className: String,
      eventHandler: EventHandler,
      loggers: Array[sbt.testing.Logger]
  )(using ExecutionContext): Future[Unit] =
    val allTests      = testInstance.registeredTests
    val filteredTests =
      config.testFilter match
        case Some(filter) =>
          allTests.filter(_.fullName.contains(filter))
        case None =>
          allTests

    if filteredTests.isEmpty then
      loggers.foreach(_.info(s"No tests found in ${className}"))
      Future.unit
    else
      val testQueue     = scala.collection.mutable.Queue.from(filteredTests)
      val executedTests = scala.collection.mutable.Set.empty[String]

      def processQueue(): Future[Unit] =
        if testQueue.isEmpty then
          Future.unit
        else
          val testDef = testQueue.dequeue()
          if executedTests.contains(testDef.fullName) then
            processQueue()
          else
            executedTests.add(testDef.fullName)
            val beforeCount = testInstance.registeredTests.size

            testInstance
              .executeTest(testDef)
              .flatMap { result =>
                val isContainer = testInstance.registeredTests.size > beforeCount

                if result.isFailure || !isContainer then
                  val event = createEvent(testDef.fullName, result)
                  eventHandler.handle(event)
                  logResult(result, loggers)

                if !result.isFailure && isContainer then
                  testInstance
                    .registeredTests
                    .foreach { t =>
                      if !executedTests.contains(t.fullName) then
                        testQueue.enqueue(t)
                    }

                processQueue()
              }

      processQueue()
    end if

  end runTests

  private def logResult(result: TestResult, loggers: Array[sbt.testing.Logger]): Unit =
    result match
      case TestResult.Success(name) =>
        loggers.foreach(_.info(Tint.green(s"  + ${name}")))
      case TestResult.Failure(name, msg, cause) =>
        loggers.foreach(_.error(Tint.red(s"  - ${name}: ${msg}")))
        // Show source code snippet for assertion failures
        cause.foreach {
          case af: AssertionFailure =>
            af.source
              .formatSnippet
              .foreach { snippet =>
                val indentedSnippet = snippet.linesIterator.map(l => s"    ${l}").mkString("\n")
                loggers.foreach(_.error(Tint.gray(indentedSnippet)))
              }
          case _ =>
            ()
        }
      case TestResult.Error(name, msg, cause) =>
        loggers.foreach(_.error(Tint.red(s"  x ${name}: ${msg}")))
        loggers.foreach(_.trace(cause))
      case TestResult.Skipped(name, reason) =>
        loggers.foreach(_.info(Tint.yellow(s"  ~ ${name}: skipped - ${reason}")))
      case TestResult.Pending(name, reason) =>
        loggers.foreach(_.info(Tint.magenta(s"  ? ${name}: pending - ${reason}")))
      case TestResult.Cancelled(name, reason) =>
        loggers.foreach(_.info(Tint.cyan(s"  ! ${name}: cancelled - ${reason}")))
      case TestResult.Ignored(name, reason) =>
        loggers.foreach(_.info(Tint.gray(s"  - ${name}: ignored - ${reason}")))

  private def createEvent(testName: String, result: TestResult): Event =
    val selector = new TestSelector(testName)
    val status   =
      result match
        case TestResult.Success(_) =>
          Status.Success
        case TestResult.Failure(_, _, _) =>
          Status.Failure
        case TestResult.Error(_, _, _) =>
          Status.Error
        case TestResult.Skipped(_, _) =>
          Status.Skipped
        case TestResult.Pending(_, _) =>
          Status.Pending
        case TestResult.Cancelled(_, _) =>
          Status.Canceled
        case TestResult.Ignored(_, _) =>
          Status.Ignored

    val throwable =
      result match
        case TestResult.Failure(_, _, Some(e)) =>
          new OptionalThrowable(e)
        case TestResult.Error(_, _, e) =>
          new OptionalThrowable(e)
        case _ =>
          new OptionalThrowable()

    UniTestEvent(
      taskDef().fullyQualifiedName(),
      taskDef().fingerprint(),
      selector,
      status,
      throwable,
      0L
    )

  end createEvent

  private def createErrorEvent(className: String, e: Throwable): Event = UniTestEvent(
    className,
    taskDef().fingerprint(),
    new SuiteSelector(),
    Status.Error,
    new OptionalThrowable(e),
    0L
  )

  /**
    * Classify an exception thrown at the spec level (during class construction) and create an
    * appropriate event
    */
  private def classifySpecLevelException(className: String, e: Throwable): (Event, String) =
    val leafName = className.split('.').last
    e match
      case ts: TestSkipped =>
        val event = UniTestEvent(
          className,
          taskDef().fingerprint(),
          new SuiteSelector(),
          Status.Skipped,
          new OptionalThrowable(ts),
          0L
        )
        (event, Tint.yellow(s"  ~ ${leafName}: skipped - ${ts.getMessage}"))
      case tp: TestPending =>
        val event = UniTestEvent(
          className,
          taskDef().fingerprint(),
          new SuiteSelector(),
          Status.Pending,
          new OptionalThrowable(tp),
          0L
        )
        (event, Tint.magenta(s"  ? ${leafName}: pending - ${tp.getMessage}"))
      case tc: TestCancelled =>
        val event = UniTestEvent(
          className,
          taskDef().fingerprint(),
          new SuiteSelector(),
          Status.Canceled,
          new OptionalThrowable(tc),
          0L
        )
        (event, Tint.cyan(s"  ! ${leafName}: cancelled - ${tc.getMessage}"))
      case ti: TestIgnored =>
        val event = UniTestEvent(
          className,
          taskDef().fingerprint(),
          new SuiteSelector(),
          Status.Ignored,
          new OptionalThrowable(ti),
          0L
        )
        (event, Tint.gray(s"  - ${leafName}: ignored - ${ti.getMessage}"))
      case _ =>
        val event = UniTestEvent(
          className,
          taskDef().fingerprint(),
          new SuiteSelector(),
          Status.Error,
          new OptionalThrowable(e),
          0L
        )
        (event, Tint.red(s"  x ${leafName}: error - ${e.getMessage}"))

    end match

  end classifySpecLevelException

end UniTestTask

/**
  * Event implementation for sbt test interface
  */
class UniTestEvent(
    _fullyQualifiedName: String,
    _fingerprint: Fingerprint,
    _selector: Selector,
    _status: Status,
    _throwable: OptionalThrowable,
    _duration: Long
) extends Event:
  override def fullyQualifiedName(): String   = _fullyQualifiedName
  override def fingerprint(): Fingerprint     = _fingerprint
  override def selector(): Selector           = _selector
  override def status(): Status               = _status
  override def throwable(): OptionalThrowable = _throwable
  override def duration(): Long               = _duration
