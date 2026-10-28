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

import wvlet.uni.design.{Design, Session}
import wvlet.uni.log.LogSupport
import wvlet.uni.surface.Surface
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
    with OutputCapture
    with PropertyCheck:
  // Storage for registered tests
  private val _tests: ListBuffer[TestDef] = ListBuffer.empty

  // Current nesting context for nested tests
  private var _context: List[String] = Nil

  // Common test tags usable unqualified in test bodies; the full set lives in
  // wvlet.uni.test.TestTag (custom tags via TestTag("name") or a String literal).
  export wvlet.uni.test.TestTag.{Flaky, UI, Electron, Integration, Smoke, Slow}

  /**
    * Register a test case with the given name and body.
    *
    * @param name
    *   the test name
    * @param tags
    *   zero or more [[TestTag]]s. Layer tags ([[TestTag.UI]], [[TestTag.Electron]], …) select tests
    *   at run time — filter from sbt with `--tags <tag>` (repeat to narrow further: each tag is
    *   ANDed, like GitHub label filters) and `--exclude-tags <tag>` (skip) — so one suite can be
    *   run layer-by-layer like VSCode's separate unit/integration/UI test commands.
    *   [[TestTag.Flaky]] additionally downgrades a failure to skipped. Pass a custom tag via
    *   `TestTag("name")` or a bare string.
    * @param body
    *   the test body. A function literal with typed parameters receives its arguments from this
    *   suite's Design: objects bound with [[addDesign]] are shared by the suite, and objects bound
    *   with [[addTestDesign]] or not bound at all are built for this test and closed after it. Take
    *   `(session: Session)` to get the test's session itself.
    *   {{{
    *     test("ping") { (client: HttpSyncClient) =>
    *       client.send(Request.get("/ping"))
    *     }
    *   }}}
    */
  protected inline def test(name: String, tags: TestTag*)(inline body: Any): Unit =
    inline if UniTestMacros.isFunctionLiteral(body) then
      sessionTest(name, tags)(body)
    else
      registerTest(name, tags)(body)

  private inline def sessionTest(name: String, tags: Seq[TestTag])(inline body: Any): Unit =
    ${
      UniTestMacros.sessionTest('this, 'name, 'tags, 'body)
    }

  /**
    * Register a test with a plain body. Used by the `test` macro.
    */
  protected[test] def registerTest(name: String, tags: Seq[TestTag])(body: => Any): Unit =
    _tests +=
      TestDef(
        name,
        () => body,
        _context,
        isFlaky = tags.contains(TestTag.Flaky),
        tags = tags.map(_.name).toSet
      )

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

  private var _design: Design     = Design.newSilentDesign
  private var _testDesign: Design = Design.newSilentDesign

  private var _suiteSession: Option[Session]        = None
  private var _suiteSessionError: Option[Throwable] = None
  private var _testsStarted: Boolean                = false
  private var _testSession: Option[Session]         = None

  /**
    * Add bindings shared by the tests of this suite that take arguments, such as a server or a
    * client. Call it from the body of the test class or of a fixture trait; calls compose in trait
    * initialization order:
    * {{{
    *   trait ServerFixture extends UniTest:
    *     addDesign(_.bindProvider[HttpHandler, HttpServer](h => NettyServer.withHandler(h).start()))
    * }}}
    * The suite's session is created when the first test that takes arguments runs, and shut down
    * after [[afterAll]], closing the objects it built in reverse creation order.
    */
  protected def addDesign(f: Design => Design): Unit =
    checkDesignOpen("addDesign")
    _design = f(_design)

  /**
    * Add bindings that each test taking arguments gets fresh, such as a temp directory. They are
    * built in the test's child session and closed after [[after]], once a Future or Rx returned by
    * the test completes. Objects that are not bound anywhere are also built per test.
    */
  protected def addTestDesign(f: Design => Design): Unit =
    checkDesignOpen("addTestDesign")
    _testDesign = f(_testDesign)

  private def checkDesignOpen(method: String): Unit =
    if _testsStarted then
      throw IllegalStateException(
        s"${method} must be called from the body of the test class or a fixture trait, before the tests run"
      )

  /**
    * Register a test whose body receives a session of this suite's Design. Used by the `test` macro
    * for a function-literal body.
    */
  protected[test] def registerSessionTest(name: String, tags: Seq[TestTag])(
      body: Session => Any
  ): Unit = registerTest(name, tags)(body(openTestSession()))

  private def openTestSession(): Session = _testSession.getOrElse {
    // Every test that needs the suite's session fails the same way when it failed to start
    _suiteSessionError.foreach(e => throw e)
    val parent = _suiteSession.getOrElse {
      val s = _design.newSession
      // Recorded before starting, so that runAfterAll shuts down a session that failed to start
      _suiteSession = Some(s)
      try
        s.start
      catch
        case e: Throwable =>
          _suiteSessionError = Some(e)
          throw e
      s
    }
    val child = parent.newChildSession(_testDesign)
    _testSession = Some(child)
    child.start
    child
  }

  // Internal accessors used by the test runner.
  private[test] def runBeforeAll(): Unit =
    _testsStarted = true
    beforeAll

  private[test] def runBefore(): Unit = before

  private[test] def runAfter(): Unit =
    runThenClose(after) {
      val s = _testSession
      _testSession = None
      s.foreach(_.shutdown)
    }

  // Like JUnit's @AfterEach, `after` runs even when `before` failed. The `before` error stays the
  // result, with an `after` error attached as suppressed.
  private[test] def runAfterFailedBefore(beforeError: Throwable): Unit =
    try
      runAfter()
    catch
      case e: Throwable =>
        if e ne beforeError then
          beforeError.addSuppressed(e)

  private[test] def runAfterAll(): Unit =
    runThenClose(afterAll) {
      val s = _suiteSession
      _suiteSession = None
      s.foreach(_.shutdown)
    }

  // Runs the hook, then closes even when it throws. When both fail, the hook's error is rethrown
  // with the close error attached as suppressed.
  private def runThenClose(hook: => Unit)(close: => Unit): Unit =
    val hookError =
      try
        hook
        None
      catch
        case e: Throwable =>
          Some(e)
    try
      close
    catch
      case e: Throwable =>
        hookError match
          case Some(h) =>
            h.addSuppressed(e)
          case None =>
            throw e
    hookError.foreach(e => throw e)

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
  private[test] def executeTest(testDef: TestDef)(using ExecutionContext): Future[TestResult] =
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
          // Subscribe as a task on the runner's execution context, after the body has returned
          Future.delegate(RxTestRunner.firstValue(rx)).transform(toTestResult)
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
            case e: RxCompletedWithoutValue =>
              TestResult.Failure(testDef.fullName, e.getMessage, Some(e))
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
