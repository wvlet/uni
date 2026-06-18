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

import wvlet.uni.http.{Request, Response, RxHttpFilter, WebSocketContext, WebSocketHandler}
import wvlet.uni.rx.Rx
import wvlet.uni.test.UniTest

import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.{HttpClient, WebSocket}
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.concurrent.{CompletionStage, CountDownLatch, LinkedBlockingQueue, TimeUnit}

class WebSocketTest extends UniTest:

  /**
    * A WebSocket.Listener that accumulates fragmented text/binary into whole messages and exposes
    * open/close latches.
    */
  private class CollectingListener extends WebSocket.Listener:
    val openLatch            = CountDownLatch(1)
    val closeLatch           = CountDownLatch(1)
    private val textQueue    = LinkedBlockingQueue[String]()
    private val binaryQueue  = LinkedBlockingQueue[Array[Byte]]()
    private val textBuffer   = StringBuilder()
    private val binaryBuffer = ByteArrayOutputStream()
    @volatile
    var error: Option[Throwable] = None

    override def onOpen(webSocket: WebSocket): Unit =
      openLatch.countDown()
      webSocket.request(Long.MaxValue)

    override def onText(
        webSocket: WebSocket,
        data: CharSequence,
        last: Boolean
    ): CompletionStage[?] =
      textBuffer.append(data)
      if last then
        textQueue.put(textBuffer.toString)
        textBuffer.clear()
      null

    override def onBinary(
        webSocket: WebSocket,
        data: ByteBuffer,
        last: Boolean
    ): CompletionStage[?] =
      val bytes = new Array[Byte](data.remaining())
      data.get(bytes)
      binaryBuffer.write(bytes)
      if last then
        binaryQueue.put(binaryBuffer.toByteArray)
        binaryBuffer.reset()
      null

    override def onClose(
        webSocket: WebSocket,
        statusCode: Int,
        reason: String
    ): CompletionStage[?] =
      closeLatch.countDown()
      null

    override def onError(webSocket: WebSocket, e: Throwable): Unit = error = Some(e)

    def nextText: String        = textQueue.poll(10, TimeUnit.SECONDS)
    def nextBinary: Array[Byte] = binaryQueue.poll(10, TimeUnit.SECONDS)

  end CollectingListener

  private def connect(port: Int, path: String, listener: WebSocket.Listener): WebSocket =
    val client = HttpClient.newHttpClient()
    val uri    = URI.create(s"ws://localhost:${port}${path}")
    client.newWebSocketBuilder().buildAsync(uri, listener).get(10, TimeUnit.SECONDS)

  test("echo text messages") {
    NettyServer
      .withPort(0)
      .withWebSocketRoute("/ws/echo") { _ =>
        new WebSocketHandler:
          override def onTextMessage(ctx: WebSocketContext, message: String): Unit = ctx.send(
            s"echo:${message}"
          )
      }
      .start { server =>
        val listener = CollectingListener()
        val ws       = connect(server.localPort, "/ws/echo", listener)
        try
          ws.sendText("hello", true)
          listener.nextText shouldBe "echo:hello"
          ws.sendText("world", true)
          listener.nextText shouldBe "echo:world"
        finally
          ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye")
      }
  }

  test("echo binary messages") {
    NettyServer
      .withPort(0)
      .withWebSocketRoute("/ws/bin") { _ =>
        new WebSocketHandler:
          override def onBinaryMessage(ctx: WebSocketContext, message: Array[Byte]): Unit = ctx
            .send(message)
      }
      .start { server =>
        val listener = CollectingListener()
        val ws       = connect(server.localPort, "/ws/bin", listener)
        try
          val payload = Array[Byte](1, 2, 3, 4, 5)
          ws.sendBinary(ByteBuffer.wrap(payload), true)
          listener.nextBinary.toSeq shouldBe payload.toSeq
        finally
          ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye")
      }
  }

  test("server can push messages on open") {
    NettyServer
      .withPort(0)
      .withWebSocketRoute("/ws/push") { _ =>
        new WebSocketHandler:
          override def onOpen(ctx: WebSocketContext): Unit = ctx.send("welcome")
      }
      .start { server =>
        val listener = CollectingListener()
        val ws       = connect(server.localPort, "/ws/push", listener)
        try listener.nextText shouldBe "welcome"
        finally ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye")
      }
  }

  test("onOpen and onClose lifecycle callbacks fire") {
    val opened = CountDownLatch(1)
    val closed = CountDownLatch(1)
    NettyServer
      .withPort(0)
      .withWebSocketRoute("/ws/life") { _ =>
        new WebSocketHandler:
          override def onOpen(ctx: WebSocketContext): Unit  = opened.countDown()
          override def onClose(ctx: WebSocketContext): Unit = closed.countDown()
      }
      .start { server =>
        val listener = CollectingListener()
        val ws       = connect(server.localPort, "/ws/life", listener)
        opened.await(10, TimeUnit.SECONDS) shouldBe true
        ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye")
        closed.await(10, TimeUnit.SECONDS) shouldBe true
      }
  }

  test("fragmented text messages are aggregated") {
    NettyServer
      .withPort(0)
      .withWebSocketRoute("/ws/frag") { _ =>
        new WebSocketHandler:
          override def onTextMessage(ctx: WebSocketContext, message: String): Unit = ctx.send(
            s"len:${message.length}"
          )
      }
      .start { server =>
        val listener = CollectingListener()
        val ws       = connect(server.localPort, "/ws/frag", listener)
        try
          // Send across two fragments; the server should see one coalesced message.
          ws.sendText("first-", false)
          ws.sendText("second", true)
          listener.nextText shouldBe s"len:${"first-second".length}"
        finally
          ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye")
      }
  }

  test("filter can reject the upgrade") {
    val denyFilter = RxHttpFilter { (_, _) =>
      Rx.single(Response.forbidden)
    }
    NettyServer
      .withPort(0)
      .withWebSocketRoute("/ws/secure", denyFilter) { _ =>
        new WebSocketHandler {}
      }
      .start { server =>
        intercept[Exception] {
          connect(server.localPort, "/ws/secure", CollectingListener())
        }
      }
  }

  test("filter returning an empty response rejects the upgrade") {
    val emptyFilter = RxHttpFilter { (_, _) =>
      Rx.empty
    }
    NettyServer
      .withPort(0)
      .withWebSocketRoute("/ws/empty", emptyFilter) { _ =>
        new WebSocketHandler {}
      }
      .start { server =>
        intercept[Exception] {
          connect(server.localPort, "/ws/empty", CollectingListener())
        }
      }
  }

  test("WebSocket works with a handler executor thread pool") {
    NettyServer
      .withPort(0)
      .withHandlerExecutorThreads(4)
      .withWebSocketRoute("/ws/exec") { _ =>
        new WebSocketHandler:
          override def onTextMessage(ctx: WebSocketContext, message: String): Unit = ctx.send(
            s"echo:${message}"
          )
      }
      .start { server =>
        val listener = CollectingListener()
        val ws       = connect(server.localPort, "/ws/exec", listener)
        try
          ws.sendText("hello", true)
          listener.nextText shouldBe "echo:hello"
        finally
          ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye")
      }
  }

  test("normal HTTP routes work alongside WebSocket routes") {
    NettyServer
      .withPort(0)
      .withHandler { request =>
        Response.ok(s"http:${request.path}")
      }
      .withWebSocketRoute("/ws/echo") { _ =>
        new WebSocketHandler:
          override def onTextMessage(ctx: WebSocketContext, message: String): Unit = ctx.send(
            s"echo:${message}"
          )
      }
      .start { server =>
        // Plain HTTP still works.
        val httpClient = HttpClient.newHttpClient()
        val httpReq    = java
          .net
          .http
          .HttpRequest
          .newBuilder()
          .uri(URI.create(s"http://localhost:${server.localPort}/hello"))
          .GET()
          .build()
        val httpResp = httpClient.send(httpReq, java.net.http.HttpResponse.BodyHandlers.ofString())
        httpResp.statusCode() shouldBe 200
        httpResp.body() shouldBe "http:/hello"

        // WebSocket also works on the same server.
        val listener = CollectingListener()
        val ws       = connect(server.localPort, "/ws/echo", listener)
        try
          ws.sendText("hi", true)
          listener.nextText shouldBe "echo:hi"
        finally
          ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye")
      }
  }

end WebSocketTest
