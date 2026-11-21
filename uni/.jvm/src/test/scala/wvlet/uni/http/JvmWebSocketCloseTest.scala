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

import java.io.{BufferedReader, InputStreamReader}
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.{CountDownLatch, LinkedBlockingQueue, TimeUnit}

/**
  * Upgrade headers and close statuses between the JDK WebSocket client and the built-in server, and
  * the client's limit on sends queued for a server that stops reading.
  */
class JvmWebSocketCloseTest extends UniTest:

  private def connect(request: Request, handler: WebSocketHandler): WebSocketContext =
    val ref   = AtomicReference[WebSocketContext]()
    val error = AtomicReference[Throwable]()
    val ready = CountDownLatch(1)
    RxRunner.runOnce(Http.webSocketClient.connect(request, handler)) {
      case OnNext(ctx) =>
        ref.set(ctx.asInstanceOf[WebSocketContext])
        ready.countDown()
      case OnCompletion(Result.Failure(e)) =>
        error.set(e)
        ready.countDown()
      case _ =>
    }
    ready.await(10, TimeUnit.SECONDS) shouldBe true
    if error.get() != null then
      throw error.get()
    ref.get()

  private class CloseRecorder extends WebSocketHandler:
    val status   = LinkedBlockingQueue[(Int, String)]()
    val messages = LinkedBlockingQueue[String]()
    override def onTextMessage(ctx: WebSocketContext, message: String): Unit = messages.put(message)

    override def onClose(ctx: WebSocketContext, statusCode: Int, reason: String): Unit = status.put(
      (statusCode, reason)
    )

  private def withServer[A](config: HttpServerConfig)(body: HttpServer => A): A =
    val server = config.withPort(0).start()
    try
      server shouldMatch { case _: JvmHttpServer =>
      }
      body(server)
    finally
      server.stop()

  test("send request headers with the upgrade to the built-in server") {
    withServer(
      Http
        .server
        .withWebSocketRoute("/ws") { request =>
          new WebSocketHandler:
            override def onOpen(ctx: WebSocketContext): Unit = ctx.send(
              request.header("Authorization").getOrElse("none")
            )
        }
    ) { server =>
      val client = CloseRecorder()
      val ctx    = connect(
        Request
          .get(s"ws://localhost:${server.localPort}/ws")
          .addHeader("Authorization", "Bearer secret"),
        client
      )
      try client.messages.poll(10, TimeUnit.SECONDS) shouldBe "Bearer secret"
      finally ctx.close()
    }
  }

  test("report close codes in both directions, with long reasons cut") {
    val serverSide = CloseRecorder()
    withServer(
      Http
        .server
        .withWebSocketRoute("/ws") { _ =>
          new WebSocketHandler:
            override def onTextMessage(ctx: WebSocketContext, message: String): Unit = ctx.close(
              4001,
              "あ" * 100
            )

            override def onClose(ctx: WebSocketContext, statusCode: Int, reason: String): Unit =
              serverSide.onClose(ctx, statusCode, reason)
        }
    ) { server =>
      val client = CloseRecorder()
      val ctx    = connect(Request.get(s"ws://localhost:${server.localPort}/ws"), client)
      ctx.send("close me")
      client.status.poll(10, TimeUnit.SECONDS) shouldBe (4001, "あ" * 41)
      serverSide.status.poll(10, TimeUnit.SECONDS) shouldBe (4001, "あ" * 41)
    }
  }

  test("report the client's close code on the built-in server") {
    val serverSide = CloseRecorder()
    withServer(Http.server.withWebSocketRoute("/ws")(_ => serverSide)) { server =>
      val ctx = connect(Request.get(s"ws://localhost:${server.localPort}/ws"), CloseRecorder())
      ctx.close(4002, "done")
      serverSide.status.poll(10, TimeUnit.SECONDS) shouldBe (4002, "done")
    }
  }

  test("reject headers the handshake sets itself") {
    val error = LinkedBlockingQueue[Throwable]()
    RxRunner.runOnce(
      Http
        .webSocketClient
        .connect(
          Request.get("ws://localhost:1/ws").addHeader("Sec-WebSocket-Key", "x"),
          new WebSocketHandler {}
        )
    ) {
      case OnCompletion(Result.Failure(e)) =>
        error.put(e)
      case _ =>
    }
    error.poll(10, TimeUnit.SECONDS) shouldMatch { case _: IllegalArgumentException =>
    }
  }

  test("close with 1013 a server that stops reading") {
    // A raw server that completes the handshake and then never reads
    val listener = ServerSocket(0)
    listener.setReceiveBufferSize(4096)
    val accepted = Thread(() =>
      val socket = listener.accept()
      val in = BufferedReader(InputStreamReader(socket.getInputStream, StandardCharsets.ISO_8859_1))
      var key  = ""
      var line = in.readLine()
      while line != null && line.nonEmpty do
        if line.toLowerCase.startsWith("sec-websocket-key:") then
          key = line.substring(line.indexOf(':') + 1).trim
        line = in.readLine()
      val response =
        "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
          s"Sec-WebSocket-Accept: ${WebSocketFrame.acceptKey(key)}\r\n\r\n"
      socket.getOutputStream.write(response.getBytes(StandardCharsets.ISO_8859_1))
      socket.getOutputStream.flush()
      // Hold the socket open without reading until the listener closes
      Thread.sleep(60000)
    )
    accepted.setDaemon(true)
    accepted.start()
    try
      val client = CloseRecorder()
      val ctx    = connect(Request.get(s"ws://localhost:${listener.getLocalPort}/ws"), client)
      val chunk  = new Array[Byte](1024 * 1024)
      var i      = 0
      while i < 64 && client.status.isEmpty do
        ctx.send(chunk)
        i += 1
      client.status.poll(30, TimeUnit.SECONDS) shouldBe (1013, "send buffer full")
    finally
      listener.close()
  }

end JvmWebSocketCloseTest
