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

import java.net.{Socket, URI}
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
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
    val server = NettyServerConfig()
      .withPort(0)
      .withGracefulShutdown(quietPeriodSeconds = 0, timeoutSeconds = 5)
      .withHandlerExecutorThreads(2)
      .withHandler(_ => Response.ok("ok"))
      .start()
    val warnings = nettyWarnings {
      try get(client, server.localPort, "/").body() shouldBe "ok"
      finally server.stop()
      // Reusing the pooled connection used to reach the stopped handler executor
      intercept[java.io.IOException] {
        get(client, server.localPort, "/")
      }
    }
    warnings shouldBe Seq.empty
  }

  test("close an idle keep-alive connection on stop") {
    val server = NettyServerConfig()
      .withPort(0)
      .withGracefulShutdown(quietPeriodSeconds = 0, timeoutSeconds = 5)
      .withHandler(_ => Response.ok("ok"))
      .start()
    val socket = Socket("127.0.0.1", server.localPort)
    try
      socket.setSoTimeout(5000)
      val out = socket.getOutputStream
      out.write("GET / HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1))
      out.flush()
      val in       = socket.getInputStream
      val response = StringBuilder()
      while !response.toString.endsWith("ok") do
        response.append(in.read().toChar)
      server.stop()
      // End of stream: the server closed the connection
      in.read() shouldBe -1
    finally
      socket.close()
  }

  private def slowServer(entered: CountDownLatch) = NettyServerConfig()
    .withPort(0)
    .withGracefulShutdown(quietPeriodSeconds = 0, timeoutSeconds = 5)
    .withHandlerExecutorThreads(2)
    .withHandler { _ =>
      entered.countDown()
      Thread.sleep(300)
      Response.ok("done")
    }
    .start()

  private def stopWhileInFlight(request: Int => HttpRequest): Unit =
    val client   = HttpClient.newHttpClient()
    val entered  = CountDownLatch(1)
    val server   = slowServer(entered)
    val response = client.sendAsync(request(server.localPort), HttpResponse.BodyHandlers.ofString())
    entered.await(5, TimeUnit.SECONDS) shouldBe true
    server.stop()
    response.get(5, TimeUnit.SECONDS).body() shouldBe "done"

  test("finish an in-flight request during stop") {
    stopWhileInFlight(port =>
      HttpRequest.newBuilder(URI.create(s"http://127.0.0.1:${port}/slow")).build()
    )
  }

  test("finish an in-flight Expect: 100-continue request during stop") {
    // The interim 100 Continue must not count as the request's response
    stopWhileInFlight(port =>
      HttpRequest
        .newBuilder(URI.create(s"http://127.0.0.1:${port}/slow"))
        .expectContinue(true)
        .POST(HttpRequest.BodyPublishers.ofString("body"))
        .build()
    )
  }

  test("stop from a request handler") {
    val client                  = HttpClient.newHttpClient()
    val stopped                 = CountDownLatch(1)
    var server: NettyHttpServer = null
    server = NettyServerConfig()
      .withPort(0)
      .withGracefulShutdown(quietPeriodSeconds = 0, timeoutSeconds = 5)
      .withHandler { _ =>
        server.stop()
        stopped.countDown()
        Response.ok("stopping")
      }
      .start()
    // stop() returns at once on the event loop, and the response still reaches the client
    get(client, server.localPort, "/shutdown").body() shouldBe "stopping"
    stopped.await(5, TimeUnit.SECONDS) shouldBe true
    server.isRunning shouldBe false
  }

end NettyServerShutdownTest
