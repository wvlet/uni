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
package wvlet.uni.design.lifecycle

import wvlet.uni.design.{Design, Session}
import wvlet.uni.test.UniTest

import scala.collection.mutable.ListBuffer

object ChildSessionSingletonTest:
  val events = ListBuffer.empty[String]

  class Server extends AutoCloseable:
    events += "open server"
    @volatile
    var isOpen = true

    override def close(): Unit =
      isOpen = false
      events += "close server"

  trait ClientApi:
    def server: Server

  class Client(val server: Server) extends ClientApi with AutoCloseable:
    events += "open client"
    override def close(): Unit = events += "close client"

/**
  * A singleton bound in a parent session but first requested from a child session must get its
  * dependencies from the parent, or they would be closed with the child while the singleton lives
  * on.
  */
class ChildSessionSingletonTest extends UniTest:
  import ChildSessionSingletonTest.*

  private def check(parentDesign: Design, build: Session => ClientApi = _.build[Client]): Unit =
    events.clear()
    val parent = parentDesign.newSession
    parent.start
    val child = parent.newChildSession(Design.newSilentDesign)
    child.start
    val client = build(child)
    child.shutdown
    client.server.isOpen shouldBe true
    build(parent.newChildSession(Design.newSilentDesign)) shouldBeTheSameInstanceAs client
    parent.shutdown
    events.toList shouldBe List("open server", "open client", "close client", "close server")

  test("a singleton's unbound dependency outlives the child session that built it") {
    check(Design.newSilentDesign.bindSingleton[Client])
  }

  test("the same for a singleton provider") {
    check(Design.newSilentDesign.bindProvider[Server, Client](s => Client(s)))
  }

  test("the same for a singleton bound to an implementation") {
    check(Design.newSilentDesign.bindImpl[ClientApi, Client], _.build[ClientApi])
  }

end ChildSessionSingletonTest
