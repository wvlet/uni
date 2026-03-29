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

import wvlet.uni.log.LogSupport
import wvlet.uni.rx.*
import scala.collection.mutable.ListBuffer
import scala.concurrent.Future

/**
  * Test definition representing a single test case
  */
case class TestDef(
    name: String,
    body: () => Any,
    parent: List[String] = Nil,
    isFlaky: Boolean = false
):
  /**
    * Full test name including parent context
    */
  def fullName: String =
    if parent.isEmpty then
      name
    else
      (parent.reverse :+ name).mkString(" / ")

/**
  * Main trait for writing tests with UniTest.
  *
  * Example usage:
  * {{{
  *   class MyTest extends UniTest:
  *     test("basic assertion") {
  *       1 + 1 shouldBe 2
  *     }
  *
  *     test("nested tests") {
  *       val list = List(1, 2, 3)
  *
  *       test("contains check") {
  *         list shouldContain 2
  *       }
  *
  *       test("size check") {
  *         list.size shouldBe 3
  *       }
  *     }
  * }}}
  */
trait UniTest extends PlatformUniTest with LogSupport with Assertions with TestControl:
  // Storage for registered tests
  private val _tests: ListBuffer[TestDef] = ListBuffer.empty

  // Current nesting context for nested tests
  private var _context: List[String] = Nil

  /**
    * Register a test case with the given name and body
    *
    * @param name
    *   the test name
    * @param flaky
    *   if true, test failures will be reported as skipped instead of failures
    * @param body
    *   the test body
    */
  protected def test(name: String, flaky: Boolean = false)(body: => Any): Unit =
    _tests += TestDef(name, () => body, _context, isFlaky = flaky)

  /**
    * Get all registered tests. Used by the test framework.
    */
  private[test] def registeredTests: Seq[TestDef] = _tests.toSeq

  /**
    * Run a test body with the given context. Used internally for nested test support.
    */
  private[test] def runWithContext(testName: String)(body: => Any): Any =
    val previousContext = _context
    _context = testName :: _context
    try body
    finally _context = previousContext

  /**
    * Execute a specific test by name
    */
  private[test] def executeTest(testDef: TestDef): TestResult =
    try
      runWithContext(testDef.name) {
        testDef.body() match
          case rx: RxOps[?] =>
            // Handle async Rx test - run and await result
            awaitRx(rx)
          case f: Future[?] =>
            // Handle async Future test - await result
            awaitFuture(f)
          case other =>
            other
      }
      TestResult.Success(testDef.fullName)
    catch
      case e: TestSkipped =>
        TestResult.Skipped(testDef.fullName, e.getMessage)
      case e: TestPending =>
        TestResult.Pending(testDef.fullName, e.getMessage)
      case e: TestCancelled =>
        TestResult.Cancelled(testDef.fullName, e.getMessage)
      case e: TestIgnored =>
        TestResult.Ignored(testDef.fullName, e.getMessage)
      case e: Throwable =>
        if testDef.isFlaky then
          TestResult.Skipped(testDef.fullName, s"[flaky] ${e.getMessage}")
        else
          e match
            case af: AssertionFailure =>
              TestResult.Failure(testDef.fullName, af.getMessage, Some(af))
            case _ =>
              TestResult.Error(testDef.fullName, e.getMessage, e)

  /**
    * Await the result of an Rx stream. Uses platform-specific implementation. On JVM/Native: blocks
    * until result is available. On JS: runs the Rx for side effects (non-blocking).
    */
  private def awaitRx[A](rx: RxOps[A]): A =
    // Use the platform-specific runRxTest method from test compat
    // Note: Use fully qualified name to avoid shadowing by wvlet.uni.rx.compat
    wvlet.uni.test.compat.runRxTest(rx)

  /**
    * Await the result of a Future. Uses platform-specific implementation. On JVM/Native: blocks
    * with Await.result. On JS: extracts the value if already completed.
    */
  private def awaitFuture[A](future: Future[A]): A = wvlet.uni.test.compat.runFutureTest(future)

end UniTest

/**
  * Test result representation
  */
enum TestResult:
  case Success(name: String)
  case Failure(name: String, message: String, cause: Option[Throwable])
  case Error(name: String, message: String, cause: Throwable)
  case Skipped(name: String, reason: String)
  case Pending(name: String, reason: String)
  case Cancelled(name: String, reason: String)
  case Ignored(name: String, reason: String)

  def isSuccess: Boolean =
    this match
      case Success(_) =>
        true
      case _ =>
        false

  def isFailure: Boolean =
    this match
      case Failure(_, _, _) =>
        true
      case Error(_, _, _) =>
        true
      case _ =>
        false

  def testName: String =
    this match
      case Success(n) =>
        n
      case Failure(n, _, _) =>
        n
      case Error(n, _, _) =>
        n
      case Skipped(n, _) =>
        n
      case Pending(n, _) =>
        n
      case Cancelled(n, _) =>
        n
      case Ignored(n, _) =>
        n

end TestResult
