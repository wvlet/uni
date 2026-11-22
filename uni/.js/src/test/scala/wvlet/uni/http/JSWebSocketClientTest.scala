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

import wvlet.uni.rx.{OnCompletion, OnFailure, OnNext, Rx, RxRunner}
import wvlet.uni.test.UniTest

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.scalajs.js

/**
  * Verifies the JS WebSocket client (global `WebSocket`, Node.js >= 22) against the in-process Node
  * server with a WebSocket route — a real client/server round-trip on the same `WebSocketHandler`.
  */
class JSWebSocketClientTest extends UniTest:

  private given ExecutionContext = scala.scalajs.concurrent.JSExecutionContext.queue

  /** Open the connection and resolve with its [[WebSocketContext]] once the handshake completes. */
  private def connect(uri: String, handler: WebSocketHandler): Future[WebSocketContext] = connect(
    Request.get(uri),
    handler
  )

  private def connect(request: Request, handler: WebSocketHandler): Future[WebSocketContext] =
    val opened = Promise[WebSocketContext]()
    RxRunner.runOnce(Http.webSocketClient.connect(request, handler)) {
      case OnNext(ctx) =>
        opened.trySuccess(ctx.asInstanceOf[WebSocketContext])
      case OnFailure(e) =>
        opened.tryFailure(e)
      case _ =>
    }
    opened.future

  /** A Future that completes after `millis` (via the JS event loop). */
  private def delay(millis: Int): Future[Unit] =
    val p = Promise[Unit]()
    js.Dynamic.global.setTimeout((() => p.trySuccess(())): js.Function0[Unit], millis)
    p.future

  test("JS WebSocket client echoes text messages") {
    NodeServerConfig()
      .withPort(0)
      .withWebSocketRoute("/ws/echo") { _ =>
        new WebSocketHandler:
          override def onTextMessage(ctx: WebSocketContext, message: String): Unit = ctx.send(
            s"echo:${message}"
          )
      }
      .startAndAwait { server =>
        val received = Promise[String]()
        val handler  =
          new WebSocketHandler:
            override def onTextMessage(ctx: WebSocketContext, message: String): Unit = received
              .trySuccess(message)
        val result =
          for
            ctx <- connect(s"ws://127.0.0.1:${server.localPort}/ws/echo", handler)
            _ = ctx.send("hello")
            message <- received.future
          yield
            ctx.close()
            message shouldBe "echo:hello"
        Rx.future(result)
      }
  }

  test("JS WebSocket client receives a server push on open") {
    NodeServerConfig()
      .withPort(0)
      .withWebSocketRoute("/ws/push") { _ =>
        new WebSocketHandler:
          override def onOpen(ctx: WebSocketContext): Unit = ctx.send("welcome")
      }
      .startAndAwait { server =>
        val received = Promise[String]()
        val handler  =
          new WebSocketHandler:
            override def onTextMessage(ctx: WebSocketContext, message: String): Unit = received
              .trySuccess(message)
        val result =
          for
            ctx     <- connect(s"ws://127.0.0.1:${server.localPort}/ws/push", handler)
            message <- received.future
          yield
            ctx.close()
            message shouldBe "welcome"
        Rx.future(result)
      }
  }

  test("server heartbeat keeps an idle connection alive via ping/pong") {
    NodeServerConfig()
      .withPort(0)
      .withWebSocketPingIntervalMillis(150)
      .withWebSocketRoute("/ws/idle") { _ =>
        new WebSocketHandler {}
      }
      .startAndAwait { server =>
        val closed  = Promise[Unit]()
        val handler =
          new WebSocketHandler:
            override def onClose(ctx: WebSocketContext): Unit = closed.trySuccess(())
        val result =
          for
            ctx <- connect(s"ws://127.0.0.1:${server.localPort}/ws/idle", handler)
            // The server pings every 150ms; the Node runtime auto-pongs, so over ~5 intervals the
            // heartbeat must NOT reap this live-but-idle connection.
            _ <- delay(800)
          yield
            ctx.close()
            closed.isCompleted shouldBe false
        Rx.future(result)
      }
  }

  test("JS WebSocket client fires onClose when the connection ends") {
    NodeServerConfig()
      .withPort(0)
      .withWebSocketRoute("/ws/life") { _ =>
        new WebSocketHandler:
          override def onOpen(ctx: WebSocketContext): Unit = ctx.send("hi")
      }
      .startAndAwait { server =>
        val closed  = Promise[Boolean]()
        val handler =
          new WebSocketHandler:
            override def onClose(ctx: WebSocketContext): Unit = closed.trySuccess(true)
        val result =
          for
            ctx <- connect(s"ws://127.0.0.1:${server.localPort}/ws/life", handler)
            _ = ctx.close()
            done <- closed.future
          yield done shouldBe true
        Rx.future(result)
      }
  }

  test("JS WebSocket client sends request headers with the upgrade on Node") {
    NodeServerConfig()
      .withPort(0)
      .withWebSocketRoute("/ws/auth") { request =>
        new WebSocketHandler:
          override def onOpen(ctx: WebSocketContext): Unit = ctx.send(
            request.header("Authorization").getOrElse("none")
          )
      }
      .startAndAwait { server =>
        val received = Promise[String]()
        val handler  =
          new WebSocketHandler:
            override def onTextMessage(ctx: WebSocketContext, message: String): Unit = received
              .trySuccess(message)
        val request = Request
          .get(s"ws://127.0.0.1:${server.localPort}/ws/auth")
          .addHeader("Authorization", "Bearer secret")
        val result =
          for
            ctx     <- connect(request, handler)
            message <- received.future
          yield
            ctx.close()
            message shouldBe "Bearer secret"
        Rx.future(result)
      }
  }

  test("JS WebSocket client and Node server report close codes in both directions") {
    val serverSide = Promise[(Int, String)]()
    NodeServerConfig()
      .withPort(0)
      .withWebSocketRoute("/ws/bye") { _ =>
        new WebSocketHandler:
          override def onTextMessage(ctx: WebSocketContext, message: String): Unit = ctx.close(
            4001,
            "x" * 300
          )

          override def onClose(ctx: WebSocketContext, statusCode: Int, reason: String): Unit =
            serverSide.trySuccess((statusCode, reason))
      }
      .startAndAwait { server =>
        val client  = Promise[(Int, String)]()
        val handler =
          new WebSocketHandler:
            override def onClose(ctx: WebSocketContext, statusCode: Int, reason: String): Unit =
              client.trySuccess((statusCode, reason))
        val result =
          for
            ctx <- connect(s"ws://127.0.0.1:${server.localPort}/ws/bye", handler)
            _ = ctx.send("close me")
            c <- client.future
            s <- serverSide.future
          yield
            c shouldBe (4001, "x" * 123)
            s shouldBe (4001, "x" * 123)
        Rx.future(result)
      }
  }

  test("JS WebSocket client reports its own close code on the Node server") {
    val serverSide = Promise[(Int, String)]()
    NodeServerConfig()
      .withPort(0)
      .withWebSocketRoute("/ws/done") { _ =>
        new WebSocketHandler:
          override def onClose(ctx: WebSocketContext, statusCode: Int, reason: String): Unit =
            serverSide.trySuccess((statusCode, reason))
      }
      .startAndAwait { server =>
        val result =
          for
            ctx <- connect(s"ws://127.0.0.1:${server.localPort}/ws/done", new WebSocketHandler {})
            _ = ctx.close(4002, "done")
            s <- serverSide.future
          yield s shouldBe (4002, "done")
        Rx.future(result)
      }
  }

end JSWebSocketClientTest
