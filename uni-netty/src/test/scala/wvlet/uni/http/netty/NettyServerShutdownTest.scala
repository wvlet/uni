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

import wvlet.uni.http.Response
import wvlet.uni.test.UniTest

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.util.concurrent.{CopyOnWriteArrayList, CountDownLatch, TimeUnit}
import java.util.logging.{Handler, Level, LogRecord, Logger as JLogger}
import scala.jdk.CollectionConverters.*

class NettyServerShutdownTest extends UniTest:

  private def get(client: HttpClient, port: Int, path: String): HttpResponse[String] = client.send(
    HttpRequest.newBuilder(URI.create(s"http://127.0.0.1:${port}${path}")).GET().build(),
    HttpResponse.BodyHandlers.ofString()
  )

  // Collects the warnings Netty logs while f runs
  private def nettyWarnings(f: => Unit): Seq[String] =
    val records = CopyOnWriteArrayList[String]()
    val handler =
      new Handler:
        override def publish(r: LogRecord): Unit =
          if r.getLevel.intValue() >= Level.WARNING.intValue() then
            records.add(s"${r.getLoggerName}: ${r.getMessage}")
        override def flush(): Unit = ()
        override def close(): Unit = ()
    val nettyLogger = JLogger.getLogger("io.netty")
    nettyLogger.addHandler(handler)
    try
      f
      records.asScala.toSeq
    finally
      nettyLogger.removeHandler(handler)

  test("stop a server with handler threads while a client keeps a connection open") {
    val client = HttpClient.newHttpClient()
    val server = NettyServer
      .withPort(0)
      .withGracefulShutdown(quietPeriodSeconds = 0, timeoutSeconds = 5)
      .withHandlerExecutorThreads(2)
      .withHandler(_ => Response.ok("ok"))
      .start()
    val warnings = nettyWarnings {
      try get(client, server.localPort, "/").body() shouldBe "ok"
      finally server.stop()
      // The client's pooled connection is closed by the server, not left for later events
      intercept[java.io.IOException] {
        get(client, server.localPort, "/")
      }
    }
    warnings shouldBe Seq.empty
  }

  test("finish an in-flight request during stop") {
    val client  = HttpClient.newHttpClient()
    val entered = CountDownLatch(1)
    val server  = NettyServer
      .withPort(0)
      .withGracefulShutdown(quietPeriodSeconds = 0, timeoutSeconds = 5)
      .withHandlerExecutorThreads(2)
      .withHandler { _ =>
        entered.countDown()
        Thread.sleep(300)
        Response.ok("done")
      }
      .start()
    val response = client.sendAsync(
      HttpRequest.newBuilder(URI.create(s"http://127.0.0.1:${server.localPort}/slow")).build(),
      HttpResponse.BodyHandlers.ofString()
    )
    entered.await(5, TimeUnit.SECONDS) shouldBe true
    server.stop()
    response.get(5, TimeUnit.SECONDS).body() shouldBe "done"
  }

end NettyServerShutdownTest
