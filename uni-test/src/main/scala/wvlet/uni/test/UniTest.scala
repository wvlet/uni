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
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

/**
  * Test definition representing a single test case
  */
case class TestDef(
    name: String,
    body: () => Any,
    parent: List[String] = Nil,
    isFlaky: Boolean = false,
    tags: Set[String] = Set.empty
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
trait UniTest
    extends PlatformUniTest
    with LogSupport
    with Assertions
    with TestControl
    with PropertyCheck:
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
    * @param tags
    *   labels for selecting/excluding this test at run time (e.g. `"ui"`, `"electron"`, `"slow"`).
    *   Filter from sbt with `-tag:<a>,<b>` (run tests with any of these tags) and `-xtag:<a>,<b>`
    *   (skip tests with any of these tags). Tags let one suite span multiple testing layers and
    *   still be run layer-by-layer, as in VSCode's separate unit/integration/UI test commands.
    * @param body
    *   the test body
    */
  protected def test(name: String, flaky: Boolean = false, tags: Seq[String] = Nil)(
      body: => Any
  ): Unit = _tests += TestDef(name, () => body, _context, isFlaky = flaky, tags = tags.toSet)

  /**
    * Lifecycle hook invoked once before any test in this spec runs. Override to perform setup work
    * that should be shared across the test class instance.
    */
  protected def beforeAll: Unit = {}

  /**
    * Lifecycle hook invoked once after all tests in this spec finish. Override to release resources
    * allocated in [[beforeAll]].
    */
  protected def afterAll: Unit = {}

  /**
    * Lifecycle hook invoked before each individual test. Override for per-test setup.
    */
  protected def before: Unit = {}

  /**
    * Lifecycle hook invoked after each individual test. Override for per-test teardown.
    */
  protected def after: Unit = {}

  // Internal accessors used by the test runner.
  private[test] def runBeforeAll(): Unit = beforeAll
  private[test] def runAfterAll(): Unit  = afterAll
  private[test] def runBefore(): Unit    = before
  private[test] def runAfter(): Unit     = after

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
    * Execute a test, returning Future[TestResult] to support async test bodies on all platforms.
    */
  private[test] def executeTest(testDef: TestDef): Future[TestResult] =
    given ExecutionContext = wvlet.uni.test.compat.executionContext

    def toTestResult: Try[Any] => Try[TestResult] =
      case Success(_) =>
        Success(TestResult.Success(testDef.fullName))
      case Failure(e) =>
        Success(classifyException(testDef, e))

    try
      // Body runs synchronously (registers nested tests as side effects).
      // Only the return value may be a Future or Rx.
      val result =
        runWithContext(testDef.name) {
          testDef.body()
        }
      result match
        case f: Future[?] =>
          f.transform(toTestResult)
        case rx: RxOps[?] =>
          // Use fully qualified name to avoid shadowing by wvlet.uni.rx.compat
          wvlet.uni.test.compat.runRxAsFuture(rx).transform(toTestResult)
        case _ =>
          Future.successful(TestResult.Success(testDef.fullName))
    catch
      case e: Throwable =>
        Future.successful(classifyException(testDef, e))

  end executeTest

  private def classifyException(testDef: TestDef, e: Throwable): TestResult =
    e match
      case e: TestSkipped =>
        TestResult.Skipped(testDef.fullName, e.getMessage)
      case e: TestPending =>
        TestResult.Pending(testDef.fullName, e.getMessage)
      case e: TestCancelled =>
        TestResult.Cancelled(testDef.fullName, e.getMessage)
      case e: TestIgnored =>
        TestResult.Ignored(testDef.fullName, e.getMessage)
      case _ =>
        if testDef.isFlaky then
          TestResult.Skipped(testDef.fullName, s"[flaky] ${e.getMessage}")
        else
          e match
            case af: AssertionFailure =>
              TestResult.Failure(testDef.fullName, af.getMessage, Some(af))
            case _ =>
              TestResult.Error(testDef.fullName, e.getMessage, e)

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
