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

import wvlet.uni.http.{Http, Request, WebSocketContext, WebSocketHandler}
import wvlet.uni.rx.{OnError, OnNext, RxRunner}
import wvlet.uni.test.UniTest

import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.{CountDownLatch, LinkedBlockingQueue, TimeUnit}

/**
  * Verifies the shared `WebSocketClient` (JVM `java.net.http` backend) against a Netty server with
  * a WebSocket route — a real client/server round-trip on the same `WebSocketHandler` abstraction.
  */
class WebSocketClientTest extends UniTest:

  /** Open the connection and return its [[WebSocketContext]] (waits for the handshake). */
  private def connect(uri: String, handler: WebSocketHandler): WebSocketContext = connect(
    Request.get(uri),
    handler
  )

  private def connect(request: Request, handler: WebSocketHandler): WebSocketContext =
    val ref   = AtomicReference[WebSocketContext]()
    val error = AtomicReference[Throwable]()
    val ready = CountDownLatch(1)
    RxRunner.runOnce(Http.webSocketClient.connect(request, handler)) {
      case OnNext(ctx) =>
        ref.set(ctx.asInstanceOf[WebSocketContext]);
        ready.countDown()
      case OnError(e) =>
        error.set(e);
        ready.countDown()
      case _ =>
    }
    ready.await(10, TimeUnit.SECONDS) shouldBe true
    if error.get() != null then
      throw error.get()
    ref.get()

  test("WebSocketClient connects and exchanges text messages") {
    NettyServerConfig()
      .withPort(0)
      .withGracefulShutdown(quietPeriodSeconds = 0, timeoutSeconds = 5)
      .withWebSocketRoute("/ws/echo") { _ =>
        new WebSocketHandler:
          override def onTextMessage(ctx: WebSocketContext, message: String): Unit = ctx.send(
            s"echo:${message}"
          )
      }
      .start { server =>
        val messages = LinkedBlockingQueue[String]()
        val handler  =
          new WebSocketHandler:
            override def onTextMessage(ctx: WebSocketContext, message: String): Unit = messages.put(
              message
            )
        val ctx = connect(s"ws://localhost:${server.localPort}/ws/echo", handler)
        try
          ctx.send("hello")
          messages.poll(10, TimeUnit.SECONDS) shouldBe "echo:hello"
        finally
          ctx.close()
      }
  }

  test("WebSocketClient receives a server push on open") {
    NettyServerConfig()
      .withPort(0)
      .withGracefulShutdown(quietPeriodSeconds = 0, timeoutSeconds = 5)
      .withWebSocketRoute("/ws/push") { _ =>
        new WebSocketHandler:
          override def onOpen(ctx: WebSocketContext): Unit = ctx.send("welcome")
      }
      .start { server =>
        val messages = LinkedBlockingQueue[String]()
        val handler  =
          new WebSocketHandler:
            override def onTextMessage(ctx: WebSocketContext, message: String): Unit = messages.put(
              message
            )
        val ctx = connect(s"ws://localhost:${server.localPort}/ws/push", handler)
        try messages.poll(10, TimeUnit.SECONDS) shouldBe "welcome"
        finally ctx.close()
      }
  }

  test("WebSocketClient fires onClose when the connection ends") {
    NettyServerConfig()
      .withPort(0)
      .withGracefulShutdown(quietPeriodSeconds = 0, timeoutSeconds = 5)
      .withWebSocketRoute("/ws/life") { _ =>
        new WebSocketHandler {}
      }
      .start { server =>
        val closed  = CountDownLatch(1)
        val handler =
          new WebSocketHandler:
            override def onClose(ctx: WebSocketContext): Unit = closed.countDown()
        val ctx = connect(s"ws://localhost:${server.localPort}/ws/life", handler)
        ctx.close()
        closed.await(10, TimeUnit.SECONDS) shouldBe true
      }
  }

  test("client heartbeat keeps an idle connection alive via ping/pong") {
    NettyServerConfig()
      .withPort(0)
      .withGracefulShutdown(quietPeriodSeconds = 0, timeoutSeconds = 5)
      .withWebSocketRoute("/ws/idle") { _ =>
        new WebSocketHandler {}
      }
      .start { server =>
        val closed  = CountDownLatch(1)
        val handler =
          new WebSocketHandler:
            override def onClose(ctx: WebSocketContext): Unit = closed.countDown()
        // The client pings every 150ms; the server auto-pongs, so the heartbeat must NOT reap this
        // live-but-idle connection over several intervals.
        val ref   = AtomicReference[WebSocketContext]()
        val ready = CountDownLatch(1)
        RxRunner.runOnce(
          Http.webSocketClient.connect(s"ws://localhost:${server.localPort}/ws/idle", handler, 150)
        ) {
          case OnNext(ctx) =>
            ref.set(ctx.asInstanceOf[WebSocketContext]);
            ready.countDown()
          case OnError(_) =>
            ready.countDown()
          case _ =>
        }
        ready.await(10, TimeUnit.SECONDS) shouldBe true
        try closed.await(800, TimeUnit.MILLISECONDS) shouldBe false
        finally Option(ref.get()).foreach(_.close())
      }
  }

  /** A handler that records the first close status it sees. */
  private class CloseRecorder extends WebSocketHandler:
    val status = LinkedBlockingQueue[(Int, String)]()
    override def onClose(ctx: WebSocketContext, statusCode: Int, reason: String): Unit = status.put(
      (statusCode, reason)
    )

  test("send request headers with the upgrade") {
    NettyServerConfig()
      .withPort(0)
      .withGracefulShutdown(quietPeriodSeconds = 0, timeoutSeconds = 5)
      .withWebSocketRoute("/ws/auth") { request =>
        new WebSocketHandler:
          override def onOpen(ctx: WebSocketContext): Unit = ctx.send(
            request.header("Authorization").getOrElse("none")
          )
      }
      .start { server =>
        val messages = LinkedBlockingQueue[String]()
        val handler  =
          new WebSocketHandler:
            override def onTextMessage(ctx: WebSocketContext, message: String): Unit = messages.put(
              message
            )
        val request = Request
          .get(s"ws://localhost:${server.localPort}/ws/auth")
          .addHeader("Authorization", "Bearer secret")
        val ctx = connect(request, handler)
        try messages.poll(10, TimeUnit.SECONDS) shouldBe "Bearer secret"
        finally ctx.close()
      }
  }

  test("report the server's close code and a reason cut to 123 bytes") {
    val serverSide = CloseRecorder()
    NettyServerConfig()
      .withPort(0)
      .withGracefulShutdown(quietPeriodSeconds = 0, timeoutSeconds = 5)
      .withWebSocketRoute("/ws/bye") { _ =>
        new WebSocketHandler:
          override def onOpen(ctx: WebSocketContext): Unit = ctx.close(4001, "x" * 300)
          override def onClose(ctx: WebSocketContext, statusCode: Int, reason: String): Unit =
            serverSide.onClose(ctx, statusCode, reason)
      }
      .start { server =>
        val client = CloseRecorder()
        connect(s"ws://localhost:${server.localPort}/ws/bye", client)
        client.status.poll(10, TimeUnit.SECONDS) shouldBe (4001, "x" * 123)
        serverSide.status.poll(10, TimeUnit.SECONDS) shouldBe (4001, "x" * 123)
      }
  }

  test("report the client's close code on the server") {
    val serverSide = CloseRecorder()
    NettyServerConfig()
      .withPort(0)
      .withGracefulShutdown(quietPeriodSeconds = 0, timeoutSeconds = 5)
      .withWebSocketRoute("/ws/done") { _ =>
        serverSide
      }
      .start { server =>
        val client = CloseRecorder()
        val ctx    = connect(s"ws://localhost:${server.localPort}/ws/done", client)
        ctx.close(4002, "done")
        serverSide.status.poll(10, TimeUnit.SECONDS) shouldBe (4002, "done")
        client.status.poll(10, TimeUnit.SECONDS) shouldBe (4002, "done")
      }
  }

  test("close with 1013 a client that stops reading") {
    val serverSide = CloseRecorder()
    NettyServerConfig()
      .withPort(0)
      .withGracefulShutdown(quietPeriodSeconds = 0, timeoutSeconds = 5)
      .withWebSocketRoute("/ws/flood") { _ =>
        new WebSocketHandler:
          override def onOpen(ctx: WebSocketContext): Unit =
            val chunk = new Array[Byte](1024 * 1024)
            val t     = Thread(() =>
              var i = 0
              while i < 64 && serverSide.status.isEmpty do
                ctx.send(chunk)
                i += 1
            )
            t.setDaemon(true)
            t.start()
          override def onClose(ctx: WebSocketContext, statusCode: Int, reason: String): Unit =
            serverSide.onClose(ctx, statusCode, reason)
      }
      .start { server =>
        // A raw client that completes the handshake and then never reads
        val socket = java.net.Socket()
        socket.setReceiveBufferSize(4096)
        socket.connect(java.net.InetSocketAddress("localhost", server.localPort))
        try
          val upgrade =
            "GET /ws/flood HTTP/1.1\r\nHost: localhost\r\nUpgrade: websocket\r\n" +
              "Connection: Upgrade\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n" +
              "Sec-WebSocket-Version: 13\r\n\r\n"
          socket.getOutputStream.write(upgrade.getBytes("ISO-8859-1"))
          socket.getOutputStream.flush()
          serverSide.status.poll(30, TimeUnit.SECONDS) shouldBe (1013, "send buffer full")
        finally
          socket.close()
      }
  }

end WebSocketClientTest
