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

import wvlet.uni.http.{HttpContent, Response, StaticFiles}
import wvlet.uni.io.{FileSystem, IOPath}
import wvlet.uni.test.UniTest

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}

/** File bodies streamed by the Netty backend: full, ranged, and compressed. */
class NettyStaticFilesTest extends UniTest:

  private val httpClient = HttpClient.newHttpClient()

  // Larger than one chunk, so the body is streamed in several writes
  private val data: Array[Byte] = Array.tabulate(3 * 1024 * 1024 + 17)(i => (i * 31 % 251).toByte)

  private lazy val site: IOPath =
    val dir = FileSystem.createTempDirectory("netty-static")
    FileSystem.writeBytes(dir / "big.bin", data)
    FileSystem.writeString(dir / "app.js", "console.log(1)" * 100)
    dir

  private def get(port: Int, path: String, headers: (String, String)*): HttpResponse[Array[Byte]] =
    val builder = HttpRequest.newBuilder().uri(URI.create(s"http://127.0.0.1:${port}${path}")).GET()
    headers.foreach((k, v) => builder.header(k, v))
    httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray())

  private def withServer(body: Int => Unit): Unit = NettyServerConfig()
    .withPort(0)
    .withGracefulShutdown(quietPeriodSeconds = 0, timeoutSeconds = 5)
    .withFilter(StaticFiles(site.path))
    .withHandler(_ => Response.notFound)
    .start(server => body(server.localPort))

  test("stream a whole file") {
    withServer { port =>
      val r = get(port, "/big.bin")
      r.statusCode() shouldBe 200
      r.headers().firstValue("Content-Length").orElse("") shouldBe data.length.toString
      r.headers().firstValue("Accept-Ranges").orElse("") shouldBe "bytes"
      r.body().sameElements(data) shouldBe true
    }
  }

  test("stream a range uncompressed even when the client accepts gzip") {
    withServer { port =>
      val r = get(port, "/big.bin", "Range" -> "bytes=2000000-2000099", "Accept-Encoding" -> "gzip")
      r.statusCode() shouldBe 206
      r.headers().firstValue("Content-Encoding").isPresent shouldBe false
      r.headers().firstValue("Content-Range").orElse("") shouldBe
        s"bytes 2000000-2000099/${data.length}"
      r.body().sameElements(data.slice(2000000, 2000100)) shouldBe true
    }
  }

  test("still compress a whole streamed file") {
    withServer { port =>
      val r = get(port, "/app.js", "Accept-Encoding" -> "gzip")
      r.statusCode() shouldBe 200
      r.headers().firstValue("Content-Encoding").orElse("") shouldBe "gzip"
      // A gzip body is another representation, so its ETag must not satisfy If-Range
      val etag = r.headers().firstValue("ETag").orElse("")
      etag shouldMatch { case s"W/\"${_}\"" =>
      }
      val resumed = get(port, "/app.js", "Range" -> "bytes=5-", "If-Range" -> etag)
      resumed.statusCode() shouldBe 200
      val unzipped = java
        .util
        .zip
        .GZIPInputStream(java.io.ByteArrayInputStream(r.body()))
        .readAllBytes()
      String(unzipped, "UTF-8") shouldBe "console.log(1)" * 100
    }
  }

  test("keep the connection usable after a streamed response") {
    withServer { port =>
      for _ <- 1 to 3 do
        get(port, "/big.bin", "Range" -> "bytes=0-9").body().sameElements(data.take(10)) shouldBe
          true
    }
  }

  test("answer 500 when a FileContent can't be opened") {
    NettyServerConfig()
      .withPort(0)
      .withGracefulShutdown(quietPeriodSeconds = 0, timeoutSeconds = 5)
      .withHandler(_ => Response.ok(HttpContent.FileContent(site / "missing.bin", 0, 10)))
      .start { server =>
        get(server.localPort, "/").statusCode() shouldBe 500
      }
  }

end NettyStaticFilesTest
