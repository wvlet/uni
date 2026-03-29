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

import java.util.concurrent.atomic.AtomicLong
import scala.concurrent.Await
import scala.concurrent.Promise
import scala.concurrent.duration.Duration

/**
  * Accumulated test statistics across all test classes. Uses AtomicLong for thread-safety since sbt
  * may run test tasks concurrently.
  */
class TestStats:
  private val _passed    = AtomicLong(0)
  private val _failed    = AtomicLong(0)
  private val _skipped   = AtomicLong(0)
  private val _pending   = AtomicLong(0)
  private val _cancelled = AtomicLong(0)
  private val _ignored   = AtomicLong(0)
  private val _errors    = AtomicLong(0)
  private val _totalTime = AtomicLong(0)

  def addTime(nanos: Long): Unit = _totalTime.addAndGet(nanos)

  def addResult(result: TestResult): Unit =
    result match
      case _: TestResult.Success =>
        _passed.incrementAndGet()
      case _: TestResult.Failure =>
        _failed.incrementAndGet()
      case _: TestResult.Error =>
        _errors.incrementAndGet()
      case _: TestResult.Skipped =>
        _skipped.incrementAndGet()
      case _: TestResult.Pending =>
        _pending.incrementAndGet()
      case _: TestResult.Cancelled =>
        _cancelled.incrementAndGet()
      case _: TestResult.Ignored =>
        _ignored.incrementAndGet()

  def totalTime: Long = _totalTime.get()

  def summary: String =
    val p = _passed.get()
    val f = _failed.get() + _errors.get()
    val s = _skipped.get()
    val o = _pending.get() + _cancelled.get() + _ignored.get()
    if p + f + s + o == 0 then
      ""
    else
      TestStats.formatSummary(passed = p, failed = f, skipped = s, pending = o)

end TestStats

object TestStats:
  def formatTime(nanos: Long): String =
    val ms = nanos / 1000000
    if ms >= 1000 then
      f"${ms / 1000.0}%.2fs"
    else
      s"${ms}ms"

  def formatSummary(passed: Long, failed: Long, skipped: Long, pending: Long): String =
    val total = passed + failed + skipped + pending
    val parts =
      List(s"${total} tests", s"${passed} passed") ++
        List(
          (failed > 0, s"${failed} failed"),
          (skipped > 0, s"${skipped} skipped"),
          (pending > 0, s"${pending} pending")
        ).collect { case (true, part) =>
          part
        }
    parts.mkString(", ")

end TestStats

/**
  * sbt test task that executes tests for a single test class
  */
class UniTestTask(
    _taskDef: TaskDef,
    testClassLoader: ClassLoader,
    config: TestConfig,
    stats: TestStats
) extends Task:

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
    * async paths use.
    */
  def execute(
      eventHandler: EventHandler,
      loggers: Array[sbt.testing.Logger],
      continuation: Array[Task] => Unit
  ): Unit =
    implicit val ec = compat.executionContext
    val className   = taskDef().fullyQualifiedName()

    try
      // Load the test class using platform-specific reflection
      val testInstance = compat.newInstance(className, testClassLoader)
      runTests(testInstance, className, eventHandler, loggers)
    catch
      case e: Throwable =>
        // Unwrap exception to get the actual cause
        val cause           = compat.findCause(e)
        val (event, logMsg) = classifySpecLevelException(className, cause)
        eventHandler.handle(event)
        loggers.foreach(_.info(logMsg))
        // Only trace for actual errors, not for skipped/pending/cancelled tests
        cause match
          case _: TestSkipped | _: TestPending | _: TestCancelled | _: TestIgnored =>
          // Don't trace for expected test control flow exceptions
          case _ =>
            loggers.foreach(_.trace(cause))
    finally
      continuation(Array.empty)
    end try

  end execute

  private def runTests(
      testInstance: UniTest,
      className: String,
      eventHandler: EventHandler,
      loggers: Array[sbt.testing.Logger]
  ): Unit =
    val allTests      = testInstance.registeredTests
    val filteredTests =
      config.testFilter match
        case Some(filter) =>
          allTests.filter(_.fullName.contains(filter))
        case None =>
          allTests

    if filteredTests.isEmpty then
      // No tests registered via test() DSL, log info
      loggers.foreach(_.info(s"No tests found in ${className}"))
    else
      val classStartTime = System.nanoTime()
      loggers.foreach(_.info(s"${className}:"))

      // Queue-based approach to handle dynamically registered nested tests
      val testQueue     = scala.collection.mutable.Queue.from(filteredTests)
      val executedTests = scala.collection.mutable.Set.empty[String]
      var classPassed   = 0L
      var classFailed   = 0L
      var classSkipped  = 0L
      var classPending  = 0L

      while testQueue.nonEmpty do
        val testDef = testQueue.dequeue()
        if !executedTests.contains(testDef.fullName) then
          executedTests.add(testDef.fullName)

          val beforeCount = testInstance.registeredTests.size
          val testStart   = System.nanoTime()
          val result      = testInstance.executeTest(testDef)
          val testElapsed = System.nanoTime() - testStart
          val isContainer = testInstance.registeredTests.size > beforeCount

          if result.isFailure || !isContainer then
            val event = createEvent(testDef.fullName, result, testElapsed)
            eventHandler.handle(event)
            logResult(result, testElapsed, loggers)
            stats.addResult(result)
            result match
              case _: TestResult.Success =>
                classPassed += 1
              case _: TestResult.Failure | _: TestResult.Error =>
                classFailed += 1
              case _: TestResult.Skipped =>
                classSkipped += 1
              case _: TestResult.Pending | _: TestResult.Cancelled | _: TestResult.Ignored =>
                classPending += 1

          if !result.isFailure && isContainer then
            testInstance
              .registeredTests
              .foreach { t =>
                if !executedTests.contains(t.fullName) then
                  testQueue.enqueue(t)
              }
        end if
      end while

      val classElapsed = System.nanoTime() - classStartTime
      stats.addTime(classElapsed)
      val classSummary = TestStats.formatSummary(
        classPassed,
        classFailed,
        classSkipped,
        classPending
      )
      val classTimeStr = TestStats.formatTime(classElapsed)
      val summaryLine  = s"  ${classSummary} (${classTimeStr})"
      val summaryColor =
        if classFailed > 0 then
          Tint.red(summaryLine)
        else
          Tint.green(summaryLine)
      loggers.foreach(_.info(summaryColor))
    end if

  end runTests

  private def logResult(
      result: TestResult,
      elapsedNanos: Long,
      loggers: Array[sbt.testing.Logger]
  ): Unit =
    val timeStr = TestStats.formatTime(elapsedNanos)
    result match
      case TestResult.Success(name) =>
        loggers.foreach(_.info(Tint.green(s"  + ${name}") + Tint.gray(s" (${timeStr})")))
      case TestResult.Failure(name, msg, cause) =>
        loggers.foreach(_.error(Tint.red(s"  - ${name}: ${msg}") + Tint.gray(s" (${timeStr})")))
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
        loggers.foreach(_.error(Tint.red(s"  x ${name}: ${msg}") + Tint.gray(s" (${timeStr})")))
        loggers.foreach(_.trace(cause))
      case TestResult.Skipped(name, reason) =>
        loggers.foreach(
          _.info(Tint.yellow(s"  ~ ${name}: skipped - ${reason}") + Tint.gray(s" (${timeStr})"))
        )
      case TestResult.Pending(name, reason) =>
        loggers.foreach(
          _.info(Tint.magenta(s"  ? ${name}: pending - ${reason}") + Tint.gray(s" (${timeStr})"))
        )
      case TestResult.Cancelled(name, reason) =>
        loggers.foreach(
          _.info(Tint.cyan(s"  ! ${name}: cancelled - ${reason}") + Tint.gray(s" (${timeStr})"))
        )
      case TestResult.Ignored(name, reason) =>
        loggers.foreach(
          _.info(Tint.gray(s"  - ${name}: ignored - ${reason}") + Tint.gray(s" (${timeStr})"))
        )
    end match

  end logResult

  private def createEvent(testName: String, result: TestResult, elapsedNanos: Long = 0L): Event =
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
      elapsedNanos / 1000000 // Convert nanos to millis for sbt event
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
