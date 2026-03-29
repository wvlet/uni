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

import wvlet.uni.http.{HttpHandler, HttpMethod, Request, Response, ServerSentEvent}
import wvlet.uni.rx.Rx
import wvlet.uni.test.UniTest

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets

class NettyServerTest extends UniTest:

  private val httpClient = HttpClient.newHttpClient()

  private def get(url: String): HttpResponse[String] =
    val request = HttpRequest.newBuilder().uri(URI.create(url)).GET().build()
    httpClient.send(request, HttpResponse.BodyHandlers.ofString())

  private def post(url: String, body: String): HttpResponse[String] =
    val request = HttpRequest
      .newBuilder()
      .uri(URI.create(url))
      .POST(HttpRequest.BodyPublishers.ofString(body))
      .header("Content-Type", "text/plain")
      .build()
    httpClient.send(request, HttpResponse.BodyHandlers.ofString())

  test("should start and stop server") {
    val server = NettyServer.withPort(0).withHandler(_ => Response.ok("test")).start()
    try
      server.isRunning shouldBe true
      server.localPort > 0 shouldBe true
    finally
      server.stop()

    server.isRunning shouldBe false
  }

  test("should handle simple GET request") {
    NettyServer
      .withPort(0)
      .withHandler { request =>
        Response.ok(s"Hello from ${request.path}")
      }
      .start { server =>
        val response = get(s"http://localhost:${server.localPort}/test")
        response.statusCode() shouldBe 200
        response.body() shouldBe "Hello from /test"
      }
  }

  test("should handle POST request with body") {
    NettyServer
      .withPort(0)
      .withHandler { request =>
        val body = request.content.toContentString
        Response.ok(s"Received: ${body}")
      }
      .start { server =>
        val response = post(s"http://localhost:${server.localPort}/data", "test data")
        response.statusCode() shouldBe 200
        response.body() shouldBe "Received: test data"
      }
  }

  test("should handle Rx async handler") {
    NettyServer
      .withPort(0)
      .withRxHandler { request =>
        Rx.single(Response.ok(s"Async: ${request.path}"))
      }
      .start { server =>
        val response = get(s"http://localhost:${server.localPort}/async")
        response.statusCode() shouldBe 200
        response.body() shouldBe "Async: /async"
      }
  }

  test("should handle Rx delayed response") {
    NettyServer
      .withPort(0)
      .withRxHandler { request =>
        Rx.single(10)
          .map { _ =>
            Response.ok("delayed")
          }
      }
      .start { server =>
        val response = get(s"http://localhost:${server.localPort}/delay")
        response.statusCode() shouldBe 200
        response.body() shouldBe "delayed"
      }
  }

  test("should return 404 for not found handler") {
    NettyServer
      .withPort(0)
      .start { server =>
        val response = get(s"http://localhost:${server.localPort}/notfound")
        response.statusCode() shouldBe 404
      }
  }

  test("should apply RxHttpFilter") {
    val loggingFilter = RxHttpFilter { (request, next) =>
      next
        .handle(request.addHeader("X-Filtered", "true"))
        .map { response =>
          response.addHeader("X-Filter-Applied", "yes")
        }
    }

    NettyServer
      .withPort(0)
      .withFilter(loggingFilter)
      .withHandler { request =>
        val filtered = request.headers.get("X-Filtered").getOrElse("false")
        Response.ok(s"Filtered: ${filtered}")
      }
      .start { server =>
        val response = get(s"http://localhost:${server.localPort}/test")
        response.statusCode() shouldBe 200
        response.body() shouldBe "Filtered: true"
        response.headers().firstValue("X-Filter-Applied").orElse("no") shouldBe "yes"
      }
  }

  test("should handle sync HttpHandler") {
    val handler = HttpHandler { request =>
      Response.ok(s"Sync: ${request.path}")
    }

    NettyServer
      .withPort(0)
      .withHandler(handler)
      .start { server =>
        val response = get(s"http://localhost:${server.localPort}/sync")
        response.statusCode() shouldBe 200
        response.body() shouldBe "Sync: /sync"
      }
  }

  test("should handle JSON response") {
    NettyServer
      .withPort(0)
      .withHandler { _ =>
        Response.ok.withJsonContent("""{"status": "ok"}""")
      }
      .start { server =>
        val response = get(s"http://localhost:${server.localPort}/json")
        response.statusCode() shouldBe 200
        response.body() shouldBe """{"status": "ok"}"""
        response.headers().firstValue("Content-Type").orElse("") shouldContain "application/json"
      }
  }

  test("should handle different HTTP methods") {
    NettyServer
      .withPort(0)
      .withHandler { request =>
        Response.ok(s"Method: ${request.method}")
      }
      .start { server =>
        val getResponse = get(s"http://localhost:${server.localPort}/method")
        getResponse.body() shouldBe "Method: GET"

        val postResponse = post(s"http://localhost:${server.localPort}/method", "")
        postResponse.body() shouldBe "Method: POST"
      }
  }

  test("should handle error in handler") {
    NettyServer
      .withPort(0)
      .withRxHandler { _ =>
        Rx.exception(RuntimeException("Test error"))
      }
      .start { server =>
        val response = get(s"http://localhost:${server.localPort}/error")
        response.statusCode() shouldBe 500
        response.body() shouldContain "Test error"
      }
  }

  test("should configure server with builder methods") {
    val config =
      NettyServerConfig()
        .withName("test-server")
        .withHost("127.0.0.1")
        .withPort(9999)
        .withMaxContentLength(1024)
        .noNativeTransport

    config.name shouldBe "test-server"
    config.host shouldBe "127.0.0.1"
    config.port shouldBe 9999
    config.maxContentLength shouldBe 1024
    config.useNativeTransport shouldBe false
  }

  test("should throw when starting already running server") {
    val server = NettyServer.withPort(0).withHandler(_ => Response.ok).start()
    try intercept[IllegalStateException] {
        server.start()
      }
    finally server.stop()
  }

  test("should configure graceful shutdown") {
    val config = NettyServerConfig().withShutdownQuietPeriod(5).withShutdownTimeout(60)

    config.shutdownQuietPeriodSeconds shouldBe 5
    config.shutdownTimeoutSeconds shouldBe 60
  }

  test("should configure graceful shutdown with convenience method") {
    val config = NettyServerConfig().withGracefulShutdown(
      quietPeriodSeconds = 3,
      timeoutSeconds = 45
    )

    config.shutdownQuietPeriodSeconds shouldBe 3
    config.shutdownTimeoutSeconds shouldBe 45
  }

  test("should configure shutdown hook") {
    val config = NettyServerConfig().withShutdownHook
    config.registerShutdownHook shouldBe true

    val config2 = config.noShutdownHook
    config2.registerShutdownHook shouldBe false
  }

  test("should configure handler executor threads") {
    val config = NettyServerConfig().withHandlerExecutorThreads(32)
    config.handlerExecutorThreads shouldBe Some(32)
  }

  test("should reject invalid handler executor threads") {
    intercept[IllegalArgumentException] {
      NettyServerConfig().withHandlerExecutorThreads(0)
    }
    intercept[IllegalArgumentException] {
      NettyServerConfig().withHandlerExecutorThreads(-1)
    }
  }

  test("should start server with handler executor threads") {
    NettyServer
      .withPort(0)
      .withHandlerExecutorThreads(4)
      .withHandler { request =>
        Response.ok(s"Handler executor: ${request.path}")
      }
      .start { server =>
        val response = get(s"http://localhost:${server.localPort}/test")
        response.statusCode() shouldBe 200
        response.body() shouldBe "Handler executor: /test"
      }
  }

  test("should handle SSE response with isEventStream") {
    // Verify that Response.eventStream creates the correct response
    val events   = Rx.fromSeq(Seq(ServerSentEvent.data("test")))
    val response = Response.eventStream(events)
    response.isEventStream shouldBe true
    response.status shouldBe wvlet.uni.http.HttpStatus.Ok_200
  }

  test("should handle SSE streaming") {
    import java.net.Socket
    import java.io.{BufferedReader, InputStreamReader}

    val events = Seq(
      ServerSentEvent.data("hello"),
      ServerSentEvent.data("world"),
      ServerSentEvent.withEventType("done", "complete")
    )

    NettyServer
      .withPort(0)
      .withRxHandler { _ =>
        Rx.single(Response.eventStream(Rx.fromSeq(events)))
      }
      .start { server =>
        // Use raw socket to read the SSE response
        val socket = Socket("localhost", server.localPort)
        socket.setSoTimeout(5000) // 5 second timeout for reads
        try
          val out = socket.getOutputStream
          out.write(
            "GET /events HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes(StandardCharsets.UTF_8)
          )
          out.flush()

          // Read raw bytes to see exactly what the server sends
          val in  = socket.getInputStream
          val buf = new Array[Byte](8192)
          val sb  = StringBuilder()
          try
            var n = in.read(buf)
            while n > 0 do
              sb.append(String(buf, 0, n, StandardCharsets.UTF_8))
              n = in.read(buf)
          catch
            case _: java.net.SocketTimeoutException =>
              () // done reading

          val raw = sb.toString()
          // Check status line
          raw shouldContain "200"
          raw shouldContain "text/event-stream"
          raw shouldContain "data: hello"
          raw shouldContain "data: world"
          raw shouldContain "event: complete"
          raw shouldContain "data: done"
        finally
          socket.close()
        end try
      }
  }

  test("should detect benign I/O exceptions") {
    import java.io.IOException
    import java.nio.channels.ClosedChannelException

    // Connection reset
    NettyRequestHandler.isBenignIOException(IOException("Connection reset by peer")) shouldBe true
    NettyRequestHandler.isBenignIOException(IOException("Connection reset")) shouldBe true

    // Broken pipe
    NettyRequestHandler.isBenignIOException(IOException("Broken pipe")) shouldBe true

    // ClosedChannelException
    NettyRequestHandler.isBenignIOException(ClosedChannelException()) shouldBe true

    // Wrapped benign exception
    NettyRequestHandler.isBenignIOException(
      RuntimeException("wrapper", IOException("Connection reset"))
    ) shouldBe true

    // Non-benign exceptions
    NettyRequestHandler.isBenignIOException(IOException("Some other error")) shouldBe false
    NettyRequestHandler.isBenignIOException(RuntimeException("not IO")) shouldBe false
  }

end NettyServerTest
