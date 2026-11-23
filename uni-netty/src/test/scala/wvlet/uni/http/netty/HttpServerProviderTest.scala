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

import wvlet.uni.http.{Http, HttpServer, Response}
import wvlet.uni.test.UniTest

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}

class HttpServerProviderTest extends UniTest:

  test("Http.server starts Netty, found through ServiceLoader") {
    val server = Http
      .server
      .withPort(0)
      .withName("portable")
      .withHandler(_ => Response.ok("hello"))
      .start()
    try
      server shouldMatch { case _: NettyHttpServer =>
      }
      val response = HttpClient
        .newHttpClient()
        .send(
          HttpRequest
            .newBuilder(URI.create(s"http://127.0.0.1:${server.localPort}/"))
            .GET()
            .build(),
          HttpResponse.BodyHandlers.ofString()
        )
      response.statusCode() shouldBe 200
      response.body() shouldBe "hello"
    finally
      server.stop()
  }

  private def postStatus(server: HttpServer, body: String): Int = HttpClient
    .newHttpClient()
    .send(
      HttpRequest
        .newBuilder(URI.create(s"http://127.0.0.1:${server.localPort}/"))
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build(),
      HttpResponse.BodyHandlers.ofString()
    )
    .statusCode()

  test("Http.server maps maxContentLength to Netty") {
    Http
      .server
      .withPort(0)
      .withMaxContentLength(16)
      .withHandler(_ => Response.ok("ok"))
      .start { server =>
        postStatus(server, "small") shouldBe 200
        postStatus(server, "x" * 100) shouldBe 413
      }
  }

  test("withBackend(Netty.options...) applies Netty-only options") {
    Http
      .server
      .withPort(0)
      .withMaxContentLength(16)
      .noRequestDecompression
      .withBackend(Netty.options.withHandlerExecutorThreads(2))
      .withHandler(_ => Response.ok("ok"))
      .start { server =>
        postStatus(server, "small") shouldBe 200
        postStatus(server, "x" * 100) shouldBe 413
      }
  }

  test("Netty options check their values when set") {
    intercept[IllegalArgumentException] {
      Netty.options.withHandlerExecutorThreads(0)
    }
  }

  test("Netty options are applied in order") {
    val config = NettyOptions
      .empty
      .withHandlerExecutorThreads(2)
      .withHandlerExecutorThreads(4)
      .applyTo(NettyServerConfig())
    config.handlerExecutorThreads shouldBe Some(4)
  }

  test("Http.server's shared limits reach the Netty config and win over Netty.options") {
    val config = NettyServerProvider.nettyConfig(
      Http
        .server
        .withMaxInitialLineLength(1024)
        .withMaxHeaderSize(2048)
        .withRequestReadTimeoutMillis(5000)
        .withWriteTimeoutMillis(6000)
        .withIdleTimeoutMillis(7000)
        .noRequestDecompression
        .withMaxDecompressedRequestSize(4096)
        .withMaxStreamingConnections(8)
        .withShutdownTimeoutMillis(2500)
        .withBackend(Netty.options.withShutdownQuietPeriod(0).withHandlerExecutorThreads(2))
    )
    config.maxInitialLineLength shouldBe 1024
    config.maxHeaderSize shouldBe 2048
    config.requestReadTimeoutMillis shouldBe 5000
    config.writeTimeoutMillis shouldBe 6000
    config.idleTimeoutMillis shouldBe Some(7000)
    config.requestDecompression shouldBe false
    config.maxDecompressedRequestSize shouldBe 4096
    config.maxStreamingConnections shouldBe 8
    // Rounded up to whole seconds
    config.shutdownTimeoutSeconds shouldBe 3
    config.shutdownQuietPeriodSeconds shouldBe 0
    config.handlerExecutorThreads shouldBe Some(2)
  }

  test("unset shared limits keep Netty's defaults") {
    val config = NettyServerProvider.nettyConfig(Http.server)
    val netty  = NettyServerConfig()
    config.requestReadTimeoutMillis shouldBe netty.requestReadTimeoutMillis
    config.writeTimeoutMillis shouldBe 0
    config.idleTimeoutMillis shouldBe None
    config.shutdownTimeoutSeconds shouldBe netty.shutdownTimeoutSeconds
    NettyServerProvider
      .nettyConfig(Http.server.withShutdownTimeoutMillis(0))
      .shutdownTimeoutSeconds shouldBe 1
  }

  test("hand Netty's buffered body to a streaming request as a stream, not decompressed") {
    Http
      .server
      .withPort(0)
      .withStreamingRequests(_.path == "/upload")
      .withHandler { (req: wvlet.uni.http.Request) =>
        req.content match
          case s: wvlet.uni.http.HttpContent.StreamContent =>
            val bytes = s.openStream.readAllBytes()
            Response.ok(s"stream ${bytes.length}")
          case other =>
            Response.ok(s"buffered ${other.length}")
      }
      .start { server =>
        def postGzip(path: String): String =
          val out = java.io.ByteArrayOutputStream()
          val gz  = java.util.zip.GZIPOutputStream(out)
          gz.write(("z" * 5000).getBytes)
          gz.close()
          HttpClient
            .newHttpClient()
            .send(
              HttpRequest
                .newBuilder(URI.create(s"http://127.0.0.1:${server.localPort}${path}"))
                .header("Content-Encoding", "gzip")
                .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray))
                .build(),
              HttpResponse.BodyHandlers.ofString()
            )
            .body()
        // The stream carries the gzip bytes as sent; the buffered path decodes them
        (postGzip("/upload").startsWith("stream ")) shouldBe true
        (postGzip("/upload") != "stream 5000") shouldBe true
        postGzip("/other") shouldBe "buffered 5000"
      }
  }

end HttpServerProviderTest
