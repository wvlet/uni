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

import wvlet.uni.http.Response
import wvlet.uni.test.UniTest

import java.io.ByteArrayOutputStream
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPOutputStream

/**
  * Requests Netty's HTTP decoder fails on must be answered with an error and a closed connection,
  * never handed to the handler (HTTP Garden found these as request-smuggling risks).
  */
class NettyMalformedRequestTest extends UniTest:

  private val calls = AtomicInteger(0)

  private def server: NettyServerConfig = NettyServerConfig()
    .withPort(0)
    .withGracefulShutdown(quietPeriodSeconds = 0, timeoutSeconds = 5)
    .withMaxInitialLineLength(256)
    .withMaxHeaderSize(512)
    .withHandler { req =>
      calls.incrementAndGet()
      Response.ok(s"body=${req.content.toContentString}")
    }

  /** Send raw bytes and read everything until the server closes the connection. */
  private def exchange(port: Int, request: Array[Byte]): String =
    val socket = Socket("127.0.0.1", port)
    try
      socket.setSoTimeout(10000)
      socket.getOutputStream.write(request)
      socket.getOutputStream.flush()
      String(socket.getInputStream.readAllBytes(), StandardCharsets.ISO_8859_1)
    finally
      socket.close()

  private def ascii(s: String): Array[Byte] = s.getBytes(StandardCharsets.ISO_8859_1)

  // A response can start right after the previous body, with no CRLF in between
  private def statuses(response: String): Seq[String] =
    """HTTP/1\.1 (\d{3}) """.r.findAllMatchIn(response).map(_.group(1)).toSeq

  /** Expect exactly one response with `status`, a closed connection, and no handler call. */
  private def rejected(request: Array[Byte], status: Int): Unit = server.start { s =>
    calls.set(0)
    val response = exchange(s.localPort, request)
    statuses(response) shouldBe Seq(status.toString)
    response.toLowerCase shouldContain "connection: close"
    calls.get() shouldBe 0
  }

  test("reject a malformed request line") {
    rejected(ascii("G(T / HTTP/1.1\r\nHost: localhost\r\n\r\n"), 400)
  }

  test("reject a request line without a version") {
    rejected(ascii("GET /\r\nHost: localhost\r\n\r\n"), 400)
  }

  test("reject a malformed header") {
    rejected(ascii("GET / HTTP/1.1\r\nHost: localhost\r\nBad Header: x\r\n\r\n"), 400)
  }

  test("reject a request line that is too long") {
    rejected(ascii(s"GET /${"a" * 512} HTTP/1.1\r\nHost: localhost\r\n\r\n"), 414)
  }

  test("reject headers that are too large") {
    rejected(ascii(s"GET / HTTP/1.1\r\nHost: localhost\r\nX-Big: ${"a" * 1024}\r\n\r\n"), 431)
  }

  test("reject a truncated chunk instead of reading into the next request") {
    // A 5-byte chunk that holds only "hel": the decoder reads "helPO", then finds no CRLF
    rejected(
      ascii(
        "POST / HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nhel" +
          "POST / HTTP/1.1\r\nHost: localhost\r\nContent-Length: 0\r\n\r\n"
      ),
      400
    )
  }

  test("reject a bare LF after chunk data") {
    rejected(
      ascii(
        "POST / HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\n\r\n" +
          "5\r\nhello\n0\r\n\r\n"
      ),
      400
    )
  }

  test("reject a bad chunk in a gzip body") {
    // The request decompressor replaces a failed last chunk with a successful one, so the
    // failure must be caught before it
    val gz = ByteArrayOutputStream()
    val z  = GZIPOutputStream(gz)
    z.write(ascii("hello"))
    z.close()
    val chunk = gz.toByteArray
    val out   = ByteArrayOutputStream()
    out.write(
      ascii(
        "POST / HTTP/1.1\r\nHost: localhost\r\nContent-Encoding: gzip\r\n" +
          s"Transfer-Encoding: chunked\r\n\r\n${chunk.length.toHexString}\r\n"
      )
    )
    out.write(chunk)
    out.write(ascii("\r\nzz\r\n\r\n"))
    rejected(out.toByteArray, 400)
  }

  test("answer earlier pipelined requests before rejecting a malformed one") {
    server.start { s =>
      calls.set(0)
      val response = exchange(
        s.localPort,
        ascii("GET /a HTTP/1.1\r\nHost: localhost\r\n\r\nG(T / HTTP/1.1\r\nHost: localhost\r\n\r\n")
      )
      statuses(response) shouldBe Seq("200", "400")
      calls.get() shouldBe 1
    }
  }

  test("keep serving well-formed requests") {
    server.start { s =>
      val response = exchange(
        s.localPort,
        ascii(
          "POST / HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\n" +
            "Connection: close\r\n\r\n5\r\nhello\r\n0\r\n\r\n"
        )
      )
      statuses(response) shouldBe Seq("200")
      response shouldContain "body=hello"
    }
  }

end NettyMalformedRequestTest
