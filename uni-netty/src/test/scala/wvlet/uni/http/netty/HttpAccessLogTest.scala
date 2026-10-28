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

import wvlet.uni.http.*
import wvlet.uni.http.router.{Endpoint, Router}
import wvlet.uni.io.IOPath
import wvlet.uni.log.FileLogHandlerConfig
import wvlet.uni.rx.Rx
import wvlet.uni.test.UniTest

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files

object HttpAccessLogTest:
  val UserId: RequestAttribute[String] = RequestAttribute("userId")

  class LoggedController:
    @Endpoint(HttpMethod.GET, "/hello")
    def hello(): String = "hello"

    @Endpoint(HttpMethod.GET, "/error")
    def error(): String = throw IllegalStateException("boom")

    @Endpoint(HttpMethod.GET, "/events")
    def events(): Response = Response.eventStream(
      Rx.fromSeq(Seq(ServerSentEvent.data("a"), ServerSentEvent.data("bc")))
    )

  // Sets the user id the way an auth filter would, and adds it to the access log
  val auth: RxHttpFilter = RxHttpFilter { (request, next) =>
    HttpAccessLogFilter.addEntry(request, "user_id", "u1")
    next.handle(request.withAttribute(UserId, "u1"))
  }

end HttpAccessLogTest

class HttpAccessLogTest extends UniTest:
  import HttpAccessLogTest.*

  private val client = HttpClient.newHttpClient()

  private def withServer[A](filter: RxHttpFilter)(f: String => A): A =
    val server = NettyServer
      .withPort(0)
      .noNativeTransport
      .withGracefulShutdown(quietPeriodSeconds = 0, timeoutSeconds = 5)
      .withFilter(filter)
      .withRxHandler(RouterHandler(Router.of[LoggedController]))
      .start()
    try f(s"http://127.0.0.1:${server.localPort}")
    finally server.stop()

  private def get(url: String, headers: (String, String)*): HttpResponse[String] =
    val builder = HttpRequest.newBuilder(URI.create(url)).GET()
    headers.foreach((k, v) => builder.header(k, v))
    client.send(builder.build(), HttpResponse.BodyHandlers.ofString(UTF_8))

  // Entries are written as the response completes, which can be just after the client has it
  private def awaitEntries(w: InMemoryHttpAccessLogWriter, n: Int): Seq[Map[String, Any]] =
    val deadline = System.currentTimeMillis() + 5000
    while w.entries.size < n && System.currentTimeMillis() < deadline do
      Thread.sleep(10)
    w.entries

  test("log a request with redacted headers and extra entries") {
    val w      = HttpAccessLogWriter.inMemory()
    val config = HttpAccessLogConfig().withExtraEntries { (_, resp) =>
      Map("has_response" -> resp.isDefined)
    }
    withServer(HttpAccessLogFilter(w, config).andThen(auth)) { base =>
      get(s"${base}/hello?lang=en", "Authorization" -> "Bearer secret", "X-Trace" -> "t1")
        .statusCode() shouldBe 200
      val e = awaitEntries(w, 1).head
      e("method") shouldBe "GET"
      e("path") shouldBe "/hello"
      e("query_string") shouldBe "lang=en"
      e("status_code") shouldBe 200
      e("response_size") shouldBe 5L
      e("user_id") shouldBe "u1"
      e("has_response") shouldBe true
      e("remote_address") shouldMatch {
        case s: String if s.startsWith("127.0.0.1:") =>
      }
      val headers = e("request_header").asInstanceOf[Map[String, String]]
      headers("authorization") shouldBe "[redacted]"
      headers("x-trace") shouldBe "t1"
      (e("response_time_ms").asInstanceOf[Long] >= 0) shouldBe true
    }
  }

  test("log 404 and exceptions, and still render the generic 500") {
    val logs = captureLogs {
      val w = HttpAccessLogWriter.inMemory()
      withServer(HttpAccessLogFilter(w)) { base =>
        get(s"${base}/missing").statusCode() shouldBe 404
        val r = get(s"${base}/error")
        r.statusCode() shouldBe 500
        r.body() shouldBe "Internal Server Error"
        val entries = awaitEntries(w, 2)
        entries(0)("status_code") shouldBe 404
        entries(1)("status_code") shouldBe 500
        entries(1)("exception_class") shouldBe "java.lang.IllegalStateException"
        entries(1)("exception_message") shouldBe "boom"
      }
    }
    logs.warnings shouldContain "Error in Rx handler: boom"
  }

  test("log a finished SSE stream to the stream writer") {
    val w      = HttpAccessLogWriter.inMemory()
    val stream = HttpAccessLogWriter.inMemory()
    withServer(HttpAccessLogFilter(w, HttpAccessLogConfig().withStreamWriter(stream))) { base =>
      get(s"${base}/events").body() shouldBe "data: a\n\ndata: bc\n\n"
      awaitEntries(w, 1).head("status_code") shouldBe 200
      val s = awaitEntries(stream, 1).head
      s("path") shouldBe "/events"
      s("stream_event_count") shouldBe 2L
      s("stream_bytes") shouldBe 19L
    }
  }

  test("log once per request, including empty handler results and failing callbacks") {
    val logs = captureLogs {
      val w      = HttpAccessLogWriter.inMemory()
      val config = HttpAccessLogConfig().withExtraEntries((_, _) =>
        throw IllegalStateException("x")
      )
      val server = NettyServer
        .withPort(0)
        .noNativeTransport
        .withGracefulShutdown(quietPeriodSeconds = 0, timeoutSeconds = 5)
        .withFilter(HttpAccessLogFilter(w, config))
        .withRxHandler { req =>
          if req.path == "/empty" then
            Rx.empty
          else
            Rx.fromSeq(Seq(Response.ok("first"), Response.ok("second")))
        }
        .start()
      try
        val base = s"http://127.0.0.1:${server.localPort}"
        get(s"${base}/empty").statusCode() shouldBe 404
        get(s"${base}/many").body() shouldBe "first"
        Thread.sleep(200)
        w.entries.map(e => (e("path"), e("status_code"))) shouldBe
          Seq(("/empty", 404), ("/many", 200))
      finally
        server.stop()
    }
    logs.warnings shouldContain "extraEntries failed: java.lang.IllegalStateException: x"
  }

  test("exclude headers and replace the redacted set") {
    val w      = HttpAccessLogWriter.inMemory()
    val config = HttpAccessLogConfig()
      .withRedactedHeaders("x-secret")
      .withExcludedHeaders("x-trace")
    withServer(HttpAccessLogFilter(w, config)) { base =>
      get(s"${base}/hello", "X-Secret" -> "s", "X-Trace" -> "t").statusCode() shouldBe 200
      val headers = awaitEntries(w, 1).head("request_header").asInstanceOf[Map[String, String]]
      headers("x-secret") shouldBe "[redacted]"
      headers.contains("x-trace") shouldBe false
    }
  }

  test("write JSON lines to a file") {
    val dir = Files.createTempDirectory("access-log")
    val w   = HttpAccessLogWriter.jsonFile(FileLogHandlerConfig(IOPath(s"${dir}/http_server.json")))
    try
      w.write(Map("method" -> "GET", "status_code" -> 200, "request_header" -> Map("a" -> "b")))
      w.write(Map("path" -> "/x\"y", "opt" -> None, "nan" -> Double.NaN))
    finally
      w.close()
    val lines = Files.readAllLines(dir.resolve("http_server.json"), UTF_8)
    lines.size shouldBe 2
    lines.get(0) shouldBe """{"method":"GET","status_code":200,"request_header":{"a":"b"}}"""
    lines.get(1) shouldBe """{"path":"/x\"y","opt":null,"nan":null}"""
  }

end HttpAccessLogTest
