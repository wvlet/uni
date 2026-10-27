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

import org.junit.platform.engine.TestExecutionResult
import org.junit.platform.engine.discovery.DiscoverySelectors
import org.junit.platform.launcher.{TestExecutionListener, TestIdentifier}
import org.junit.platform.launcher.core.{LauncherDiscoveryRequestBuilder, LauncherFactory}
import sbt.testing.{Event, EventHandler, Selector, Status, TaskDef}
import wvlet.uni.test.spi.{TestConfig, TestStats, UniTestFingerprint, UniTestTask}

import scala.collection.mutable.ListBuffer

/**
  * Runs suites through the real runners, sbt's UniTestTask and the JUnit engine used by IDEs, to
  * check what a suite cannot observe about itself: shutdown after the last test, and what happens
  * when an object or a hook fails.
  */
class DesignLifecycleTest extends UniTest:
  import DesignLifecycleTest.*

  private def runSbtEvents(suite: Class[? <: UniTest]): Seq[Event] =
    val events = ListBuffer.empty[Event]
    UniTestTask(
      TaskDef(suite.getName, UniTestFingerprint, false, Array.empty[Selector]),
      suite.getClassLoader,
      TestConfig(),
      TestStats()
    ).execute(
      new EventHandler:
        def handle(e: Event): Unit = events.synchronized(events += e)
      ,
      Array.empty
    )
    events.toSeq

  // The status of each reported event: one per test, plus one per suite-level error
  private def runSbt(suite: Class[? <: UniTest]): Seq[Status] = runSbtEvents(suite).map(_.status())

  // The result of each finished test and of the suite
  private def runJUnitResults(suite: Class[? <: UniTest]): Seq[TestExecutionResult] =
    val results = ListBuffer.empty[TestExecutionResult]
    val request = LauncherDiscoveryRequestBuilder
      .request()
      .selectors(DiscoverySelectors.selectClass(suite))
      .build()
    LauncherFactory
      .create()
      .execute(
        request,
        new TestExecutionListener:
          override def executionFinished(id: TestIdentifier, r: TestExecutionResult): Unit =
            if id.getSource.isPresent then
              results += r
      )
    results.toSeq

  private def runJUnit(suite: Class[? <: UniTest]): Seq[TestExecutionResult.Status] =
    runJUnitResults(suite).map(_.getStatus)

  // Whether the error carries a suppressed error whose message contains the text
  private def hasSuppressed(e: Throwable, text: String): Boolean = e
    .getSuppressed
    .flatMap(s => Option(s.getMessage))
    .exists(_.contains(text))

  private val healthyEvents = Seq(
    "open server",
    "open client",
    "open tempDir",
    "close tempDir",
    "open tempDir",
    "close tempDir",
    "close client",
    "close server"
  )

  test("objects are closed per test, and in reverse creation order after the suite") {
    log.clear()
    runSbt(classOf[Healthy]) shouldBe Seq(Status.Success, Status.Success)
    log.events shouldBe healthyEvents
  }

  test("an object that fails to start fails the test that needs it; started ones are closed") {
    log.clear()
    runSbt(classOf[ClientFailsToOpen]) shouldBe Seq(Status.Error, Status.Success)
    log.events shouldBe Seq("open server", "close server")
  }

  test("an object that fails to close does not keep the others open") {
    log.clear()
    runSbt(classOf[ClientFailsToClose]) shouldBe Seq(Status.Success, Status.Error)
    log.events shouldBe Seq("open server", "open client", "close server")
  }

  test("a shutdown error is attached to the after hook's error") {
    log.clear()
    val events = runSbtEvents(classOf[AfterAndCloseFail])
    events.map(_.status()) shouldBe Seq(Status.Error)
    val e = events.head.throwable().get()
    e.getMessage shouldContain "after failed"
    hasSuppressed(e, "close failed") shouldBe true
  }

  private val beforeFailsEvents = Seq(
    // The failed test: `after` runs, but its body and session do not
    "after",
    "open server",
    "open client",
    "open tempDir",
    "after",
    "close tempDir",
    "close client",
    "close server"
  )

  test("when before throws, after still runs, but the test's body and session do not") {
    log.clear()
    runSbt(classOf[BeforeFails]) shouldBe Seq(Status.Error, Status.Success)
    log.events shouldBe beforeFailsEvents
  }

  test("the JUnit engine runs after when before throws") {
    log.clear()
    runJUnit(classOf[BeforeFails]) shouldBe
      Seq(
        TestExecutionResult.Status.FAILED,
        TestExecutionResult.Status.SUCCESSFUL,
        TestExecutionResult.Status.SUCCESSFUL
      )
    log.events shouldBe beforeFailsEvents
  }

  test("when before and after both throw, the after error is attached to the before error") {
    val events = runSbtEvents(classOf[BeforeAndAfterFail])
    events.map(_.status()) shouldBe Seq(Status.Error)
    val e = events.head.throwable().get()
    e.getMessage shouldContain "setup failed"
    hasSuppressed(e, "after failed") shouldBe true

    val junitError = runJUnitResults(classOf[BeforeAndAfterFail]).head.getThrowable.get()
    junitError.getMessage shouldContain "setup failed"
    hasSuppressed(junitError, "after failed") shouldBe true
  }

  test("a suite session that fails to start fails every test that needs it") {
    log.clear()
    runSbt(classOf[SuiteSessionFailsToStart]) shouldBe
      Seq(Status.Error, Status.Error, Status.Success)
    startCalls shouldBe 1
  }

  test("the JUnit engine runs the same lifecycle") {
    log.clear()
    runJUnit(classOf[Healthy]).count(_ == TestExecutionResult.Status.SUCCESSFUL) shouldBe 3
    log.events shouldBe healthyEvents
  }

end DesignLifecycleTest

object DesignLifecycleTest:
  // Suites nested in an object are not discovered by sbt; only the tests above run them.
  private val log = EventLog()

  trait Fixtures extends ClientFixture with TempDirFixture:
    addDesign(_.bindInstance[EventLog](log))

  class Healthy extends Fixtures:
    test("first") { (client: Client, dir: TempDir) =>
      (client.isOpen && dir.isOpen) shouldBe true
    }

    test("second") { (dir: TempDir) =>
      dir.isOpen shouldBe true
    }

  class ClientFailsToOpen extends ServerFixture:
    addDesign(
      _.bindInstance[EventLog](log)
        .bindProvider[Server, Client](_ => throw IllegalStateException("connection refused"))
    )

    test("needs the client") { (client: Client) =>
      fail("the client should not have been built")
    }

    test("needs only the server") { (server: Server) =>
      server.isOpen shouldBe true
    }

  class LeakyClient(server: Server, log: EventLog) extends Client(server, log):
    override def close(): Unit = throw IllegalStateException("close failed")

  class ClientFailsToClose extends ServerFixture:
    addDesign(_.bindInstance[EventLog](log).bindImpl[Client, LeakyClient])

    test("passes") { (client: Client) =>
      client.isOpen shouldBe true
    }

  class AfterAndCloseFail extends TempDirFixture:
    addDesign(_.bindInstance[EventLog](log))
    addTestDesign(
      _.bindSingleton[TempDir].onShutdown(_ => throw IllegalStateException("close failed"))
    )

    override protected def after: Unit = throw IllegalStateException("after failed")

    test("passes, then its teardown fails") { (dir: TempDir) =>
      dir.isOpen shouldBe true
    }

  private var startCalls = 0

  class SuiteSessionFailsToStart extends UniTest:
    addDesign(
      _.bindSingleton[Server]
        .onStart { _ =>
          startCalls += 1
          throw IllegalStateException("start failed")
        }
        .bindInstance[EventLog](log)
        .withProductionMode
    )

    test("first") { (server: Server) =>
      fail("never runs")
    }

    test("second") { (server: Server) =>
      fail("never runs")
    }

    test("plain tests still run") {
      startCalls shouldBe 1
    }

  class BeforeFails extends Fixtures:
    private var failNext = true

    override protected def before: Unit =
      if failNext then
        failNext = false
        throw IllegalStateException("setup failed")

    override protected def after: Unit = log.add("after")

    test("errors in before") { (dir: TempDir) =>
      fail("never runs")
    }

    test("passes") { (client: Client, dir: TempDir) =>
      dir.isOpen shouldBe true
    }

  class BeforeAndAfterFail extends UniTest:
    override protected def before: Unit = throw IllegalStateException("setup failed")
    override protected def after: Unit  = throw IllegalStateException("after failed")

    test("errors in before") {
      fail("never runs")
    }

end DesignLifecycleTest
