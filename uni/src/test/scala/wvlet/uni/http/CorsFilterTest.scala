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

import scala.collection.mutable
import scala.util.{Failure, Success, Try}

class CorsFilterTest extends UniTest:
  private val App = "https://app.example.com"

  private val policy = CorsPolicy().withAllowedOrigins(App)

  // A handler that records the requests it sees
  private class Recorder(response: Response = Response.ok("hello")) extends RxHttpHandler:
    val seen                                            = mutable.ArrayBuffer.empty[Request]
    override def handle(request: Request): Rx[Response] =
      seen += request
      Rx.single(response)

  private def run(filter: CorsFilter, request: Request, handler: RxHttpHandler): Response =
    var result: Option[Response] = None
    filter(request, handler).run(r => result = Some(r))
    result.get

  private def preflight(origin: String, method: String = "POST"): Request = Request
    .options("/api/messages")
    .addHeader(HttpHeader.Origin, origin)
    .addHeader(HttpHeader.AccessControlRequestMethod, method)

  test("handle a request without Origin with no CORS headers") {
    val handler  = Recorder()
    val response = run(CorsFilter(policy), Request.get("/api"), handler)
    handler.seen.size shouldBe 1
    response.status shouldBe HttpStatus.Ok_200
    response.hasHeader(HttpHeader.AccessControlAllowOrigin) shouldBe false
  }

  test("answer an allowed preflight with 204 without calling the handler") {
    val handler  = Recorder()
    val response = run(
      CorsFilter(policy.withAllowCredentials(true)),
      preflight(App).addHeader(HttpHeader.AccessControlRequestHeaders, "Content-Type, X-Api-Key"),
      handler
    )
    handler.seen.isEmpty shouldBe true
    response.status shouldBe HttpStatus.NoContent_204
    response.header(HttpHeader.AccessControlAllowOrigin) shouldBe Some(App)
    response.header(HttpHeader.AccessControlAllowMethods) shouldBe Some("GET, HEAD, POST")
    response.header(HttpHeader.AccessControlAllowHeaders) shouldBe Some("Content-Type, X-Api-Key")
    response.header(HttpHeader.AccessControlAllowCredentials) shouldBe Some("true")
    response.header(HttpHeader.AccessControlMaxAge) shouldBe Some("600")
    response.header(HttpHeader.Vary).get shouldContain "Origin"
  }

  test("reject a preflight from a disallowed origin, method, or header with 403") {
    val restricted = CorsFilter(policy.withAllowedHeaders("Content-Type"))
    val handler    = Recorder()

    val badOrigin = run(restricted, preflight("https://evil.example"), handler)
    badOrigin.status shouldBe HttpStatus.Forbidden_403
    badOrigin.hasHeader(HttpHeader.AccessControlAllowOrigin) shouldBe false

    run(restricted, preflight(App, method = "DELETE"), handler).status shouldBe
      HttpStatus.Forbidden_403

    run(
      restricted,
      preflight(App).addHeader(HttpHeader.AccessControlRequestHeaders, "content-type, X-Secret"),
      handler
    ).status shouldBe HttpStatus.Forbidden_403

    handler.seen.isEmpty shouldBe true
  }

  test("match allowed headers case-insensitively") {
    val response = run(
      CorsFilter(policy.withAllowedHeaders("Content-Type", "Authorization")),
      preflight(App).addHeader(HttpHeader.AccessControlRequestHeaders, "content-type"),
      Recorder()
    )
    response.status shouldBe HttpStatus.NoContent_204
    response.header(HttpHeader.AccessControlAllowHeaders) shouldBe
      Some("Content-Type, Authorization")
  }

  test("treat OPTIONS without Access-Control-Request-Method as an ordinary request") {
    val handler  = Recorder()
    val response = run(
      CorsFilter(policy),
      Request.options("/api").addHeader(HttpHeader.Origin, App),
      handler
    )
    handler.seen.size shouldBe 1
    response.header(HttpHeader.AccessControlAllowOrigin) shouldBe Some(App)
  }

  test("add CORS headers to a response for an allowed origin") {
    val handler  = Recorder(Response.ok("hi").addHeader(HttpHeader.Vary, "Accept-Encoding"))
    val response = run(
      CorsFilter(policy.withExposedHeaders("X-Request-Id")),
      Request.post("/api").addHeader(HttpHeader.Origin, App),
      handler
    )
    response.status shouldBe HttpStatus.Ok_200
    response.header(HttpHeader.AccessControlAllowOrigin) shouldBe Some(App)
    response.header(HttpHeader.AccessControlExposeHeaders) shouldBe Some("X-Request-Id")
    response.hasHeader(HttpHeader.AccessControlAllowCredentials) shouldBe false
    response.header(HttpHeader.Vary) shouldBe Some("Accept-Encoding, Origin")
  }

  test("handle a request from a disallowed origin without CORS headers") {
    val handler  = Recorder()
    val response = run(
      CorsFilter(policy),
      Request.get("/api").addHeader(HttpHeader.Origin, "https://evil.example"),
      handler
    )
    handler.seen.size shouldBe 1
    response.status shouldBe HttpStatus.Ok_200
    response.hasHeader(HttpHeader.AccessControlAllowOrigin) shouldBe false
    // A cache must not reuse this response for an allowed origin
    response.header(HttpHeader.Vary) shouldBe Some("Origin")
  }

  test("add Vary: Origin to a response for a request without Origin") {
    val response = run(CorsFilter(policy), Request.get("/api"), Recorder())
    response.header(HttpHeader.Vary) shouldBe Some("Origin")
    // With any origin allowed, Allow-Origin is always *, so the response doesn't vary
    run(CorsFilter(CorsPolicy().withAllowedOrigins("*")), Request.get("/api"), Recorder())
      .hasHeader(HttpHeader.Vary) shouldBe false
  }

  test("merge exposed headers with those the handler set") {
    val response = run(
      CorsFilter(policy.withExposedHeaders("X-Request-Id")),
      Request.get("/api").addHeader(HttpHeader.Origin, App),
      Recorder(Response.ok.addHeader(HttpHeader.AccessControlExposeHeaders, "X-Total-Count"))
    )
    response.header(HttpHeader.AccessControlExposeHeaders) shouldBe
      Some("X-Total-Count, X-Request-Id")
  }

  test("answer any origin with * when credentials are off") {
    val response = run(
      CorsFilter(CorsPolicy().withAllowedOrigins("*")),
      Request.get("/api").addHeader(HttpHeader.Origin, "https://anyone.example"),
      Recorder()
    )
    response.header(HttpHeader.AccessControlAllowOrigin) shouldBe Some("*")
  }

  test("refuse a policy that allows any origin with credentials") {
    intercept[IllegalArgumentException] {
      CorsFilter(CorsPolicy().withAllowedOrigins("*").withAllowCredentials(true))
    }
  }

  test("normalize listed origins") {
    val p = CorsPolicy().withAllowedOrigins("HTTPS://App.Example.com/")
    p.isOriginAllowed(App) shouldBe true
    p.isOriginAllowed("https://app.example.com:8443") shouldBe false
    CorsPolicy().isOriginAllowed(App) shouldBe false
  }

  test("allow origins by a predicate") {
    val pattern = """https://.*\.example\.com$""".r
    val p       = CorsPolicy().withOriginFilter(pattern.matches)
    p.isOriginAllowed(App) shouldBe true
    p.isOriginAllowed("https://app.example.com.evil.test") shouldBe false
  }

  test("pass a handler failure on unchanged") {
    val failing = RxHttpHandler(_ => Rx.exception(IllegalStateException("boom")))
    var result  = Option.empty[Try[Response]]
    CorsFilter(policy)(Request.get("/api").addHeader(HttpHeader.Origin, App), failing)
      .transformTry(t => Success(t))
      .run(t => result = Some(t))
    result shouldMatch { case Some(Failure(_: IllegalStateException)) =>
    }
  }

  test("register the CORS headers for the server's 500 on a failed handler") {
    val (request, serverError) = ServerErrorResponse.attach(
      Request.get("/api").addHeader(HttpHeader.Origin, App)
    )
    val failing = RxHttpHandler(_ => Rx.exception(IllegalStateException("boom")))
    var result  = Option.empty[Try[Response]]
    CorsFilter(policy.withAllowCredentials(true).withExposedHeaders("X-Request-Id"))(
      request,
      failing
    ).transformTry(t => Success(t)).run(t => result = Some(t))
    // The failure itself is passed on for the server and the access log to report
    result shouldMatch { case Some(Failure(_: IllegalStateException)) =>
    }
    val response = serverError(Response.internalServerError("Internal Server Error"))
    response.header(HttpHeader.AccessControlAllowOrigin) shouldBe Some(App)
    response.header(HttpHeader.AccessControlAllowCredentials) shouldBe Some("true")
    response.header(HttpHeader.AccessControlExposeHeaders) shouldBe Some("X-Request-Id")
    response.header(HttpHeader.Vary) shouldBe Some("Origin")
  }

  test("register only Vary for the server's 500 on a request from a disallowed origin") {
    val (request, serverError) = ServerErrorResponse.attach(
      Request.get("/api").addHeader(HttpHeader.Origin, "https://evil.example.com")
    )
    CorsFilter(policy)(request, Recorder()).run(_ => ())
    val response = serverError(Response.internalServerError)
    response.hasHeader(HttpHeader.AccessControlAllowOrigin) shouldBe false
    response.header(HttpHeader.Vary) shouldBe Some("Origin")
  }

end CorsFilterTest
