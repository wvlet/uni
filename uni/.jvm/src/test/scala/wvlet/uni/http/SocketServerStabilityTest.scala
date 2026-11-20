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
package wvlet.uni.http

import wvlet.uni.rx.Rx
import wvlet.uni.test.UniTest

import java.io.{ByteArrayOutputStream, InputStream}
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger, AtomicReference}

/**
  * What keeps the built-in JVM server healthy over a long uptime, as uni-netty is: memory that
  * returns to its baseline, an accept loop that survives errors, handler work that stops once no
  * one waits for it, and a shutdown that lets in-flight requests finish.
  */
class SocketServerStabilityTest extends UniTest:

  private val serverRef = AtomicReference[HttpServer]()

  private val app = Http
    .server
    .withHandler { (req: Request) =>
      req.path match
        case "/upload" =>
          Response.ok.withTextContent(req.content.length.toString)
        case "/events" =>
          // An endless stream, as a live feed would be
          Response.eventStream(
            Rx.timer(50, TimeUnit.MILLISECONDS).map(i => ServerSentEvent.data(i.toString))
          )
        case "/shutdown" =>
          serverRef.get().stop()
          Response.ok.withTextContent("stopping")
        case _ =>
          Response.ok.withTextContent("hello")
    }

  private def withServer[A](
      config: HttpServerConfig = app,
      update: SocketServerLimits => SocketServerLimits = identity
  )(body: JvmHttpServer => A): A =
    val c      = config.withPort(0)
    val server = JvmHttpServer(c, update(JvmHttpServer.limits(c)))
    server.start()
    serverRef.set(server)
    try body(server)
    finally server.stop()

  private def connect(server: HttpServer): Socket =
    val s = Socket("127.0.0.1", server.localPort)
    s.setSoTimeout(10000)
    s

  private def send(s: Socket, raw: String): Unit =
    s.getOutputStream.write(raw.getBytes(StandardCharsets.ISO_8859_1))
    s.getOutputStream.flush()

  /** Read one response head and its Content-Length body; returns (status, body). */
  private def readResponse(in: InputStream): (Int, String) =
    val head = ByteArrayOutputStream()
    var last = 0
    while last != 0x0d0a0d0a do
      val b = in.read()
      if b < 0 then
        fail(s"Connection closed after: ${head.toString(StandardCharsets.ISO_8859_1)}")
      head.write(b)
      last = (last << 8) | b
    val lines = head.toString(StandardCharsets.ISO_8859_1).trim.split("\r\n")
    val len   = lines
      .collectFirst {
        case l if l.toLowerCase.startsWith("content-length:") =>
          l.substring(l.indexOf(':') + 1).trim.toInt
      }
      .getOrElse(0)
    (lines(0).split(" ")(1).toInt, String(in.readNBytes(len), StandardCharsets.UTF_8))

  private def get(server: HttpServer, path: String): (Int, String) =
    val s = connect(server)
    try
      send(s, s"GET ${path} HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
      readResponse(s.getInputStream)
    finally
      s.close()

  /** Read through the next line break, failing if the server closes the connection first. */
  private def awaitLine(in: InputStream): Unit =
    var b = in.read()
    while b != '\n' do
      if b < 0 then
        fail("Connection closed before the stream started")
      b = in.read()

  /** Wait up to 10 s for `cond`, polling. */
  private def eventually(cond: => Boolean): Boolean =
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
    while !cond && System.nanoTime() < deadline do
      Thread.sleep(20)
    cond

  test("shrink a kept-alive connection's read buffer after a large request") {
    withServer() { server =>
      val s = connect(server)
      try
        val body = "x" * (1024 * 1024)
        send(
          s,
          s"POST /upload HTTP/1.1\r\nHost: localhost\r\nContent-Length: ${body
              .length}\r\n\r\n${body}"
        )
        readResponse(s.getInputStream) shouldBe (200, body.length.toString)
        // The connection is still open, waiting for its next request with a small buffer
        eventually(
          server.readBufferSizes.nonEmpty && server.readBufferSizes.forall(_ <= 8192)
        ) shouldBe true
        send(s, "GET / HTTP/1.1\r\nHost: localhost\r\n\r\n")
        readResponse(s.getInputStream) shouldBe (200, "hello")
      finally
        s.close()
    }
  }

  test("keep accepting connections after the accept loop hits an unexpected error") {
    val failures = AtomicInteger(2)
    val c        = app.withPort(0)
    val server   =
      new JvmHttpServer(c, JvmHttpServer.limits(c)):
        override protected def listen(host: String, port: Int, backlog: Int): SocketListener =
          val l = JvmSocketListener(host, port, backlog)
          new SocketListener:
            override def port: Int                  = l.port
            override def accept(): SocketConnection =
              val conn = l.accept()
              if conn != null && failures.getAndDecrement() > 0 then
                conn.close()
                throw IllegalStateException("injected accept failure")
              conn
            override def close(): Unit = l.close()
    server.start()
    try
      // The first two connections hit the injected failure and are closed
      for _ <- 1 to 2 do
        val s = connect(server)
        try
          send(s, "GET / HTTP/1.1\r\nHost: localhost\r\n\r\n")
          s.getInputStream.read() shouldBe -1
        catch
          case _: java.io.IOException =>
            ()
        finally
          s.close()
      get(server, "/") shouldBe (200, "hello")
      server.isRunning shouldBe true
    finally
      server.stop()
  }

  test("have no handler timeout by default, as uni-netty") {
    JvmHttpServer.limits(app).handlerTimeoutMillis shouldBe 0L
  }

  test("cancel an async handler's work when the handler timeout fires") {
    val cancelled = AtomicBoolean(false)
    val slow      = Http
      .server
      .withRxHandler { (_: Request) =>
        Rx.delay(60, TimeUnit.SECONDS).map(_ => Response.ok).onCancel(cancelled.set(true))
      }
    withServer(slow, _.copy(handlerTimeoutMillis = 200)) { server =>
      get(server, "/")._1 shouldBe 503
      eventually(cancelled.get()) shouldBe true
    }
  }

  test("wait for a slow async handler while the handler timeout is off") {
    val slow = Http
      .server
      .withRxHandler { (_: Request) =>
        Rx.delay(500, TimeUnit.MILLISECONDS).map(_ => Response.ok.withTextContent("late"))
      }
    withServer(slow, _.copy(handlerTimeoutMillis = 0)) { server =>
      get(server, "/") shouldBe (200, "late")
    }
  }

  test("end streaming connections at once on stop(), freeing their slots") {
    withServer() { server =>
      val s = connect(server)
      try
        send(s, "GET /events HTTP/1.1\r\nHost: localhost\r\n\r\n")
        // Wait for the stream to start
        awaitLine(s.getInputStream)
        server.activeStreams shouldBe 1
        val start = System.nanoTime()
        server.stop()
        val tookMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
        (tookMillis < 5000) shouldBe true
        server.openConnections shouldBe 0
        server.activeStreams shouldBe 0
      finally
        s.close()
    }
  }

  test("answer a request that calls stop(), then stop") {
    withServer() { server =>
      get(server, "/shutdown") shouldBe (200, "stopping")
      eventually(server.openConnections == 0) shouldBe true
      server.isRunning shouldBe false
    }
  }

  test("return to no open connections or streams after many clients come and go") {
    withServer() { server =>
      val threadsBefore = Thread.activeCount()
      for i <- 1 to 50 do
        val s = connect(server)
        try
          if i % 5 == 0 then
            send(s, "GET /events HTTP/1.1\r\nHost: localhost\r\n\r\n")
            awaitLine(s.getInputStream)
          else
            send(s, "GET / HTTP/1.1\r\nHost: localhost\r\n\r\n")
            readResponse(s.getInputStream) shouldBe (200, "hello")
        finally s.close()
      // Closed SSE clients are found by the next failed write; plain ones by the next read
      eventually(server.openConnections == 0 && server.activeStreams == 0) shouldBe true
      // Connections run on virtual threads, so no platform thread is left per client
      (Thread.activeCount() <= threadsBefore + 2) shouldBe true
    }
  }

  test("log a recurring warning at most once per interval") {
    val logged  = Seq.newBuilder[String]
    val warning = RateLimitedWarning((msg, _) => logged += msg, intervalMillis = 60000L)
    warning.warn("accept failed")
    warning.warn("accept failed")
    warning.warn("accept failed")
    logged.result() shouldBe Seq("accept failed")
  }

  test("back off a failing accept from 10 ms up to 1 s") {
    SocketHttpServer.nextBackoff(0) shouldBe 10L
    SocketHttpServer.nextBackoff(10) shouldBe 20L
    SocketHttpServer.nextBackoff(640) shouldBe 1000L
    SocketHttpServer.nextBackoff(1000) shouldBe 1000L
  }

end SocketServerStabilityTest
