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
import wvlet.uni.test.Framework
import wvlet.uni.test.UniTest

import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters.*

/**
  * Checks what the sbt runner writes to the loggers of a task, which sbt prints as one block per
  * suite
  */
class RunnerOutputTest extends UniTest:

  /**
    * Run a fixture suite through a fresh runner on its own thread, as sbt does, and return the
    * lines written to the task's logger, without ANSI colors
    */
  private def runFixture(cls: Class[?]): Seq[String] =
    val runner = Framework().runner(Array("--log-capture"), Array.empty, getClass.getClassLoader)
    val task   =
      runner
        .tasks(Array(TaskDef(cls.getName, UniTestFingerprint, false, Array(SuiteSelector()))))
        .head
    val lines  = ConcurrentLinkedQueue[String]()
    val logger =
      new Logger:
        private def add(msg: String): Unit = lines.add(msg.replaceAll("\u001b\\[[0-9;]*m", ""))
        override def ansiCodesSupported(): Boolean = false
        override def error(msg: String): Unit      = add(msg)
        override def warn(msg: String): Unit       = add(msg)
        override def info(msg: String): Unit       = add(msg)
        override def debug(msg: String): Unit      = ()
        override def trace(t: Throwable): Unit     = ()
    val handler =
      new EventHandler:
        override def handle(event: Event): Unit = ()
    var error: Throwable = null
    val t                = Thread { () =>
      try
        task.execute(handler, Array(logger))
        ()
      catch
        case e: Throwable =>
          error = e
    }
    t.start()
    t.join(30000)
    runner.done()
    t.isAlive shouldBe false
    if error != null then
      throw error
    lines.asScala.toSeq

  end runFixture

  test("log records of a test appear between the class header and its result") {
    val lines = runFixture(classOf[LoggingFixture])
    debug(lines.mkString("\n"))
    val header  = lines.indexWhere(_.endsWith("LoggingFixture:"))
    val log     = lines.indexWhere(_.contains("log from the failing test"))
    val failure = lines.indexWhere(_.contains("- logs and fails"))
    (header >= 0) shouldBe true
    (header < log) shouldBe true
    (log < failure) shouldBe true
  }

  test("a suite cancelled in beforeAll is not counted as failed") {
    val lines = runFixture(classOf[CancelInBeforeAllFixture])
    debug(lines.mkString("\n"))
    lines.exists(_.contains("cancelled - no credentials")) shouldBe true
    lines.exists(_.contains("failed")) shouldBe false
  }

  test("a suite skipped in its constructor prints its class header") {
    val lines = runFixture(classOf[SkipInConstructorFixture])
    debug(lines.mkString("\n"))
    val header = lines.indexWhere(_.endsWith("SkipInConstructorFixture:"))
    val skip   = lines.indexWhere(_.contains("skipped - not available"))
    (header >= 0) shouldBe true
    (header < skip) shouldBe true
  }

  test("log capture is on in CI unless a test argument says otherwise") {
    TestConfig.isCI(Map("CI" -> "true")) shouldBe true
    TestConfig.isCI(Map("CI" -> "false")) shouldBe false
    TestConfig.isCI(Map.empty) shouldBe false
    TestConfig.parse(Array("--log-capture")).logCaptureEnabled shouldBe true
    TestConfig.parse(Array("--no-log-capture")).logCaptureEnabled shouldBe false
  }

end RunnerOutputTest

// Package-private, so sbt does not discover these suites; only RunnerOutputTest runs them
private[spi] class LoggingFixture extends UniTest:
  test("logs and fails") {
    warn("log from the failing test")
    1 shouldBe 2
  }

private[spi] class CancelInBeforeAllFixture extends UniTest:
  override protected def beforeAll: Unit = cancel("no credentials")

  test("never runs") {}

private[spi] class SkipInConstructorFixture extends UniTest:
  skip("not available")

  test("never runs") {}
