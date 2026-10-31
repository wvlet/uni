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

import wvlet.uni.http.rpc.{RPCDispatcher, RPCRouter, RPCStatus}
import wvlet.uni.test.UniTest

import java.nio.charset.StandardCharsets
import java.util.concurrent.{CountDownLatch, TimeUnit}
import scala.util.control.NonFatal
import scala.scalanative.libc.string as cstring
import scala.scalanative.posix.arpa.inet
import scala.scalanative.posix.netinet.in.{in_addr, sockaddr_in}
import scala.scalanative.posix.netinet.inOps.*
import scala.scalanative.posix.sys.socket as csocket
import scala.scalanative.posix.sys.socket.sockaddr
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*

/**
  * Verifies the Scala Native HTTP server with a raw POSIX-socket client (a real loopback
  * round-trip). The server runs on its own accept/worker threads, so the blocking client on the
  * test thread does not deadlock.
  */
trait NativeTodoApi:
  def add(title: String): String

class NativeTodoApiImpl extends NativeTodoApi:
  def add(title: String): String = title

class NativeServerTest extends UniTest:

  /** A parsed HTTP response: status code, headers (lower-cased keys), and body. */
  private case class RawResponse(status: Int, headers: Map[String, String], body: String)

  /** Connect to 127.0.0.1:port, send the raw request, read the full response, and parse it. */
  private def request(port: Int, raw: String): RawResponse =
    val fd = connectLoopback(port)
    try
      NativeSocket.sendAll(fd, raw.getBytes(StandardCharsets.ISO_8859_1))
      val acc   = scala.collection.mutable.ArrayBuffer.empty[Byte]
      var chunk = NativeSocket.recvChunk(fd)
      while chunk.nonEmpty do
        acc ++= chunk
        chunk = NativeSocket.recvChunk(fd)
      parse(new String(acc.toArray, StandardCharsets.ISO_8859_1))
    finally
      NativeSocket.close(fd)

  private def parse(text: String): RawResponse =
    val sep  = text.indexOf("\r\n\r\n")
    val head =
      if sep >= 0 then
        text.substring(0, sep)
      else
        text
    val body =
      if sep >= 0 then
        text.substring(sep + 4)
      else
        ""
    val lines      = head.split("\r\n")
    val statusCode = lines(0).split(" ")(1).toInt
    val headers    =
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
    RawResponse(statusCode, headers, body)

  /**
    * Decode an HTTP chunked-transfer body (`<hex>\r\n<data>\r\n` … `0\r\n\r\n`) into its payload.
    */
  private def dechunk(body: String): String =
    val sb       = StringBuilder()
    var rest     = body
    var continue = true
    while continue do
      val nl = rest.indexOf("\r\n")
      if nl < 0 then
        continue = false
      else
        val size = Integer.parseInt(rest.substring(0, nl).trim, 16)
        if size == 0 then
          continue = false
        else
          val dataStart = nl + 2
          sb.append(rest.substring(dataStart, dataStart + size))
          rest = rest.substring(dataStart + size + 2) // skip the chunk data + its trailing CRLF
    sb.toString

  private def connectLoopback(port: Int): Int =
    val fd = csocket.socket(csocket.AF_INET, csocket.SOCK_STREAM, 0)
    if fd < 0 then
      throw RuntimeException("Failed to create client socket")
    val addr = stackalloc[sockaddr_in]()
    cstring.memset(addr.asInstanceOf[Ptr[Byte]], 0, sizeof[sockaddr_in])
    addr.sin_family = csocket.AF_INET.toUShort
    addr.sin_port = inet.htons(port.toUShort)
    val ia = stackalloc[in_addr]()
    Zone.acquire { implicit z =>
      ia._1 = inet.inet_addr(toCString("127.0.0.1"))
    }
    addr.sin_addr = !ia
    if csocket.connect(fd, addr.asInstanceOf[Ptr[sockaddr]], sizeof[sockaddr_in].toUInt) < 0 then
      NativeSocket.close(fd)
      throw RuntimeException(s"Failed to connect to 127.0.0.1:${port}")
    fd

  test("should handle a GET request") {
    NativeServerConfig()
      .withHandler { req =>
        Response.ok(s"Hello from ${req.path}")
      }
      .withPort(0)
      .start { server =>
        val response = request(
          server.localPort,
          "GET /test HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
        )
        response.status shouldBe 200
        response.body shouldBe "Hello from /test"
      }
  }

  test("should read a raw UTF-8 request target as UTF-8") {
    NativeServerConfig()
      .withHandler { req =>
        // Code points as hex, so the response body stays ASCII
        val q = req.getQueryParam("q").getOrElse("")
        Response.ok(s"${req.path} ${q.codePoints().toArray.map(_.toHexString).mkString(",")}")
      }
      .withPort(0)
      .start { server =>
        // The raw helper writes one byte per char, so pass the UTF-8 bytes as ISO-8859-1 chars
        val target = String(
          "/café?q=é🐱".getBytes(StandardCharsets.UTF_8),
          StandardCharsets.ISO_8859_1
        )
        val response = request(
          server.localPort,
          s"GET ${target} HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
        )
        response.status shouldBe 200
        String(response.body.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8) shouldBe
          "/café e9,1f431"
      }
  }

  test("should handle a POST request with a body") {
    NativeServerConfig()
      .withHandler { req =>
        Response.ok(s"Received: ${req.content.toContentString}")
      }
      .withPort(0)
      .start { server =>
        val response = request(
          server.localPort,
          "POST /data HTTP/1.1\r\nHost: localhost\r\nContent-Length: 7\r\nConnection: close\r\n\r\npayload"
        )
        response.status shouldBe 200
        response.body shouldBe "Received: payload"
      }
  }

  test("should round-trip request and response headers") {
    NativeServerConfig()
      .withHandler { req =>
        Response.ok("ok").addHeader("X-Echoed", req.header("X-Echo").getOrElse("none"))
      }
      .withPort(0)
      .start { server =>
        val response = request(
          server.localPort,
          "GET /headers HTTP/1.1\r\nHost: localhost\r\nX-Echo: ping\r\nConnection: close\r\n\r\n"
        )
        response.status shouldBe 200
        response.headers.get("x-echoed") shouldBe Some("ping")
      }
  }

  test("should return 404 from the default handler") {
    NativeServerConfig()
      .withPort(0)
      .start { server =>
        val response = request(
          server.localPort,
          "GET /missing HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
        )
        response.status shouldBe 404
      }
  }

  test("should reject an unsupported HTTP method with 400") {
    NativeServerConfig()
      .withHandler(_ => Response.ok("ok"))
      .withPort(0)
      .start { server =>
        val response = request(
          server.localPort,
          "FROBNICATE /x HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
        )
        response.status shouldBe 400
      }
  }

  test("should not send a body for a HEAD request") {
    NativeServerConfig()
      .withHandler(req => Response.ok(s"Hello from ${req.path}"))
      .withPort(0)
      .start { server =>
        val response = request(
          server.localPort,
          "HEAD /test HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
        )
        response.status shouldBe 200
        // Content-Length reflects the would-be body, but no body is sent (RFC 9110).
        response.headers.get("content-length") shouldBe Some("16")
        response.body shouldBe ""
      }
  }

  test("should reject conflicting Content-Length headers with 400") {
    NativeServerConfig()
      .withHandler(_ => Response.ok("ok"))
      .withPort(0)
      .start { server =>
        val response = request(
          server.localPort,
          "POST /x HTTP/1.1\r\nHost: localhost\r\nContent-Length: 0\r\nContent-Length: 5\r\nConnection: close\r\n\r\n"
        )
        response.status shouldBe 400
      }
  }

  test("should reject a non-numeric Content-Length with 400") {
    NativeServerConfig()
      .withHandler(_ => Response.ok("ok"))
      .withPort(0)
      .start { server =>
        val response = request(
          server.localPort,
          "POST /x HTTP/1.1\r\nHost: localhost\r\nContent-Length: abc\r\nConnection: close\r\n\r\n"
        )
        response.status shouldBe 400
      }
  }

  test("should honor an explicitly-set response Content-Type") {
    NativeServerConfig()
      .withHandler(_ => Response.ok("{}").withContentType(ContentType.ApplicationJson))
      .withPort(0)
      .start { server =>
        val response = request(
          server.localPort,
          "GET /j HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
        )
        response.status shouldBe 200
        response.headers.get("content-type") shouldBe Some("application/json")
      }
  }

  test("should stream Server-Sent Events with chunked encoding") {
    import wvlet.uni.rx.Rx
    val events = Seq(ServerSentEvent.data("hello"), ServerSentEvent.data("world"))
    NativeServerConfig()
      .withRxHandler(_ => Rx.single(Response.eventStream(Rx.fromSeq(events))))
      .withPort(0)
      .start { server =>
        val response = request(
          server.localPort,
          "GET /events HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
        )
        response.status shouldBe 200
        response.headers.get("content-type") shouldBe Some("text/event-stream")
        response.headers.get("transfer-encoding") shouldBe Some("chunked")
        val decoded = dechunk(response.body)
        decoded shouldContain "data: hello"
        decoded shouldContain "data: world"
      }
  }

  test("should not stream a body for HEAD on an SSE endpoint") {
    import wvlet.uni.rx.Rx
    NativeServerConfig()
      .withRxHandler(_ =>
        Rx.single(Response.eventStream(Rx.fromSeq(Seq(ServerSentEvent.data("x")))))
      )
      .withPort(0)
      .start { server =>
        val response = request(
          server.localPort,
          "HEAD /events HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
        )
        response.status shouldBe 200
        response.headers.get("content-type") shouldBe Some("text/event-stream")
        response.body shouldBe ""
      }
  }

  test("should report a bound ephemeral port") {
    NativeServerConfig()
      .withPort(0)
      .start { server =>
        (server.localPort > 0) shouldBe true
        server.isRunning shouldBe true
      }
  }

  test("should reject a foreign Host on a loopback server with 403") {
    NativeServerConfig()
      .withHandler(_ => Response.ok("ok"))
      .withPort(0)
      .start { server =>
        val port = server.localPort
        request(port, s"GET / HTTP/1.1\r\nHost: localhost:${port}\r\nConnection: close\r\n\r\n")
          .status shouldBe 200
        request(port, s"GET / HTTP/1.1\r\nHost: evil.example:${port}\r\nConnection: close\r\n\r\n")
          .status shouldBe 403
      }
  }

  test("should reject streaming connections over the limit with 503") {
    import wvlet.uni.rx.Rx
    NativeServerConfig()
      .withRxHandler { req =>
        if req.path == "/events" then
          Rx.single(Response.eventStream(Rx.variable(ServerSentEvent.data("hi"))))
        else
          Rx.single(Response.ok("ok"))
      }
      .withMaxStreamingConnections(1)
      .withPort(0)
      .start { server =>
        val port = server.localPort
        val fd   = connectLoopback(port)
        try
          NativeSocket.sendAll(
            fd,
            "GET /events HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1)
          )
          // The open stream's headers (and first event) arrive
          String(NativeSocket.recvChunk(fd), StandardCharsets.ISO_8859_1) shouldContain " 200 "
          request(port, "GET /events HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
            .status shouldBe 503
          // Ordinary requests are still served
          request(port, "GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
            .status shouldBe 200
        finally
          NativeSocket.close(fd)
      }
  }

  test("should keep workers free for ordinary requests by default") {
    NativeServerConfig().effectiveMaxStreamingConnections shouldBe 12
    NativeServerConfig().withWorkerThreads(4).effectiveMaxStreamingConnections shouldBe 2
    NativeServerConfig().withWorkerThreads(1).effectiveMaxStreamingConnections shouldBe 1
  }

  test("should close an idle keep-alive connection after the idle timeout") {
    NativeServerConfig()
      .withHandler(_ => Response.ok("ok"))
      .withIdleTimeoutMillis(300)
      .withPort(0)
      .start { server =>
        val fd = connectLoopback(server.localPort)
        try
          // Keep-alive request (no Connection: close), so the server waits for a follow-up request.
          NativeSocket.sendAll(
            fd,
            "GET /x HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1)
          )
          NativeSocket.recvChunk(fd).nonEmpty shouldBe true // the 200 response
          // Now stay idle; the server should close the connection after the idle timeout.
          val start = System.currentTimeMillis()
          var chunk = NativeSocket.recvChunk(fd)
          while chunk.nonEmpty do
            chunk = NativeSocket.recvChunk(fd)
          val elapsed = System.currentTimeMillis() - start
          (elapsed < 5000) shouldBe true
        finally
          NativeSocket.close(fd)
      }
  }

  test("should close a connection with an incomplete request after the read timeout") {
    NativeServerConfig()
      .withHandler(_ => Response.ok("ok"))
      .withReadTimeoutMillis(300)
      .withPort(0)
      .start { server =>
        // Headers are never terminated, so the read times out mid-request.
        val response = request(server.localPort, "GET /x HTTP/1.1\r\nHost: localhost\r\n")
        response.status shouldBe 400
      }
  }

  /**
    * Send `requests` POSTs one after another over one kept-alive connection, checking that each
    * response echoes its own request. Records the first mismatch or error in `failures`.
    */
  private def keepAliveClient(
      port: Int,
      client: Int,
      requests: Int,
      failures: java.util.Queue[String]
  ): Unit =
    val fd  = connectLoopback(port)
    val buf = scala.collection.mutable.ArrayBuffer.empty[Byte]
    // Read one response off the connection, leaving any later bytes in buf
    def readResponse(): Option[RawResponse] =
      var sep = -1
      while sep < 0 do
        sep = new String(buf.toArray, StandardCharsets.ISO_8859_1).indexOf("\r\n\r\n")
        if sep < 0 then
          val chunk = NativeSocket.recvChunk(fd)
          if chunk.isEmpty then
            return None
          buf ++= chunk
      val head = parse(new String(buf.take(sep).toArray, StandardCharsets.ISO_8859_1))
      val end  = sep + 4 + head.headers("content-length").toInt
      while buf.length < end do
        val chunk = NativeSocket.recvChunk(fd)
        if chunk.isEmpty then
          return None
        buf ++= chunk
      val body = new String(buf.slice(sep + 4, end).toArray, StandardCharsets.UTF_8)
      buf.remove(0, end)
      Some(head.copy(body = body))

    try
      var i = 0
      while i < requests do
        val body = s"client ${client} request ${i}"
        NativeSocket.sendAll(
          fd,
          (
            s"POST /c${client} HTTP/1.1\r\nHost: localhost\r\nContent-Length: ${body.length}" +
              s"\r\n\r\n${body}"
          ).getBytes(StandardCharsets.ISO_8859_1)
        )
        readResponse() match
          case Some(r) if r.status == 200 && r.body == s"/c${client}:${body}" =>
            i += 1
          case other =>
            failures.add(s"client ${client} request ${i}: ${other}")
            i = requests
    catch
      case NonFatal(e) =>
        failures.add(s"client ${client}: ${e}")
    finally
      NativeSocket.close(fd)

  end keepAliveClient

  test("should answer concurrent keep-alive clients in order") {
    NativeServerConfig()
      .withHandler(req => Response.ok(s"${req.path}:${req.content.asString.getOrElse("")}"))
      .withPort(0)
      .start { server =>
        val failures = java.util.concurrent.ConcurrentLinkedQueue[String]()
        val threads  = (0 until 8).map { c =>
          Thread(() => keepAliveClient(server.localPort, c, requests = 200, failures))
        }
        threads.foreach(_.start())
        threads.foreach(_.join(30000))
        threads.exists(_.isAlive) shouldBe false
        failures.toArray.toSeq shouldBe Seq.empty
      }
  }

  // ---- WebSocket ----

  /**
    * A minimal raw-socket WebSocket client: upgrade handshake, masked text send, server-frame read.
    */
  private class WsClient(fd: Int):
    private val buf = scala.collection.mutable.ArrayBuffer.empty[Byte]

    private def ensure(n: Int): Unit =
      while buf.length < n do
        val c = NativeSocket.recvChunk(fd)
        if c.isEmpty then
          throw RuntimeException("WebSocket connection closed unexpectedly")
        buf ++= c

    private def headerEnd: Int =
      var i = 0
      while i + 3 < buf.length do
        if buf(i) == '\r' && buf(i + 1) == '\n' && buf(i + 2) == '\r' && buf(i + 3) == '\n' then
          return i
        i += 1
      -1

    /**
      * Send the upgrade request and return the parsed handshake response (leftover frames
      * buffered).
      */
    def handshake(path: String, key: String): RawResponse =
      val req =
        s"GET ${path} HTTP/1.1\r\nHost: localhost\r\n" +
          s"Upgrade: websocket\r\nConnection: Upgrade\r\n" +
          s"Sec-WebSocket-Key: ${key}\r\nSec-WebSocket-Version: 13\r\n\r\n"
      NativeSocket.sendAll(fd, req.getBytes(StandardCharsets.ISO_8859_1))
      var idx = headerEnd
      while idx < 0 do
        val c = NativeSocket.recvChunk(fd)
        if c.isEmpty then
          throw RuntimeException("no handshake response")
        buf ++= c
        idx = headerEnd
      val head = new String(buf.slice(0, idx).toArray, StandardCharsets.ISO_8859_1)
      buf.remove(0, idx + 4)
      parse(head)

    def sendText(text: String): Unit =
      val payload = text.getBytes(StandardCharsets.UTF_8)
      val mask    = Array[Byte](0x12, 0x34, 0x56, 0x78)
      val masked  = new Array[Byte](payload.length)
      var i       = 0
      while i < payload.length do
        masked(i) = (payload(i) ^ mask(i % 4)).toByte
        i += 1
      val header = Array[Byte](0x81.toByte, (0x80 | payload.length).toByte)
      NativeSocket.sendAll(fd, header ++ mask ++ masked)

    /** Read one server frame and return its UTF-8 text payload. */
    def readText(): String =
      ensure(2)
      var len    = buf(1) & 0x7f
      var header = 2
      if len == 126 then
        ensure(4)
        len = ((buf(2) & 0xff) << 8) | (buf(3) & 0xff)
        header = 4
      ensure(header + len)
      buf.remove(0, header)
      val payload = buf.slice(0, len).toArray
      buf.remove(0, len)
      new String(payload, StandardCharsets.UTF_8)

    def close(): Unit = NativeSocket.close(fd)

  end WsClient

  test("acceptKey matches the RFC 6455 example") {
    WebSocketFrame.acceptKey("dGhlIHNhbXBsZSBub25jZQ==") shouldBe "s3pPLMBiTxaQ9kYGzzhZRbK+xOo="
  }

  test("WebSocket echoes text messages") {
    NativeServerConfig()
      .withPort(0)
      .withWebSocketRoute("/ws/echo") { _ =>
        new WebSocketHandler:
          override def onTextMessage(ctx: WebSocketContext, message: String): Unit = ctx.send(
            s"echo:${message}"
          )
      }
      .start { server =>
        val client = WsClient(connectLoopback(server.localPort))
        try
          val resp = client.handshake("/ws/echo", "dGhlIHNhbXBsZSBub25jZQ==")
          resp.status shouldBe 101
          resp.headers.get("sec-websocket-accept") shouldBe Some("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=")
          client.sendText("hello")
          client.readText() shouldBe "echo:hello"
        finally
          client.close()
      }
  }

  test("WebSocket can push a message on open") {
    NativeServerConfig()
      .withPort(0)
      .withWebSocketRoute("/ws/push") { _ =>
        new WebSocketHandler:
          override def onOpen(ctx: WebSocketContext): Unit = ctx.send("welcome")
      }
      .start { server =>
        val client = WsClient(connectLoopback(server.localPort))
        try
          client.handshake("/ws/push", "dGhlIHNhbXBsZSBub25jZQ==").status shouldBe 101
          client.readText() shouldBe "welcome"
        finally
          client.close()
      }
  }

  test("WebSocket fires onOpen and onClose") {
    val opened = CountDownLatch(1)
    val closed = CountDownLatch(1)
    NativeServerConfig()
      .withPort(0)
      .withWebSocketRoute("/ws/life") { _ =>
        new WebSocketHandler:
          override def onOpen(ctx: WebSocketContext): Unit  = opened.countDown()
          override def onClose(ctx: WebSocketContext): Unit = closed.countDown()
      }
      .start { server =>
        val client = WsClient(connectLoopback(server.localPort))
        client.handshake("/ws/life", "dGhlIHNhbXBsZSBub25jZQ==").status shouldBe 101
        opened.await(5, TimeUnit.SECONDS) shouldBe true
        client.close()
        closed.await(5, TimeUnit.SECONDS) shouldBe true
      }
  }

  test("WebSocket upgrade without a key is rejected with 400") {
    NativeServerConfig()
      .withPort(0)
      .withWebSocketRoute("/ws") { _ =>
        new WebSocketHandler {}
      }
      .start { server =>
        val resp = request(
          server.localPort,
          "GET /ws HTTP/1.1\r\nHost: localhost\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n\r\n"
        )
        resp.status shouldBe 400
      }
  }

  test("WebSocket upgrade with an unsupported version is rejected with 426") {
    NativeServerConfig()
      .withPort(0)
      .withWebSocketRoute("/ws") { _ =>
        new WebSocketHandler {}
      }
      .start { server =>
        val resp = request(
          server.localPort,
          "GET /ws HTTP/1.1\r\nHost: localhost\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
            "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 8\r\n\r\n"
        )
        resp.status shouldBe 426
        resp.headers.get("sec-websocket-version") shouldBe Some("13")
      }
  }

  test("WebSocket upgrade can be rejected by a filter") {
    val deny = RxHttpFilter { (_, _) =>
      wvlet.uni.rx.Rx.single(Response.forbidden)
    }
    NativeServerConfig()
      .withPort(0)
      .withWebSocketRoute("/ws/secure", deny) { _ =>
        new WebSocketHandler {}
      }
      .start { server =>
        val client = WsClient(connectLoopback(server.localPort))
        try client.handshake("/ws/secure", "dGhlIHNhbXBsZSBub25jZQ==").status shouldBe 403
        finally client.close()
      }
  }

  test("should not send RPC stack traces unless detailedErrors is enabled") {
    val router = RPCRouter.of[NativeTodoApi](NativeTodoApiImpl())
    val path   = router.routes.head.path
    // Missing the "request" wrapper, so decoding fails with INVALID_REQUEST_U1
    val body                                         = """{"title":"hello"}"""
    def post(dispatcher: RPCDispatcher): RawResponse = NativeServerConfig()
      .withRxHandler(dispatcher.dispatch)
      .withPort(0)
      .start { server =>
        request(
          server.localPort,
          s"POST ${path} HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/json\r\n" +
            s"Content-Length: ${body.length}\r\nConnection: close\r\n\r\n${body}"
        )
      }

    val response = post(RPCDispatcher(router))
    response.status shouldBe 400
    response.body shouldContain RPCStatus.INVALID_REQUEST_U1.name
    response.body shouldNotContain "stackTrace"

    post(RPCDispatcher(router).withDetailedErrors(true)).body shouldContain "stackTrace"
  }

end NativeServerTest
