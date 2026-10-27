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

import java.io.File
import java.nio.file.Paths
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
  * Test bodies must not run on ExecutionContext.global: a body that synchronously awaits a Future
  * submitted to that pool would otherwise occupy the very threads the Future needs.
  *
  * The check runs the sbt runner in a fresh JVM, so the global pool is created with only two
  * threads and no compensation for `blocking`, and runs two suites concurrently as sbt does.
  */
class RunnerStarvationTest extends UniTest:

  test("suites awaiting Futures on a saturated global pool complete") {
    val javaBin = Paths.get(System.getProperty("java.home"), "bin", "java").toString
    val cmd     = List(
      javaBin,
      "-Dscala.concurrent.context.minThreads=2",
      "-Dscala.concurrent.context.numThreads=2",
      "-Dscala.concurrent.context.maxThreads=2",
      "-Dscala.concurrent.context.maxExtraThreads=0",
      "-cp",
      RunnerStarvationMain.classpath,
      RunnerStarvationMain.getClass.getName.stripSuffix("$")
    )
    val process = ProcessBuilder(cmd.asJava).redirectErrorStream(true).start()
    try
      // Read on another thread, so that waitFor below can time out if the child hangs
      val output =
        Future(String(process.getInputStream.readAllBytes()))(using ExecutionContext.global)
      val exited = process.waitFor(60, TimeUnit.SECONDS)
      exited shouldBe true
      val log = Await.result(output, 10.seconds)
      debug(log)
      log shouldContain "starvation-check: 2 passed, 0 failed"
      process.exitValue() shouldBe 0
    finally
      process.destroyForcibly()
  }

end RunnerStarvationTest

/**
  * Entry point of the forked JVM. Runs the fixture suites through the sbt runner, each on its own
  * thread, and prints the outcome.
  */
object RunnerStarvationMain:
  // Both suites must hold their runner thread at the same time for the pool to be saturated
  private[spi] val rendezvous = CyclicBarrier(2)

  private val fixtures = Seq(classOf[StarvationFixtureA], classOf[StarvationFixtureB])

  /**
    * The classpath of the forked JVM. The test may run in sbt's own JVM, so java.class.path does
    * not contain the test classes; collect the locations of the classes the runner needs instead.
    */
  def classpath: String =
    val anchors = Seq(
      getClass,
      classOf[UniTest],
      classOf[sbt.testing.Framework],
      wvlet.uni.cli.Tint.getClass,
      classOf[scala.Predef$],
      classOf[scala.util.boundary$]
    )
    anchors
      .map(c => Paths.get(c.getProtectionDomain.getCodeSource.getLocation.toURI).toString)
      .distinct
      .mkString(File.pathSeparator)

  def main(args: Array[String]): Unit =
    val runner = Framework().runner(Array.empty, Array.empty, getClass.getClassLoader)
    val tasks  = runner.tasks(
      fixtures
        .map(c => TaskDef(c.getName, UniTestFingerprint, false, Array(SuiteSelector())))
        .toArray
    )
    val events  = ConcurrentLinkedQueue[Event]()
    val handler =
      new EventHandler:
        override def handle(event: Event): Unit = events.add(event)
    val logger =
      new Logger:
        override def ansiCodesSupported(): Boolean = false
        override def error(msg: String): Unit      = println(msg)
        override def warn(msg: String): Unit       = println(msg)
        override def info(msg: String): Unit       = println(msg)
        override def debug(msg: String): Unit      = ()
        override def trace(t: Throwable): Unit     = t.printStackTrace(System.out)

    val threads = tasks.map { task =>
      val t = Thread(() => task.execute(handler, Array(logger)): Unit)
      t.setDaemon(true)
      t.start()
      t
    }
    val deadline = 30.seconds.fromNow
    threads.foreach(_.join(deadline.timeLeft.toMillis.max(1)))
    runner.done()

    val all    = events.asScala.toSeq
    val passed = all.count(_.status() == Status.Success)
    val failed = all.size - passed
    val hung   = threads.count(_.isAlive)
    println(s"starvation-check: ${passed} passed, ${failed} failed, ${hung} hung")
    // Exit explicitly: hung runner threads must not keep this JVM alive
    System.exit(
      if passed == fixtures.size && failed == 0 && hung == 0 then
        0
      else
        1
    )

  end main

  /**
    * Wait until both suites run, then block on a trivial Future scheduled on the global pool
    */
  private[spi] def awaitOnGlobalPool(): Unit =
    rendezvous.await(10, TimeUnit.SECONDS)
    val f = Future(1)(using ExecutionContext.global)
    require(Await.result(f, 5.seconds) == 1)

end RunnerStarvationMain

// Package-private, so sbt does not discover these suites; only RunnerStarvationMain runs them
private[spi] class StarvationFixtureA extends UniTest:
  test("await a Future on the global pool") {
    RunnerStarvationMain.awaitOnGlobalPool()
  }

private[spi] class StarvationFixtureB extends UniTest:
  test("await a Future on the global pool") {
    RunnerStarvationMain.awaitOnGlobalPool()
  }
