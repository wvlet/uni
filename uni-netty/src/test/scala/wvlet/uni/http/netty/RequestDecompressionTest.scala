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

import wvlet.uni.http.{HttpMethod, Request, Response, RxHttpFilter}
import wvlet.uni.http.router.{Endpoint, Router}
import wvlet.uni.rx.Rx
import wvlet.uni.test.UniTest

import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.{Deflater, DeflaterOutputStream, GZIPInputStream, GZIPOutputStream}

object RequestDecompressionTest:
  class EchoController:
    @Endpoint(HttpMethod.POST, "/echo-text")
    def echoText(request: Request): String = request.content.toContentString

class RequestDecompressionTest extends UniTest:
  private val client = HttpClient.newHttpClient()

  // Counts requests that got past the pipeline into the filter chain
  private val filtered = AtomicInteger(0)
  private val counter  = RxHttpFilter { (request, next) =>
    filtered.incrementAndGet()
    next.handle(request)
  }

  private val router = RouterHandler(Router.of[RequestDecompressionTest.EchoController])

  // Echo the raw body and the Content-Encoding header the handler saw
  private def handle(request: Request): Rx[Response] =
    if request.path == "/echo" then
      Rx.single(
        Response
          .ok
          .withBytesContent(request.content.toContentBytes)
          .addHeader("X-Content-Encoding", request.header("Content-Encoding").getOrElse("none"))
      )
    else
      router.handle(request)

  private def serverConfig: NettyServerConfig = NettyServer
    .withPort(0)
    .noNativeTransport
    .withGracefulShutdown(quietPeriodSeconds = 0, timeoutSeconds = 5)
    .withMaxContentLength(1024 * 1024)
    .withFilter(counter)
    .withRxHandler(handle)

  private def withServer[A](config: NettyServerConfig = serverConfig)(f: String => A): A =
    val server = config.start()
    try f(s"http://127.0.0.1:${server.localPort}")
    finally server.stop()

  private def post(
      url: String,
      body: Array[Byte],
      headers: (String, String)*
  ): HttpResponse[Array[Byte]] =
    val b = HttpRequest
      .newBuilder(URI.create(url))
      .POST(HttpRequest.BodyPublishers.ofByteArray(body))
    headers.foreach((k, v) => b.header(k, v))
    client.send(b.build(), HttpResponse.BodyHandlers.ofByteArray())

  private def gzip(data: Array[Byte]): Array[Byte] =
    val out = ByteArrayOutputStream()
    val gz  = GZIPOutputStream(out)
    gz.write(data)
    gz.close()
    out.toByteArray

  private def deflate(data: Array[Byte], nowrap: Boolean = false): Array[Byte] =
    val out = ByteArrayOutputStream()
    val d   = DeflaterOutputStream(out, Deflater(Deflater.DEFAULT_COMPRESSION, nowrap))
    d.write(data)
    d.close()
    out.toByteArray

  private def gunzip(data: Array[Byte]): Array[Byte] =
    val in = GZIPInputStream(java.io.ByteArrayInputStream(data))
    try in.readAllBytes()
    finally in.close()

  private val textBytes: Array[Byte] = (0 until 1000)
    .map(i => s"line ${i}: hello gzip")
    .mkString("\n")
    .getBytes(UTF_8)

  private val binary: Array[Byte] = Array.tabulate[Byte](256 * 64)(i => (i % 256).toByte)

  test("decode gzip to the exact raw bytes and drop Content-Encoding") {
    withServer() { base =>
      val resp = post(s"${base}/echo", gzip(binary), "Content-Encoding" -> "gzip")
      resp.statusCode() shouldBe 200
      resp.body().toSeq shouldBe binary.toSeq
      resp.headers().firstValue("X-Content-Encoding").orElse("") shouldBe "none"
    }
  }

  test("decode x-gzip, deflate (zlib and raw), and x-deflate") {
    withServer() { base =>
      val cases = Seq(
        "x-gzip"    -> gzip(textBytes),
        "deflate"   -> deflate(textBytes),
        "deflate"   -> deflate(textBytes, nowrap = true),
        "x-deflate" -> deflate(textBytes),
        "GZIP"      -> gzip(textBytes)
      )
      cases.foreach { (encoding, body) =>
        val resp = post(s"${base}/echo", body, "Content-Encoding" -> encoding)
        resp.statusCode() shouldBe 200
        resp.body().toSeq shouldBe textBytes.toSeq
      }
    }
  }

  test("decode a compressed Unicode JSON body before an @Endpoint reads it") {
    withServer() { base =>
      val json = """{ "model": "test", "future": "こんにちは 🐱" }"""
      for encoding <- Seq("gzip", "deflate") do
        val body =
          if encoding == "gzip" then
            gzip(json.getBytes(UTF_8))
          else
            deflate(json.getBytes(UTF_8))
        val resp = post(
          s"${base}/echo-text",
          body,
          "Content-Type"     -> "application/json",
          "Content-Encoding" -> encoding
        )
        resp.statusCode() shouldBe 200
        String(resp.body(), UTF_8) shouldBe json
    }
  }

  test("pass uncompressed requests and unsupported encodings through unchanged") {
    withServer() { base =>
      val plain = post(s"${base}/echo", textBytes)
      plain.statusCode() shouldBe 200
      plain.body().toSeq shouldBe textBytes.toSeq

      val br = post(s"${base}/echo", textBytes, "Content-Encoding" -> "br")
      br.statusCode() shouldBe 200
      br.body().toSeq shouldBe textBytes.toSeq
      br.headers().firstValue("X-Content-Encoding").orElse("") shouldBe "br"
    }
  }

  test("handle compressed and plain requests on the same connection") {
    withServer() { base =>
      for i <- 0 until 5 do
        val data = s"request ${i}".getBytes(UTF_8)
        val resp =
          if i % 2 == 0 then
            post(s"${base}/echo", gzip(data), "Content-Encoding" -> "gzip")
          else
            post(s"${base}/echo", data)
        resp.body().toSeq shouldBe data.toSeq
    }
  }

  test("still compress responses") {
    withServer() { base =>
      val resp = post(
        s"${base}/echo",
        gzip(textBytes),
        "Content-Encoding" -> "gzip",
        "Accept-Encoding"  -> "gzip"
      )
      resp.statusCode() shouldBe 200
      resp.headers().firstValue("Content-Encoding").orElse("") shouldBe "gzip"
      gunzip(resp.body()).toSeq shouldBe textBytes.toSeq
    }
  }

  test("reject a malformed compressed body with 400 before the filters run") {
    withServer() { base =>
      filtered.set(0)
      val resp = post(
        s"${base}/echo",
        "not a gzip stream".getBytes(UTF_8),
        "Content-Encoding" -> "gzip"
      )
      resp.statusCode() shouldBe 400
      String(resp.body(), UTF_8) shouldBe "Malformed compressed request body"
      resp.headers().firstValue("Content-Type").orElse("") shouldContain "text/plain"
      resp.headers().firstValue("Connection").orElse("") shouldBe "close"
      filtered.get() shouldBe 0
    }
  }

  test("bound the decoded size of compressed requests only") {
    withServer(serverConfig.withMaxDecompressedRequestSize(1024)) { base =>
      filtered.set(0)
      val tooLarge = post(
        s"${base}/echo",
        gzip(Array.ofDim[Byte](64 * 1024)),
        "Content-Encoding" -> "gzip"
      )
      tooLarge.statusCode() shouldBe 413
      String(tooLarge.body(), UTF_8) shouldBe "Decompressed request body exceeds 1024 bytes"
      tooLarge.headers().firstValue("Content-Type").orElse("") shouldContain "text/plain"
      tooLarge.headers().firstValue("Connection").orElse("") shouldBe "close"
      filtered.get() shouldBe 0

      val withinLimit = post(
        s"${base}/echo",
        gzip(Array.ofDim[Byte](1024)),
        "Content-Encoding" -> "gzip"
      )
      withinLimit.statusCode() shouldBe 200
      withinLimit.body().length shouldBe 1024

      val uncompressed = post(s"${base}/echo", Array.ofDim[Byte](64 * 1024))
      uncompressed.statusCode() shouldBe 200
      uncompressed.body().length shouldBe 64 * 1024
    }
  }

  test("pass compressed bytes through with noRequestDecompression") {
    withServer(serverConfig.noRequestDecompression) { base =>
      val gz   = gzip(textBytes)
      val resp = post(s"${base}/echo", gz, "Content-Encoding" -> "gzip")
      resp.statusCode() shouldBe 200
      resp.body().toSeq shouldBe gz.toSeq
      resp.headers().firstValue("X-Content-Encoding").orElse("") shouldBe "gzip"
    }
  }

  test("validate maxDecompressedRequestSize") {
    intercept[IllegalArgumentException] {
      NettyServerConfig().withMaxDecompressedRequestSize(0)
    }
    NettyServerConfig()
      .withMaxDecompressedRequestSize(128 * 1024 * 1024)
      .maxDecompressedRequestSize shouldBe 128 * 1024 * 1024
    NettyServer.withPort(0).requestDecompression shouldBe true
  }

end RequestDecompressionTest
