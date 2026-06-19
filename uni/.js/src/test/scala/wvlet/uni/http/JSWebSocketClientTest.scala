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

import wvlet.uni.rx.{OnError, OnNext, Rx, RxRunner}
import wvlet.uni.test.UniTest

import scala.concurrent.{ExecutionContext, Future, Promise}

/**
  * Verifies the JS WebSocket client (global `WebSocket`, Node.js >= 22) against the in-process Node
  * server with a WebSocket route — a real client/server round-trip on the same `WebSocketHandler`.
  */
class JSWebSocketClientTest extends UniTest:

  private given ExecutionContext = scala.scalajs.concurrent.JSExecutionContext.queue

  /** Open the connection and resolve with its [[WebSocketContext]] once the handshake completes. */
  private def connect(uri: String, handler: WebSocketHandler): Future[WebSocketContext] =
    val opened = Promise[WebSocketContext]()
    RxRunner.runOnce(Http.webSocketClient.connect(uri, handler)) {
      case OnNext(ctx) =>
        opened.trySuccess(ctx.asInstanceOf[WebSocketContext])
      case OnError(e) =>
        opened.tryFailure(e)
      case _ =>
    }
    opened.future

  test("JS WebSocket client echoes text messages") {
    NodeServer
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
    NodeServer
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

  test("JS WebSocket client fires onClose when the connection ends") {
    NodeServer
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

end JSWebSocketClientTest
