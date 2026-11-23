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
import wvlet.uni.rx.Rx
import wvlet.uni.test.UniTest

import java.io.ByteArrayOutputStream
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
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

  test("answer an earlier async response before rejecting a malformed one") {
    NettyServerConfig()
      .withPort(0)
      .withGracefulShutdown(quietPeriodSeconds = 0, timeoutSeconds = 5)
      // The response is ready only after the malformed request has been read
      .withRxHandler(_ => Rx.delay(300, TimeUnit.MILLISECONDS).map(_ => Response.ok("late")))
      .start { s =>
        val response = exchange(
          s.localPort,
          ascii(
            "GET /a HTTP/1.1\r\nHost: localhost\r\n\r\nG(T / HTTP/1.1\r\nHost: localhost\r\n\r\n"
          )
        )
        statuses(response) shouldBe Seq("200", "400")
        response shouldContain "late"
      }
  }

  test("answer a lowercase method with 501, not as the uppercase one") {
    server.start { s =>
      calls.set(0)
      val response = exchange(
        s.localPort,
        ascii("get / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
      )
      statuses(response) shouldBe Seq("501")
      calls.get() shouldBe 0
    }
  }

  test("skip 100 Continue for a request without a body") {
    server.start { s =>
      val response = exchange(
        s.localPort,
        ascii(
          "POST / HTTP/1.1\r\nHost: localhost\r\nExpect: 100-continue\r\nConnection: close\r\n\r\n"
        )
      )
      statuses(response) shouldBe Seq("200")
    }
  }

  test("still send 100 Continue for a request with a body") {
    server.start { s =>
      val response = exchange(
        s.localPort,
        ascii(
          "POST / HTTP/1.1\r\nHost: localhost\r\nExpect: 100-continue\r\nContent-Length: 5\r\n" +
            "Connection: close\r\n\r\nhello"
        )
      )
      statuses(response) shouldBe Seq("100", "200")
      response shouldContain "body=hello"
    }
  }

  test("reject an HTTP version other than 1.0 and 1.1 with 505") {
    rejected(ascii("GET / HTTP/2.0\r\nHost: localhost\r\n\r\n"), 505)
  }

  test("check the version before the method") {
    rejected(ascii("BREW / HTTP/2.0\r\nHost: localhost\r\n\r\n"), 505)
  }

  test("reject a protocol other than HTTP with 400") {
    rejected(ascii("GET / FOO/1.1\r\nHost: localhost\r\n\r\n"), 400)
  }

  test("answer earlier pipelined requests before rejecting an unsupported version") {
    server.start { s =>
      calls.set(0)
      val response = exchange(
        s.localPort,
        ascii(
          "GET /a HTTP/1.1\r\nHost: localhost\r\n\r\nGET /b HTTP/2.0\r\nHost: localhost\r\n\r\n"
        )
      )
      statuses(response) shouldBe Seq("200", "505")
      calls.get() shouldBe 1
    }
  }

  test("still serve HTTP/1.0") {
    server.start { s =>
      val response = exchange(s.localPort, ascii("GET / HTTP/1.0\r\nHost: localhost\r\n\r\n"))
      statuses(response) shouldBe Seq("200")
    }
  }

  // The built-in server gives the same statuses (HttpReaderSpecCases)
  test("reject a Content-Length that doesn't fit in a Long with 400") {
    rejected(
      ascii("POST / HTTP/1.1\r\nHost: localhost\r\nContent-Length: 99999999999999999999\r\n\r\n"),
      400
    )
  }

  test("reject a chunk size that doesn't fit in an Int with 400") {
    rejected(
      ascii(
        "POST / HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\n\r\n" +
          "ffffffffffffffffffff\r\n"
      ),
      400
    )
  }

  test("reject a request line whose words aren't separated by one SP") {
    // Netty's decoder splits on any run of SP, HTAB, VT, FF, or CR; RFC 9112 allows one SP
    val lines = Seq(
      "GET  / HTTP/1.1",
      "GET /  HTTP/1.1",
      "GET\t/ HTTP/1.1",
      "GET /\tHTTP/1.1",
      "GET\u000b/ HTTP/1.1",
      "GET /\u000cHTTP/1.1",
      "GET\r / HTTP/1.1",
      "GET / \rHTTP/1.1",
      " GET / HTTP/1.1",
      "\tGET / HTTP/1.1"
    )
    lines.foreach { line =>
      rejected(ascii(s"${line}\r\nHost: localhost\r\n\r\n"), 400)
    }
  }

  test("reject HTTP Garden's request line with a bare CR after the method") {
    rejected(
      ascii(
        "GET\r /1 HTTP/1.1\r\nHost: localhos8\r\n\r\nBAD\r\n\r\n" +
          "GET /never-read HTTP/1.1\r\nHost: loc\ralhost\r\n\r\n"
      ),
      400
    )
  }

  test("reject a bare CR before the request line") {
    // Netty skips any control bytes there; RFC 9112 Section 2.2 allows only empty lines
    Seq("\r", "\r\r\n", "\r\n\r", "\n\r").foreach { prefix =>
      rejected(ascii(s"${prefix}GET / HTTP/1.1\r\nHost: localhost\r\n\r\n"), 400)
    }
  }

  test("skip empty lines before the request line, and reject a bare CR between requests") {
    server.start { s =>
      calls.set(0)
      val response = exchange(
        s.localPort,
        ascii(
          "\r\n\nGET /a HTTP/1.1\r\nHost: localhost\r\n\r\n\r\nGET /b HTTP/1.1\r\n" +
            "Host: localhost\r\n\r\n\rGET /c HTTP/1.1\r\nHost: localhost\r\n\r\n"
        )
      )
      statuses(response) shouldBe Seq("200", "200", "400")
      calls.get() shouldBe 2
    }
  }

  test("answer an earlier pipelined request with a body before rejecting a bad separator") {
    server.start { s =>
      calls.set(0)
      val response = exchange(
        s.localPort,
        ascii(
          "POST /a HTTP/1.1\r\nHost: localhost\r\nContent-Length: 5\r\n\r\nhello" +
            "GET\t/b HTTP/1.1\r\nHost: localhost\r\n\r\n"
        )
      )
      statuses(response) shouldBe Seq("200", "400")
      response shouldContain "body=hello"
      calls.get() shouldBe 1
    }
  }

  test("send no body in a response to HEAD, and keep reading pipelined requests") {
    server.start { s =>
      val response = exchange(
        s.localPort,
        ascii(
          "HEAD / HTTP/1.1\r\nHost: localhost\r\n\r\n" +
            "POST / HTTP/1.1\r\nHost: localhost\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok"
        )
      )
      statuses(response) shouldBe Seq("200", "200")
      // Only the POST's body: the HEAD response announces a length but sends nothing
      "body=".r.findAllMatchIn(response).size shouldBe 1
      response shouldContain "body=ok"
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
