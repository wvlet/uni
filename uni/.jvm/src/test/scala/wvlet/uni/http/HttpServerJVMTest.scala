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

import java.io.{ByteArrayOutputStream, InputStream}
import java.net.{Socket, URI}
import java.net.http.{HttpClient as JHttpClient, WebSocket as JWebSocket}
import java.nio.charset.StandardCharsets
import java.util.concurrent.{CompletableFuture, CompletionStage, TimeUnit}

/**
  * The JVM's built-in server, which `Http.server` starts when no backend (uni-netty) is on the
  * classpath. Requests are raw bytes over a socket, so the tests see exactly what goes on the wire.
  */
class HttpServerJVMTest extends UniTest:

  private def withServer[A](config: HttpServerConfig)(body: HttpServer => A): A =
    val server = config.withPort(0).start()
    try
      server shouldMatch { case _: JvmHttpServer =>
      }
      body(server)
    finally
      server.stop()

  private val echo = Http
    .server
    .withHandler { (req: Request) =>
      req.path match
        case "/hello" =>
          Response.ok.withTextContent("hello")
        case "/echo" =>
          Response.ok.withTextContent(req.content.toContentString)
        case "/header" =>
          Response.ok.withTextContent(req.header("X-Test").getOrElse("none"))
        case _ =>
          Response.notFound
    }

  private def connect(server: HttpServer): Socket =
    val s = Socket("127.0.0.1", server.localPort)
    s.setSoTimeout(10000)
    s

  private def send(s: Socket, raw: String): Unit =
    s.getOutputStream.write(raw.getBytes(StandardCharsets.ISO_8859_1))
    s.getOutputStream.flush()

  /** Read one response whose body is delimited by Content-Length. */
  private def readResponse(in: InputStream): (Int, Map[String, String], String) =
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
    val len  = headers.get("content-length").map(_.toInt).getOrElse(0)
    val body = in.readNBytes(len)
    (status, headers, String(body, StandardCharsets.UTF_8))

  private def request(server: HttpServer, raw: String): (Int, Map[String, String], String) =
    val s = connect(server)
    try
      send(s, raw)
      readResponse(s.getInputStream)
    finally
      s.close()

  test("Http.server starts the built-in server when no backend is on the classpath") {
    withServer(echo) { server =>
      server.isRunning shouldBe true
      (server.localPort > 0) shouldBe true
      request(server, "GET /hello HTTP/1.1\r\nHost: localhost\r\n\r\n") shouldMatch {
        case (200, _, "hello") =>
      }
    }
  }

  test("read a POST body and request headers") {
    withServer(echo) { server =>
      val body = "こんにちは"
      val n    = body.getBytes(StandardCharsets.UTF_8).length
      val s    = connect(server)
      try
        s.getOutputStream
          .write(
            s"POST /echo HTTP/1.1\r\nHost: localhost\r\nContent-Length: ${n}\r\n\r\n".getBytes(
              StandardCharsets.ISO_8859_1
            ) ++ body.getBytes(StandardCharsets.UTF_8)
          )
        readResponse(s.getInputStream)._3 shouldBe body
      finally
        s.close()
      request(server, "GET /header HTTP/1.1\r\nHost: localhost\r\nX-Test:  value \r\n\r\n")
        ._3 shouldBe "value"
    }
  }

  test("serve keep-alive and pipelined requests on one connection") {
    withServer(echo) { server =>
      val s = connect(server)
      try
        val in = s.getInputStream
        send(s, "GET /hello HTTP/1.1\r\nHost: localhost\r\n\r\n")
        readResponse(in)._3 shouldBe "hello"
        // Two requests in one write, the second split from its body
        send(
          s,
          "GET /hello HTTP/1.1\r\nHost: localhost\r\n\r\nPOST /echo HTTP/1.1\r\nHost: localhost\r\nContent-Length: 3\r\n\r\nab"
        )
        readResponse(in)._3 shouldBe "hello"
        send(s, "c")
        readResponse(in)._3 shouldBe "abc"
      finally
        s.close()
    }
  }

  test("parse a request that arrives one byte at a time") {
    withServer(echo) { server =>
      val s = connect(server)
      try
        "\r\nGET /header HTTP/1.1\r\nHost: localhost\r\nX-Test: slow\r\n\r\n".foreach { c =>
          send(s, c.toString)
        }
        readResponse(s.getInputStream)._3 shouldBe "slow"
      finally
        s.close()
    }
  }

  test("answer HEAD with headers only, and 404 and 400 for unknown paths and methods") {
    withServer(echo) { server =>
      val (status, headers, body) = request(
        server,
        "HEAD /hello HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
      )
      status shouldBe 200
      headers.get("content-length") shouldBe Some("5")
      body shouldBe ""
      request(server, "GET /missing HTTP/1.1\r\nHost: localhost\r\n\r\n")._1 shouldBe 404
      request(server, "BREW /hello HTTP/1.1\r\nHost: localhost\r\n\r\n")._1 shouldBe 400
      request(server, "GET\r\nHost: localhost\r\n\r\n")._1 shouldBe 400
      request(
        server,
        "POST /echo HTTP/1.1\r\nHost: localhost\r\nContent-Length: 1\r\nContent-Length: 2\r\n\r\nab"
      )._1 shouldBe 400
    }
  }

  test("reject a foreign Host on a loopback server with 403") {
    withServer(echo) { server =>
      request(server, "GET /hello HTTP/1.1\r\nHost: evil.example.com\r\n\r\n")._1 shouldBe 403
    }
  }

  test("stream Server-Sent Events") {
    val sse = Http
      .server
      .withHandler { (_: Request) =>
        Response.eventStream(Rx.fromSeq(Seq(ServerSentEvent.data("a"), ServerSentEvent.data("b"))))
      }
    withServer(sse) { server =>
      val s = connect(server)
      try
        send(s, "GET /events HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
        val text = String(s.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
        text shouldContain "text/event-stream"
        text shouldContain "data: a"
        text shouldContain "data: b"
        text.endsWith("0\r\n\r\n") shouldBe true
      finally
        s.close()
    }
  }

  test("echo WebSocket messages") {
    val ws =
      Http
        .server
        .withWebSocketRoute("/ws") { _ =>
          new WebSocketHandler:
            override def onTextMessage(ctx: WebSocketContext, message: String): Unit = ctx.send(
              s"echo: ${message}"
            )
        }
    withServer(ws) { server =>
      val received = CompletableFuture[String]()
      val listener =
        new JWebSocket.Listener:
          override def onText(
              webSocket: JWebSocket,
              data: CharSequence,
              last: Boolean
          ): CompletionStage[?] =
            received.complete(data.toString)
            null
      val client = JHttpClient.newHttpClient()
      try
        val socket = client
          .newWebSocketBuilder()
          .buildAsync(URI.create(s"ws://localhost:${server.localPort}/ws"), listener)
          .get(10, TimeUnit.SECONDS)
        socket.sendText("hi", true)
        received.get(10, TimeUnit.SECONDS) shouldBe "echo: hi"
        socket.abort()
      finally
        client.close()
    }
  }

  test("stop closes an idle keep-alive connection without waiting for its timeout") {
    val server = echo.withPort(0).start()
    val s      = connect(server)
    try
      send(s, "GET /hello HTTP/1.1\r\nHost: localhost\r\n\r\n")
      readResponse(s.getInputStream)._3 shouldBe "hello"
      val start = System.nanoTime()
      server.stop()
      val elapsedMillis = (System.nanoTime() - start) / 1000000
      (elapsedMillis < 3000) shouldBe true
      s.getInputStream.read() shouldBe -1
    finally
      s.close()
  }

end HttpServerJVMTest
