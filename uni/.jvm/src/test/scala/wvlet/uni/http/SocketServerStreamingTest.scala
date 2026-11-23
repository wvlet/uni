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

import wvlet.uni.test.UniTest

import java.io.{ByteArrayOutputStream, IOException, InputStream}
import java.net.{Socket, URI}
import java.net.http.{
  HttpClient as JHttpClient,
  HttpRequest as JHttpRequest,
  HttpResponse as JHttpResponse
}
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference

/**
  * Streamed request bodies on the built-in server: the handler reads them as they arrive, beyond
  * `maxContentLength`, with Content-Length and chunked framing, `100 Continue` on the first read,
  * and the rest of an unread body drained or the connection closed.
  */
class SocketServerStreamingTest extends UniTest:

  private val leftover = AtomicReference[InputStream]()

  // Streams /upload/*; /buffered stays buffered. /upload/count reads the whole body, /upload/skip
  // reads none, and /upload/keep keeps the stream for a read after the response
  private val app = Http
    .server
    .withMaxContentLength(1024)
    .withStreamingRequests(_.path.startsWith("/upload/"))
    .withHandler { (req: Request) =>
      req.content match
        case s: HttpContent.StreamContent if req.path == "/upload/count" =>
          val in    = s.openStream
          val buf   = new Array[Byte](64 * 1024)
          var total = 0L
          var n     = in.read(buf)
          while n >= 0 do
            total += n
            n = in.read(buf)
          Response.ok.withTextContent(s"${total}").addHeader("X-Length", s.length.toString)
        case s: HttpContent.StreamContent if req.path == "/upload/keep" =>
          leftover.set(s.openStream)
          Response.ok.withTextContent("kept")
        case _: HttpContent.StreamContent =>
          Response.ok.withTextContent("skipped")
        case other =>
          Response.ok.withTextContent(s"buffered ${other.length}")
    }

  private def withServer[A](config: HttpServerConfig = app)(body: HttpServer => A): A =
    val server = JvmHttpServer(config.withPort(0))
    server.start()
    try body(server)
    finally server.stop()

  private def connect(server: HttpServer): Socket =
    val s = Socket("127.0.0.1", server.localPort)
    s.setSoTimeout(10000)
    s

  private def send(s: Socket, raw: String): Unit =
    s.getOutputStream.write(raw.getBytes(StandardCharsets.ISO_8859_1))
    s.getOutputStream.flush()

  case class Resp(status: Int, headers: Map[String, String], body: String)

  private def readResponse(in: InputStream): Resp =
    val head = ByteArrayOutputStream()
    var last = 0
    while last != 0x0d0a0d0a do
      val b = in.read()
      if b < 0 then
        fail(s"Connection closed after: ${head.toString(StandardCharsets.ISO_8859_1)}")
      head.write(b)
      last = (last << 8) | b
    val lines   = head.toString(StandardCharsets.ISO_8859_1).trim.split("\r\n")
    val headers =
      lines
        .drop(1)
        .map { l =>
          val c = l.indexOf(':')
          l.substring(0, c).trim.toLowerCase -> l.substring(c + 1).trim
        }
        .toMap
    val len = headers.get("content-length").map(_.toInt).getOrElse(0)
    Resp(lines(0).split(" ")(1).toInt, headers, String(in.readNBytes(len), StandardCharsets.UTF_8))

  private def isClosed(s: Socket): Boolean =
    try
      s.getInputStream.read() < 0
    catch
      case _: IOException =>
        true

  private def post(server: HttpServer, path: String, body: Array[Byte], chunked: Boolean) =
    JHttpClient
      .newHttpClient()
      .send(
        JHttpRequest
          .newBuilder(URI.create(s"http://127.0.0.1:${server.localPort}${path}"))
          .POST(
            if chunked then
              // A publisher of unknown length makes the client send a chunked body
              JHttpRequest.BodyPublishers.ofInputStream(() => java.io.ByteArrayInputStream(body))
            else
              JHttpRequest.BodyPublishers.ofByteArray(body)
          )
          .build(),
        JHttpResponse.BodyHandlers.ofString()
      )

  test("stream a body far larger than maxContentLength, with Content-Length or chunked") {
    withServer() { server =>
      val body = new Array[Byte](8 * 1024 * 1024)
      val r1   = post(server, "/upload/count", body, chunked = false)
      r1.statusCode() shouldBe 200
      r1.body() shouldBe body.length.toString
      r1.headers().firstValue("X-Length").get() shouldBe body.length.toString
      val r2 = post(server, "/upload/count", body, chunked = true)
      r2.statusCode() shouldBe 200
      r2.body() shouldBe body.length.toString
      r2.headers().firstValue("X-Length").get() shouldBe "-1"
      // Requests the predicate doesn't match stay buffered and limited
      // Small enough to be sent in full before the early 413 closes the connection
      post(server, "/buffered", new Array[Byte](4096), chunked = false).statusCode() shouldBe 413
      post(server, "/buffered", new Array[Byte](10), chunked = false).body() shouldBe "buffered 10"
    }
  }

  test("drain a small unread body and keep the connection; close after a large one") {
    withServer() { server =>
      val s = connect(server)
      try
        send(
          s,
          s"POST /upload/skip HTTP/1.1\r\nHost: localhost\r\nContent-Length: 1000\r\n\r\n${"x" *
              1000}"
        )
        readResponse(s.getInputStream).body shouldBe "skipped"
        // The same connection serves the next request
        send(s, "POST /upload/count HTTP/1.1\r\nHost: localhost\r\nContent-Length: 3\r\n\r\nabc")
        readResponse(s.getInputStream).body shouldBe "3"
      finally
        s.close()

      val big = connect(server)
      try
        send(
          big,
          s"POST /upload/skip HTTP/1.1\r\nHost: localhost\r\nContent-Length: ${1024 * 1024}\r\n\r\n"
        )
        val r = readResponse(big.getInputStream)
        r.body shouldBe "skipped"
        r.headers("connection") shouldBe "close"
        isClosed(big) shouldBe true
      finally
        big.close()
    }
  }

  test("send 100 Continue only when the handler reads, after the filters") {
    val guarded = app.withFilter(
      RxHttpFilter { (req, next) =>
        if req.header("Authorization").isEmpty then
          wvlet.uni.rx.Rx.single(Response(HttpStatus.Unauthorized_401))
        else
          next.handle(req)
      }
    )
    withServer(guarded) { server =>
      val head =
        "POST /upload/count HTTP/1.1\r\nHost: localhost\r\nExpect: 100-continue\r\nContent-Length: 5\r\n"
      // Rejected by the filter: the final status comes without a 100, and the body is never sent
      val rejected = connect(server)
      try
        send(rejected, head + "\r\n")
        val r = readResponse(rejected.getInputStream)
        r.status shouldBe 401
        isClosed(rejected) shouldBe true
      finally
        rejected.close()
      // Accepted: 100 Continue arrives once the handler reads, then the body is read
      val accepted = connect(server)
      try
        send(accepted, head + "Authorization: yes\r\n\r\n")
        readResponse(accepted.getInputStream).status shouldBe 100
        send(accepted, "hello")
        readResponse(accepted.getInputStream).body shouldBe "5"
      finally
        accepted.close()
    }
  }

  test("answer 413 for a streamed body over maxStreamingRequestSize") {
    withServer(app.withMaxStreamingRequestSize(1000)) { server =>
      val s = connect(server)
      try
        send(s, "POST /upload/count HTTP/1.1\r\nHost: localhost\r\nContent-Length: 5000\r\n\r\n")
        readResponse(s.getInputStream).status shouldBe 413
      finally
        s.close()
      // A chunked body is cut off once it passes the limit, and its error replaces the response
      val c = connect(server)
      try
        send(
          c,
          s"POST /upload/count HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\n\r\n800\r\n${"y" *
              2048}\r\n"
        )
        readResponse(c.getInputStream).status shouldBe 413
        isClosed(c) shouldBe true
      finally
        c.close()
    }
  }

  test("answer 400 for a malformed chunk and 408 for a body that stalls past its deadline") {
    withServer(app.withStreamingRequestTimeoutMillis(300)) { server =>
      val bad = connect(server)
      try
        send(
          bad,
          "POST /upload/count HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\n\r\nzz\r\n"
        )
        readResponse(bad.getInputStream).status shouldBe 400
      finally
        bad.close()
      val stalled = connect(server)
      try
        send(
          stalled,
          "POST /upload/count HTTP/1.1\r\nHost: localhost\r\nContent-Length: 10\r\n\r\nabc"
        )
        readResponse(stalled.getInputStream).status shouldBe 408
      finally
        stalled.close()
    }
  }

  test("keep the handler's response when the unread rest of the body is malformed") {
    withServer() { server =>
      val s = connect(server)
      try
        send(
          s,
          "POST /upload/skip HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\n\r\nzz\r\n"
        )
        val r = readResponse(s.getInputStream)
        r.status shouldBe 200
        r.body shouldBe "skipped"
        isClosed(s) shouldBe true
      finally
        s.close()
    }
  }

  test("refuse reads of the body after the response is sent") {
    withServer() { server =>
      val s = connect(server)
      try
        send(s, "POST /upload/keep HTTP/1.1\r\nHost: localhost\r\nContent-Length: 3\r\n\r\nabc")
        readResponse(s.getInputStream).body shouldBe "kept"
        intercept[IOException] {
          leftover.get().read()
        }
      finally
        s.close()
    }
  }

  test("let a filter read the body and leave it for the handler") {
    val peeking = app.withFilter(
      RxHttpFilter { (req, next) =>
        // RPC argument logging and similar filters read the whole body
        req.content.toContentString
        next.handle(req)
      }
    )
    withServer(peeking) { server =>
      val s = connect(server)
      try
        send(s, "POST /upload/count HTTP/1.1\r\nHost: localhost\r\nContent-Length: 4\r\n\r\nabcd")
        readResponse(s.getInputStream).body shouldBe "4"
      finally
        s.close()
    }
  }

end SocketServerStreamingTest
