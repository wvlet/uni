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

import wvlet.uni.design.Session

import scala.collection.mutable.ListBuffer
import scala.concurrent.{ExecutionContext, Future}

/**
  * Records what the objects below open and close, so tests can check the order.
  */
class EventLog:
  private val buf = ListBuffer.empty[String]

  def add(event: String): Unit = buf.synchronized(buf += event)
  def events: Seq[String]      = buf.synchronized(buf.toList)
  def clear(): Unit            = buf.synchronized(buf.clear())

/**
  * Stand-ins for a server, a client of it, and a per-test temp directory.
  */
abstract class Logged(name: String, log: EventLog) extends AutoCloseable:
  log.add(s"open ${name}")
  @volatile
  private var open = true

  def isOpen: Boolean = open

  override def close(): Unit =
    open = false
    log.add(s"close ${name}")

class Server(log: EventLog)                 extends Logged("server", log)
class Client(server: Server, log: EventLog) extends Logged("client", log)
class TempDir(log: EventLog)                extends Logged("tempDir", log)

/**
  * Fixture traits as documented in docs/core/unitest.md ("Reusable Fixtures"): each adds bindings,
  * and mixing traits combines them.
  */
trait ServerFixture extends UniTest:
  addDesign(_.bindSingleton[Server])

trait ClientFixture extends ServerFixture:
  addDesign(_.bindSingleton[Client])

trait TempDirFixture extends UniTest:
  addTestDesign(_.bindSingleton[TempDir])

class DesignTestTest extends ClientFixture with TempDirFixture:
  private given ExecutionContext = compat.executionContext

  private val log = DesignTestTest.log
  addDesign(_.bindInstance[EventLog](log))

  // Tests of a suite run one at a time in registration order, so they can hand values forward.
  private var firstClient: Option[Client] = None
  private var lastDir: Option[TempDir]    = None

  test("an ordinary test opens no session") {
    log.events shouldBe Nil
  }

  test("arguments are built from the suite's design") { (client: Client) =>
    log.events shouldBe Seq("open server", "open client")
    client.isOpen shouldBe true
    firstClient = Some(client)
  }

  test("objects bound with addDesign are shared by the suite") { (client: Client, dir: TempDir) =>
    client shouldBeTheSameInstanceAs firstClient.get
    lastDir = Some(dir)
  }

  test("objects bound with addTestDesign are fresh in each test") { (dir: TempDir) =>
    dir shouldNotBeTheSameInstanceAs lastDir.get
    lastDir.get.isOpen shouldBe false
    lastDir = Some(dir)
  }

  test("an async test keeps its objects open until the Future completes") { (dir: TempDir) =>
    lastDir = Some(dir)
    // Hop through the execution context twice, so that a shutdown on the body's return (instead
    // of on the Future's completion) would close it first. This is deterministic on single-threaded
    // Scala.js.
    Future(()).flatMap(_ => Future(dir.isOpen)).map(_ shouldBe true)
  }

  test("the async test's objects were closed after its Future completed") {
    lastDir.get.isOpen shouldBe false
  }

  test("a test can take its session") { (session: Session) =>
    session.build[Client] shouldBeTheSameInstanceAs firstClient.get
  }

  test("up to five arguments") { (a: Client, b: Server, c: TempDir, d: EventLog, e: Session) =>
    (a.isOpen && b.isOpen && c.isOpen) shouldBe true
    d shouldBeTheSameInstanceAs log
  }

  test("nested tests") {
    test("a nested test gets its own objects") { (dir: TempDir) =>
      dir shouldNotBeTheSameInstanceAs lastDir.get
      dir.isOpen shouldBe true
    }
  }

  test("addDesign cannot be called once the tests run") {
    intercept[IllegalStateException] {
      addDesign(_.bindSingleton[TempDir])
    }
  }

end DesignTestTest

object DesignTestTest:
  private val log = EventLog()
