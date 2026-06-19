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

import java.nio.charset.StandardCharsets
import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.scalajs.js

/**
  * Verifies the Node.js WebSocket backend with a manual `net.Socket` client (no `ws` dependency).
  * The client mirrors the Native `WsClient` but is async, so each test returns an `Rx`/`Future` the
  * framework awaits, matching the Node SSE test idiom.
  */
class NodeWebSocketTest extends UniTest:

  private given ExecutionContext = scala.scalajs.concurrent.JSExecutionContext.queue

  private val key            = "dGhlIHNhbXBsZSBub25jZQ=="
  private val expectedAccept = "s3pPLMBiTxaQ9kYGzzhZRbK+xOo="

  /** Parsed WebSocket handshake response: status code + lower-cased headers. */
  private case class Handshake(status: Int, headers: Map[String, String])

  /** A minimal async WebSocket client over a raw Node `net.Socket`. */
  private class NodeWsClient(port: Int):
    private val socket = NodeModules
      .builtin("net")
      .applyDynamic("createConnection")(port, "127.0.0.1")
      .asInstanceOf[js.Dynamic]

    private val buf              = mutable.ArrayBuffer.empty[Byte]
    private var handshook        = false
    private val handshakePromise = Promise[Handshake]()
    private val frameQueue       = mutable.Queue.empty[(Int, Array[Byte])]
    private val waiters          = mutable.Queue.empty[Promise[(Int, Array[Byte])]]

    private val onData: js.Function1[js.Dynamic, Unit] =
      (chunk: js.Dynamic) =>
        buf ++= NodeBytes.toBytes(chunk)
        process()

    private val onError: js.Function1[js.Dynamic, Unit] =
      (e: js.Dynamic) =>
        if !handshakePromise.isCompleted then
          handshakePromise.failure(RuntimeException(s"socket error: ${e}"))

    socket.applyDynamic("on")("data", onData)
    socket.applyDynamic("on")("error", onError)

    /** Send the upgrade request; resolves with the parsed handshake response. */
    def connect(path: String, version: String = "13"): Future[Handshake] =
      val onConnect: js.Function0[Unit] =
        () =>
          val req =
            s"GET ${path} HTTP/1.1\r\nHost: localhost\r\n" +
              s"Upgrade: websocket\r\nConnection: Upgrade\r\n" +
              s"Sec-WebSocket-Key: ${key}\r\nSec-WebSocket-Version: ${version}\r\n\r\n"
          socket.applyDynamic("write")(
            NodeBytes.toUint8Array(req.getBytes(StandardCharsets.ISO_8859_1))
          )
      socket.applyDynamic("on")("connect", onConnect)
      handshakePromise.future

    def sendText(text: String): Unit = send(
      WebSocketFrame.OpText,
      text.getBytes(StandardCharsets.UTF_8)
    )

    def sendBinary(data: Array[Byte]): Unit = send(WebSocketFrame.OpBinary, data)

    /** Await the next server frame (opcode + payload). */
    def nextFrame(): Future[(Int, Array[Byte])] =
      if frameQueue.nonEmpty then
        Future.successful(frameQueue.dequeue())
      else
        val p = Promise[(Int, Array[Byte])]()
        waiters.enqueue(p)
        p.future

    def nextText(): Future[String] = nextFrame().map((_, data) =>
      new String(data, StandardCharsets.UTF_8)
    )

    def close(): Unit =
      try
        socket.applyDynamic("destroy")()
      catch
        case _: Throwable =>
          ()

    /** Build a masked client frame. */
    private def send(opcode: Int, payload: Array[Byte]): Unit =
      val mask   = Array[Byte](0x12, 0x34, 0x56, 0x78)
      val header = Array[Byte]((0x80 | opcode).toByte, (0x80 | payload.length).toByte)
      val masked = new Array[Byte](payload.length)
      var i      = 0
      while i < payload.length do
        masked(i) = (payload(i) ^ mask(i % 4)).toByte
        i += 1
      socket.applyDynamic("write")(NodeBytes.toUint8Array(header ++ mask ++ masked))

    private def process(): Unit =
      if !handshook then
        val idx = headerEnd
        if idx >= 0 then
          val head = new String(buf.slice(0, idx).toArray, StandardCharsets.ISO_8859_1)
          buf.remove(0, idx + 4)
          val parsed = parseHead(head)
          handshook = parsed.status == 101
          handshakePromise.success(parsed)
      if handshook then
        parseFrames()

    private def headerEnd: Int =
      var i = 0
      while i + 3 < buf.length do
        if buf(i) == '\r' && buf(i + 1) == '\n' && buf(i + 2) == '\r' && buf(i + 3) == '\n' then
          return i
        i += 1
      -1

    private def parseHead(head: String): Handshake =
      val lines   = head.split("\r\n")
      val status  = lines(0).split(" ")(1).toInt
      val headers =
        lines
          .drop(1)
          .flatMap { line =>
            val c = line.indexOf(':')
            if c > 0 then
              Some(line.substring(0, c).trim.toLowerCase -> line.substring(c + 1).trim)
            else
              None
          }
          .toMap
      Handshake(status, headers)

    /** Parse unmasked server frames buffered so far (tests stay under the 16-bit length). */
    private def parseFrames(): Unit =
      var go = true
      while go do
        if buf.length < 2 then
          go = false
        else
          val opcode    = buf(0) & 0x0f
          val base      = buf(1) & 0x7f
          var headerLen = 2
          var len       = base
          if base == 126 then
            if buf.length < 4 then
              go = false
            else
              len = ((buf(2) & 0xff) << 8) | (buf(3) & 0xff)
              headerLen = 4
          if go && buf.length >= headerLen + len then
            val payload = buf.slice(headerLen, headerLen + len).toArray
            buf.remove(0, headerLen + len)
            if waiters.nonEmpty then
              waiters.dequeue().success((opcode, payload))
            else
              frameQueue.enqueue((opcode, payload))
          else
            go = false

  end NodeWsClient

  test("WebSocket handshake accept key and text echo") {
    NodeServer
      .withPort(0)
      .withWebSocketRoute("/ws/echo") { _ =>
        new WebSocketHandler:
          override def onTextMessage(ctx: WebSocketContext, message: String): Unit = ctx.send(
            s"echo:${message}"
          )
      }
      .startAndAwait { server =>
        val client = NodeWsClient(server.localPort)
        val result =
          for
            hs <- client.connect("/ws/echo")
            _ =
              hs.status shouldBe 101
              hs.headers.get("sec-websocket-accept") shouldBe Some(expectedAccept)
              client.sendText("hello")
            text <- client.nextText()
          yield
            client.close()
            text shouldBe "echo:hello"
        Rx.future(result)
      }
  }

  test("WebSocket echoes binary messages") {
    NodeServer
      .withPort(0)
      .withWebSocketRoute("/ws/bin") { _ =>
        new WebSocketHandler:
          override def onBinaryMessage(ctx: WebSocketContext, message: Array[Byte]): Unit = ctx
            .send(message)
      }
      .startAndAwait { server =>
        val client  = NodeWsClient(server.localPort)
        val payload = Array[Byte](1, 2, 3, 4, 5)
        val result  =
          for
            _ <- client.connect("/ws/bin")
            _ = client.sendBinary(payload)
            frame <- client.nextFrame()
          yield
            client.close()
            frame._1 shouldBe WebSocketFrame.OpBinary
            frame._2.toSeq shouldBe payload.toSeq
        Rx.future(result)
      }
  }

  test("WebSocket can push a message on open") {
    NodeServer
      .withPort(0)
      .withWebSocketRoute("/ws/push") { _ =>
        new WebSocketHandler:
          override def onOpen(ctx: WebSocketContext): Unit = ctx.send("welcome")
      }
      .startAndAwait { server =>
        val client = NodeWsClient(server.localPort)
        val result =
          for
            _    <- client.connect("/ws/push")
            text <- client.nextText()
          yield
            client.close()
            text shouldBe "welcome"
        Rx.future(result)
      }
  }

  test("WebSocket fires onOpen and onClose") {
    val opened = Promise[Boolean]()
    val closed = Promise[Boolean]()
    NodeServer
      .withPort(0)
      .withWebSocketRoute("/ws/life") { _ =>
        new WebSocketHandler:
          override def onOpen(ctx: WebSocketContext): Unit  = opened.success(true)
          override def onClose(ctx: WebSocketContext): Unit = closed.success(true)
      }
      .startAndAwait { server =>
        val client = NodeWsClient(server.localPort)
        val result =
          for
            _ <- client.connect("/ws/life")
            _ <- opened.future
            _ = client.close()
            c <- closed.future
          yield c shouldBe true
        Rx.future(result)
      }
  }

  test("WebSocket upgrade without a key is rejected with 400") {
    NodeServer
      .withPort(0)
      .withWebSocketRoute("/ws") { _ =>
        new WebSocketHandler {}
      }
      .startAndAwait { server =>
        // Send an upgrade with no Sec-WebSocket-Key via a raw socket.
        val socket = NodeModules
          .builtin("net")
          .applyDynamic("createConnection")(server.localPort, "127.0.0.1")
          .asInstanceOf[js.Dynamic]
        val received                               = Promise[Int]()
        val buffer                                 = mutable.ArrayBuffer.empty[Byte]
        val onData: js.Function1[js.Dynamic, Unit] =
          (chunk: js.Dynamic) =>
            buffer ++= NodeBytes.toBytes(chunk)
            val text = new String(buffer.toArray, StandardCharsets.ISO_8859_1)
            if text.contains("\r\n") && !received.isCompleted then
              received.success(text.split(" ")(1).toInt)
        val onConnect: js.Function0[Unit] =
          () =>
            val req =
              "GET /ws HTTP/1.1\r\nHost: localhost\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n\r\n"
            socket.applyDynamic("write")(
              NodeBytes.toUint8Array(req.getBytes(StandardCharsets.ISO_8859_1))
            )
        socket.applyDynamic("on")("data", onData)
        socket.applyDynamic("on")("connect", onConnect)
        Rx.future(received.future)
          .map { status =>
            try
              socket.applyDynamic("destroy")()
            catch
              case _: Throwable =>
                ()
            status shouldBe 400
          }
      }
  }

  test("WebSocket upgrade with an unsupported version is rejected with 426") {
    NodeServer
      .withPort(0)
      .withWebSocketRoute("/ws") { _ =>
        new WebSocketHandler {}
      }
      .startAndAwait { server =>
        val client = NodeWsClient(server.localPort)
        Rx.future(client.connect("/ws", version = "8"))
          .map { hs =>
            client.close()
            hs.status shouldBe 426
            hs.headers.get("sec-websocket-version") shouldBe Some("13")
          }
      }
  }

  test("WebSocket upgrade can be rejected by a filter") {
    val deny = RxHttpFilter { (_, _) =>
      Rx.single(Response.forbidden)
    }
    NodeServer
      .withPort(0)
      .withWebSocketRoute("/ws/secure", deny) { _ =>
        new WebSocketHandler {}
      }
      .startAndAwait { server =>
        val client = NodeWsClient(server.localPort)
        Rx.future(client.connect("/ws/secure"))
          .map { hs =>
            client.close()
            hs.status shouldBe 403
          }
      }
  }

end NodeWebSocketTest
