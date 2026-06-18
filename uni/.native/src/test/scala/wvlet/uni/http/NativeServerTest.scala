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

import java.nio.charset.StandardCharsets
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
    NativeServer
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

  test("should handle a POST request with a body") {
    NativeServer
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
    NativeServer
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
    NativeServer
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
    NativeServer
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

  test("should report a bound ephemeral port") {
    NativeServer
      .withPort(0)
      .start { server =>
        (server.localPort > 0) shouldBe true
        server.isRunning shouldBe true
      }
  }

end NativeServerTest
