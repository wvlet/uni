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
package wvlet.uni.http.netty

import sbt.testing.{Event, EventHandler, Selector, Status, TaskDef}
import wvlet.uni.http.{Http, HttpHandler, HttpServer, HttpSyncClient, Request, Response}
import wvlet.uni.test.UniTest
import wvlet.uni.test.spi.{TestConfig, TestStats, UniTestFingerprint, UniTestTask}

import java.util.concurrent.atomic.AtomicInteger
import scala.collection.mutable.ListBuffer

/**
  * The fixtures of docs/core/unitest.md ("Reusable Fixtures"), run against a real server.
  */
trait HttpServerFixture extends UniTest:
  // Each suite binds an HttpHandler that defines how its server answers
  addDesign(
    _.bindProvider[HttpHandler, HttpServer](handler =>
      NettyServer.withPort(0).withHandler(handler).start()
    )
  )

trait HttpClientFixture extends HttpServerFixture:
  addDesign(
    _.bindProvider[HttpServer, HttpSyncClient](server =>
      Http.client.withBaseUri(s"http://localhost:${server.localPort}").newSyncClient
    )
  )

class HttpClientFixtureTest extends HttpClientFixture:
  private val requestCount = AtomicInteger(0)

  addDesign(
    _.bindInstance[HttpHandler] { request =>
      requestCount.incrementAndGet()
      Response.ok(s"hello ${request.path}")
    }
  )

  private var firstClient: Option[HttpSyncClient] = None

  test("the client reaches the suite's server") { (client: HttpSyncClient) =>
    firstClient = Some(client)
    client.send(Request.get("/ping")).contentAsString shouldBe Some("hello /ping")
  }

  test("later tests reuse the same server and client") {
    (server: HttpServer, client: HttpSyncClient) =>
      server.isRunning shouldBe true
      client shouldBeTheSameInstanceAs firstClient.get
      client.send(Request.get("/again")).contentAsString shouldBe Some("hello /again")
      requestCount.get() shouldBe 2
  }

end HttpClientFixtureTest

/**
  * Runs a suite through the sbt runner to check that the server is stopped after the suite.
  */
class HttpClientFixtureShutdownTest extends UniTest:
  test("the server is stopped after the suite") {
    val events = ListBuffer.empty[Event]
    UniTestTask(
      TaskDef(
        classOf[HttpClientFixtureShutdownTest.Suite].getName,
        UniTestFingerprint,
        false,
        Array.empty[Selector]
      ),
      getClass.getClassLoader,
      TestConfig(),
      TestStats()
    ).execute(
      new EventHandler:
        def handle(e: Event): Unit = events += e
      ,
      Array.empty
    )
    events.map(_.status()).toSeq shouldBe Seq(Status.Success)
    HttpClientFixtureShutdownTest.started.map(_.isRunning) shouldBe Some(false)
  }

object HttpClientFixtureShutdownTest:
  // Not discovered by sbt, because it is nested in an object
  private var started: Option[HttpServer] = None

  class Suite extends HttpClientFixture:
    addDesign(_.bindInstance[HttpHandler](_ => Response.ok("ok")))

    test("records the server") { (server: HttpServer, client: HttpSyncClient) =>
      started = Some(server)
      client.send(Request.get("/")).contentAsString shouldBe Some("ok")
    }
