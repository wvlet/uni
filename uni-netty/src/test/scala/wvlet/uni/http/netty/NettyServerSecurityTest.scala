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

import wvlet.uni.http.{
  HttpMultiMap,
  HttpStatus,
  Request,
  Response,
  ServerSentEvent,
  WebSocketHandler
}
import wvlet.uni.http.rpc.{RPCDispatcher, RPCRouter, RPCStatus}
import wvlet.uni.log.Logger
import wvlet.uni.rx.Rx
import wvlet.uni.test.UniTest

import java.io.{ByteArrayOutputStream, InputStream}
import java.net.{Socket, SocketTimeoutException}
import java.nio.charset.StandardCharsets

trait SecurityTestApi:
  def fail(message: String): String

class SecurityTestApiImpl extends SecurityTestApi:
  override def fail(message: String): String =
    throw IllegalStateException(s"connection to db.internal failed: ${message}")

class NettyServerSecurityTest extends UniTest:

  /** Send a raw HTTP request and return the response head (status line + headers). */
  private def rawRequest(port: Int, request: String): String =
    val socket = Socket("127.0.0.1", port)
    try
      socket.setSoTimeout(10000)
      socket.getOutputStream.write(request.getBytes(StandardCharsets.ISO_8859_1))
      socket.getOutputStream.flush()
      readHead(socket.getInputStream)
    finally
      socket.close()

  private def readHead(in: InputStream): String =
    val out  = ByteArrayOutputStream()
    var done = false
    while !done do
      val b = in.read()
      if b < 0 then
        done = true
      else
        out.write(b)
        done = out.toString(StandardCharsets.ISO_8859_1).endsWith("\r\n\r\n")
    out.toString(StandardCharsets.ISO_8859_1)

  private def upgradeRequest(port: Int, origin: Option[String]): String =
    val originHeader = origin.map(o => s"Origin: ${o}\r\n").getOrElse("")
    s"GET /ws HTTP/1.1\r\nHost: 127.0.0.1:${port}\r\nConnection: Upgrade\r\nUpgrade: websocket\r\n" +
      s"Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n" +
      s"${originHeader}\r\n"

  private def wsServer: NettyServerConfig =
    NettyServer
      .withPort(0)
      .withGracefulShutdown(quietPeriodSeconds = 0, timeoutSeconds = 5)
      .withWebSocketRoute("/ws") { _ =>
        new WebSocketHandler {}
      }

  test("bind to loopback by default") {
    NettyServerConfig().host shouldBe "127.0.0.1"
    NettyServer
      .withPort(0)
      .withGracefulShutdown(quietPeriodSeconds = 0, timeoutSeconds = 5)
      .withHandler(_ => Response.ok)
      .start { server =>
        server.localAddress shouldContain "127.0.0.1"
      }
  }

  test("hide exception messages in 500 responses by default") {
    Logger
      .of[NettyRequestHandler]
      .suppressWarnings {
        NettyServer
          .withPort(0)
          .withGracefulShutdown(quietPeriodSeconds = 0, timeoutSeconds = 5)
          .withHandler(_ => throw IllegalStateException("password=hunter2"))
          .start { server =>
            val head = rawRequest(server.localPort, "GET / HTTP/1.1\r\nHost: localhost\r\n\r\n")
            head shouldContain " 500 "
          }
      }
  }

  test("show exception messages when detailed errors are enabled") {
    Logger
      .of[NettyRequestHandler]
      .suppressWarnings {
        NettyServer
          .withPort(0)
          .withGracefulShutdown(quietPeriodSeconds = 0, timeoutSeconds = 5)
          .withDetailedErrors(true)
          .withRxHandler(_ => Rx.exception(IllegalStateException("visible detail")))
          .start { server =>
            val client   = java.net.http.HttpClient.newHttpClient()
            val response = client.send(
              java
                .net
                .http
                .HttpRequest
                .newBuilder()
                .uri(java.net.URI.create(s"http://127.0.0.1:${server.localPort}/"))
                .build(),
              java.net.http.HttpResponse.BodyHandlers.ofString()
            )
            response.statusCode() shouldBe 500
            response.body() shouldBe "visible detail"
          }
      }
  }

  test("hide unexpected RPC exception messages by default") {
    Logger
      .of[RPCDispatcher]
      .suppressWarnings {
        val router = RPCRouter.of[SecurityTestApi](SecurityTestApiImpl())
        val rpc    = RPCHandler(router)
        val req    = Request
          .post(router.routes.head.path)
          .withJsonContent("""{"request":{"message":"x"}}""")
        val response = rpc.handle(req).toSeq.head
        response.status shouldBe HttpStatus.InternalServerError_500
        response.contentAsString.get shouldContain RPCStatus.INTERNAL_ERROR_I0.name
        response.contentAsString.get.contains("db.internal") shouldBe false
        response.contentAsString.get.contains("stackTrace") shouldBe false

        val detailed = RPCHandler(router).withDetailedErrors(true).handle(req).toSeq.head
        detailed.contentAsString.get shouldContain "db.internal"
      }
  }

  test("reject a response header containing CR/LF") {
    Logger
      .of[NettyRequestHandler]
      .suppressWarnings {
        NettyServer
          .withPort(0)
          .withGracefulShutdown(quietPeriodSeconds = 0, timeoutSeconds = 5)
          .withHandler { _ =>
            // Bypass Response.addHeader's validation, as a hand-built header map would
            Response
              .ok
              .withHeaders(HttpMultiMap.empty.add("X-Echo", "a\r\nSet-Cookie: session=evil"))
          }
          .start { server =>
            val head = rawRequest(server.localPort, "GET / HTTP/1.1\r\nHost: localhost\r\n\r\n")
            head shouldContain " 500 "
            head.contains("Set-Cookie") shouldBe false
          }
      }
  }

  test("accept WebSocket upgrades without Origin or from the same origin") {
    wsServer.start { server =>
      val port = server.localPort
      rawRequest(port, upgradeRequest(port, None)) shouldContain " 101 "
      rawRequest(port, upgradeRequest(port, Some(s"http://127.0.0.1:${port}"))) shouldContain
        " 101 "
    }
  }

  test("reject cross-origin WebSocket upgrades") {
    wsServer.start { server =>
      val port = server.localPort
      rawRequest(port, upgradeRequest(port, Some("https://evil.example"))) shouldContain " 403 "
    }
  }

  test("accept WebSocket upgrades from allowed origins") {
    wsServer
      .withAllowedOrigins("http://localhost:5173")
      .start { server =>
        val port = server.localPort
        rawRequest(port, upgradeRequest(port, Some("http://localhost:5173"))) shouldContain " 101 "
        rawRequest(port, upgradeRequest(port, Some("https://evil.example"))) shouldContain " 403 "
      }
  }

  test("close a connection that does not finish its request in time") {
    NettyServer
      .withPort(0)
      .withGracefulShutdown(quietPeriodSeconds = 0, timeoutSeconds = 5)
      .withRequestReadTimeoutMillis(200)
      .withHandler(_ => Response.ok("ok"))
      .start { server =>
        val socket = Socket("127.0.0.1", server.localPort)
        try
          socket.setSoTimeout(5000)
          // Send a partial request line and stall, as a slowloris client would
          socket.getOutputStream.write("GET / HTTP/1.1\r\nHo".getBytes(StandardCharsets.US_ASCII))
          socket.getOutputStream.flush()
          val start  = System.currentTimeMillis()
          val result =
            try
              socket.getInputStream.read()
            catch
              case _: SocketTimeoutException =>
                -2
          result shouldBe -1
          (System.currentTimeMillis() - start < 4000) shouldBe true
        finally
          socket.close()
      }
  }

  test("still time out an idle connection after an early 413") {
    NettyServer
      .withPort(0)
      .withGracefulShutdown(quietPeriodSeconds = 0, timeoutSeconds = 5)
      .withMaxContentLength(16)
      .withRequestReadTimeoutMillis(300)
      .withHandler(_ => Response.ok("ok"))
      .start { server =>
        val socket = Socket("127.0.0.1", server.localPort)
        try
          socket.setSoTimeout(5000)
          val out = socket.getOutputStream
          out.write(
            "POST / HTTP/1.1\r\nHost: localhost\r\nContent-Length: 100\r\n\r\n".getBytes(
              StandardCharsets.US_ASCII
            )
          )
          out.flush()
          readHead(socket.getInputStream) shouldContain " 413 "
          // Finish the rejected body, then stay idle: the server must still close the connection
          out.write(("x" * 100).getBytes(StandardCharsets.US_ASCII))
          out.flush()
          val in = socket.getInputStream
          var b  = 0
          while b >= 0 do
            b = in.read()
          b shouldBe -1
        finally
          socket.close()
      }
  }

  test("keep serving complete requests on a kept-alive connection") {
    NettyServer
      .withPort(0)
      .withGracefulShutdown(quietPeriodSeconds = 0, timeoutSeconds = 5)
      .withRequestReadTimeoutMillis(500)
      .withHandler { _ =>
        // A handler slower than the read timeout must not be cut off
        Thread.sleep(800)
        Response.ok("ok")
      }
      .start { server =>
        val socket = Socket("127.0.0.1", server.localPort)
        try
          socket.setSoTimeout(5000)
          val req = "GET / HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes(StandardCharsets.US_ASCII)
          socket.getOutputStream.write(req)
          readHead(socket.getInputStream) shouldContain " 200 "
        finally
          socket.close()
      }
  }

  test("reject requests for a foreign Host on a loopback server") {
    NettyServer
      .withPort(0)
      .withGracefulShutdown(quietPeriodSeconds = 0, timeoutSeconds = 5)
      .withHandler(_ => Response.ok)
      .start { server =>
        val port = server.localPort
        rawRequest(port, s"GET / HTTP/1.1\r\nHost: localhost:${port}\r\n\r\n") shouldContain " 200 "
        rawRequest(port, s"GET / HTTP/1.1\r\nHost: evil.example:${port}\r\n\r\n") shouldContain
          " 403 "
        // The WebSocket upgrade path is covered too: a rebinding page is "same-origin"
        val rebinding = upgradeRequest(port, Some(s"http://evil.example:${port}")).replace(
          s"Host: 127.0.0.1:${port}",
          s"Host: evil.example:${port}"
        )
        rawRequest(port, rebinding) shouldContain " 403 "
      }
  }

  test("accept configured hosts, or every host with noHostCheck") {
    NettyServer
      .withPort(0)
      .withGracefulShutdown(quietPeriodSeconds = 0, timeoutSeconds = 5)
      .withHandler(_ => Response.ok)
      .withAllowedHosts("api.example.com")
      .start { server =>
        val port = server.localPort
        rawRequest(port, "GET / HTTP/1.1\r\nHost: api.example.com\r\n\r\n") shouldContain " 200 "
        rawRequest(port, "GET / HTTP/1.1\r\nHost: localhost\r\n\r\n") shouldContain " 403 "
      }
    NettyServer
      .withPort(0)
      .withGracefulShutdown(quietPeriodSeconds = 0, timeoutSeconds = 5)
      .withHandler(_ => Response.ok)
      .noHostCheck
      .start { server =>
        rawRequest(server.localPort, "GET / HTTP/1.1\r\nHost: evil.example\r\n\r\n") shouldContain
          " 200 "
      }
  }

  test("reject SSE streams over the limit with 503") {
    Logger
      .of[NettyRequestHandler]
      .suppressWarnings {
        NettyServer
          .withPort(0)
          .withGracefulShutdown(quietPeriodSeconds = 0, timeoutSeconds = 5)
          .withHandler(_ => Response.eventStream(Rx.variable(ServerSentEvent.data("hello"))))
          .withMaxStreamingConnections(1)
          .start { server =>
            val port   = server.localPort
            val first  = Socket("127.0.0.1", port)
            val stream = s"GET /events HTTP/1.1\r\nHost: localhost:${port}\r\n\r\n"
            try
              first.setSoTimeout(10000)
              first.getOutputStream.write(stream.getBytes(StandardCharsets.ISO_8859_1))
              first.getOutputStream.flush()
              readHead(first.getInputStream) shouldContain " 200 "
              rawRequest(port, stream) shouldContain " 503 "
            finally
              first.close()
            // Closing the first stream frees its slot
            var head  = ""
            var tries = 0
            while !head.contains(" 200 ") && tries < 50 do
              Thread.sleep(100)
              tries += 1
              val socket = Socket("127.0.0.1", port)
              try
                socket.setSoTimeout(10000)
                socket.getOutputStream.write(stream.getBytes(StandardCharsets.ISO_8859_1))
                socket.getOutputStream.flush()
                head = readHead(socket.getInputStream)
              finally
                socket.close()
            head shouldContain " 200 "
          }
      }
  }

end NettyServerSecurityTest
