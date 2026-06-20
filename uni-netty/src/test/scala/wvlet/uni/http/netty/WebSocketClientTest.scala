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

import wvlet.uni.http.{Http, WebSocketContext, WebSocketHandler}
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
  private def connect(uri: String, handler: WebSocketHandler): WebSocketContext =
    val ref   = AtomicReference[WebSocketContext]()
    val error = AtomicReference[Throwable]()
    val ready = CountDownLatch(1)
    RxRunner.runOnce(Http.webSocketClient.connect(uri, handler)) {
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
    NettyServer
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
        val ctx = connect(s"ws://localhost:${server.localPort}/ws/echo", handler)
        try
          ctx.send("hello")
          messages.poll(10, TimeUnit.SECONDS) shouldBe "echo:hello"
        finally
          ctx.close()
      }
  }

  test("WebSocketClient receives a server push on open") {
    NettyServer
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
        val ctx = connect(s"ws://localhost:${server.localPort}/ws/push", handler)
        try messages.poll(10, TimeUnit.SECONDS) shouldBe "welcome"
        finally ctx.close()
      }
  }

  test("WebSocketClient fires onClose when the connection ends") {
    NettyServer
      .withPort(0)
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
    NettyServer
      .withPort(0)
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

end WebSocketClientTest
