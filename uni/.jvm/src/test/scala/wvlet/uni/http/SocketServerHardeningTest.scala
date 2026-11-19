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

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, IOException, InputStream}
import java.net.{Socket, SocketException, URI}
import java.net.http.{
  HttpClient as JHttpClient,
  HttpRequest as JHttpRequest,
  HttpResponse as JHttpResponse
}
import java.nio.charset.StandardCharsets
import java.util.zip.{Deflater, DeflaterOutputStream, GZIPInputStream, GZIPOutputStream}

/**
  * The safeguards and HTTP/1.1 features the socket server shares with Netty's pipeline: request
  * size limits, the request and write deadlines, chunked bodies, `Expect: 100-continue`, keep-alive
  * rules, and request and response compression. Requests are raw bytes, so the tests see exactly
  * what goes on the wire.
  */
class SocketServerHardeningTest extends UniTest:

  private val echo = Http
    .server
    .withHandler { (req: Request) =>
      req.path match
        case "/echo" =>
          Response
            .ok
            .withTextContent(req.content.toContentString)
            .addHeader("X-Content-Length", req.header(HttpHeader.ContentLength).getOrElse("none"))
            .addHeader(
              "X-Content-Encoding",
              req.header(HttpHeader.ContentEncoding).getOrElse("none")
            )
        case "/big" =>
          Response.ok.withTextContent("x" * 4096).addHeader(HttpHeader.ETag, "\"v1\"")
        case "/small" =>
          Response.ok.withTextContent("tiny")
        case "/framed" =>
          // A handler-set Transfer-Encoding must not reach the wire next to Content-Length
          Response.ok.withTextContent("body").addHeader(HttpHeader.TransferEncoding, "chunked")
        case "/huge" =>
          Response.ok.withContent(HttpContent.bytes(new Array[Byte](64 * 1024 * 1024)))
        case _ =>
          Response.ok.withTextContent("hello")
    }

  private def withServer[A](
      config: HttpServerConfig = echo,
      update: SocketServerLimits => SocketServerLimits = identity
  )(body: HttpServer => A): A =
    val c      = config.withPort(0)
    val server = JvmHttpServer(c, update(JvmHttpServer.limits(c)))
    server.start()
    try body(server)
    finally server.stop()

  private def connect(server: HttpServer): Socket =
    val s = Socket("127.0.0.1", server.localPort)
    s.setSoTimeout(10000)
    s

  private def send(s: Socket, raw: String): Unit = send(
    s,
    raw.getBytes(StandardCharsets.ISO_8859_1)
  )

  private def send(s: Socket, bytes: Array[Byte]): Unit =
    s.getOutputStream.write(bytes)
    s.getOutputStream.flush()

  case class Resp(status: Int, headers: Map[String, String], body: Array[Byte]):
    def text: String = String(body, StandardCharsets.UTF_8)

  /** Read one response head, then a body of its Content-Length. */
  private def readResponse(in: InputStream, withBody: Boolean = true): Resp =
    val head = ByteArrayOutputStream()
    var last = 0
    while last != 0x0d0a0d0a do
      val b = in.read()
      if b < 0 then
        fail(s"Connection closed after: ${head.toString(StandardCharsets.ISO_8859_1)}")
      head.write(b)
      last = (last << 8) | b
    val lines   = head.toString(StandardCharsets.ISO_8859_1).trim.split("\r\n")
    val status  = lines(0).split(" ")(1).toInt
    val headers =
      lines
        .drop(1)
        .map { l =>
          val c = l.indexOf(':')
          l.substring(0, c).trim.toLowerCase -> l.substring(c + 1).trim
        }
        .toMap
    val len =
      if withBody then
        headers.get("content-length").map(_.toInt).getOrElse(0)
      else
        0
    Resp(status, headers, in.readNBytes(len))

  private def request(server: HttpServer, raw: String, withBody: Boolean = true): Resp =
    val s = connect(server)
    try
      send(s, raw)
      readResponse(s.getInputStream, withBody)
    finally
      s.close()

  /** Whether the server closed the connection: the next read sees EOF or a reset. */
  private def isClosed(s: Socket): Boolean =
    try
      s.getInputStream.read() < 0
    catch
      case _: IOException =>
        true

  private def gzip(data: Array[Byte]): Array[Byte] =
    val out = ByteArrayOutputStream()
    val gz  = GZIPOutputStream(out)
    gz.write(data)
    gz.close()
    out.toByteArray

  private def deflate(data: Array[Byte], raw: Boolean): Array[Byte] =
    val out = ByteArrayOutputStream()
    val d   = DeflaterOutputStream(out, Deflater(Deflater.DEFAULT_COMPRESSION, raw))
    d.write(data)
    d.close()
    out.toByteArray

  test("limit the request line (414), headers (431), and body (413) separately") {
    withServer() { server =>
      request(server, s"GET /${"a" * 5000} HTTP/1.1\r\nHost: localhost\r\n\r\n").status shouldBe 414
      request(server, s"GET / HTTP/1.1\r\nHost: localhost\r\nX-Big: ${"b" * 9000}\r\n\r\n")
        .status shouldBe 431
      // A header block near the limit is fine
      request(server, s"GET / HTTP/1.1\r\nHost: localhost\r\nX-Big: ${"b" * 7000}\r\n\r\n")
        .status shouldBe 200
      request(
        server,
        s"POST /echo HTTP/1.1\r\nHost: localhost\r\nContent-Length: ${2 * 1024 * 1024}\r\n\r\n"
      ).status shouldBe 413
    }
  }

  test("close a connection that trickles a request past the request deadline (slowloris)") {
    withServer(update = _.copy(requestTimeoutMillis = 500)) { server =>
      val s = connect(server)
      try
        val started = System.nanoTime()
        send(s, "GET / HTTP/1.1\r\n")
        // Each byte arrives well within the per-read timeout, but the request never ends in time
        var answered = false
        var i        = 0
        while !answered && i < 40 do
          try
            send(s, "X")
            Thread.sleep(100)
            answered = s.getInputStream.available() > 0
          catch
            case _: SocketException =>
              answered = true
          i += 1
        val r = readResponse(s.getInputStream)
        r.status shouldBe 408
        ((System.nanoTime() - started) / 1000000 < 3000) shouldBe true
      finally
        s.close()
    }
  }

  test("close a connection whose client stops reading the response") {
    withServer(update = _.copy(writeTimeoutMillis = 300)) { server =>
      val s = java.net.Socket()
      s.setReceiveBufferSize(4096)
      s.connect(java.net.InetSocketAddress("127.0.0.1", server.localPort))
      s.setSoTimeout(10000)
      try
        send(s, "GET /huge HTTP/1.1\r\nHost: localhost\r\n\r\n")
        // Let the server fill the socket buffers and stall, then give the watchdog time to act
        Thread.sleep(2000)
        val in    = s.getInputStream
        val buf   = new Array[Byte](65536)
        var total = 0L
        var n     = 0
        try
          while
            n = in.read(buf)
            n > 0
          do
            total += n
        catch
          case _: IOException =>
            ()
        // The server gave up long before the 64 MiB body was sent
        (total < 64L * 1024 * 1024) shouldBe true
      finally
        s.close()
    }
  }

  test("decode chunked request bodies, with extensions and trailers") {
    withServer() { server =>
      val s = connect(server)
      try
        send(
          s,
          "POST /echo HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\n\r\n" +
            "5;ext=1\r\nhello\r\n6\r\n world\r\n0\r\nX-Trailer: t\r\n\r\n"
        )
        val r = readResponse(s.getInputStream)
        r.text shouldBe "hello world"
        r.headers("x-content-length") shouldBe "11"
        // The connection stays usable
        send(s, "GET / HTTP/1.1\r\nHost: localhost\r\n\r\n")
        readResponse(s.getInputStream).text shouldBe "hello"
      finally
        s.close()
    }
  }

  test("accept a streamed body from java.net.http.HttpClient") {
    withServer() { server =>
      val client = JHttpClient.newHttpClient()
      val body   = ("streamed " * 1000).getBytes(StandardCharsets.UTF_8)
      val req    = JHttpRequest
        .newBuilder(URI.create(s"http://localhost:${server.localPort}/echo"))
        // An InputStream body has no known length, so the client sends it chunked
        .POST(JHttpRequest.BodyPublishers.ofInputStream(() => ByteArrayInputStream(body)))
        .build()
      val resp = client.send(req, JHttpResponse.BodyHandlers.ofString())
      resp.statusCode() shouldBe 200
      resp.body() shouldBe String(body, StandardCharsets.UTF_8)
    }
  }

  test("close the connection after a request framed by both Transfer-Encoding and Content-Length") {
    withServer() { server =>
      val s = connect(server)
      try
        send(
          s,
          "POST /echo HTTP/1.1\r\nHost: localhost\r\nContent-Length: 3\r\nTransfer-Encoding: chunked\r\n\r\n1\r\nx\r\n0\r\n\r\n"
        )
        val r = readResponse(s.getInputStream)
        r.text shouldBe "x"
        r.headers("connection") shouldBe "close"
        isClosed(s) shouldBe true
      finally
        s.close()
    }
  }

  test("send 100 Continue before reading the body, and 417 for other expectations") {
    withServer() { server =>
      val s = connect(server)
      try
        send(
          s,
          "POST /echo HTTP/1.1\r\nHost: localhost\r\nExpect: 100-continue\r\nContent-Length: 5\r\n\r\n"
        )
        val in = s.getInputStream
        readResponse(in).status shouldBe 100
        send(s, "hello")
        readResponse(in).text shouldBe "hello"
      finally
        s.close()
      request(
        server,
        "POST /echo HTTP/1.1\r\nHost: localhost\r\nExpect: 100-continue\r\nContent-Length: 99999999\r\n\r\n"
      ).status shouldBe 413
      request(
        server,
        "POST /echo HTTP/1.1\r\nHost: localhost\r\nExpect: magic\r\nContent-Length: 1\r\n\r\nx"
      ).status shouldBe 417
    }
  }

  test("close HTTP/1.0 connections unless they ask for keep-alive") {
    withServer() { server =>
      val s = connect(server)
      try
        send(s, "GET / HTTP/1.0\r\n\r\n")
        readResponse(s.getInputStream).headers("connection") shouldBe "close"
        isClosed(s) shouldBe true
      finally
        s.close()
      val k = connect(server)
      try
        send(k, "GET / HTTP/1.0\r\nConnection: keep-alive\r\n\r\n")
        readResponse(k.getInputStream).headers("connection") shouldBe "keep-alive"
        send(k, "GET / HTTP/1.1\r\nHost: localhost\r\nConnection: keep-alive, close\r\n\r\n")
        readResponse(k.getInputStream).headers("connection") shouldBe "close"
        isClosed(k) shouldBe true
      finally
        k.close()
    }
  }

  test("decode gzip and deflate request bodies before the handler") {
    withServer() { server =>
      val text = "compressed body " * 100
      val data = text.getBytes(StandardCharsets.UTF_8)
      Seq(
        "gzip"    -> gzip(data),
        "deflate" -> deflate(data, raw = false),
        "deflate" -> deflate(data, raw = true)
      ).foreach { (encoding, body) =>
        val s = connect(server)
        try
          send(
            s,
            s"POST /echo HTTP/1.1\r\nHost: localhost\r\nContent-Encoding: ${encoding}\r\nContent-Length: ${body
                .length}\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1) ++ body
          )
          val r = readResponse(s.getInputStream)
          r.text shouldBe text
          r.headers("x-content-encoding") shouldBe "none"
          r.headers("x-content-length") shouldBe data.length.toString
        finally
          s.close()
      }
      // Other encodings reach the handler as sent
      request(
        server,
        "POST /echo HTTP/1.1\r\nHost: localhost\r\nContent-Encoding: br\r\nContent-Length: 2\r\n\r\nab"
      ).headers("x-content-encoding") shouldBe "br"
      request(
        server,
        "POST /echo HTTP/1.1\r\nHost: localhost\r\nContent-Encoding: gzip\r\nContent-Length: 4\r\n\r\nnope"
      ).status shouldBe 400
    }
  }

  test("reject a compressed request that expands past the decompressed size limit") {
    withServer(update = _.copy(maxDecompressedRequestSize = 10000)) { server =>
      val bomb = gzip(new Array[Byte](1000000))
      val s    = connect(server)
      try
        send(
          s,
          s"POST /echo HTTP/1.1\r\nHost: localhost\r\nContent-Encoding: gzip\r\nContent-Length: ${bomb
              .length}\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1) ++ bomb
        )
        readResponse(s.getInputStream).status shouldBe 413
        isClosed(s) shouldBe true
      finally
        s.close()
    }
  }

  test("compress responses for Accept-Encoding, as uni-netty does") {
    withServer() { server =>
      val r = request(
        server,
        "GET /big HTTP/1.1\r\nHost: localhost\r\nAccept-Encoding: gzip\r\n\r\n"
      )
      r.headers("content-encoding") shouldBe "gzip"
      r.headers("vary") shouldBe "Accept-Encoding"
      r.headers("etag") shouldBe "W/\"v1\""
      r.headers("content-type") shouldContain "text/plain"
      String(
        GZIPInputStream(ByteArrayInputStream(r.body)).readAllBytes(),
        StandardCharsets.UTF_8
      ) shouldBe "x" * 4096

      val d = request(
        server,
        "GET /big HTTP/1.1\r\nHost: localhost\r\nAccept-Encoding: gzip;q=0.5, deflate\r\n\r\n"
      )
      d.headers("content-encoding") shouldBe "deflate"

      // Identity when not asked for, for small bodies, and for HEAD
      request(server, "GET /big HTTP/1.1\r\nHost: localhost\r\n\r\n")
        .headers
        .get("content-encoding") shouldBe None
      request(server, "GET /big HTTP/1.1\r\nHost: localhost\r\nAccept-Encoding: gzip;q=0\r\n\r\n")
        .headers
        .get("content-encoding") shouldBe None
      request(server, "GET /small HTTP/1.1\r\nHost: localhost\r\nAccept-Encoding: gzip\r\n\r\n")
        .headers
        .get("content-encoding") shouldBe None
      val head = request(
        server,
        "HEAD /big HTTP/1.1\r\nHost: localhost\r\nAccept-Encoding: gzip\r\n\r\n",
        withBody = false
      )
      head.headers.get("content-encoding") shouldBe None
      head.headers("content-length") shouldBe "4096"
    }
  }

  test("drop a handler-set Transfer-Encoding next to the server's Content-Length") {
    withServer() { server =>
      val r = request(server, "GET /framed HTTP/1.1\r\nHost: localhost\r\n\r\n")
      r.headers.get("transfer-encoding") shouldBe None
      r.text shouldBe "body"
    }
  }

  test("reject smuggling-prone request heads") {
    withServer() { server =>
      Seq(
        "GET / HTTP/1.1\r\nHost: localhost\r\nContent-Length : 0\r\n\r\n",
        "GET / HTTP/1.1\r\nHost: localhost\r\nX-A: a\r\n b\r\n\r\n",
        "GET / HTTP/1.1\nHost: localhost\r\n\r\n",
        "POST /echo HTTP/1.1\r\nHost: localhost\r\nContent-Length: +1\r\n\r\nx",
        "POST /echo HTTP/1.1\r\nHost: localhost\r\nContent-Length: 1, 1\r\n\r\nx",
        "GET / HTTP/1.1\r\nHost: localhost\r\nHost: evil.example.com\r\n\r\n"
      ).foreach { raw =>
        request(server, raw).status shouldBe 400
      }
    }
  }

end SocketServerHardeningTest
