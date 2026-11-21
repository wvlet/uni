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

import wvlet.uni.util.Result

import wvlet.uni.rx.{OnCompletion, OnNext, RxRunner}
import wvlet.uni.test.UniTest

import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.{CountDownLatch, LinkedBlockingQueue, TimeUnit}

/**
  * Verifies the Native WebSocket client (raw socket + the shared codec in client mode) against the
  * in-process Native server — a real client/server round-trip on the same `WebSocketHandler`.
  */
class NativeWebSocketClientTest extends UniTest:

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
      case OnCompletion(Result.Failure(e)) =>
        error.set(e);
        ready.countDown()
      case _ =>
    }
    ready.await(10, TimeUnit.SECONDS) shouldBe true
    if error.get() != null then
      throw error.get()
    ref.get()

  test("Native WebSocket client echoes text messages") {
    NativeServerConfig()
      .withPort(0)
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
        val ctx = connect(s"ws://127.0.0.1:${server.localPort}/ws/echo", handler)
        try
          ctx.send("hello")
          messages.poll(10, TimeUnit.SECONDS) shouldBe "echo:hello"
        finally
          ctx.close()
      }
  }

  test("Native WebSocket client receives a server push on open") {
    NativeServerConfig()
      .withPort(0)
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
        val ctx = connect(s"ws://127.0.0.1:${server.localPort}/ws/push", handler)
        try messages.poll(10, TimeUnit.SECONDS) shouldBe "welcome"
        finally ctx.close()
      }
  }

  test("server heartbeat keeps an idle connection alive via ping/pong") {
    NativeServerConfig()
      .withPort(0)
      .withWebSocketPingIntervalMillis(150)
      .withWebSocketRoute("/ws/idle") { _ =>
        new WebSocketHandler {}
      }
      .start { server =>
        val closed  = CountDownLatch(1)
        val handler =
          new WebSocketHandler:
            override def onClose(ctx: WebSocketContext): Unit = closed.countDown()
        val ctx = connect(s"ws://127.0.0.1:${server.localPort}/ws/idle", handler)
        try
          // The server pings every 150ms; the client auto-pongs, so the heartbeat must NOT reap this
          // live-but-idle connection over several intervals.
          closed.await(800, TimeUnit.MILLISECONDS) shouldBe false
        finally ctx.close()
      }
  }

  test("Native WebSocket client fires onClose when the connection ends") {
    NativeServerConfig()
      .withPort(0)
      .withWebSocketRoute("/ws/life") { _ =>
        new WebSocketHandler {}
      }
      .start { server =>
        val closed  = CountDownLatch(1)
        val handler =
          new WebSocketHandler:
            override def onClose(ctx: WebSocketContext): Unit = closed.countDown()
        val ctx = connect(s"ws://127.0.0.1:${server.localPort}/ws/life", handler)
        ctx.close()
        closed.await(10, TimeUnit.SECONDS) shouldBe true
      }
  }

  test("Native WebSocket client sends request headers with the upgrade") {
    NativeServerConfig()
      .withPort(0)
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
        val ctx = connect(
          Request
            .get(s"ws://127.0.0.1:${server.localPort}/ws/auth")
            .addHeader("Authorization", "Bearer secret"),
          handler
        )
        try messages.poll(10, TimeUnit.SECONDS) shouldBe "Bearer secret"
        finally ctx.close()
      }
  }

  test("Native WebSocket client and server report close codes in both directions") {
    val serverSide = LinkedBlockingQueue[(Int, String)]()
    NativeServerConfig()
      .withPort(0)
      .withWebSocketRoute("/ws/bye") { _ =>
        new WebSocketHandler:
          override def onTextMessage(ctx: WebSocketContext, message: String): Unit = ctx.close(
            4001,
            "x" * 300
          )

          override def onClose(ctx: WebSocketContext, statusCode: Int, reason: String): Unit =
            serverSide.put((statusCode, reason))
      }
      .start { server =>
        val client  = LinkedBlockingQueue[(Int, String)]()
        val handler =
          new WebSocketHandler:
            override def onClose(ctx: WebSocketContext, statusCode: Int, reason: String): Unit =
              client.put((statusCode, reason))
        val ctx = connect(s"ws://127.0.0.1:${server.localPort}/ws/bye", handler)
        ctx.send("close me")
        client.poll(10, TimeUnit.SECONDS) shouldBe (4001, "x" * 123)
        serverSide.poll(10, TimeUnit.SECONDS) shouldBe (4001, "x" * 123)
      }
  }

end NativeWebSocketClientTest
